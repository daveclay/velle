package velle.validation

import velle.*

// ── V22: closure declaration legality + V1 closure extension (Reference §6,
// "Inline part creation") ─────────────────────────────────────────────────
//
// Edge resolution (and its V22 diagnostics) lives in Model.closures — forced
// here. What this check adds is V1's closure self-pair: a closure commit
// makes N entrants of a part shape, so a rule triggered by it coincides with
// itself across sibling firings, and a body assigning through the language-
// populated back-reference provably converges on one container instance —
// a write-write conflict, refused totally (value-equal RHS included).

internal fun Validator.checkClosures() {
    val partEdges = partEdgesOfEveryClosure()
    if (partEdges.isEmpty()) return
    for (rule in model.rules.values) {
        val base = model.baseOfExpr(rule.condition) ?: continue
        partEdges.filter { edge -> base in model.basesOf(edge.partShape) }
            .forEach { edge -> checkNoAssignmentThroughBackRef(rule, base, edge) }
    }
}

/** Every part edge of every closure, nested ones included. A by-value edge (OQ63 §6.1) nests
 *  one record per commit, so no sibling multiplicity and no back-reference: only part edges
 *  carry the self-pair. */
private fun Validator.partEdgesOfEveryClosure(): List<ClosureEdge> {
    val partEdges = mutableListOf<ClosureEdge>()
    fun collect(edge: ClosureEdge) {
        if (!edge.byValue) partEdges.add(edge)
        edge.children.forEach { child -> collect(child) }
    }
    model.closures.values.flatten().forEach { edge -> collect(edge) }
    return partEdges
}

private fun Validator.checkNoAssignmentThroughBackRef(rule: RuleDecl, base: String, edge: ClosureEdge) {
    for (assignment in rule.body.filterIsInstance<Assignment>()) {
        if (!assignsThroughBackRef(assignment.target, edge)) continue
        diagnostics.add(Diagnostic("V1", "'${rule.name}' when ${base} assigns through " +
            "'${edge.backRef}' — at a closure commit this rule fires once per inline " +
            "'${base}', every firing writing the same field of the same container: a " +
            "proven write-write conflict. Derive the aggregate on the container, or " +
            "move the condition to the container for once-per-act granularity (Reference §6)"))
    }
}

/** Whether [target] writes a field of the record [edge]'s back-reference points at. */
private fun assignsThroughBackRef(target: PathExpr, edge: ClosureEdge): Boolean =
    (target.root == edge.backRef && target.segs.isNotEmpty()) ||
        (target.root == "this" && target.segs.size >= 2 && target.segs[0].name == edge.backRef)
