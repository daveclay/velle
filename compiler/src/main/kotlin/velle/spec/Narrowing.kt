package velle.spec

import velle.Binary
import velle.Expr
import velle.IsExpr
import velle.Model
import velle.NotExpr
import velle.PathExpr

// ── What an `is` test shows about a record's shape (Reference §10) ────────────
//
// `target is Post` governing a branch means `target` holds a Post there, so a name only Post
// declares can be read on it: on the right of an `and`, in the `then` of an `if`. Over a static
// union the test failing shows the rest of the union: in `Content = Post or Photo`, `target is
// not Post` holding shows a Photo.

/** A path, named by its names after any leading `this`, and the shape a test shows its record to be. */
typealias Narrowings = Map<List<String>, String>

/** Works out what a condition shows about the shape of the records its paths reach. */
class ShapeNarrowing(private val model: Model, private val paths: PathResolver) {

    /** What [condition] shows where it [holds], or where it fails when [holds] is false, read at [scope]. */
    fun shownBy(condition: Expr, holds: Boolean, scope: Scope): Narrowings = when {
        // `x is not A` holding is `x is A` failing
        condition is NotExpr && condition.inner is IsExpr -> shownBy(condition.inner, !holds, scope)
        // a conjunction holding is both sides holding
        condition is Binary && condition.op == "and" && holds -> shownBy(condition.left, true, scope) + shownBy(condition.right, true, scope)
        // a disjunction failing is both sides failing
        condition is Binary && condition.op == "or" && !holds -> shownBy(condition.left, false, scope) + shownBy(condition.right, false, scope)
        condition is IsExpr && condition.kind == "refinement" -> shownByTest(condition, holds, scope)
        else -> emptyMap()
    }

    private fun shownByTest(test: IsExpr, holds: Boolean, scope: Scope): Narrowings {
        val path = test.subject as? PathExpr ?: return emptyMap()
        val named = test.refinement ?: return emptyMap()
        // a name the spec does not declare is refused where it is written, and narrows nothing
        if (!model.namesShapeOrRefinement(named)) return emptyMap()
        if (holds) return mapOf(namesOf(path) to named)
        val declared = paths.resolve(path, scope).endShape ?: return emptyMap()
        val rest = model.residualOf(declared, named) ?: return emptyMap()
        return mapOf(namesOf(path) to rest)
    }

    companion object {
        /** A path's names after any leading `this`: the key [Narrowings] uses. */
        fun namesOf(path: PathExpr): List<String> =
            (if (path.root == "this") emptyList() else listOf(path.root)) + path.segs.map { seg -> seg.name }
    }
}
