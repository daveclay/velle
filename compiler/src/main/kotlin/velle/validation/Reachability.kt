package velle.validation

import velle.*

// ── V3: reachability ─────────────────────────────────────────────────────

internal fun Validator.checkReachability(rule: RuleDecl) {
    val problem = reachabilityProblem(rule) ?: return
    diagnostics.add(Diagnostic("V3", problem))
}

/** Why [rule] can never fire, or under-fires; null where it can fire. */
private fun Validator.reachabilityProblem(rule: RuleDecl): String? {
    val schedules = rule.triggers.filter { it != "commit" }
    val bases = model.basesOfExpr(rule.condition)
    val base = bases.joinToString(" or ")
    if (leavesBareShapeOnlyByDeletion(rule, bases))
        return "rule '${rule.name}' can never fire — '$base' is a bare shape with no " +
            "predicate to flip, and 'when leaving' never fires at deletion; a record leaves a bare shape " +
            "only by being deleted. Did you mean 'when deleted $base'? (Reference §13)"
    if (rule.trigger == Trigger.DELETED && bases.isNotEmpty() && bases.none { it in model.deletedShapes })
        return "rule '${rule.name}' can never fire — no rule deletes '$base' (Reference §13)"
    if (agingIsUnobserved(rule, bases, schedules))
        return "rule '${rule.name}' — its condition depends on the passage of time, " +
            "which no act commit changes: entry by aging is unobserved. Add a schedule to 'on', or this rule under-fires (Reference §11)"
    if (schedules.isNotEmpty()) return null // tick-served
    if (possibleCommits.none { affects(it, rule) })
        return "rule '${rule.name}' can never fire — no act declaration, rule effect, or tick " +
            "in the spec can affect its condition (Reference §11, §22)"
    return null
}

/** The bare-shape edge (OQ37-R17): a bare shape has no predicate to flip,
 *  and `when leaving` never fires at deletion — a record leaves a bare
 *  shape only by being deleted (Reference §13). A static union is bare the
 *  same way: entry is creation of an operand instance (Reference §9). */
private fun Validator.leavesBareShapeOnlyByDeletion(rule: RuleDecl, bases: Set<String>): Boolean {
    val bareShape = (rule.condition as? RefName)
        ?.let { it.where == null && (it.name in model.shapes || model.isStaticUnion(it.name)) } == true
    return bareShape && rule.trigger.onPredicateExit && !rule.trigger.onDeletion && bases.none { it in model.transients }
}

/** A transient act's partition is evaluated exactly once, at the act's
 *  own commit (Reference §4, "Transient acts"): the act cannot age into a
 *  state, and V17 bans a schedule on it — so a time-dependent condition
 *  over a transient act is not under-firing, it is read at the request. */
private fun Validator.agingIsUnobserved(rule: RuleDecl, bases: Set<String>, schedules: List<String>): Boolean {
    val transientSubject = bases.isNotEmpty() && bases.all { it in model.transients }
    return conditionSummary(rule).readsTime && schedules.isEmpty() && !transientSubject
}
