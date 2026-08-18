package velle

/**
 * Rule-graph emission (diagrams.md): the whole system's cause
 * graph as one Mermaid flowchart — nodes are the exposed acts and the rules,
 * and an edge means "this commit's writes can change that rule's condition".
 * It is the sequence diagrams' may-fire derivation rendered once, globally:
 * the one-page map read before any per-act deep dive.
 *
 * Velle has no control flow, so this is not a flowchart of steps. Fan-out
 * means "every edge whose guard holds fires, in no order" (V16), never a
 * choice being made. A decision diamond appears only where exclusivity is
 * proven: the accept/refuse split an input-constrained `never` compiles to,
 * and a transient act's provably exclusive partitions (complements: exactly
 * one branch, always). Edge style carries the transaction structure — solid
 * fires inside the same envelope, dotted crosses a boundary (`after commit`,
 * or armed now and swept at the labeled tick).
 *
 * Precision reuses the state-flow analyses: an edge is dropped when the write
 * provably moves the condition the wrong way (an ArchiveRequest cannot make
 * `when leaving ArchivedInvoice` fire), a born-member condition provably
 * false at birth drops the birth edge, and a self-loop labeled "disarms its
 * own guard" is the guard-disarm pattern made visible — the rule's own write
 * is exactly what makes its re-evaluation harmless.
 */
internal object RuleGraphGen {

    fun render(model: Model, catalog: KindCatalog): String {
        val g = Graph(model, catalog)
        return buildString {
            appendLine("## Rule graph")
            appendLine()
            appendLine(
                "The whole system on one page: an edge means the source's commit can change the target " +
                    "rule's condition — **may**-cause, like everything else here. Solid edges fire inside " +
                    "the same transaction envelope; dotted edges cross a boundary (`after commit`, or " +
                    "armed now and swept at the labeled tick). Fan-out is not a choice: every edge whose " +
                    "guard holds fires, in no particular order. A diamond appears only where exclusivity " +
                    "is proven — the accept/refuse split of an input-constrained `never`, and a transient " +
                    "act's complement partitions. A self-loop marked *disarms its own guard* is the " +
                    "guard-disarm pattern: the rule's own write makes its re-evaluation harmless. An act " +
                    "with no outgoing edge commits quietly — data recorded, no rule woken."
            )
            appendLine()
            appendLine("```mermaid")
            appendLine("flowchart LR")
            g.build().forEach { appendLine("    $it") }
            appendLine("```")
            if (g.legend.isNotEmpty()) {
                appendLine()
                g.legend.distinct().forEach { appendLine("- $it") }
            }
            appendLine()
        }
    }

    private class Graph(val model: Model, val catalog: KindCatalog) {

        val analysis = FlowAnalysis(model, catalog)
        val legend = mutableListOf<String>()
        private val lines = mutableListOf<String>()
        private val summaries = mutableMapOf<String, ReadSummary>()

        fun build(): List<String> {
            for (act in model.exposed) lines.add("commit$act[\"commit$act\"]")
            for (rule in model.rules.values) lines.add("${rule.name}[\"${rule.name}\"]")
            for (act in model.exposed) {
                if (act in model.transients) renderTransient(act)
                else renderAct(act)
            }
            for (rule in model.rules.values) for (k in catalog.producedKinds(rule)) {
                val kindDesc = when (k) {
                    is CommitKind.Create -> "inserts ${k.shape}"
                    is CommitKind.Assign -> "writes ${k.shape}.${k.field}"
                    CommitKind.TimePasses -> continue
                }
                for (target in model.rules.values) {
                    if (target === rule) selfEdge(rule, k, kindDesc)
                    else edge(rule.name, k, kindDesc, target)
                }
            }
            return lines.distinct()
        }

        // ── an ordinary act: boundary refusal diamond, then may-fire edges ───

        private fun renderAct(act: String) {
            val boundary = model.nevers.filter { boundaryActOf(it) == act }
            var src = "commit$act"
            if (boundary.isNotEmpty()) {
                val question = boundary.joinToString(" or ") { Printer.expr((it.target as RefName).where!!) }
                lines.add("commit$act --> q$act{\"${esc(question)} ?\"}")
                lines.add("q$act -->|\"violated — refused, nothing begins\"| ref$act([\"refusal\"])")
                lines.add("q$act -->|\"passes\"| env$act[\"the $act envelope\"]")
                src = "env$act"
            }
            for (rule in model.rules.values) edge(src, CommitKind.Create(act), null, rule)
        }

        /** A `never` compiled to the commit boundary: targets the exposed act
         *  itself and reads nothing beyond the act's own stored fields. */
        private fun boundaryActOf(n: NeverDecl): String? {
            val t = n.target as? RefName ?: return null
            if (t.name !in model.exposed || t.where == null) return null
            val s = ReadSummary().also { model.collectExpr(t.where, t.name, it) }
            val selfContained = !s.opaque && !s.readsTime && s.existsShapes.isEmpty() &&
                s.collShapes.isEmpty() && s.collFields.isEmpty() && s.fields.all { it.first == t.name }
            return t.name.takeIf { selfContained }
        }

        // ── a transient act: the decided-once dispatch ───────────────────────

