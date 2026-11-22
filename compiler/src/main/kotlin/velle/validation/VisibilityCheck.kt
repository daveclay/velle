package velle.validation

import velle.*

// ── visibility (Reference §22, "The actor"; OQ33 §5.7–§5.10) ─────────────
//
// `visible to <Shape> [as name] [where p]` on a shape body (record-level) or a
// field, `visible to anyone` for a public one, a top-level clause as the
// spec-wide default. Applied by the read surface only; here the clauses are
// checked as predicates over the shape with the viewer bound, and V32 makes
// every readable shape say what it is once the spec has any authorization.

internal fun Validator.checkVisibility() {
    model.visibilityDefault?.let { clause -> checkVisibleClause(clause, null, "the spec-wide 'visible to'") }
    for ((name, shape) in model.shapes) checkShapeVisibility(name, shape)
    for ((name, refinement) in model.refinements) {
        fieldClausesWithNames(refinement.members).forEach { (fieldName, clause) -> checkVisibleClause(clause, name, "'$name.$fieldName'") }
    }
    if (!model.hasAuthorization) return
    checkEveryReadableShapeDeclaresVisibility()
}

private fun Validator.checkShapeVisibility(name: String, shape: ShapeDecl) {
    val recordClauses = shape.members.filterIsInstance<VisibleClause>()
    val fieldClauses = fieldClausesWithNames(shape.members)
    if (name in model.transients && (recordClauses.isNotEmpty() || fieldClauses.isNotEmpty()))
        diagnostics.add(Diagnostic("F3", "'$name' is a transient act, never read, and declares visibility"))
    recordClauses.forEach { clause -> checkVisibleClause(clause, name, "'$name'") }
    fieldClauses.forEach { (fieldName, clause) -> checkVisibleClause(clause, name, "'$name.$fieldName'") }
}

/** Each field's `visible to` clauses, with the name of the field that declares it. */
private fun Validator.fieldClausesWithNames(members: List<Member>): List<Pair<String?, VisibleClause>> =
    members.flatMap { member -> model.fieldClausesOf(member).map { clause -> memberName(member) to clause } }

/** Checks one clause: its viewer is a shape, and its `where` reads [scope] with the viewer bound.
 *  [scope] is null for the spec-wide default, which has no shape to read. */
private fun Validator.checkVisibleClause(clause: VisibleClause, scope: String?, where: String) {
    val viewerShape = clause.shape ?: return
    if (viewerShape !in model.shapes && viewerShape !in model.refinements) {
        diagnostics.add(Diagnostic("F1", "$where — 'visible to $viewerShape' names unknown shape '$viewerShape'"))
        return
    }
    model.basesOf(viewerShape).filter { base -> base in model.exposed }.forEach { actBase ->
        diagnostics.add(Diagnostic("F2", "$where — 'visible to $viewerShape': a viewer's shape is a `shape`, never an act " +
            "('$actBase' is one): the person a record is shown to is a record (Reference §22, \"The actor\")"))
    }
    if (scope == null) return
    if (model.membersOf(scope).containsKey(clause.name))
        diagnostics.add(Diagnostic("F3", "$where — binds the viewer as '${clause.name}', which is already a member of '$scope'" +
            " — name the viewer: `visible to $viewerShape as <name>`"))
    clause.where?.let { predicate ->
        scopeByAlias[clause.name] = viewerShape
        try { checkExpr(predicate, scope) } finally { scopeByAlias.remove(clause.name) }
    }
}

/** V32: once a spec has authorization, every readable shape declares its visibility. */
private fun Validator.checkEveryReadableShapeDeclaresVisibility() {
    for (name in model.shapes.keys) {
        if (name in model.transients) continue
        if (model.recordVisibility(name).isNotEmpty()) continue
        diagnostics.add(Diagnostic("V32", "'$name' declares no visibility, and this spec has authorization — say who may " +
            "see it: a `visible to <Shape> [where ...]` clause in its body, `visible to anyone` for a public one, " +
            "or a spec-wide `visible to <Shape>` at the top of the spec (Reference §22, \"The actor\")"))
    }
}
