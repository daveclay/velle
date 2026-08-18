package velle

/**
 * Class-diagram emission (diagrams.md): one Mermaid class
 * diagram of the whole spec — shapes with their properties, declared
 * relationships as edges (labeled with the field, and the inferred inverse
 * collection where one exists), and refinements attached to their base with
 * UML's hollow-triangle arrow plus a note carrying the membership predicate.
 *
 * The rendering keeps UML's own convention for what Velle derives: `/name`
 * marks a derived property (computed from the spec's expression, never
 * stored), exactly UML's marker for a derived attribute. Full derivation
 * expressions stay out of the class boxes — Mermaid treats any member line
 * containing parentheses as a method — and live in the spec itself; the box
 * says only that the property is derived.
 */
internal object ClassDiagramGen {

    fun render(model: Model): String = buildString {
        appendLine("## Class diagram")
        appendLine()
        appendLine(
            "`/name` marks a derived property — computed, never stored. Solid arrows are declared " +
                "relationships, labeled with the declaring field (and the inferred inverse collection, " +
                "where one exists). A hollow-triangle arrow reads \"is a refinement of\": membership is " +
                "decided by the predicate in the attached note, instant by instant, not fixed at creation. " +
                "Dashed arrows connect a composed refinement to the refinements it is built from."
        )
        appendLine()
        appendLine("```mermaid")
        appendLine("classDiagram")
        appendLine("    direction LR")
        val edges = mutableListOf<String>()
        for ((name, shape) in model.shapes) {
            appendLine("    class $name {")
            if (name in model.exposed)
                appendLine("        <<${if (name in model.transients) "expose transient" else "expose"}>>")
            for (m in shape.members) {
                if (m is StoredProp && m.type is RelType) {
                    edges.add(relEdge(model, name, m.name, m.type))
                    continue
                }
                memberLine(m)?.let { appendLine("        $it") }
            }
            appendLine("    }")
        }
        edges.forEach { appendLine("    $it") }
        for ((name, r) in model.refinements) {
            val base = model.baseOf(name) ?: continue
            appendLine("    class $name {")
            appendLine("        <<refinement>>")
            for (m in r.members) memberLine(m)?.let { appendLine("        $it") }
            appendLine("    }")
            appendLine("    $base <|-- $name")
            for (operand in refNames(r.expr).filter { it in model.refinements }.distinct())
                appendLine("    $name ..> $operand")
            appendLine("    note for $name \"= ${noteEsc(Printer.refExpr(r.expr))}\"")
        }
        appendLine("```")
        appendLine()
    }

    private fun memberLine(m: Member): String? = when (m) {
        is StoredProp -> "${m.name}: ${Printer.type(m.type)}" +
            (m.initially?.let { init -> Printer.expr(init).takeIf { "(" !in it }?.let { " = $it" } } ?: "")
        is DerivedProp -> "/${m.name}: ${Printer.type(m.type)}" + if (m.captured) " captured at entry" else ""
        is TimestampProp -> "${m.name}: timestamp on ${m.on}"
        is FrozenClause -> "frozen " + (m.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "all stored fields")
    }

    private fun relEdge(model: Model, owner: String, field: String, t: RelType): String {
        val targetCard = if (t.many) "*" else if (t.optional) "0..1" else "1"
        val inverse = model.membersOf(t.shape).values
            .firstOrNull { it.inverse?.shape == owner && it.inverse?.field == field }
        val label = field + (inverse?.let { " (inverse ${it.name})" } ?: "")
        return "$owner \"*\" --> \"$targetCard\" ${t.shape} : $label"
    }

    private fun refNames(e: RefExpr): List<String> = when (e) {
        is RefName -> listOf(e.name)
        is RefNot -> refNames(e.inner)
        is RefAnd -> refNames(e.left) + refNames(e.right)
        is RefOr -> refNames(e.left) + refNames(e.right)
    }

    private fun noteEsc(s: String) = mermaidEsc(s).replace("\"", "'")
}
