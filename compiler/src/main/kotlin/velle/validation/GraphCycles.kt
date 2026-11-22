package velle.validation

// ── cycles of a directed graph (the definition graph for V14, the rule cascade for V16) ──

/**
 * Every cycle a depth-first walk of [edges] meets, starting from each key in
 * order, each cycle as its distinct nodes in walk order. A node met again
 * after its walk has finished is not walked twice, so a cycle is found once
 * per walk that enters it.
 */
internal fun <Node> cyclesIn(edges: Map<Node, Collection<Node>>): List<List<Node>> {
    val cycles = mutableListOf<List<Node>>()
    val visiting = mutableSetOf<Node>()
    val done = mutableSetOf<Node>()
    fun walkFrom(node: Node, path: List<Node>) {
        if (node in done) return
        if (node in visiting) {
            cycles.add(path.dropWhile { onPath -> onPath != node })
            return
        }
        visiting.add(node)
        edges[node].orEmpty().forEach { next -> walkFrom(next, path + node) }
        visiting.remove(node)
        done.add(node)
    }
    edges.keys.forEach { node -> walkFrom(node, emptyList()) }
    return cycles
}
