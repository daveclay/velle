package velle.validation

import velle.*

// ── walks (Reference §7, "Walks") ───────────────────────────────────────────
//
// A walk follows one relationship of its own shape back to that shape. The
// construct ends by what it is — each record is visited once — so V14 has
// nothing to prove about it; what is checked is that the relationship exists
// and points back, that `until` walks a `one`, and that the depth is a number
// that does something.

internal fun Validator.checkWalk(walk: WalkExpr, scope: String) {
    val relationship = model.membersOf(scope)[walk.rel]
    if (relationship == null) {
        diagnostics.add(Diagnostic("F1", "'${Printer.expr(walk)}' — '${walk.rel}' is not a member of '$scope'"))
        return
    }
    targetWalkedBack(walk, relationship, scope) ?: return
    if (walk.until != null && relationship.type is VType.Coll) refuseUntilThroughMany(walk)
    walk.steps?.let { steps -> checkWalkSteps(walk, steps) }
    // the condition is asked of each record reached, with that record as `this` (Reference §7)
    walk.until?.let { condition ->
        val until = model.scopes.ofUntil(walk, scopeAt(scope))
        withThisScope(until.subject) { withOnlyNarrowing(until.narrowed) { checkExpr(condition, until.element) } }
    }
}

/** The shape [relationship] points at, when it is a relationship back to the walk's own shape; null, refused, otherwise. */
private fun Validator.targetWalkedBack(walk: WalkExpr, relationship: MemberInfo, scope: String): String? {
    val spelled = Printer.expr(walk)
    val target = recordShapeOf(relationship.type)
    if (target == null) {
        diagnostics.add(Diagnostic("F2", "'$spelled' — a walk follows a relationship, and '$scope.${walk.rel}' is not one"))
        return null
    }
    val ownBase = model.baseOf(scope) ?: scope
    val reachedBase = model.baseOf(target) ?: target
    if (model.typeAccepts(reachedBase, ownBase) || model.typeAccepts(ownBase, reachedBase)) return target
    diagnostics.add(Diagnostic("F2", "'$spelled' — a walk follows a relationship back to its own shape, " +
        "but '$scope.${walk.rel}' points at '$target'; walk a relationship of '$ownBase' that points at '$ownBase'"))
    return null
}

private fun Validator.refuseUntilThroughMany(walk: WalkExpr) {
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(walk)}' — 'until' walks a `one`: through the `many` '${walk.rel}' two records at the same " +
        "distance may both satisfy the condition, and \"the nearest\" has no single answer; walk a `one`, or " +
        "declare the set with `walk ${walk.rel}` and select from it with first(... by ...) (Reference §7)"))
}

private fun Validator.checkWalkSteps(walk: WalkExpr, steps: Int) {
    val spelled = Printer.expr(walk)
    if (steps < 1) diagnostics.add(Diagnostic("F2", "'$spelled' — a walk takes at least one step"))
    else if (steps == 1) diagnostics.add(Diagnostic("A2", "'$spelled' — one step through '${walk.rel}' is '${walk.rel}' itself; " +
        "read the relationship, or walk further", advisory = true))
}
