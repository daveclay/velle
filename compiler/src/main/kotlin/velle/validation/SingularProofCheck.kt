package velle.validation

import velle.*
import velle.spec.partsJoinedByAnd

// ── V12 (refinement slice): at-most-one proofs for (Refinement for expr) ─
//
// A singular reference to a refinement R is licensed by the whole-spec
// singularity proof (Reference §10; §20 "Episodes as data"): at most one
// member of R can reference a given subject. The proof is inductive over
// commits, with two legs — every producer of R's base carries the entry
// guard, and R's predicate is anti-monotone (membership, once lost, can't
// return, so members only ever appear at guarded creation). v0 proves the
// coarse fail-closed slice of each leg; base-shape singular references
// stay runtime-enforced (V12 beyond this slice is TODO, checks.md).

internal fun Validator.checkSingularProofs() {
    forEachSpecExpr { site, expr ->
        if (expr is SingularFor && expr.shape in model.refinements) checkSingularRead(SingularRead(site, expr.shape))
    }
}

/** A `(refinement for ...)` read as one record at [site], with the two phrases every V12 message about it shares. */
private data class SingularRead(val site: String, val refinement: String) {
    val demand get() = "$site reads '($refinement for ...)' as one record"
    val selectFix get() = "select one with 'latest($refinement where ... by ...)' or 'first(...)' instead"
}

private fun Validator.checkSingularRead(read: SingularRead) {
    // over a union the proof runs for each operand's records (Reference §9)
    for (base in model.basesOf(read.refinement)) checkSingularOverBase(read, base)
}

private fun Validator.checkSingularOverBase(read: SingularRead, base: String) {
    val refinement = read.refinement
    if (base in model.exposed)
        diagnostics.add(Diagnostic("V12", "${read.demand}, but '$base' is an act, so a caller can commit a second " +
            "'$refinement' while one exists; ${read.selectFix}"))
    antiMonotoneFailure(refinement, base)?.let { why ->
        diagnostics.add(Diagnostic("V12", "${read.demand}, but the compiler cannot see that a record is never a member " +
            "of '$refinement' twice: $why; ${read.selectFix}"))
    }
    for (rule in model.rules.values) checkCreationsGuarded(read, rule, base)
}

private fun Validator.checkCreationsGuarded(read: SingularRead, rule: RuleDecl, base: String) {
    val creations = rule.body.statements().filterIsInstance<Creation>().filter { creation -> creation.shape == base }
    for (creation in creations) {
        if (creationGuarded(rule, creation, read.refinement, base)) continue
        diagnostics.add(Diagnostic("V12", "${read.demand}, but rule '${rule.name}' creates a '$base' without " +
            "checking that no '${read.refinement}' exists for it, so two could exist at once; condition the rule " +
            "on 'not exists ${read.refinement} for ...', or ${read.selectFix}"))
    }
}

/**
 * Anti-monotone: once an instance leaves R it can never re-enter, so guarded
 * creation bounds membership forever. v0's provable conjunct forms: `not
 * exists ...` (monotone while no rule deletes the witness — OQ37), and `not <flag>`
 * where every writer assigns literal true (a one-way latch, checked against
 * the same write set V1 walks). Returns null when proven, else the reason.
 */
private fun Validator.antiMonotoneFailure(refinement: String, base: String): String? {
    val conjuncts = refPredicateConjuncts(RefName(refinement))
        ?: return "'$refinement' is defined with 'or' or 'not' around its conditions, and the compiler reads only " +
            "a plain 'and' of 'not exists ...' and 'not <flag>' tests"
    return conjuncts.firstNotNullOfOrNull { conjunct -> reentryReason(conjunct, refinement, base) }
}

/** Why a record that left [refinement] could join it again through [conjunct], or null when it cannot. */
private fun Validator.reentryReason(conjunct: Expr, refinement: String, base: String): String? {
    val tested = (conjunct as? NotExpr)?.inner
    return when {
        tested is ExistsExpr -> deletedWitnessReason(tested, refinement, base)
        tested is PathExpr && tested.segs.isEmpty() -> unlatchedFlagReason(tested.root, refinement, base)
        else -> "a condition of '$refinement' is not a 'not exists ...' or 'not <flag>' test, so a record " +
            "that leaves '$refinement' could join it again"
    }
}

/** `not exists W`: the reason it fails when a rule deletes W's base, else null. [base] is the
 *  shape whose records the refinement holds, where a path in the test starts. */
private fun Validator.deletedWitnessReason(exists: ExistsExpr, refinement: String, base: String): String? {
    // 'not exists W' was anti-monotone because facts persisted; a
    // deleter of W makes re-entry by deletion possible (OQ37)
    val witnessBase = witnessShape(exists, base)?.let { shape -> model.baseOf(shape) } ?: return null
    if (witnessBase !in model.deletedShapes) return null
    return "a rule deletes '$witnessBase', so a record that left '$refinement' because a '$witnessBase' existed " +
        "could join it again when that '$witnessBase' is deleted"
}

