package velle.validation

import velle.*
import velle.spec.PathStart
import velle.spec.ResolvedPath
import velle.spec.Scope
import velle.spec.UnresolvedStep

// ── path resolution (Reference §9, §10; checks F1, F2) ──────────────────────
//
// A path's root is `this`, an alias, a member of the scope, or a shape named
// as a membership atom or a collection source; each later step is a member of
// the record the step before it reached.

/** The shape of the records a member of [type] reaches: its record's, or its collection's. Null for a value. */
internal fun recordShapeOf(type: VType): String? = type.instanceShape() ?: (type as? VType.Coll)?.shape

/** The point of the spec the validator is checking at, as the resolver reads it: [scope] is the element. */
internal fun Validator.scopeAt(scope: String): Scope = Scope(
    element = scope,
    subject = thisScope ?: scope,
    recordNames = scopeByAlias.toMap(),
    valueNames = scalarTypeByAlias.keys - scopeByAlias.keys,
    narrowed = scopeByNarrowedPath.toMap(),
)

/** F1 for a name that names nothing, F2 for a read through an optional actor or through a value. */
internal fun Validator.resolvePath(path: PathExpr, scope: String) {
    // a narrowed prefix (`target is Post` governing this branch) shows the record present
    if (narrowedPrefix(path) == null) checkReadThroughOptionalActor(path)
    val resolved = model.paths.resolve(path, scopeAt(scope))
    when (resolved.start) {
        is PathStart.ValueName -> checkNoReadThroughValue(path)
        // a membership atom or a collection source
        is PathStart.ShapeName -> return
        else -> resolved.unresolved?.let { unresolved -> refuseUnresolved(path, resolved, unresolved) }
    }
}

private fun Validator.refuseUnresolved(path: PathExpr, resolved: ResolvedPath, unresolved: UnresolvedStep) {
    // a name after a value has no record to be a member of; F2 reads that elsewhere
    val shape = unresolved.readOn ?: return
    if (resolved.start is PathStart.Element && resolved.steps.isEmpty()) return refuseUnknownRoot(path, shape)
    val read = Printer.expr(PathExpr(path.root, path.segs.take(segmentsUpToUnresolved(resolved))))
    diagnostics.add(declaredSeparately(unresolved.name, shape, read) ?: Diagnostic("F1", "'${unresolved.name}' is not a member of '$shape'"))
}

/** How many of a path's names after its root are spelled up to and including the one that did not resolve. */
private fun segmentsUpToUnresolved(resolved: ResolvedPath): Int =
    // a path from the element spells its first step as its root
    if (resolved.start is PathStart.Element) resolved.steps.size else resolved.steps.size + 1

/** A rule's `by Shape?`: the actor may be absent on some path to the trigger (V31), so a read
 *  through it needs an `is some` first, as any optional does (Reference §10). */
private fun Validator.checkReadThroughOptionalActor(path: PathExpr) {
    val actor = ruleActor ?: return
    if (path.root != actor.name || !actor.optional || path.segs.isEmpty()) return
    diagnostics.add(Diagnostic("F2", "'${Printer.expr(path)}' reads through '${actor.name}', which may be absent here " +
        "(the rule's `by ${actor.shape}?`) — narrow with '${actor.name} is some' first (Reference §10)"))
}

private fun Validator.checkNoReadThroughValue(path: PathExpr) {
    if (path.segs.isEmpty()) return
    diagnostics.add(Diagnostic("F1", "'${Printer.expr(path)}' reads through '${path.root}', which is a value, not a record (a `for each` name over numbers or scalar values)"))
}

private fun Validator.refuseUnknownRoot(path: PathExpr, scope: String) {
    val actorHint = if (path.root == "actor" && model.shapes.values.any { shape -> shape.actor != null })
        " — 'actor' is what an act's `by` clause binds in the act's own scope, and what a rule reads " +
        "when its header declares `by <Shape>?` (Reference §22, \"The actor\")" else ""
    diagnostics.add(declaredSeparately(path.root, scope, "this.${path.root}") ?: Diagnostic("F1", "'${path.root}' is not a member of '$scope' " +
        "(bare names resolve in the innermost scope only — use this.<field> for the subject)$actorHint"))
}

/** F1 over a union (Reference §9): a name each operand declares for itself is not one declaration,
 *  so the message names the declarations and the two spellings, narrowing and `is`. */
private fun Validator.declaredSeparately(name: String, scope: String, read: String): Diagnostic? {
    if (!model.isCrossBase(scope)) return null
    val declaring = model.basesOf(scope).filter { base -> name in model.membersOf(base) }
    if (declaring.isEmpty()) return null
    val first = declaring.first()
    val spelled = declaring.joinToString(" and ") { base -> "'$base'" }
    return Diagnostic("F1", "'$name' is declared on $spelled separately, so it is not a member of '$scope'; " +
        "read it after narrowing, 'if ${read.substringBeforeLast(".")} is $first then $read else ...', " +
        "or declare '$name' once on a shape they take with 'is' (Reference §9)")
}
