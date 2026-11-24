package velle

/** The comparison of two specs as Markdown, for a product owner and the engineer beside them. */
object ComparisonReport {

    fun rendered(
        before: SpecVersion,
        after: SpecVersion,
        edits: List<Edit>,
        consequences: List<Consequence> = emptyList(),
        records: List<RecordChange> = emptyList(),
    ): String = buildString {
        appendLine("# What changed from ${before.fileName} to ${after.fileName}")
        appendLine()
        appendLine(LIMITS)
        appendLine()
        if (edits.isEmpty()) {
            appendLine("No change.")
            return@buildString
        }
        appendLine("## What was edited")
        appendLine()
        edits.forEach { edit -> appendEdit(edit) }
        appendConsequences(consequences, edits)
        appendRecords(records)
    }

    private fun StringBuilder.appendRecords(records: List<RecordChange>) {
        if (records.isEmpty()) return
        appendLine()
        appendLine("## What happens to a record like this")
        appendLine()
        appendLine(RECORD_LIMITS)
        records.groupBy { record -> record.base }.forEach { (base, group) ->
            appendLine()
            appendLine("### ${Sentences.capitalized(Sentences.withArticle(base))} like this")
            appendLine()
            group.forEach { record ->
                appendLine("- ${record.sentence}")
                appendLine("  ${record.pointer.rendered()}")
            }
        }
    }

    private val RECORD_LIMITS = listOf(
        "What the edits do to a record of the kind the system keeps, worked out from the two specs alone.",
        "It says which kind of record, never which records or how many.",
    ).joinToString("\n")

    private fun StringBuilder.appendConsequences(consequences: List<Consequence>, edits: List<Edit>) {
        if (consequences.isEmpty()) return
        appendLine()
        appendLine("## What that changes")
        appendLine()
        appendLine(CONSEQUENCE_LIMITS)
        consequences.groupBy { consequence -> consequence.subject }.forEach { (subject, group) ->
            appendLine()
            appendLine("### ${subject.ifEmpty { "The spec as a whole" }}")
            appendLine()
            group.forEach { consequence -> appendConsequence(consequence, edits) }
        }
    }

    private val CONSEQUENCE_LIMITS = listOf(
        "What the compiler derives from the edits for declarations nobody edited. Each is something",
        "that can now happen or can no longer happen; none says what will happen.",
    ).joinToString("\n")

    private fun StringBuilder.appendConsequence(consequence: Consequence, edits: List<Edit>) {
        appendLine("- ${consequence.sentence}")
        causingEdits(consequence, edits).forEach { edit -> appendLine("  ${edit.pointer.rendered()}") }
    }

    /** The edits a consequence names something of; every edit when it names none of them. */
    private fun causingEdits(consequence: Consequence, edits: List<Edit>): List<Edit> {
        if (edits.size == 1) return edits
        val named = edits.filter { edit -> edit.subject in consequence.names }
        return named.ifEmpty { edits }
    }

    private val LIMITS = listOf(
        "This report compares what two versions of a spec say. It describes a system in general,",
        "never the records of a running one. Each line points at the place in the spec that says it.",
        "Where the report has no words for a change yet, it shows the Velle instead and says so.",
    ).joinToString("\n")

    private fun StringBuilder.appendEdit(edit: Edit) {
        val sentence = edit.sentence
        if (sentence != null) {
            appendLine("- $sentence")
            appendLine("  ${edit.pointer.rendered()}")
            return
        }
        appendLine("- ${edit.subject.ifEmpty { "A declaration" }} changed, and this report has no words for it yet. The Velle:")
        appendLine("  ${edit.pointer.rendered()}")
        edit.velleBefore?.let { velle -> appendVelle("Before", velle) }
        edit.velleAfter?.let { velle -> appendVelle("Now", velle) }
    }

    private fun StringBuilder.appendVelle(label: String, velle: String) {
        appendLine("  $label:")
        appendLine("  ```velle")
        velle.lines().forEach { line -> appendLine("  $line") }
        appendLine("  ```")
    }
}
