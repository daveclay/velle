package velle.validation

import velle.*

// ── V14 (starting values): `initially` values that read one another ───────

/** Starting values that read one another (Reference §5): the runtime computes each `initially`
 *  after the ones it reads, and a cycle leaves no order to compute them in. */
internal fun Validator.checkStartingValueCycles() {
    val edges = mutableMapOf<DefinitionNode, Set<DefinitionNode>>()
    for (shapeName in model.shapes.keys)
        for ((fieldName, fieldsRead) in model.startingValues.readsOf(shapeName))
            edges[DefinitionNode.Property(shapeName, fieldName)] = fieldsRead.map { fieldRead -> DefinitionNode.Property(shapeName, fieldRead) }.toSet()
    for (cycle in reportedDefinitionCycles(edges))
        diagnostics.add(Diagnostic("V14", startingCycleMessage(cycle.filterIsInstance<DefinitionNode.Property>())))
}

/** `'Chart.doubled' starts from 'Chart.base', which starts from 'Chart.doubled', so neither can be computed first; ...` */
private fun startingCycleMessage(cycle: List<DefinitionNode.Property>): String {
    val first = cycle.first()
    if (cycle.size == 1)
        return "'${definitionLabel(first)}' starts from its own value, which it does not have until it has started; start it without '${first.property}'"
    val chain = (cycle.drop(1) + first).joinToString(", which starts from ") { field -> "'${definitionLabel(field)}'" }
    val ending = if (cycle.size == 2) "so neither can be computed first; start one of them without the other"
        else "so none of them can be computed first; start one of them without the others"
    return "'${definitionLabel(first)}' starts from $chain, $ending"
}
