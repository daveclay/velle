package velle.validation

import velle.*

// ── narrowing (Reference §10; V11's union slice) ────────────────────────────
//
// `path is Name` in a governing predicate licenses Name's own properties on
// that path inside the licensed branch: the then-branch of a conditional,
// the right side of a conjunction. Over a two-operand static union the
// else-branch narrows to the other operand — the complement is exhaustive
// by construction, since an instance has exactly one base.

/** A path a test shows to hold a record of [shape] in the branch it governs. */
internal data class NarrowedPath(val path: PathExpr, val shape: String)

/** The longest leading part of a path that is narrowed: its first [length] steps after the root hold a [shape]. */
internal data class NarrowedPrefix(val length: Int, val shape: String)

private fun Validator.pathKey(path: PathExpr) = path.root + path.segs.joinToString("") { segment -> "." + segment.name }

internal fun Validator.narrowedPrefix(path: PathExpr): NarrowedPrefix? {
    for (length in path.segs.size downTo 0)
        scopeByNarrowedPath[pathKey(PathExpr(path.root, path.segs.take(length)))]?.let { shape -> return NarrowedPrefix(length, shape) }
    return null
}

internal fun Validator.narrowingsOf(condition: Expr, scope: String): List<NarrowedPath> =
    predicateConjuncts(condition).mapNotNull { conjunct -> narrowingOfConjunct(conjunct, scope) }

private fun Validator.narrowingOfConjunct(conjunct: Expr, scope: String): NarrowedPath? {
    // `x is not A` holding is `x is A` failing: the rest of the union (Reference §10)
    if (conjunct is NotExpr) return complementNarrowingsOf(conjunct.inner, scope).singleOrNull()
    val test = conjunct as? IsExpr ?: return null
    val subject = test.subject as? PathExpr ?: return null
    return when (test.kind) {
        // a name the spec does not declare is refused where it is written, and narrows nothing
        "refinement" -> test.refinement!!.takeIf { named -> model.namesShapeOrRefinement(named) }?.let { named -> NarrowedPath(subject, named) }
        // `actor is some` on a rule's optional actor: present in this branch
        "some" -> optionalActorNamedBy(subject)?.let { actor -> NarrowedPath(subject, actor.shape) }
        else -> null
    }
}

/** The rule's optional actor when [path] is its bare name, else null. */
private fun Validator.optionalActorNamedBy(path: PathExpr): RuleActor? =
    ruleActor?.takeIf { actor -> actor.optional && actor.name == path.root && path.segs.isEmpty() }

/** What a test failing shows: over a union, the operands it did not name (Reference §10). */
internal fun Validator.complementNarrowingsOf(condition: Expr, scope: String): List<NarrowedPath> {
    // `if x is not A then ... else <x is A>`
    if (condition is NotExpr)
        return narrowingsOf(condition.inner, scope).filter { narrowed -> (condition.inner as? IsExpr)?.refinement == narrowed.shape }
    val test = condition as? IsExpr ?: return emptyList()
    val subject = test.subject as? PathExpr ?: return emptyList()
    // `if actor is none then ... else <present>` on a rule's optional actor
    if (test.kind == "none") optionalActorNamedBy(subject)?.let { actor -> return listOf(NarrowedPath(subject, actor.shape)) }
    if (test.kind != "refinement") return emptyList()
    return listOfNotNull(residualNarrowing(test, subject, scope))
}

/** `x is A` failing over a union: `x` holds what remains of its declared type without `A`. */
private fun Validator.residualNarrowing(test: IsExpr, subject: PathExpr, scope: String): NarrowedPath? {
    val declared = pathTypeName(subject, scope) ?: return null
    val named = test.refinement ?: return null
    val rest = model.residualOf(declared, named) ?: return null
    return NarrowedPath(subject, rest)
}

/** The declared type name a path resolves to — a shape, refinement, or static union — or null. */
internal fun Validator.pathTypeName(path: PathExpr, scope: String): String? {
    narrowedPrefix(path)?.let { prefix -> return typeNameAfterPrefix(path, prefix) }
    if (path.root == "this" && path.segs.isEmpty()) return thisScope ?: scope
    return model.pathElementShape(path, scope, scopeByAlias, thisScope ?: scope)
}

private fun Validator.typeNameAfterPrefix(path: PathExpr, prefix: NarrowedPrefix): String? {
    var shape = prefix.shape
    for (segment in path.segs.drop(prefix.length))
        shape = model.membersOf(shape)[segment.name]?.type?.instanceShape() ?: return null
    return shape
}

/** Checks [body] with what [condition] shows where it [holds] or where it fails. */
internal inline fun Validator.withPresence(condition: Expr, holds: Boolean, scope: String, body: () -> Unit) {
    val saved = branchNarrowing
    branchNarrowing = saved + model.types.narrowingOf(condition, holds, scope, typeEnv())
    try { body() } finally { branchNarrowing = saved }
}

/** V11: a plain dot after a step that may be absent, `site.region` with `site: one Site?` and no
 *  test showing `site` present, names the step and the two spellings that read it. */
internal fun Validator.checkDotsLicensed(path: PathExpr, scope: String) {
    val step = model.types.untestedStep(path, scope, typeEnv()) ?: return
    // the `?.` goes on the dot after the step: the root is a step of its own unless it is `this`
    val dotAfterStep = step.size - (if (path.root == "this") 0 else 1)
    val licensed = PathExpr(path.root, path.segs.mapIndexed { index, segment -> if (index == dotAfterStep) segment.copy(viaQdot = true) else segment })
    refuseUnlicensedDot(path, step, licensed)
}

/** V11 for the steps after a selection or another non-path expression: the dot directly after it
 *  needs no test, and a plain dot after a later step that may be absent is refused as in a path. */
internal fun Validator.checkDotsLicensed(access: Access, scope: String) {
    val step = model.types.untestedStepAfter(access, scope, typeEnv()) ?: return
    // the step's own name is the last of [step]; its root is the target as written, one name
    val dotAfterStep = step.size - 1
    val licensed = access.copy(segs = access.segs.mapIndexed { index, segment -> if (index == dotAfterStep) segment.copy(viaQdot = true) else segment })
    refuseUnlicensedDot(access, step, licensed)
}

private fun Validator.refuseUnlicensedDot(read: Expr, step: List<String>, licensed: Expr) {
    val stepSpelled = step.joinToString(".")
    diagnostics.add(Diagnostic("V11", "'${Printer.expr(read)}' reads through '$stepSpelled', which may be absent; " +
        "test '$stepSpelled is some' first, or write '${Printer.expr(licensed)}' to read absent when it is (Reference §10)"))
}

internal inline fun Validator.withNarrowing(narrowings: List<NarrowedPath>, body: () -> Unit) {
    val savedShapes = narrowings.map { narrowed -> pathKey(narrowed.path) to scopeByNarrowedPath[pathKey(narrowed.path)] }
    narrowings.forEach { narrowed -> scopeByNarrowedPath[pathKey(narrowed.path)] = narrowed.shape }
    try { body() } finally {
        savedShapes.forEach { (key, shape) -> if (shape == null) scopeByNarrowedPath.remove(key) else scopeByNarrowedPath[key] = shape }
    }
}
