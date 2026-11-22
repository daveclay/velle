package velle.validation

import velle.*

// ── V17/V18: transient acts (Reference §4, "Transient acts") ────────────────
//
// A transient act exists only within its own commit's transaction — an
// input to the state, not a member of it. V17 (isolation): nothing durable
// or later may depend on it. V18 (totality): every request gets a response
// — an act no rule answers would be ignored with no record it ever
// arrived; v0 proves the coarse slice (a bare-shape rule, or a syntactic
// complement pair) and fails closed otherwise.

internal fun Validator.checkTransients() {
    if (model.transients.isEmpty()) return
    checkNoFieldTypedTransient()
    checkNoExpressionReadsTransient()
    checkNoRuleCreatesTransient()
    for (rule in model.rules.values) checkRuleOverTransientFiresAtCommit(rule)
    checkEveryRequestAnswered()
}

// ── V17: no property anywhere may be typed to a transient shape ──────────

private fun Validator.checkNoFieldTypedTransient() {
    model.shapes.forEach { (name, shape) -> shape.members.forEach { member -> checkFieldNotTypedTransient(name, member) } }
    model.refinements.forEach { (name, refinement) -> refinement.members.forEach { member -> checkFieldNotTypedTransient(name, member) } }
}

private fun Validator.checkFieldNotTypedTransient(owner: String, member: Member) {
    val (fieldName, type) = when (member) {
        is StoredProp -> member.name to member.type
        is DerivedProp -> member.name to member.type
        else -> return
    }
    val relation = type as? RelType ?: return
    // a field typed with a taken shape that a transient act takes would reference
    // the act's record (Reference §9, "Sharing a field across shapes"), unless the
    // field's owner is a part of that taker's own closure (Reference §6)
    if (relation.shape in model.sharedShapes) return checkTakenShapeField(owner, fieldName, relation.shape)
    if (relation.shape !in model.transients) return
    // a part of a transient act's closure points at its container, and two
    // shapes of one closure may point at each other (Reference §6)
    if (model.sameTransientClosure(owner, relation.shape)) return
    diagnostics.add(Diagnostic("V17", "'$owner.$fieldName' references transient ${model.transientKind(relation.shape)} '${relation.shape}' — " +
        "it exists only within its own commit's transaction, so nothing durable " +
        "may point at it; copy the fields the outcome needs instead"))
}

private fun Validator.checkTakenShapeField(owner: String, fieldName: String, takenShape: String) {
    model.basesOf(takenShape)
        .filter { taker -> taker in model.transients && !model.sameTransientClosure(owner, taker) }
        .forEach { taker ->
            diagnostics.add(Diagnostic("V17", "'$owner.$fieldName' is typed with '$takenShape', and transient ${model.transientKind(taker)} '$taker' is $takenShape — " +
                "a record of it exists only within its own commit's transaction, so nothing durable " +
                "may point at it; copy the fields the outcome needs instead"))
        }
}

// ── V17: the transient shape's name may appear in no expression ──────────
//
// Even from its own refinement family, that is a read across acts. Two
// exceptions: a rule or refinement over a shape a transient act takes with
// `is` runs inside that act's own commit, so narrowing to the act there
// reads the record at hand (Reference §9, "Sharing a field across shapes"),
// and a rule over the act narrows a part of its own closure the same way;
// and a derived property of the act, or of a part of its closure, that reads
// a shape of the same closure is read inside that commit too, as the inverse
// a part's back-reference infers is (Reference §6, "A request that is not
// kept, with its parts")

private fun Validator.checkNoExpressionReadsTransient() {
    val transientSubjectsBySite = transientSubjectsBySite()
    val transientShapeBySite = model.transients.associateBy { shape -> "shape '$shape'" }
    forEachSpecExpr { site, expr ->
        val mentioned = transientMentionedBy(expr, transientSubjectsBySite[site].orEmpty()) ?: return@forEachSpecExpr
        val readingShape = transientShapeBySite[site]
        if (readingShape != null && model.sameTransientClosure(readingShape, mentioned)) return@forEachSpecExpr
        diagnostics.add(Diagnostic("V17", "$site reads transient ${model.transientKind(mentioned)} '$mentioned' — " +
            "it is not kept after its commit, so no expression may query it; " +
            "read the durable outcomes its rules produced instead"))
    }
}

/** The transient act [expr] names, or null; [siteSubjects] are the transient acts its site's subject spans. */
private fun Validator.transientMentionedBy(expr: Expr, siteSubjects: Set<String>): String? {
    fun transientOrNull(shape: String?) = shape?.takeIf { name -> name in model.transients }
    return when (expr) {
        is PathExpr -> transientOrNull(expr.root)
        is ExistsExpr -> transientOrNull(expr.shape) ?: transientOrNull(firstSourceRoot(expr))
        is SingularFor -> transientOrNull(expr.shape)
        is ShapeForSource -> transientOrNull(expr.shape)
        is IsExpr -> transientNarrowedTo(expr, siteSubjects)
        else -> null
    }
}

