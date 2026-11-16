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
                "Dashed arrows connect a composed refinement to the refinements it is built from. " +
                "A dotted hollow-triangle arrow reads \"is\": the shape takes the pointed-at shape's " +
                "declarations as its own, and that shape has no records of its own. A hollow-triangle arrow " +
                "labelled `or` reads \"is one of\": the pointed-at box is a union of the shapes that point at it, " +
                "listing what every one of them shares, and a refinement over it is attached to the union."
        )
        appendLine()
        appendLine("```mermaid")
        appendLine("classDiagram")
        appendLine("    direction LR")
        val edges = mutableListOf<String>()
        // a shape other shapes take with `is` (Reference §9): drawn once with its own members,
        // and each taker points at it; the taker lists only what it declares itself
        for ((name, shape) in model.sharedShapes) {
            appendLine("    class $name {")
            appendLine("        <<taken>>")
            for (m in shape.members) {
                if (m is StoredProp && m.type is RelType) { edges.add(relEdge(model, name, m.name, m.type)); continue }
                memberLine(model, name, m)?.let { appendLine("        $it") }
            }
            appendLine("    }")
        }
        for ((name, shape) in model.shapes) {
            appendLine("    class $name {")
            if (name in model.exposed)
                appendLine("        <<${if (name in model.transients) "transient act" else "act"}>>")
            shape.takes.forEach { taken -> edges.add("$taken <|.. $name : is") }
            for (m in shape.members) {
                if (m.declaredOn != null) continue
                if (m is StoredProp && m.type is RelType) {
                    edges.add(relEdge(model, name, m.name, m.type))
                    continue
                }
                // the actor an act is committed by (Reference §22): an edge to the clause's shape, marked `by`
                if (m is ActorProp) {
                    edges.add("$name \"*\" --> \"1\" ${model.baseOf(m.shape) ?: m.shape} : ${m.name} (by ${m.shape})")
                    continue
                }
                memberLine(model, shape.name, m)?.let { appendLine("        $it") }
            }
            appendLine("    }")
        }
        edges.forEach { appendLine("    $it") }
        // a static union (Reference §9): one box with what every operand shares, each operand pointing at it
        for ((name, _) in model.refinements) {
            if (!model.isStaticUnion(name) || name in model.sharedShapes) continue
            appendLine("    class $name {")
            appendLine("        <<union>>")
            for (m in model.membersOf(name).values) if (m.name != "id" && m.inverse == null)
                appendLine("        ${if (m.stored) "" else "/"}${m.name}: ${model.spell(m.type)}")
            appendLine("    }")
            for (operand in model.basesOf(name)) appendLine("    $name <|-- $operand : or")
        }
        for ((name, r) in model.refinements) {
            if (model.isStaticUnion(name)) continue
            // a refinement over a union attaches to the union; one over nothing nameable is left out
            val base = model.baseOf(name) ?: model.unionRootOf(name) ?: continue
            appendLine("    class $name {")
            appendLine("        <<refinement>>")
            for (m in r.members) memberLine(model, name, m)?.let { appendLine("        $it") }
            appendLine("    }")
            appendLine("    $base <|-- $name")
            for (operand in refNames(r.expr).filter { it in model.refinements }.distinct())
                appendLine("    $name ..> $operand")
            appendLine("    note for $name \"= ${noteEsc(Printer.refExpr(r.expr))}\"")
        }
        appendLine("```")
        appendLine()
    }

    private fun memberLine(model: Model, owner: String, m: Member): String? = when (m) {
        is StoredProp -> "${m.name}: ${Printer.type(m.type)}" +
            (m.initially?.let { init -> Printer.expr(init).takeIf { "(" !in it }?.let { " = $it" } } ?: "")
        is DerivedProp -> "/${m.name}: ${m.type?.let(Printer::type) ?: model.spell(model.membersOf(owner).getValue(m.name).type)}" + if (m.captured) " captured at entry" else ""
        is TimestampProp -> "${m.name}: timestamp on ${m.on}"
        is ActorProp -> "${m.name}: one ${m.shape} (by)"
        is VisibleClause -> Printer.visible(m)
        is FrozenClause -> "frozen " + (m.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "all stored fields")
        UndeletableClause -> "undeletable"
        is UniqueKey -> "unique (${m.fields.joinToString(", ")})"
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
