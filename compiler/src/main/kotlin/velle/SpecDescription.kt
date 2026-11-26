package velle

/** The declarations about one shape or act within a section of a description, or, with no [subject], the section's whole list. */
data class DescribedSubject(val subject: String?, val declarations: List<DescribedDeclaration>)

/** One section of a description, under its own heading. */
data class DescriptionSection(val heading: String, val subjects: List<DescribedSubject>)

/**
 * What one version of a spec says. With a [focus], only what it says about that shape or act: the
 * declaration, its refinements, every rule and prohibition over either, and every other rule that
 * creates, changes, or deletes one. [opening] is a line said before everything else.
 */
data class Description(val fileName: String, val focus: String?, val sections: List<DescriptionSection>, val opening: String? = null) {
    /** Every declaration described, in the order the description gives them. */
    val declarations: List<DescribedDeclaration>
        get() = sections.flatMap { section -> section.subjects.flatMap { described -> described.declarations } }
}

/** The sections of a description, in the order a reader meets them. */
private enum class Section {
    WORDS, KEPT, REQUESTS, RULES, RULES_FROM_ELSEWHERE, PROHIBITIONS;

    fun heading(focus: String?): String = when (this) {
        WORDS -> "Words and types the spec uses"
        KEPT -> "What the system keeps"
        REQUESTS -> "What someone can ask for"
        RULES -> "What the rules do"
        RULES_FROM_ELSEWHERE -> "What rules over other records do to ${focus ?: "it"}"
        PROHIBITIONS -> "What is never allowed"
    }
}

/** Where one declaration goes in a description: the section, and the shape or act it is about. */
private class Placed(val compared: Compared, val section: Section, val subject: String?)

/**
 * A spec said in the words of a description ([DescribedDeclarations]), part by part, each
 * declaration pointing at its line and each part with no words shown as its Velle.
 */
object SpecDescription {

    fun of(version: SpecVersion, focus: String? = null, opening: String? = null): Description {
        val compared = version.declarations.map { located -> Compared(located, version) }
        val placed = compared.map { declaration -> placed(declaration) }
        val chosen = if (focus == null) placed else focused(placed, focus, version)
        val sections = Section.entries.mapNotNull { section ->
            describedSection(section, focus, chosen.filter { declaration -> declaration.section == section }, version)
        }
        return Description(version.fileName, focus, sections, opening)
    }

    /** The names a description can focus on: each shape and act, with the refinements of it. */
    fun focusNames(version: SpecVersion): Map<String, List<String>> {
        val refinements = version.declarations.map { located -> located.decl }.filterIsInstance<RefinementDecl>()
        return version.baseShapes.sorted().associateWith { base ->
            refinements.filter { refinement -> version.baseOf(refinement.name) == base }.map { refinement -> refinement.name }.sorted()
        }
    }

    private fun focused(placed: List<Placed>, focus: String, version: SpecVersion): List<Placed> {
        val about = placed.filter { declaration -> declaration.subject == focus }
        val fromElsewhere = placed.filter { declaration -> declaration.section == Section.RULES && declaration.subject != focus }
            .filter { declaration -> RuleWrites.of(declaration.compared.declaration as RuleDecl, version).contains(focus) }
            .map { declaration -> Placed(declaration.compared, Section.RULES_FROM_ELSEWHERE, declaration.subject) }
        return about + fromElsewhere
    }

    private fun placed(compared: Compared): Placed {
        val version = compared.version
        return when (val declaration = compared.declaration) {
            is ShapeDecl -> Placed(compared, sectionOf(declaration.name, version), declaration.name)
            is RefinementDecl -> version.baseOf(declaration.name).let { base -> Placed(compared, sectionOf(base, version), base) }
            is RuleDecl -> Placed(compared, Section.RULES, version.baseOf(Sentences.leftmostName(declaration.condition)))
            is NeverDecl -> Placed(compared, Section.PROHIBITIONS, version.baseOf(compared.subject))
            is EnumDecl, is UsesDecl, is VisibilityDefault -> Placed(compared, Section.WORDS, null)
        }
    }

    private fun sectionOf(base: String, version: SpecVersion): Section = if (base in version.acts) Section.REQUESTS else Section.KEPT

    /** A section with a subject per shape or act, in the order the spec first mentions each; its shape or act first. */
    private fun describedSection(section: Section, focus: String?, placed: List<Placed>, version: SpecVersion): DescriptionSection? {
        if (placed.isEmpty()) return null
        val subjects = placed.groupBy { declaration -> declaration.subject }.map { (subject, group) ->
            DescribedSubject(subject, shapeFirst(group).map { declaration -> described(declaration.compared, version) })
        }
        return DescriptionSection(section.heading(focus), subjects)
    }

    private fun shapeFirst(group: List<Placed>): List<Placed> = group.sortedWith(
        compareBy(
            { declaration -> declaration.compared.declaration !is ShapeDecl },
            { declaration -> declaration.compared.located.line },
        ),
    )