/** The shape of the records `exists W for ...` or `exists (<source> where ...)` tests for: a shape
 *  named bare, the records a path from [base] reaches (`this.retirements`, `retirements`), or a
 *  `(W for ...)`. Those are the only sources the parser accepts in an `exists` (`parseBinding`).
 *  Null when a path does not resolve, which is refused with F1 elsewhere. */
private fun Validator.witnessShape(exists: ExistsExpr, base: String): String? {
    if (exists.shape != null) return exists.shape
    return when (val source = exists.collection?.bindings?.firstOrNull()?.source) {
        is PathExpr ->
            if (source.segs.isEmpty() && model.namesShapeOrRefinement(source.root)) source.root
            else model.pathElementShape(source, base)
        is ShapeForSource -> source.shape
        else -> null
    }
}

/** `not flag`: the reason it fails when a rule sets [flag] on [base] to anything but true, else null. */
private fun Validator.unlatchedFlagReason(flag: String, refinement: String, base: String): String? {
    val falseWriter = writes.firstOrNull { write ->
        write.owner == base && write.member.name == flag && write.value != BoolLit(true)
    } ?: return null
    return "rule '${falseWriter.rule.name}' sets '$flag' to something other than true, " +
        "so a record that left '$refinement' could join it again"
}

/** Flattened conjuncts of a refinement expression's predicates; null on or/not composition (fail closed). */
internal fun Validator.refPredicateConjuncts(refinementExpr: RefExpr): List<Expr>? = when (refinementExpr) {
    is RefName -> namedConjuncts(refinementExpr)
    is RefAnd -> {
        val leftConjuncts = refPredicateConjuncts(refinementExpr.left) ?: return null
        val rightConjuncts = refPredicateConjuncts(refinementExpr.right) ?: return null
        leftConjuncts + rightConjuncts
    }
    else -> null
}

/** A shape's or refinement's name with its `where`: the refinement's own conjuncts, then the `where`'s. */
private fun Validator.namedConjuncts(named: RefName): List<Expr>? {
    val definitionConjuncts = when (named.name) {
        in model.shapes -> emptyList()
        in model.refinements -> refPredicateConjuncts(model.refinements.getValue(named.name).expr)
        else -> null
    } ?: return null
    return definitionConjuncts + named.where?.let(::partsJoinedByAnd).orEmpty()
}

/** `this`: the rule's subject. */
private val thisPath = PathExpr("this")

/**
 * Does [rule]'s condition carry the entry guard for [refinement], correlated on a
 * field this [creation] populates with `this`? Accepted guard spellings:
 * `not exists R for this` (correlating field resolved by type match) and
 * `not exists (R where f == this)`.
 */
private fun Validator.creationGuarded(rule: RuleDecl, creation: Creation, refinement: String, base: String): Boolean {
    val scope = subjectScope(rule) ?: return false
    val correlated = fieldsSetToSubject(creation, base, scope)
    val conjuncts = refPredicateConjuncts(rule.condition) ?: return false
    return conjuncts.any { conjunct -> isEntryGuard(conjunct, refinement, base, scope, correlated) }
}

/** The fields [creation] sets to the subject: written `field = this`, or the one field of [base] a `for this` fills by type. */
private fun Validator.fieldsSetToSubject(creation: Creation, base: String, scope: String): Set<String> = buildSet {
    creation.fields.forEach { field -> if (field.value == thisPath) add(field.name) }
    if (creation.forExpr == thisPath) typeMatchedFields(base, thisPath, scope).singleOrNull()?.let { fieldName -> add(fieldName) }
}

/** [conjunct] is `not exists R for this` or `not exists (R where field == this)` over a [correlated] field. */
private fun Validator.isEntryGuard(conjunct: Expr, refinement: String, base: String, scope: String, correlated: Set<String>): Boolean {
    val exists = (conjunct as? NotExpr)?.inner as? ExistsExpr ?: return false
    if (exists.shape == refinement && exists.forExpr == thisPath)
        return typeMatchedFields(base, thisPath, scope).singleOrNull() in correlated
    val collection = exists.collection ?: return false
    return isCorrelatedQuery(collection, refinement, correlated)
}

/** `(R where field == this)` or `(R where this == field)`, with field one of [correlated]. */
private fun isCorrelatedQuery(collection: CollectionExpr, refinement: String, correlated: Set<String>): Boolean {
    if (collection.bindings.singleOrNull()?.source != PathExpr(refinement)) return false
    val equality = collection.where as? Binary ?: return false
    if (equality.op != "==") return false
    return isCorrelatedField(equality.left, equality.right, correlated) || isCorrelatedField(equality.right, equality.left, correlated)
}

/** [fieldSide] is a bare field of [correlated] and [subjectSide] is `this`. */
private fun isCorrelatedField(fieldSide: Expr, subjectSide: Expr, correlated: Set<String>): Boolean =
    subjectSide == thisPath && fieldSide is PathExpr && fieldSide.segs.isEmpty() && fieldSide.root in correlated
