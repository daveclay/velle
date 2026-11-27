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

/** The shape a rule's condition is over, which its body reads `this` and bare names on; null when it names none. */
fun Model.ruleSubjectShape(rule: RuleDecl): String? =
    (rule.condition as? RefName)?.name?.takeIf { name -> name in shapes || name in refinements } ?: baseOfExpr(rule.condition)

/** The names a rule binds outside its blocks (Reference §14, §22): its `by` actor and its creations' `as` names, each to its shape. */
fun Model.namesBoundOutsideBlocks(rule: RuleDecl): Map<String, String> =
    rule.by?.let { actor -> mapOf(actor.name to actor.shape) }.orEmpty() +
        rule.creations().mapNotNull { creation -> creation.alias?.let { alias -> alias to creation.shape } }.toMap()

/** Every name a rule binds, each to the shape of its records or [VALUE_ALIAS]: what a path in its body may start from. */
fun Model.namesBoundIn(rule: RuleDecl): Map<String, String> {
    val outside = namesBoundOutsideBlocks(rule)
    val subject = ruleSubjectShape(rule) ?: return outside
    return outside + loopElementShapes(rule, subject, outside)
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
