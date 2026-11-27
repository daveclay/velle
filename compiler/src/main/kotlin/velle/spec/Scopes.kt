package velle.spec

import velle.Binary
import velle.Binding
import velle.CollectionExpr
import velle.Expr
import velle.ForEach
import velle.IfExpr
import velle.LoopSource
import velle.Model
import velle.PathExpr
import velle.SetExpr
import velle.ShapeForSource
import velle.VType
import velle.WalkExpr

// ── The scope each part of an expression is read at (Reference §7, §10) ───────
//
// An expression read at one [Scope] reads some of its parts at another: the right side
// of an `and` where its left side holds, a branch of an `if` under what its test shows,
// a filter's `where` on each record of the collection, a walk's `until` on each record
// the walk reaches. Every part of the compiler that walks an expression asks
// [ScopeRules] for the scope of each part, so that two walks cannot read one name
// against different records.

/** What one binding of a collection, `loans as loan`, or one name of a `for each` runs over. */
sealed interface BoundElement {
    /** Records of [shape]. */
    data class Records(val shape: String) : BoundElement

    /** Values of a `many` of values, such as `options` in `(options as option where ...)`. */
    data object Values : BoundElement

    /** A source whose records cannot be told. */
    data object Unknown : BoundElement
}

/**
 * The scopes a collection such as `(loans as loan, loan.fines where amount > 0)` is read at.
 * Each binding's source is read at its entry of [sources], which has the `as` names of the
 * bindings before it. [element] is the shape of the records the collection holds, null when
 * it cannot be told. The `where` is read at [where].
 */
data class FilterScopes(val sources: List<Scope>, val element: String?, val where: Scope)

/** Answers which scope each part of an expression is read at. */
class ScopeRules(private val model: Model, private val paths: PathResolver, private val narrowing: ShapeNarrowing) {

    /** The scope of the right side of [binary]: `a and b` reads `b` where `a` holds, `a or b` reads `b` where `a` fails. */
    fun ofRightSide(binary: Binary, scope: Scope): Scope = when (binary.op) {
        "and" -> scope.narrowedBy(narrowing.shownBy(binary.left, holds = true, scope))
        "or" -> scope.narrowedBy(narrowing.shownBy(binary.left, holds = false, scope))
        else -> scope
    }

    /** The scope of the `then` branch: what the test shows where it holds is in force. */
    fun ofThen(conditional: IfExpr, scope: Scope): Scope =
        scope.narrowedBy(narrowing.shownBy(conditional.condition, holds = true, scope))

    /** The scope of the `else` branch: what the test shows where it fails is in force. */
    fun ofElse(conditional: IfExpr, scope: Scope): Scope =
        scope.narrowedBy(narrowing.shownBy(conditional.condition, holds = false, scope))

    /** The scopes [collection] is read at when the expression that holds it is read at [scope]. */
    fun ofFilter(collection: CollectionExpr, scope: Scope): FilterScopes {
        val sources = mutableListOf<Scope>()
        var bound: String? = null
        var scopeSoFar = scope
        for (binding in collection.bindings) {
            sources.add(scopeSoFar)
            val element = elementOf(binding, scopeSoFar)
            if (element is BoundElement.Records) bound = element.shape
            scopeSoFar = withNameOf(binding, element, scopeSoFar)
        }
        // a filter that names a refinement, `(claims where PaidClaim)`, holds that refinement's members
        val element = bound?.let { shape -> collection.where?.let { where -> model.membershipAtomOf(where, shape) } ?: shape }
        // the `where` reads bare names on the innermost element, while `this` keeps naming the subject
        return FilterScopes(sources, element, scopeSoFar.inside(element ?: scope.element))
    }

    /** What [binding] runs over when its source is read at [scope]. */
    fun elementOf(binding: Binding, scope: Scope): BoundElement = when (val source = binding.source) {
        // a `many` of values is filtered by naming each value (Reference §5); with no name it has nothing a `where` could read
        is PathExpr -> elementOfPath(source, scope).takeUnless { element -> element == BoundElement.Values && binding.alias == null } ?: BoundElement.Unknown
        is ShapeForSource -> BoundElement.Records(source.shape)
        is WalkExpr -> elementOfWalk(source, scope)
        else -> BoundElement.Unknown
    }

    /** What a `for each` name runs over when its source is read at [scope] (Reference §14); a range runs over numbers. */
    fun elementOfLoop(source: LoopSource, scope: Scope): BoundElement {
        val collection = (source as? LoopSource.Over)?.expr ?: return BoundElement.Values
        return when (collection) {
            is PathExpr -> elementOfPath(collection, scope)
            is SetExpr -> ofFilter(collection.collection, scope).element?.let { shape -> BoundElement.Records(shape) } ?: BoundElement.Unknown
            is WalkExpr -> elementOfWalk(collection, scope)
            else -> BoundElement.Unknown
        }
    }

    /**
     * The scope of the body of [block] (Reference §14): each name is bound to what it runs over, and each
     * source reads the names before it. A name whose source holds no records is bound to a value, which no
     * member resolves on.
     */
    fun ofBlock(block: ForEach, scope: Scope): Scope =
        block.bindings.fold(scope) { scopeSoFar, binding ->
            when (val element = elementOfLoop(binding.source, scopeSoFar)) {
                is BoundElement.Records -> scopeSoFar.withRecordName(binding.name, element.shape)
                BoundElement.Values, BoundElement.Unknown -> scopeSoFar.withValueName(binding.name)
            }
        }

    /** The records or values a path names as a collection: a shape named on its own is its records. */
    private fun elementOfPath(source: PathExpr, scope: Scope): BoundElement {
        if (source.segs.isEmpty() && model.namesShapeOrRefinement(source.root)) return BoundElement.Records(source.root)
        val resolved = paths.resolve(source, scope)
        resolved.endShape?.let { shape -> return BoundElement.Records(shape) }
        return if (resolved.endType is VType.CollS) BoundElement.Values else BoundElement.Unknown
    }

    private fun elementOfWalk(walk: WalkExpr, scope: Scope): BoundElement =
        model.walkTarget(walk, scope.element)?.let { shape -> BoundElement.Records(shape) } ?: BoundElement.Unknown

    private fun withNameOf(binding: Binding, element: BoundElement, scope: Scope): Scope {
        val name = binding.alias ?: return scope
        return when (element) {
            is BoundElement.Records -> scope.withRecordName(name, element.shape)
            BoundElement.Values -> scope.withValueName(name)
            BoundElement.Unknown -> scope
        }
    }

    /**
     * The scope of a walk's `until` condition (Reference §7, "Walks"): it is asked of each record the
     * walk reaches, so `this` and a bare name both name that record. Names bound to other records stay.
     * A narrowing of the record the walk starts from says nothing about a record it reaches, and is dropped.
     */
    fun ofUntil(walk: WalkExpr, scope: Scope): Scope {
        val target = model.walkTarget(walk, scope.element)
        // over a union the condition reads the members every operand declares
        val reached = target?.takeIf { shape -> model.isStaticUnion(shape) } ?: scope.element
        val keptNames = scope.recordNames.keys + scope.valueNames
        return scope.copy(
            element = reached,
            subject = reached,
            narrowed = scope.narrowed.filterKeys { names -> names.firstOrNull() in keptNames },
        )
    }
}
