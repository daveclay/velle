package velle

import velle.Sentences.Subject

/**
 * The form a part with no words is held back by, named for a reader of the work list in
 * `compiler/src/test/resources/describe/without-words.md`: "the function `matches`",
 * "`count` over a filter (`where`)". Found as the smallest expression in the part that has no
 * words while everything inside it does.
 */
object FormsWithoutWords {

    /** The form that keeps [expression] from having words, or null when it has them. */
    fun of(expression: Expr, subject: Subject): String? {
        if (hasWords(expression, subject)) return null
        return label(smallestWithoutWords(expression, subject))
    }

    /** The form that keeps [expression], read as a condition, from having words, or null when it has them. */
    fun ofCondition(expression: Expr, subject: Subject): String? {
        if (Sentences.predicate(expression, subject) != null) return null
        return when (expression) {
            is Binary -> if (expression.op == "and" || expression.op == "or") {
                ofCondition(expression.left, subject) ?: ofCondition(expression.right, subject) ?: label(expression)
            } else {
                of(expression, subject) ?: label(expression)
            }
            is NotExpr -> ofCondition(expression.inner, subject) ?: label(expression)
            else -> of(expression, subject) ?: label(expression)
        }
    }

    /** The form that keeps a refinement expression from having words. */
    fun of(expression: RefExpr, baseShapes: Set<String>, subject: Subject): String? {
        if (Sentences.clause(expression, baseShapes, subject) != null) return null
        return when (expression) {
            is RefName -> expression.where?.let { predicate -> ofCondition(predicate, subject) } ?: "a refinement name"
            is RefNot -> of(expression.inner, baseShapes, subject) ?: "`not` over a refinement"
            is RefAnd -> of(expression.left, baseShapes, subject) ?: of(expression.right, baseShapes, subject)
            is RefOr -> of(expression.left, baseShapes, subject) ?: of(expression.right, baseShapes, subject)
        }
    }

    private fun hasWords(expression: Expr, subject: Subject): Boolean =
        Sentences.value(expression, subject) != null || Sentences.predicate(expression, subject) != null

    private fun smallestWithoutWords(expression: Expr, subject: Subject): Expr {
        val childWithoutWords = childrenOf(expression).firstOrNull { child -> !hasWords(child, subject) }
            ?: return expression
        return smallestWithoutWords(childWithoutWords, subject)
    }

    private fun childrenOf(expression: Expr): List<Expr> = when (expression) {
        is ExistsExpr -> listOfNotNull(expression.forExpr) + expression.collection?.let(::collectionParts).orEmpty()
        is AggCall -> collectionParts(expression.collection)
        is SetExpr -> collectionParts(expression.collection)
        else -> partsOf(expression)
    }

    private fun collectionParts(collection: CollectionExpr): List<Expr> =
        collection.bindings.map { binding -> binding.source }.filterNot { source -> source is PathExpr } + listOfNotNull(collection.where)

    private fun label(expression: Expr): String = when (expression) {
        is Binary -> "the operator `${expression.op}`"
        is FunCall -> "the function `${expression.name}`"
        is AggCall -> aggregateLabel(expression)
        is IsExpr -> "`is ${isWords(expression)}`"
        is ExistsExpr -> existsLabel(expression)
        is IfExpr -> "`if ... then ... else` inside another value"
        is Interpolation -> "a text with pieces, `\"...\${...}\"`"
        is SetExpr -> "a set of records as a value, `(Shape where ...)`"
        is SingularFor -> "the one record for something, `(Shape for ...)`"
        is ShapeForSource -> "a collection `Shape for ...`"
        is ListLit -> "a list of values, `[...]`"
        EmptyLit -> "`empty`"
        is WalkExpr -> if (expression.until != null) "`walk ... until`" else "`walk`"
        is Access -> "a field read off a function's result"
        is NotExpr -> "`not` over ${label(expression.inner)}"
        is PathExpr -> if (expression.root.first().isUpperCase()) "a refinement used as a condition" else "a true-or-false field used as a condition"
        else -> "`${Printer.expr(expression)}`"
    }

    private fun isWords(expression: IsExpr): String = when (expression.kind) {
        "notEmpty" -> "not empty"
        else -> expression.kind
    }

    private fun aggregateLabel(expression: AggCall): String = when {
        expression.keyLookup -> "a record found by its key, `(Shape where key == value)`"
        expression.orderBy.isNotEmpty() -> "`${expression.name}` ordered `by`"
        expression.collection.bindings.size > 1 -> "`${expression.name}` over several collections"
        expression.collection.bindings.any { binding -> binding.alias != null } -> "`${expression.name}` over a collection named with `as`"
        expression.collection.where != null -> "`${expression.name}` over a filter (`where`)"
        else -> "`${expression.name}`"
    }

    private fun existsLabel(expression: ExistsExpr): String {
        val collection = expression.collection ?: return "`exists Shape for ...`"
        val binding = collection.bindings.singleOrNull() ?: return "`exists` over several collections"
        val source = binding.source
        return when {
            binding.alias != null -> "`exists` over a collection named with `as`"
            source !is PathExpr -> "`exists` over ${label(source)}"
            source.segs.isNotEmpty() -> "`exists` over a path of more than one step"
            else -> "`exists (... where ...)`"
        }
    }
}
