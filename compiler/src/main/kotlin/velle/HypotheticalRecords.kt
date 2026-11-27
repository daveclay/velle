package velle

/** One thing an edit does to a record like the ones the system keeps, of the shape [base]. */
data class RecordChange(val base: String, val sentence: String, val pointer: Pointer)

/**
 * What the edits do to a hypothetical record of a kept shape, worked out from the two specs
 * alone, never from a store (the guide `Comparing-specs.md`). A refinement whose condition
 * changed gains the records that meet the new condition and not the old, and loses the ones
 * that meet the old and not the new. A rule on a schedule runs at its next tick over whoever is
 * a member then (Reference §16), so what it does with such a record is said as what it does. Any
 * other rule over the refinement could run for such a record, which is said as a "could".
 */
object HypotheticalRecords {

    fun of(comparison: Comparison): List<RecordChange> =
        comparison.changed.flatMap { (old, new) -> refinementChange(old, new, comparison) } +
            comparison.added.flatMap { added -> constraintAdded(added, comparison.beforeRenamed) }

    // ── a refinement whose condition changed ─────────────────────────────────────

    private fun refinementChange(old: Compared, new: Compared, comparison: Comparison): List<RecordChange> {
        val oldRefinement = old.declaration as? RefinementDecl ?: return emptyList()
        val newRefinement = new.declaration as? RefinementDecl ?: return emptyList()
        val before = comparison.beforeRenamed
        val after = comparison.after
        val oldConjuncts = conjunctsOf(agreed(oldRefinement, before), before)
        val newConjuncts = conjunctsOf(agreed(newRefinement, after), after)
        val oldSpelled = oldConjuncts.map { conjunct -> Printer.refExpr(conjunct) }.toSet()
        val newSpelled = newConjuncts.map { conjunct -> Printer.refExpr(conjunct) }.toSet()
        val common = newConjuncts.filter { conjunct -> Printer.refExpr(conjunct) in oldSpelled }
        val onlyOld = oldConjuncts.filter { conjunct -> Printer.refExpr(conjunct) !in newSpelled }
        val onlyNew = newConjuncts.filter { conjunct -> Printer.refExpr(conjunct) !in oldSpelled }
        val base = after.baseOf(newRefinement.name)
        val entering = (if (onlyOld.isEmpty()) null else common + onlyNew + negationOfAll(onlyOld))?.takeUnless(::contradictory)
        val leaving = (if (onlyNew.isEmpty()) null else common + onlyOld + negationOfAll(onlyNew))?.takeUnless(::contradictory)
        return listOfNotNull(
            entering?.let { conditions -> membershipChange(base, newRefinement.name, conditions, entering = true, after, new.pointer) },
            leaving?.let { conditions -> membershipChange(base, newRefinement.name, conditions, entering = false, before, new.pointer) },
        ).flatten()
    }

    private fun agreed(refinement: RefinementDecl, version: SpecVersion): RefExpr =
        (AgreedSpelling(version).of(refinement) as RefinementDecl).expr

    private fun membershipChange(
        base: String,
        refinement: String,
        conditions: List<RefExpr>,
        entering: Boolean,
        version: SpecVersion,
        pointer: Pointer,
    ): List<RecordChange> {
        val record = Sentences.capitalized(Sentences.withArticle(base))
        val movement = if (entering) "becomes" else "stops being"
        val clauses = conditions.map { condition -> Sentences.clause(condition, version.baseShapes) ?: "`${Printer.refExpr(condition)}` holds" }
        val membership = RecordChange(base, "$record $movement ${Sentences.withArticle(refinement)} by this change alone when ${listed(clauses)}.", pointer)
        val rules = rulesReading(refinement, version).mapNotNull { (rule, through) -> ruleOutcome(rule, base, refinement, through, entering, version) }
        return listOf(membership) + rules.map { sentence -> RecordChange(base, sentence, pointer) }
    }

    /** What a rule over the refinement does with a record that entered or left it by the change alone.
     *  [through] is the refinement the rule names that is made from [refinement], or null when it names [refinement] itself. */
    private fun ruleOutcome(rule: RuleDecl, base: String, refinement: String, through: String?, entering: Boolean, version: SpecVersion): String? {
        val madeFrom = through?.let { composite -> ", since $composite is made from $refinement" } ?: ""
        val sweepOnly = rule.preposition == "on" && "commit" !in rule.triggers && rule.trigger == Trigger.ENTRY
        return if (sweepOnly) {
            sweepOutcome(rule, through == null, entering, madeFrom, version)
        } else {
            commitOutcome(rule, base, refinement, through, entering)
        }
    }

    /** A rule on a schedule runs at its next tick over whoever is a member then (Reference §16). */
    private fun sweepOutcome(rule: RuleDecl, namesItDirectly: Boolean, entering: Boolean, madeFrom: String, version: SpecVersion): String {
        val ticks = "At the next ${rule.triggers.filter { trigger -> trigger != "commit" }.joinToString(" or ")} tick"
        if (!namesItDirectly) {
            return if (entering) "$ticks, ${rule.name} could run for it$madeFrom." else "$ticks, ${rule.name} could stop running for it$madeFrom."
        }
        if (!entering) return "$ticks, ${rule.name} no longer runs for it."
        val condition = Sentences.clause(rule.condition, version.baseShapes)?.let { clause -> " if $clause" } ?: ""
        return "$ticks, ${rule.name} runs for it$condition."
    }