    private fun described(compared: Compared, version: SpecVersion): DescribedDeclaration =
        DescribedDeclarations(compared, linesOf(compared, version)).described()

    /** The lines [compared] spans: from its first line to the line before the next declaration. */
    private fun linesOf(compared: Compared, version: SpecVersion): DeclarationLines {
        val firstLine = compared.located.line
        val writtenLines = version.writtenSource.lines()
        val nextLine = version.declarations.map { located -> located.line }.filter { line -> line > firstLine }.minOrNull()
        return DeclarationLines(writtenLines, firstLine, (nextLine ?: (writtenLines.size + 1)) - 1)
    }
}

/**
 * The shapes and acts a rule's body creates, changes a field of, or deletes, found by following
 * each path from the record the rule is over through its `one` relationships.
 */
object RuleWrites {

    fun of(rule: RuleDecl, version: SpecVersion): Set<String> {
        val shapes = version.declarations.map { located -> located.decl }.filterIsInstance<ShapeDecl>().associateBy { shape -> shape.name }
        val base = version.baseOf(Sentences.leftmostName(rule.condition))
        return rule.body.statements().mapNotNull { statement -> written(statement, base, shapes) }.toSet()
    }

    private fun written(statement: BodyItem, base: String, shapes: Map<String, ShapeDecl>): String? = when (statement) {
        is Creation -> statement.shape
        is Assignment -> owningShape(statement.target.steps().dropLast(1), base, shapes)
        is DeleteStmt -> owningShape(statement.target.steps(), base, shapes)
        else -> null
    }

    private fun PathExpr.steps(): List<String> =
        (if (root == "this") emptyList() else listOf(root)) + segs.map { segment -> segment.name }

    /** The shape reached from [base] by following [relationships], each a `one` field; null when a step is not one. */
    private fun owningShape(relationships: List<String>, base: String, shapes: Map<String, ShapeDecl>): String? =
        relationships.fold<String, String?>(base) { shapeName, relationship ->
            val owner = shapeName?.let { name -> shapes[name] } ?: return null
            val field = owner.members.filterIsInstance<StoredProp>().firstOrNull { member -> member.name == relationship }
            (field?.type as? RelType)?.takeUnless { type -> type.many }?.shape
        }
}

/** A description of a spec as Markdown, laid out as the comparison of two specs is ([ComparisonReport]). */
object DescriptionReport {

    fun rendered(description: Description): String = buildString {
        val about = description.focus?.let { focus -> " about $focus" } ?: ""
        appendLine("# What ${description.fileName} says$about")
        appendLine()
        appendLine(LIMITS)
        description.opening?.let { opening ->
            appendLine()
            appendLine(opening)
        }
        if (description.sections.isEmpty()) {
            appendLine()
            appendLine("The spec has no declarations.")
        }
        description.sections.forEach { section -> appendSection(section) }
    }

    private val LIMITS = listOf(
        "This description says what one version of a spec says. It describes a system in general,",
        "never the records of a running one. Each line points at the place in the spec that says it.",
        "Where there are no words for a part yet, it shows the Velle instead and says so.",
    ).joinToString("\n")

    private fun StringBuilder.appendSection(section: DescriptionSection) {
        appendLine()
        appendLine("## ${section.heading}")
        section.subjects.forEach { described -> appendSubject(described) }
    }

    private fun StringBuilder.appendSubject(described: DescribedSubject) {
        appendLine()
        described.subject?.let { subject ->
            appendLine("### $subject")
            appendLine()
        }
        described.declarations.forEach { declaration -> appendDeclaration(declaration) }
    }

    private fun StringBuilder.appendDeclaration(declaration: DescribedDeclaration) {
        when (val summary = declaration.summary) {
            is Phrase.Said -> appendLine("- ${summary.sentence}")
            is Phrase.Unsaid -> appendLine("- There are no words for ${declaration.label} yet. The Velle:")
        }
        appendLine("  ${declaration.pointer.rendered()}")
        (declaration.summary as? Phrase.Unsaid)?.let { summary -> appendVelle(summary.velle, "  ") }
        declaration.details.forEach { detail -> appendDetail(detail) }
    }

    private fun StringBuilder.appendDetail(detail: Phrase) {
        when (detail) {
            is Phrase.Said -> appendLine("  - ${detail.sentence}")
            is Phrase.Unsaid -> {
                appendLine("  - There are no words for this part yet. The Velle, line ${detail.line}:")
                appendVelle(detail.velle, "    ")
            }
        }
    }

    private fun StringBuilder.appendVelle(velle: String, indent: String) {
        appendLine("$indent```velle")
        velle.lines().forEach { line -> appendLine("$indent$line".trimEnd()) }
        appendLine("$indent```")
    }
}
