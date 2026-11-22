package velle.validation

// ── V14: the definition graph's nodes and its cycles ────────────────────────
//
// A node is a definition that evaluation unfolds: a derived property (or a
// field's starting value) of a shape, or a refinement. An edge runs from a
// definition to each definition it reads. checkWellFoundedness and
// checkStartingValueCycles each build a graph of these and report its cycles.

internal sealed interface DefinitionNode {
    data class Property(val shape: String, val property: String) : DefinitionNode
    data class Refinement(val name: String) : DefinitionNode
}

internal fun definitionLabel(node: DefinitionNode) = when (node) {
    is DefinitionNode.Property -> "${node.shape}.${node.property}"
    is DefinitionNode.Refinement -> "shape '${node.name}'"
}

/**
 * The cycles of [edges] with one message each. A read of a derived property
 * unfolds what that property reads, so two properties computed from each
 * other also show up as each one reading itself; the cycle through both is
 * the one reported, and a cycle whose nodes all lie on a larger reported
 * cycle is dropped.
 */
internal fun reportedDefinitionCycles(edges: Map<DefinitionNode, Set<DefinitionNode>>): List<List<DefinitionNode>> {
    val distinct = cyclesIn(edges).distinctBy { cycle -> cycle.toSet() }
    return distinct.filter { cycle ->
        val nodes = cycle.toSet()
        distinct.none { other -> other.toSet().let { otherNodes -> nodes != otherNodes && otherNodes.containsAll(nodes) } }
    }
}