        private fun renderTransient(act: String) {
            val partitions = model.refinements.values.filter {
                (it.expr as? RefName)?.let { e -> e.name == act && e.where != null } == true
            }
            if (partitions.isEmpty()) return
            val exclusive = partitions.size >= 2 &&
                partitions.indices.all { i -> (i + 1 until partitions.size).all { j -> analysis.disjoint(partitions[i], partitions[j]) } }
            val complementPair = partitions.size == 2 && run {
                val (a, b) = partitions.map { it.whereExpr()!! }
                a == NotExpr(b) || b == NotExpr(a)
            }
            if (complementPair) {
                val positive = partitions.first { it.whereExpr() !is NotExpr }
                val negative = partitions.first { it !== positive }
                lines.add("commit$act --> q$act{\"${esc(Printer.expr(positive.whereExpr()!!))} ?\"}")
                branch(act, "yes — exactly one branch, the guards are complements", positive)
                branch(act, "no", negative)
            } else if (exclusive) {
                lines.add("commit$act --> q$act{\"decided once, at this commit\"}")
                for (p in partitions) branch(act, esc(Printer.expr(p.whereExpr()!!)), p)
                lines.add("q$act -->|\"no partition applies\"| end$act([\"no consequence\"])")
            } else {
                // no exclusivity proof — guarded fan-out, never a diamond
                for (p in partitions) for (rule in rulesOf(p))
                    emit("commit$act", rule.name, listOf("when ${Printer.expr(p.whereExpr()!!)}"), dotted = false)
            }
        }

        private fun branch(act: String, label: String, p: RefinementDecl) {
            val rules = rulesOf(p)
            if (rules.isEmpty()) lines.add("q$act -->|\"${esc(label)}\"| ${p.name}[\"${p.name}\"]")
            for (rule in rules) lines.add("q$act -->|\"${esc(label)}\"| ${rule.name}")
        }

        private fun rulesOf(p: RefinementDecl): List<RuleDecl> =
            model.rules.values.filter {
                (it.condition as? RefName)?.let { c -> c.name == p.name && c.where == null } == true
            }

        // ── the may-fire edges, pruned by the direction proofs ───────────────

        private fun edge(from: String, k: CommitKind, kindDesc: String?, target: RuleDecl) {
            val base = model.baseOfExpr(target.condition) ?: return
            if (base in model.transients) return // partition rules are wired through the dispatch diamond
            var guard: String? = guardText(target)
            if (k is CommitKind.Create && k.shape == base) {
                if (target.leaving) return // a birth cannot cause an exit
                when (analysis.initTruthRef(target.condition)) {
                    false -> return // provably not a member at birth
                    true -> guard = null // provably fires for every new instance
                    null -> {}
                }
            } else {
                if (!catalog.touches(k, summaryOf(target))) return
                when (analysis.dirRef(target.condition, k)) {
                    Dir.TOWARD_TRUE -> if (target.leaving) return
                    Dir.TOWARD_FALSE -> if (!target.leaving) return
                    else -> {}
                }
            }
            emit(from, target.name, listOfNotNull(kindDesc, timing(target), guard), dotted = timing(target) != null)
        }

        private fun selfEdge(rule: RuleDecl, k: CommitKind, kindDesc: String) {
            if (!catalog.touches(k, summaryOf(rule))) return
            val verb = when (analysis.dirRef(rule.condition, k)) {
                Dir.TOWARD_FALSE -> "disarms its own guard"
                Dir.TOWARD_TRUE -> "re-arms its own condition"
                else -> "feeds its own condition"
            }
            emit(rule.name, rule.name, listOf(kindDesc, verb), dotted = timing(rule) != null)
        }

        private fun guardText(rule: RuleDecl): String? {
            val cond = rule.condition
            if (!rule.leaving && cond is RefName && cond.where == null && cond.name in model.shapes) return null
            val leaving = if (rule.leaving) "leaving " else ""
            val printed = Printer.refExpr(cond)
            if (printed.length <= 60) return "when $leaving$printed"
            legend.add("**${rule.name}** fires when $leaving$printed")
            return "when $leaving${(cond as? RefName)?.name ?: "…"} …"
        }

        private fun timing(rule: RuleDecl): String? {
            val schedules = rule.triggers.filter { it != "commit" }.joinToString(", ")
            return when {
                rule.preposition == "after" ->
                    "after commit" + if (schedules.isEmpty()) "" else ", healed every $schedules"
                rule.preposition == "on" && "commit" !in rule.triggers -> "swept every $schedules"
                else -> null
            }
        }

        private fun emit(from: String, to: String, parts: List<String>, dotted: Boolean) {
            val arrow = if (dotted) "-.->" else "-->"
            val label = parts.joinToString(" — ")
            lines.add(if (label.isEmpty()) "$from $arrow $to" else "$from $arrow|\"${esc(label)}\"| $to")
        }

        private fun summaryOf(rule: RuleDecl): ReadSummary =
            summaries.getOrPut(rule.name) { model.summaryOfRefExpr(rule.condition) }

        private fun esc(s: String) = mermaidEsc(s).replace("\"", "'")
    }
}
