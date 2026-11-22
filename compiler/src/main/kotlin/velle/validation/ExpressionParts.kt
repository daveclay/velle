package velle.validation

import velle.*

// ── the parts of an expression, for the checks that walk one ──────────────
//
// An expression reads its operands directly, and a form that holds a
// collection, `exists (...)`, an aggregate, or a filter, also reads that
// collection's sources and its `where`. A walk that treats the collection's
// `where` like any other operand uses [subExpressionsOf]; a walk that changes
// what a bare name means inside the `where` takes [operandsOf] and
// [collectionOf] apart.

/** The expressions [expression] reads directly, outside any collection it holds, in reading order. */
internal fun operandsOf(expression: Expr): List<Expr> = when (expression) {
    is ExistsExpr -> listOfNotNull(expression.forExpr)
    is WalkExpr -> listOfNotNull(expression.until)
    else -> partsOf(expression)
}

/** The collection [expression] holds: an `exists (...)`'s, an aggregate's, or a filter's; null for any other form. */
internal fun collectionOf(expression: Expr): CollectionExpr? = when (expression) {
    is ExistsExpr -> expression.collection
    is AggCall -> expression.collection
    is SetExpr -> expression.collection
    else -> null
}

/** A collection's binding sources, then its `where`. */
internal fun sourcesAndFilterOf(collection: CollectionExpr): List<Expr> =
    collection.bindings.map { binding -> binding.source } + listOfNotNull(collection.where)

/** Every expression [expression] reads directly, its operands first and then its collection's sources and `where`. */
internal fun subExpressionsOf(expression: Expr): List<Expr> =
    operandsOf(expression) + collectionOf(expression)?.let(::sourcesAndFilterOf).orEmpty()