/** The root name of the first source `exists (...)` queries, when that source is a path. */
private fun firstSourceRoot(exists: ExistsExpr): String? =
    (exists.collection?.bindings?.firstOrNull()?.source as? PathExpr)?.root

/** The transient refinement [narrowing] narrows to, or null when it is none or lies in the closure of one of [siteSubjects]. */
private fun Validator.transientNarrowedTo(narrowing: IsExpr, siteSubjects: Set<String>): String? {
    val refinement = narrowing.refinement ?: return null
    val narrowedBase = model.baseOf(refinement) ?: refinement
    if (narrowedBase !in model.transients) return null
    if (siteSubjects.any { subject -> model.sameTransientClosure(subject, narrowedBase) }) return null
    return refinement
}

/** The transient acts each rule's, refinement's, and `never`'s subject spans, by the site name
 *  [forEachSpecExpr] reports: non-empty only for a subject that is a shape such an act
 *  takes with `is`, or a refinement of one. */
private fun Validator.transientSubjectsBySite(): Map<String, Set<String>> {
    val bySite = mutableMapOf<String, Set<String>>()
    for (rule in model.rules.values) {
        val subject = subjectScope(rule) ?: continue
        bySite["rule '${rule.name}'"] = model.basesOf(subject).filter { base -> base in model.transients }.toSet()
    }
    for (name in model.refinements.keys)
        bySite["shape '$name'"] = model.basesOf(name).filter { base -> base in model.transients }.toSet()
    for (never in model.nevers)
        bySite[neverSite(never)] = model.basesOfExpr(never.target).filter { base -> base in model.transients }.toSet()
    return bySite
}

// ── V17: only the boundary commits a transient act ───────────────────────

/** A rule-created instance would persist, contradicting the marker. */
private fun Validator.checkNoRuleCreatesTransient() {
    for (rule in model.rules.values) {
        rule.body.statements().filterIsInstance<Creation>().filter { creation -> creation.shape in model.transients }.forEach { creation ->
            diagnostics.add(Diagnostic("V17", "rule '${rule.name}' creates transient ${model.transientKind(creation.shape)} '${creation.shape}' — " +
                "a transient shape arrives as input at the boundary, never as a rule's effect"))
        }
    }
}

/** V17: rules over a transient act fire only at its one commit. */
private fun Validator.checkRuleOverTransientFiresAtCommit(rule: RuleDecl) {
    val subject = subjectScope(rule) ?: return
    val act = model.basesOf(subject).firstOrNull { base -> base in model.transients } ?: return
    if (rule.leaving)
        diagnostics.add(Diagnostic("V17", "rule '${rule.name}' — 'when ${rule.trigger.keyword.trim()}' over transient ${model.transientKind(act)} '$act' " +
            "is meaningless: its refinements are evaluated exactly once, at the commit; there are no exits"))
    if (rule.preposition == "after" || rule.triggers.any { trigger -> trigger != "commit" })
        diagnostics.add(Diagnostic("V17", "rule '${rule.name}' — transient ${model.transientKind(act)} '$act' is gone before any " +
            "'after commit' firing or tick runs; handle it at its commit, and hang asynchronous work " +
            "off a durable intent the handling rule creates"))
}

// ── V18: every request gets a response ───────────────────────────────────
//
// Either a rule fires, or the boundary refuses. A `never` over the act is an
// answer too: the refused configuration gets its response as the commit's
// refusal, with nothing kept (Reference §4; §21).

private fun Validator.checkEveryRequestAnswered() {
    for (act in model.transients.filter { transient -> model.transientClosureOf[transient] == transient }) checkRequestAnswered(act)
}

private fun Validator.checkRequestAnswered(act: String) {
    // a rule or `never` over a taken shape the act takes answers it too (Reference §9)
    val handlers = model.rules.values.filter { rule ->
        !rule.leaving && subjectScope(rule)?.let { subject -> act in model.basesOf(subject) } == true
    }
    val refusals = model.nevers.filter { never -> act in model.basesOfExpr(never.target) }
    if (handlers.any { rule -> isBareOver(act, rule.condition) } || refusals.any { never -> isBareOver(act, never.target) }) return
    val answers: List<List<Expr>?> =
        handlers.map { rule -> refPredicateConjuncts(rule.condition) } +
            refusals.map { never -> refPredicateConjuncts(never.target) }
    if (hasComplementPair(answers)) return
    if (wordsAnswer(act, answers.filterNotNull())) return
    diagnostics.add(Diagnostic("V18", "a '$act' can arrive that no rule or 'never' answers, and it is " +
        "transient, so an unanswered one would be ignored with no record that it arrived. The compiler " +
        "sees every '$act' answered when a rule or a 'never' is written over the bare '$act', or when two " +
        "answers have conditions that are each other's opposite, 'P' and 'not P'; add a rule over '$act' " +
        "with no condition, refuse the rest with 'never ($act where not P)', or write one rule on the " +
        "opposite condition whose reason is a conditional value (Reference §4)"))
}

