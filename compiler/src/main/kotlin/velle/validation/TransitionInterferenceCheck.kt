package velle.validation

import velle.*

// ── V15 (third leg): transition interference ─────────────────────────────
//
// Two unordered siblings can each flip one refinement's membership — by
// assigning a field its predicate reads, or by creating an instance of a
// shape it consults. Whether the mid-transaction membership history has a
// transition at all — does the instance pass through the refinement
// between the two effects? — then depends on which sibling fired first
// (checks.md V15; OQ16's value-dependent interference case, fail-closed:
// statically there is only "both inputs affected by unordered siblings").
// An unobserved refinement is skipped — with no observer, the
// mid-transaction history is not part of the outcome. Observers are rules
// whose conditions depend on the refinement (transitively), and the
// refinement's own captures: a capture evaluates at the entry commit and
// persists, so it observes the history with no rule involved. Captures
// add a channel of their own (probe E): the captured VALUE reads fields —
// a sibling assigning one while another causes the entry makes the
// captured value depend on their order.

/** A refinement something observes, with what the check needs to know about it. */
private class ObservedRefinement(
    val name: String,
    /** The refinement's captured properties. */
    val captures: List<DerivedProp>,
    /** Who observes the mid-transaction history, as the diagnostic says it. */
    val observer: String,
    /** What the refinement's predicate reads. */
    val predicateReads: ReadSummary,
    /** The shapes whose records are members: one base, or each operand of a union (Reference §9). */
    val bases: Set<String>,
    val signByConsult: Map<String, Int>,
    /** What the captured expressions read. */
    val capturedReads: ReadSummary,
)

internal fun Validator.checkTransitionInterference() {
    val commitRules = onCommitRules()
    if (commitRules.size < 2) return
    val watcherByRefinement = firstWatcherByRefinement()
    for ((refinementName, refinement) in model.refinements) {
        val observed = observedRefinement(refinementName, refinement, watcherByRefinement[refinementName]) ?: continue
        checkSiblingPairs(observed, commitRules)
    }
}

/** Each refinement watched by a rule, with the first rule watching it. */
private fun Validator.firstWatcherByRefinement(): Map<String, RuleDecl> {
    val watcherByRefinement = mutableMapOf<String, RuleDecl>()
    for (rule in model.rules.values) watchedRefinements(rule).forEach { watcherByRefinement.putIfAbsent(it, rule) }
    return watcherByRefinement
}

/** The refinement as the check sees it, or null where nothing observes it. */
private fun Validator.observedRefinement(name: String, refinement: RefinementDecl, watcher: RuleDecl?): ObservedRefinement? {
    val captures = refinement.members.filterIsInstance<DerivedProp>().filter { it.captured }
    if (watcher == null && captures.isEmpty()) return null
    val observer = watcher?.let { "rule '${it.name}' watches it" } ?: "its captures record the entry"
    val capturedReads = ReadSummary().also { reads ->
        captures.forEach { model.collectExpr(it.expr, name, reads) }
    }
    return ObservedRefinement(
        name, captures, observer,
        predicateReads = model.predicateSummary(name),
        bases = model.basesOf(name),
        signByConsult = refinementPolarities(name).signByConsult,
        capturedReads = capturedReads,
    )
}

/** Checks every ordered pair of distinct on-commit rules against [observed]. */
private fun Validator.checkSiblingPairs(observed: ObservedRefinement, commitRules: List<RuleDecl>) {
    for (firstIndex in commitRules.indices) for (secondIndex in commitRules.indices) {
        if (firstIndex == secondIndex) continue
        checkSiblingPair(observed, commitRules[firstIndex], commitRules[secondIndex], reportOrder = firstIndex < secondIndex)
    }
}

/** [reportOrder] is true for one of the two orderings of each pair, so the
 *  order diagnostic is reported once per pair. */
