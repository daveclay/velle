package velle

/**
 * One level of a body in an order every `as` name bound at that level is
 * bound before it is read (Reference §15: a read orders the two statements).
 * The validator refused cycles (V33), so a stable topological order exists;
 * statements without such reads keep their written order. A block counts as
 * reading every name its statements read.
 */
internal fun orderedItems(items: List<BodyItem>): List<BodyItem> {
    val namedCreations = items.filterIsInstance<Creation>().filter { creation -> creation.alias != null }
    if (namedCreations.isEmpty()) return items
    val names = namedCreations.map { creation -> creation.alias!! }.toSet()
    val ordered = mutableListOf<BodyItem>()
    val placed = mutableSetOf<BodyItem>()
    fun place(item: BodyItem) {
        if (item in placed) return
        placed.add(item)
        for (name in aliasesRead(item, names)) place(namedCreations.first { creation -> creation.alias == name })
        ordered.add(item)
    }
    items.forEach { item -> place(item) }
    return ordered
}

/** The names among [names] that [item] reads. */
private fun aliasesRead(item: BodyItem, names: Set<String>): Set<String> = when (item) {
    is Creation -> (item.fields.map { field -> field.value } + listOfNotNull(item.forExpr))
        .flatMap { expr -> rootsOf(expr) }.filter { root -> root in names }.toSet()
    is ForEach -> item.body.flatMap { inner -> aliasesRead(inner, names) }.toSet()
    else -> emptySet()
}

/** The root name of every path [expr] reads, in reading order. */
private fun rootsOf(expr: Expr): List<String> {
    val roots = mutableListOf<String>()
    collectRoots(expr, roots)
    return roots
}

private fun collectRoots(expr: Expr, roots: MutableList<String>) {
    for (child in childrenOf(expr)) collectRoots(child, roots)
    if (expr is PathExpr) roots.add(expr.root)
}

/** The expressions [expr] reads directly, in reading order: [partsOf] for the forms without a collection of their own. */
private fun childrenOf(expr: Expr): List<Expr> = when (expr) {
    is ExistsExpr -> listOfNotNull(expr.forExpr) + expr.collection?.let(::collectionParts).orEmpty()
    is AggCall -> collectionParts(expr.collection)
    is SetExpr -> collectionParts(expr.collection)
    is WalkExpr -> listOfNotNull(expr.until)
    else -> partsOf(expr)
}

private fun collectionParts(collection: CollectionExpr): List<Expr> =
    collection.bindings.map { binding -> binding.source } + listOfNotNull(collection.where)
