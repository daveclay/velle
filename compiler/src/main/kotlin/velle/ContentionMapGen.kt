package velle

/**
 * The contention map (OQ40): the artifact family's *between*-envelope member.
 * Per exposed act and per schedule-fired rule, the derived serialization
 * domain in business words — which work queues on which keys, what contends
 * with what, and every whole-shape width with the read that caused it.
 * A tolerated width shows as accepted, naming the declaration carrying the
 * tolerance; an unexamined width is a ⚠ row (the advisory ruling, 2026-08-18:
 * A5 warns, compilation proceeds).
 */
internal object ContentionMapGen {

    fun render(model: Model): String {
        val analysis = DomainAnalysis(model)
        return buildString {
            appendLine("## Contention map")
            appendLine()
            appendLine(
                "Where the system can work in parallel and where work must wait its turn. Work " +
                    "sharing a **queue key** is handled one item at a time in arrival order (U3), like " +
                    "any queue; work whose keys differ runs independently. A key is evaluated per " +
                    "call — `deposit.account` names the row that call's work queues on. Two envelopes " +
                    "contend iff their key sets intersect: path keys by the row they evaluate to, " +
                    "value keys by equal committed values, and a system-wide row with anything " +
                    "touching its shape. Derived from the spec's own read and write paths — " +
                    "no author declares a queue key."
            )
            appendLine()
            appendLine("| envelope | queues on | notes |")
            appendLine("|---|---|---|")
            for ((act, domain) in analysis.actDomains)
                appendLine(row("commit$act", domain, decap(act), scheduled = false))
            for ((rule, domain) in analysis.scheduledRuleDomains) {
                val decl = model.rules.getValue(rule)
                val schedules = decl.triggers.filter { it != "commit" }.joinToString(", ")
                appendLine(row("$rule (each $schedules firing)", domain, "this", scheduled = true))
            }
            appendLine()
        }
    }

    private fun row(envelope: String, domain: SerializationDomain, subjectNoun: String, scheduled: Boolean): String {
        val keys = domain.renderKeys(subjectNoun)
        val keyText = when {
            domain.wide -> {
                val shapes = domain.widenings.map { it.shape }.distinct()
                "one queue over ${shapes.joinToString(", ") { "`$it`" }}, system-wide" +
                    (if (keys.isNotEmpty()) " (plus ${keys.joinToString(", ") { "`$it`" }})" else "")
            }
            keys.isEmpty() -> "nothing — contends with no other work"
            else -> keys.joinToString(", ") { "`$it`" }
        }
        val notes = buildList {
            for (w in domain.widenings) {
                val mark = when {
                    w.tolerated -> "tolerated — ${w.declaration}"
                    // a schedule-fired rule's width is priced at the cadence, not
                    // per commit — the cadence discharge (OQ40), no ⚠
                    scheduled -> "once per tick"
                    else -> "⚠ unexamined"
                }
                add("$mark: ${w.cause}")
            }
            if (!domain.wide && keys.isNotEmpty())
                add("work with equal keys takes turns; different keys run at the same time")
        }
        val warn = if (!scheduled && domain.exposed.isNotEmpty()) "⚠ " else ""
        return "| $warn$envelope | $keyText | ${notes.joinToString("; ")} |"
    }

    private fun decap(s: String) = s.replaceFirstChar { it.lowercase() }
}