private fun Validator.checkSiblingPair(observed: ObservedRefinement, first: RuleDecl, second: RuleDecl, reportOrder: Boolean) {
    if (!canInterfere(first, second)) return
    val firstFlips = membershipFlips(first, observed)
    val secondFlips = membershipFlips(second, observed)
    if (reportOrder && orderDecidesTransition(firstFlips, secondFlips)) {
        diagnostics.add(Diagnostic("V15", "'${first.name}' (${firstFlips.keys.joinToString(", ")}) and sibling '${second.name}' (${secondFlips.keys.joinToString(", ")}) " +
            "each affect membership in '${observed.name}', and ${observed.observer}: whether the transaction passes " +
            "through '${observed.name}' between the two effects depends on their unstated order; " +
            "state the intent — condition one rule on the other's outcome, move both effects " +
            "into one rule, or move one rule to `after commit` so it runs as its own " +
            "transaction and sees the other's outcome already settled (OQ16)"))
    }
    // one sibling causes the entry, the other writes what the
    // captured expressions read: the captured value depends on order
    if (observed.captures.isEmpty() || firstFlips.isEmpty()) return
    for (what in readConflicts(second, observed.capturedReads))
        diagnostics.add(Diagnostic("V15", "'${first.name}' can cause entry into '${observed.name}', whose captures " +
            "read $what, which sibling '${second.name}' writes — the captured value depends on " +
            "their unstated order; state the intent (OQ16)"))
}

/** Both siblings can flip membership, and not provably in the
 *  same direction: whether the transaction passes through the
 *  refinement depends on their order. Same-direction effects
 *  are safe — the flip lands at whichever effect completes the
 *  predicate, in both orders (two prerequisites of one
 *  conjunction never race each other). */
private fun orderDecidesTransition(firstFlips: Map<String, Int>, secondFlips: Map<String, Int>): Boolean {
    val signs = (firstFlips.values + secondFlips.values).toSet()
    val sameDirection = signs.size == 1 && signs.single() != 0
    return firstFlips.isNotEmpty() && secondFlips.isNotEmpty() && (firstFlips.keys + secondFlips.keys).size >= 2 && !sameDirection
}

/** How [rule] can flip membership in [observed]: effect → sign
 *  (+1 toward membership, -1 away, 0 both ways). Field writes are
 *  value-dependent (0). A creation flips only through a consult a
 *  fresh instance can satisfy, at that consult's polarity — and
 *  creating the base shape itself is entry (+) unless the
 *  predicate provably excludes fresh instances. */
private fun Validator.membershipFlips(rule: RuleDecl, observed: ObservedRefinement): Map<String, Int> {
    val effects = ruleEffects(rule)
    val reads = observed.predicateReads
    if (reads.opaque)
        return (effects.assignedFields.map { (owner, field) -> "$owner.$field" } +
            effects.createdShapes.map { "creates '$it'" } +
            effects.deletedShapes.map { "deletes '$it'" }).associateWith { 0 }
    val fieldFlips = effects.assignedFields.intersect(reads.fields + reads.collFields)
        .associate { (owner, field) -> "$owner.$field" to 0 }
    val creationFlips = effects.createdShapes.mapNotNull { createdShape ->
        creationSign(createdShape, effects.createdShapes, observed)?.let { sign -> "creates '$createdShape'" to sign }
    }.toMap()
    val deletionFlips = effects.deletedShapes.mapNotNull { deletedShape ->
        deletionSign(deletedShape, observed)?.let { sign -> "deletes '$deletedShape'" to sign }
    }.toMap()
    return fieldFlips + creationFlips + deletionFlips
}

/** The sign at which creating [createdShape] flips membership, or null where it cannot. */
private fun Validator.creationSign(createdShape: String, createdShapes: Set<String>, observed: ObservedRefinement): Int? {
    if (createdShape in observed.bases && !freshCannotEnter(observed.name, createdShapes)) return +1
    val signs = observed.signByConsult.keys.filter { model.baseOf(it) == createdShape }
        .filter { it in model.shapes || !freshCannotEnter(it, createdShapes) }
        .map { observed.signByConsult.getValue(it) }
    if (signs.isEmpty()) return null
    return singleDirection(signs)
}

/** Deletion mirrors creation with the sign negated: it can only
 *  unsatisfy a consult; the deleted base instance can only leave;
 *  reads through references at the shape flip value-dependently. */
private fun Validator.deletionSign(deletedShape: String, observed: ObservedRefinement): Int? {
    if (deletedShape in observed.bases) return -1
    val signs = observed.signByConsult.keys.filter { model.baseOf(it) == deletedShape }
        .map { -observed.signByConsult.getValue(it) }
    val throughReference = readsReferenceTo(observed.predicateReads, deletedShape)
    if (signs.isEmpty() && !throughReference) return null
    return if (throughReference) 0 else singleDirection(signs)
}

/** The one nonzero sign all of [signs] share, else 0 (both ways). */
private fun singleDirection(signs: List<Int>): Int =
    signs.toSet().singleOrNull()?.takeIf { it != 0 } ?: 0
