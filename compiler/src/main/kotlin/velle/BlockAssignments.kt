package velle

/** An assignment written inside `for each` blocks (Reference §14), with the blocks around it, outermost first. */
data class BlockAssignment(val assignment: Assignment, val blocks: List<ForEach>) {
    /** The block the assignment stands directly in. */
    val innermost: ForEach get() = blocks.last()
}

/** Every assignment of the rule written inside a block. */
fun RuleDecl.blockAssignments(): List<BlockAssignment> {
    val found = mutableListOf<BlockAssignment>()
    fun walk(items: List<BodyItem>, blocks: List<ForEach>) {
        for (item in items) when (item) {
            is Assignment -> if (blocks.isNotEmpty()) found.add(BlockAssignment(item, blocks))
            is ForEach -> walk(item.body, blocks + item)
            else -> {}
        }
    }
    walk(body, emptyList())
    return found
}

/** The shape of the records a `for each` source runs over, or [VALUE_ALIAS] when it runs over numbers or values, or is unknown. */
fun Model.loopElementShape(source: LoopSource, scope: String, aliases: Map<String, String>): String {
    val collection = (source as? LoopSource.Over)?.expr ?: return VALUE_ALIAS
    val shape = when (collection) {
        is PathExpr ->
            if (collection.segs.isEmpty() && (collection.root in shapes || collection.root in refinements)) collection.root
            else pathElementShape(collection, scope, aliases)
        is SetExpr -> collectionElement(collection.collection, scope, aliases)
        is WalkExpr -> walkTarget(collection, scope)
        else -> null
    }
    return shape ?: VALUE_ALIAS
}

/** The names the rule's blocks bind, each to the shape of its records or [VALUE_ALIAS]; each name's source reads the names before it. */
fun Model.loopElementShapes(rule: RuleDecl, scope: String, outerAliases: Map<String, String>): Map<String, String> {
    val shapeByLoopName = linkedMapOf<String, String>()
    for ((binding, _) in rule.loopBindings()) {
        shapeByLoopName[binding.name] = loopElementShape(binding.source, scope, outerAliases + shapeByLoopName)
    }
    return shapeByLoopName
}

/** The shape of the records each name of [blocks] runs over, outermost first, as the blocks around an assignment bind them. */
fun Model.loopElementShapesAlong(blocks: List<ForEach>, scope: String, outerAliases: Map<String, String>): Map<String, String> {
    val shapeByLoopName = linkedMapOf<String, String>()
    for (binding in blocks.flatMap { block -> block.bindings }) {
        shapeByLoopName[binding.name] = loopElementShape(binding.source, scope, outerAliases + shapeByLoopName)
    }
    return shapeByLoopName
}