/** [condition] is the bare [act], or a bare shape it takes, with no `where`. */
private fun Validator.isBareOver(act: String, condition: RefExpr): Boolean {
    val named = condition as? RefName ?: return false
    return (named.name == act || named.name in model.sharedShapes) && named.where == null
}

/** Two of [answers] each have one condition, and the two are `P` and `not P`. */
private fun hasComplementPair(answers: List<List<Expr>?>): Boolean =
    answers.any { first ->
        answers.any { second -> first !== second && areOpposites(first?.singleOrNull(), second?.singleOrNull()) }
    }

private fun areOpposites(first: Expr?, second: Expr?): Boolean =
    first != null && second != null && (first == NotExpr(second) || second == NotExpr(first))

/**
 * V18 over a field declared by its words (Reference §5): the answers whose one condition
 * names words of one such field of [act] cover it when every word is named. When some
 * are named and others not, each missing word is reported by name, and the act counts
 * as answered here so the general sentence is not added on top. Returns whether this
 * case decided the act.
 */
private fun Validator.wordsAnswer(act: String, answers: List<List<Expr>>): Boolean {
    val actShape = model.shapes[act] ?: return false
    for (field in actShape.members.filterIsInstance<StoredProp>()) {
        val wordsType = (field.type as? ScalarType)?.let { scalar -> model.typeScope[scalar.name] } as? EnumType ?: continue
        val named = answers.mapNotNull { conditions -> conditions.singleOrNull() }.flatMap { condition -> wordsNamedBy(condition, field.name) }.toSet()
        if (named.isEmpty()) continue
        reportUnansweredWords(act, field.name, wordsType, named)
        return true
    }
    return false
}

private fun Validator.reportUnansweredWords(act: String, fieldName: String, wordsType: EnumType, named: Set<String>) {
    val namedWords = named.filter { word -> word in wordsType.words }.joinToString(" and ") { word -> "\"$word\"" }
    for (word in wordsType.words.filter { word -> word !in named })
        diagnostics.add(Diagnostic("V18", "a '$act' whose '$fieldName' is \"$word\" can arrive and no rule or 'never' answers it, " +
            "and it is transient, so it would be ignored with no record that it arrived; the answers name " +
            "$namedWords of the ${numberWord(wordsType.words.size)} words of '${wordsType.name}'. " +
            "Answer \"$word\" in a rule, or refuse it with 'never ($act where $fieldName == \"$word\")' (Reference §4)"))
}

/** The words a condition names for [field]: `field == "w"`, `"w" == field`, `field in ["w", ...]`,
 *  and `or` between those; empty when the condition is anything else. */
private fun wordsNamedBy(condition: Expr, field: String): List<String> {
    if (condition !is Binary) return emptyList()
    return when (condition.op) {
        "==" -> wordOfEquality(condition, field)
        "in" -> wordsOfMembership(condition, field)
        "or" -> wordsOfEither(condition, field)
        else -> emptyList()
    }
}

/** `field == "w"` or `"w" == field`. */
private fun wordOfEquality(equality: Binary, field: String): List<String> {
    val word = when {
        readsField(equality.left, field) -> equality.right as? TextLit
        readsField(equality.right, field) -> equality.left as? TextLit
        else -> null
    }
    return listOfNotNull(word?.value)
}

/** `field in ["w", ...]`, every item a word. */
private fun wordsOfMembership(membership: Binary, field: String): List<String> {
    val list = membership.right as? ListLit ?: return emptyList()
    if (!readsField(membership.left, field) || !list.items.all { item -> item is TextLit }) return emptyList()
    return list.items.map { item -> (item as TextLit).value }
}

/** `A or B`, both sides naming words. */
private fun wordsOfEither(either: Binary, field: String): List<String> {
    val left = wordsNamedBy(either.left, field)
    val right = wordsNamedBy(either.right, field)
    return if (left.isEmpty() || right.isEmpty()) emptyList() else left + right
}

/** `field` or `this.field`. */
private fun readsField(expr: Expr, field: String) = expr == PathExpr(field) || fieldNameOfThis(expr) == field
