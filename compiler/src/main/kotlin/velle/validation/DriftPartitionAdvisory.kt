package velle.validation

import velle.*

// ── A4 (advisory): drift-act partitions ──────────────────────────
//
// An act is data — it persists — so a refinement of an act shape over
// mutable state is re-evaluated at every later change to that state: an
// act applied long ago drifts into the refused side when the state flips
// (a spurious firing per flip) and drifts back when it flips again (a
// stale re-fire). The signature: a rule triggered by a partition of an
// act on an `is <Refinement>` atom, whose body does not disarm
// its own trigger. Legitimate drift-reactive rules (the compensation
// pattern, windowed sweeps) pass, because they disarm — the disarm proof
// doubles as the anchor. Worked exhibit: examples/partition-drift/.

internal fun Validator.checkDriftExposedPartitions() {
    for (rule in model.rules.values) {
        if (rule.leaving) continue
        val actBase = exposedKeptActBase(rule) ?: continue
        val stateAtom = refinementStateAtom(rule) ?: continue
        if (guardAtoms(rule).any { guardAtom -> disarmed(rule, guardAtom) }) continue
        val atom = Printer.expr(stateAtom)
        diagnostics.add(Diagnostic("A4", "rule '${rule.name}' partitions act '$actBase' on mutable state " +
            "($atom) with no handled-anchor — acts persist, so every later flip of that state " +
            "re-partitions every '$actBase' ever committed: spurious firings on entry, stale re-fires " +
            "on return. Mark the act `transient` (the partition then evaluates once, at its " +
            "commit), anchor the partition with the outcome evidence the rule produces (scope it to " +
            "unhandled acts), or accept per-flip re-firing deliberately (examples/partition-drift/)",
            advisory = true))
    }
}

/** The base of [rule]'s subject when it is an exposed act that persists, null otherwise. */
private fun Validator.exposedKeptActBase(rule: RuleDecl): String? {
    val scope = subjectScope(rule) ?: return null
    val base = model.baseOf(scope) ?: return null
    if (base !in model.exposed) return null
    if (base in model.transients) return null // evaluated once, at the act's commit: drift cannot exist
    return base
}

/** The first `is <Refinement>` conjunct of [rule]'s condition, negated or not, null when there is none. */
private fun Validator.refinementStateAtom(rule: RuleDecl): Expr? {
    val conjuncts = refPredicateConjuncts(rule.condition) ?: return null
    return conjuncts.firstOrNull { conjunct ->
        val inner = (conjunct as? NotExpr)?.inner ?: conjunct
        inner is IsExpr && inner.kind == "refinement"
    }
}
