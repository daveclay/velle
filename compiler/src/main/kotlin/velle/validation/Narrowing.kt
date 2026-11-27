package velle.validation

import velle.*
import velle.spec.Narrowings
import velle.spec.ShapeNarrowing
import velle.spec.partsJoinedByAnd

// ── narrowing (Reference §10; V11's union slice) ────────────────────────────
//
// `path is Name` in a governing predicate licenses Name's own properties on
// that path inside the licensed branch: the then-branch of a conditional,
// the right side of a conjunction. Over a two-operand static union the
// else-branch narrows to the other operand — the complement is exhaustive
// by construction, since an instance has exactly one base.

/** The longest leading part of a path that is narrowed: its first [length] steps after the root hold a [shape]. */
internal data class NarrowedPrefix(val length: Int, val shape: String)

internal fun Validator.narrowedPrefix(path: PathExpr): NarrowedPrefix? {
    for (length in path.segs.size downTo 0)
        scopeByNarrowedPath[ShapeNarrowing.namesOf(PathExpr(path.root, path.segs.take(length)))]?.let { shape -> return NarrowedPrefix(length, shape) }
    return null
}

/** `actor is some` holding, or `actor is none` failing, on a rule's optional actor: present in the branch, as its shape. */
internal fun Validator.actorShownPresent(condition: Expr, holds: Boolean): Narrowings {
    val tests = if (holds) partsJoinedByAnd(condition) else listOf(condition)
    return tests.mapNotNull { test -> actorShownPresentBy(test, holds) }.toMap()
}

private fun Validator.actorShownPresentBy(test: Expr, holds: Boolean): Pair<List<String>, String>? {
    // `not (actor is none)` holding is `actor is none` failing
    if (test is NotExpr) return actorShownPresentBy(test.inner, !holds)
    val isTest = test as? IsExpr ?: return null
    val subject = isTest.subject as? PathExpr ?: return null
    val presentWhen = if (holds) "some" else "none"
    if (isTest.kind != presentWhen) return null
    val actor = optionalActorNamedBy(subject) ?: return null
    return ShapeNarrowing.namesOf(subject) to actor.shape
}

/** The rule's optional actor when [path] is its bare name, else null. */
private fun Validator.optionalActorNamedBy(path: PathExpr): RuleActor? =
    ruleActor?.takeIf { actor -> actor.optional && actor.name == path.root && path.segs.isEmpty() }

/** The declared type name a path resolves to — a shape, refinement, or static union — or null. */
internal fun Validator.pathTypeName(path: PathExpr, scope: String): String? =
    model.paths.resolve(path, scopeAt(scope)).endShape

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

internal inline fun Validator.withNarrowing(narrowings: Narrowings, body: () -> Unit) {
    val savedShapes = narrowings.keys.map { names -> names to scopeByNarrowedPath[names] }
    scopeByNarrowedPath.putAll(narrowings)
    try { body() } finally {
        savedShapes.forEach { (key, shape) -> if (shape == null) scopeByNarrowedPath.remove(key) else scopeByNarrowedPath[key] = shape }
    }
}

/** Checks [body] with [narrowings] the only ones in force: a walk's `until` reads another record than the one the tests around it narrowed. */
internal inline fun Validator.withOnlyNarrowing(narrowings: Narrowings, body: () -> Unit) {
    val narrowingsOutside = scopeByNarrowedPath.toMap()
    scopeByNarrowedPath.clear()
    scopeByNarrowedPath.putAll(narrowings)
    try { body() } finally {
        scopeByNarrowedPath.clear()
        scopeByNarrowedPath.putAll(narrowingsOutside)
    }
}
