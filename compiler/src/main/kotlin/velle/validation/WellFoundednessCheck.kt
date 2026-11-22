package velle.validation

import velle.*

// ── V14: definition-graph well-foundedness (stratify, then prove each self-read ends) ──
//
// Evaluation unfolds definitions, and unfolding is the one thing that can
// fail to bottom out, so the definition graph (derived properties;
// refinements referencing refinements) must be well-founded. The acyclic
// part passes free. A cycle through two or more definitions is refused.
// A single derived property reading itself is accepted in exactly two
// forms, both one step through an optional to-one on its own shape:
//   (a) the step is `latest`/`first` over the same shape picking a record
//       strictly earlier by a field fixed at creation (`previous` and
//       `streakAfter`, Reference §19);
//   (b) the step is a stored field and a spendable
//       `never (S where step == this)` forbids a record being its own step
//       (`root` through `parent`, Reference §7, §21).
// Everything else is refused with a message naming the reason and the
// spelling that passes (checks.md V14); fixpoint semantics are never
// attempted.

internal fun Validator.checkWellFoundedness() {
    val derivedProperties = derivedPropertiesByNode()
    val edges = buildMap<DefinitionNode, Set<DefinitionNode>> {
        for ((node, property) in derivedProperties) put(node, definitionsReadByProperty(node.shape, property))
        for ((name, refinement) in model.refinements) put(DefinitionNode.Refinement(name), definitionsReadByRefinement(refinement))
    }
    for (cycle in reportedDefinitionCycles(edges)) {
        val message = cycleMessage(cycle, derivedProperties) ?: continue
        diagnostics.add(Diagnostic("V14", message))
    }
}

/** Every derived property of every shape, by its node, in declaration order. */
private fun Validator.derivedPropertiesByNode(): Map<DefinitionNode.Property, DerivedProp> = buildMap {
    for ((shapeName, shape) in model.shapes)
        for (property in shape.members.filterIsInstance<DerivedProp>()) put(DefinitionNode.Property(shapeName, property.name), property)
}

/** The definitions the formula of [property] on [owner] reads: derived properties and refinements. */
private fun Validator.definitionsReadByProperty(owner: String, property: DerivedProp): Set<DefinitionNode> {
    val summary = ReadSummary()
    model.collectExpr(property.expr, owner, summary)
    return definitionsIn(summary, refinementsMentionedIn(property.expr))
}

/** The definitions [refinement]'s expression reads: derived properties and refinements. */
private fun Validator.definitionsReadByRefinement(refinement: RefinementDecl): Set<DefinitionNode> =
    definitionsIn(model.summaryOfRefExpr(refinement.expr), refinementsMentionedIn(refinement.expr))

private fun definitionsIn(summary: ReadSummary, refinements: Set<String>): Set<DefinitionNode> =
    summary.derivedSeen.map { (owner, property) -> DefinitionNode.Property(owner, property) }.toSet() +
        refinements.map { refinement -> DefinitionNode.Refinement(refinement) }

/** The message for one cycle, or null when it is a self-read the compiler can see ends. */
private fun Validator.cycleMessage(cycle: List<DefinitionNode>, derivedProperties: Map<DefinitionNode.Property, DerivedProp>): String? {
    val only = cycle.singleOrNull()
    if (only is DefinitionNode.Refinement) {
        return "shape '${only.name}' is defined by itself, so it can never be decided; define it without '${only.name}'"
    }
    if (only is DefinitionNode.Property) {
        val property = derivedProperties.getValue(only)
        return selfReadProblem(only.shape, property)?.message(only.shape, property.name)
    }
    return mutualCycleMessage(cycle)
}

/** `'Kind.label' is computed from 'Kind.path', which is computed from 'Kind.label', so neither can be computed first; ...` */
private fun mutualCycleMessage(cycle: List<DefinitionNode>): String {
    fun quoted(node: DefinitionNode) = when (node) {
        is DefinitionNode.Property -> "'${node.shape}.${node.property}'"
        is DefinitionNode.Refinement -> "'${node.name}'"
    }
    fun verb(node: DefinitionNode) = if (node is DefinitionNode.Property) "is computed from" else "is defined by"
    val first = cycle.first()
    val text = StringBuilder(if (first is DefinitionNode.Refinement) "shape ${quoted(first)}" else quoted(first))
    (cycle + first).zipWithNext().forEachIndexed { index, (from, to) ->
        text.append(if (index == 0) " ${verb(from)} " else ", which ${verb(from)} ")
        text.append(quoted(to))
    }
    val decided = if (cycle.all { node -> node is DefinitionNode.Refinement }) "decided" else "computed"
    text.append(
        if (cycle.size == 2) ", so neither can be $decided first; define one of them without the other"
        else ", so none of them can be $decided first; define one of them without the others")
    return text.toString()
}
