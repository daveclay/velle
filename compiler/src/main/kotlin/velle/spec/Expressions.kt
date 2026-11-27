package velle.spec

import velle.*

// ── The parts of an expression ───────────────────────────────────────────────
//
// An expression reads its operands directly, and a form that holds a
// collection, `exists (...)`, an aggregate, or a filter, also reads that
// collection's sources and its `where`. A walk that treats the collection's
// `where` like any other operand uses [subExpressionsOf]; a walk that changes
// what a bare name means inside the `where` takes [operandsOf] and
// [collectionOf] apart.

/** The expressions [expression] reads directly, outside any collection it holds, in reading order. */
fun operandsOf(expression: Expr): List<Expr> = when (expression) {
    is ExistsExpr -> listOfNotNull(expression.forExpr)
    is WalkExpr -> listOfNotNull(expression.until)
    else -> partsOf(expression)
}

/** The collection [expression] holds: an `exists (...)`'s, an aggregate's, or a filter's; null for any other form. */
fun collectionOf(expression: Expr): CollectionExpr? = when (expression) {
    is ExistsExpr -> expression.collection
    is AggCall -> expression.collection
    is SetExpr -> expression.collection
    else -> null
}

/** A collection's binding sources, then its `where`. */
fun sourcesAndFilterOf(collection: CollectionExpr): List<Expr> =
    collection.bindings.map { binding -> binding.source } + listOfNotNull(collection.where)

/** Every expression [expression] reads directly, its operands first and then its collection's sources and `where`. */
fun subExpressionsOf(expression: Expr): List<Expr> =
    operandsOf(expression) + collectionOf(expression)?.let(::sourcesAndFilterOf).orEmpty()

/** The expressions directly inside [expression], for every form that holds no collection of its own. */
fun partsOf(expression: Expr): List<Expr> = when (expression) {
    is UnaryMinus -> listOf(expression.inner)
    is Binary -> listOf(expression.left, expression.right)
    is NotExpr -> listOf(expression.inner)
    is IfExpr -> listOf(expression.condition, expression.thenExpr, expression.elseExpr)
    is Interpolation -> expression.pieces
    is IsExpr -> listOf(expression.subject)
    is FunCall -> expression.args
    is SingularFor -> listOf(expression.forExpr)
    is ShapeForSource -> listOf(expression.forExpr)
    is Access -> listOf(expression.target)
    else -> emptyList()
}