    /**
     * Any other rule looks for a record entering or leaving at a commit, and none made this one: the
     * change to the spec moved it. Whether the rule then runs for it is not settled, so the sentence
     * says it could.
     */
    private fun commitOutcome(rule: RuleDecl, base: String, refinement: String, through: String?, entering: Boolean): String? {
        val firesOnThisMove = if (entering) rule.trigger == Trigger.ENTRY else rule.trigger.onPredicateExit
        if (!firesOnThisMove) return null
        val move = if (entering) "made it ${Sentences.withArticle(refinement)}" else "made it stop being ${Sentences.withArticle(refinement)}"
        val madeFrom = through?.let { composite -> ", and $composite is made from $refinement" } ?: ""
        return "${rule.name} could then run for it, though nothing happened to the $base itself: the change to the spec is what $move$madeFrom."
    }

    /** The rules whose condition reads [refinement], each with the refinement it reads it through, or null
     *  when it names [refinement] itself. */
    private fun rulesReading(refinement: String, version: SpecVersion): List<Pair<RuleDecl, String?>> {
        val rules = version.declarations.map { located -> located.decl }.filterIsInstance<RuleDecl>()
        return rules.mapNotNull { rule ->
            val named = namesIn(rule.condition)
            val through = named.firstOrNull { name -> refinement in version.refinementsReadBy(name) }
            when {
                refinement in named -> rule to null
                through != null -> rule to through
                else -> null
            }
        }
    }

    private fun namesIn(expression: RefExpr): Set<String> = when (expression) {
        is RefName -> setOf(expression.name)
        is RefNot -> namesIn(expression.inner)
        is RefAnd -> namesIn(expression.left) + namesIn(expression.right)
        is RefOr -> namesIn(expression.left) + namesIn(expression.right)
    }

    // ── conjuncts ────────────────────────────────────────────────────────────────

    /** A condition as the list of things that must all hold: `OverdueInvoice and not ArchivedInvoice`
     *  is two, `Invoice where balance > 0 and due < today` is two. */
    private fun conjunctsOf(expression: RefExpr, version: SpecVersion): List<RefExpr> = when (expression) {
        is RefAnd -> conjunctsOf(expression.left, version) + conjunctsOf(expression.right, version)
        is RefName -> nameConjuncts(expression, version)
        else -> listOf(expression)
    }

    private fun nameConjuncts(expression: RefName, version: SpecVersion): List<RefExpr> {
        val base = version.baseOf(expression.name)
        val membership = if (expression.name in version.baseShapes) emptyList() else listOf(RefName(expression.name))
        val where = expression.where ?: return membership
        return membership + predicateConjuncts(where).map { conjunct -> RefName(base, conjunct) }
    }

    private fun predicateConjuncts(predicate: Expr): List<Expr> =
        if (predicate is Binary && predicate.op == "and") predicateConjuncts(predicate.left) + predicateConjuncts(predicate.right)
        else listOf(predicate)

    /**
     * Whether the conditions can never all hold, by the two facts that need no values: a condition
     * and its negation, and `a < b` beside `not (a <= b)` (one is less, the other not even at most).
     * A contradiction the tool cannot see is still printed, and reads as a record nobody can have.
     */
    private fun contradictory(conditions: List<RefExpr>): Boolean {
        val spelled = conditions.map { condition -> Printer.refExpr(condition) }.toSet()
        val predicates = conditions.mapNotNull { condition -> (condition as? RefName)?.where }
        val negatedHeld = conditions.any { condition -> Printer.refExpr(negated(condition)) in spelled }
        val strictBesideNotAtMost = predicates.any { predicate ->
            predicate is Binary && predicate.op == "<" &&
                predicates.any { other -> other == NotExpr(Binary("<=", predicate.left, predicate.right)) }
        }
        return negatedHeld || strictBesideNotAtMost
    }

    private fun negationOfAll(conjuncts: List<RefExpr>): List<RefExpr> {
        val single = conjuncts.singleOrNull() ?: return listOf(RefNot(conjuncts.reduce { left, right -> RefAnd(left, right) }))
        return listOf(negated(single))
    }

    private fun negated(conjunct: RefExpr): RefExpr = when {
        conjunct is RefNot -> conjunct.inner
        conjunct is RefName && conjunct.where is NotExpr -> RefName(conjunct.name, conjunct.where.inner)
        conjunct is RefName && conjunct.where != null -> RefName(conjunct.name, NotExpr(conjunct.where))
        else -> RefNot(conjunct)
    }

    private fun listed(clauses: List<String>): String = when (clauses.size) {
        0 -> "it always does"
        1 -> clauses.single()
        2 -> "${clauses[0]} and ${clauses[1]}"
        else -> clauses.dropLast(1).joinToString(", ") + ", and " + clauses.last()
    }

    // ── a constraint added ───────────────────────────────────────────────────────

    /** A `never` added over a shape or kept act the earlier version, [before], already declared: a
     *  record stored under it may already break the prohibition, and while it does, every commit that
     *  adds a record of the same base is refused, naming it. A transient act stores nothing. */
    private fun constraintAdded(added: Compared, before: SpecVersion): List<RecordChange> {
        val prohibition = added.declaration as? NeverDecl ?: return emptyList()
        val version = added.version
        val subject = prohibition.requirement?.subject ?: Sentences.leftmostName(prohibition.target)
        val base = version.baseOf(subject)
        if (base !in before.keptBaseShapes || base !in version.keptBaseShapes) return emptyList()
        val forbidden = DeclarationSentences.forbidden(prohibition, version) ?: return emptyList()
        val connective = if (prohibition.requirement != null) "unless" else "when"
        val sentence = "${Sentences.capitalized(Sentences.withArticle(subject))} stored before this change is now forbidden " +
            "$connective $forbidden. While such ${Sentences.withArticle(subject)} is stored, every new $base is refused, " +
            "and the refusal names the stored one."
        return listOf(RecordChange(base, sentence, added.pointer))
    }
}
