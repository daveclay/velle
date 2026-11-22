package velle.validation

import velle.*

// ── path resolution (Reference §9, §10; checks F1, F2) ──────────────────────
//
// A path's root is `this`, an alias, a member of the scope, or a shape named
// as a membership atom or a collection source; each later step is a member of
// the record the step before it reached.

/** The shape of the records a member of [type] reaches: its record's, or its collection's. Null for a value. */
internal fun recordShapeOf(type: VType): String? = type.instanceShape() ?: (type as? VType.Coll)?.shape

internal fun Validator.resolvePath(path: PathExpr, scope: String) {
    // a narrowed prefix (`target is Post` governing this branch) resolves the
    // rest of the path against the narrowed scope — V11's union slice
    narrowedPrefix(path)?.let { prefix -> walkSegments(prefix.shape, path.segs.drop(prefix.length), path); return }
    checkReadThroughOptionalActor(path)
    val rootShape: String? = when {
        path.root == "this" -> thisScope ?: scope
        path.root in scopeByAlias -> scopeByAlias[path.root]
        path.root in scalarTypeByAlias -> {
            checkNoReadThroughValue(path)
            return
        }
        isShapeOrRefinement(path.root) -> return // membership atom / collection source
        else -> memberRootShape(path, scope) ?: return
    }
    walkSegments(rootShape, path.segs, path)
}

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

/** The shape a root that names a member of [scope] reaches; F1 when it names no member. */
private fun Validator.memberRootShape(path: PathExpr, scope: String): String? {
    val member = model.membersOf(scope)[path.root]
    if (member == null) {
        refuseUnknownRoot(path, scope)
        return null
    }
    return recordShapeOf(member.type)
}

private fun Validator.refuseUnknownRoot(path: PathExpr, scope: String) {
    val actorHint = if (path.root == "actor" && model.shapes.values.any { shape -> shape.actor != null })
        " — 'actor' is what an act's `by` clause binds in the act's own scope, and what a rule reads " +
        "when its header declares `by <Shape>?` (Reference §22, \"The actor\")" else ""
    diagnostics.add(declaredSeparately(path.root, scope, "this.${path.root}") ?: Diagnostic("F1", "'${path.root}' is not a member of '$scope' " +
        "(bare names resolve in the innermost scope only — use this.<field> for the subject)$actorHint"))
}

/** Each of [segments] is a member of the record the step before it reached, from [start]. */
private fun Validator.walkSegments(start: String?, segments: List<Seg>, path: PathExpr) {
    var current = start
    for (segment in segments) {
        val shape = current ?: return
        val member = model.membersOf(shape)[segment.name]
        if (member == null) {
            val read = Printer.expr(PathExpr(path.root, path.segs.take(path.segs.indexOf(segment) + 1)))
            diagnostics.add(declaredSeparately(segment.name, shape, read) ?: Diagnostic("F1", "'${segment.name}' is not a member of '$shape'"))
            return
        }
        current = recordShapeOf(member.type)
    }
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
