package velle

/**
 * Rule-graph emission (diagrams.md): the whole system's cause
 * graph as one Mermaid flowchart — nodes are the acts and the rules,
 * and an edge means "this commit's writes can change that rule's condition".
 * It is the sequence diagrams' may-fire derivation rendered once, globally:
 * the one-page map read before any per-act deep dive.
 *
 * Velle has no control flow, so this is not a flowchart of steps. Fan-out
 * means "every edge whose guard holds fires, in no order" (V16), never a
 * choice being made. A decision diamond appears only where exclusivity is
 * proven: the accept/refuse split a door-checked `never` compiles to,
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
/**
 * One fact the rule graph draws as an edge: a commit of [source] (an act's `commit<Act>`, a
 * rule, or a branch of a transient act's dispatch) can change the condition of [rule].
 * [parts] say how (what the source writes, when the rule runs, under which guard), and
 * [crossesBoundary] is set when the rule runs outside the source's envelope. [causesRun] is
 * false for the one edge drawn although it cannot cause the rule to run: a rule's edge to
 * itself when its own write can only move its condition away from running it, drawn as
 * "disarms its own guard". The rule graph draws these and the comparison of two specs
 * compares those that can cause a run.
 */
internal data class MayFire(
    val source: String,
    val rule: String,
    val parts: List<String>,
    val crossesBoundary: Boolean,
    val causesRun: Boolean = true,
)

internal object RuleGraphGen {

    /** Every may-fire edge of the rule graph, before any drawing. */
    fun mayFire(model: Model, catalog: KindCatalog): List<MayFire> =
        Graph(model, catalog).also { graph -> graph.build() }.mayFire.distinct()

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
                    "is proven — the accept/refuse split of a door-checked `never`, and a transient " +
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
        val mayFire = mutableListOf<MayFire>()
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
                    is CommitKind.Delete -> "deletes ${k.shape}"
                    CommitKind.TimePasses -> continue
                }
                val creations = if (k is CommitKind.Create) catalog.creationsOf(rule, k.shape) else emptyList()
                for (target in model.rules.values) {
                    if (target === rule) selfEdge(rule, k, kindDesc, creations)
                    else edge(rule.name, k, kindDesc, target, creations)
                }
            }
            return lines.distinct()
        }

        // ── an ordinary act: boundary refusal diamond, then may-fire edges ───

        private fun renderAct(act: String) {
            val boundary = model.nevers.filter { boundaryActOf(it) == act }
            var src = "commit$act"
            if (boundary.isNotEmpty()) {
                val question = boundary.joinToString(" or ") {
                    it.requirement?.let { r -> "requires ${Printer.expr(r.predicate)}" } ?: Printer.expr((it.target as RefName).where!!)
                }
                lines.add("commit$act --> q$act{\"${esc(question)} ?\"}")
                lines.add("q$act -->|\"violated — refused, nothing begins\"| ref$act([\"refusal\"])")
                lines.add("q$act -->|\"passes\"| env$act[\"the $act envelope\"]")
                src = "env$act"
            }
            for (rule in model.rules.values) edge(src, CommitKind.Create(act), null, rule)
        }

        /** A `never` compiled to the commit boundary: targets the act
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
            for (rule in rules) {
                mayFire.add(MayFire("commit$act", rule.name, listOf(label), crossesBoundary = false))
                lines.add("q$act -->|\"${esc(label)}\"| ${rule.name}")
            }
        }

        private fun rulesOf(p: RefinementDecl): List<RuleDecl> =
            model.rules.values.filter {
                (it.condition as? RefName)?.let { c -> c.name == p.name && c.where == null } == true
            }

        // ── the may-fire edges, pruned by the direction proofs ───────────────

        /** [creations] are the source rule's creation statements of a created shape; empty for an act arriving. */
        private fun edge(from: String, k: CommitKind, kindDesc: String?, target: RuleDecl, creations: List<Creation> = emptyList()) {
            val base = model.baseOfExpr(target.condition) ?: return
            if (base in model.transients) return // partition rules are wired through the dispatch diamond
            var guard: String? = guardText(target)
            if (k is CommitKind.Create && k.shape == base) {
                if (target.leaving) return // a birth cannot cause an exit
                when (conditionAtBirth(target.condition, creations)) {
                    false -> return // provably not a member at birth
                    true -> guard = null // provably fires for every new instance
                    null -> {}
                }
            } else if (k is CommitKind.Delete && k.shape == base && target.trigger.onDeletion) {
                // the deletion itself is the exit cause this rule reacts to
            } else {
                if (target.trigger == Trigger.DELETED) return // fires only at deletions of its own shape
                val touched =
                    if (k is CommitKind.Delete && k.shape == base) catalog.deletionTouchesOthers(k, summaryOf(target))
                    else catalog.touches(k, summaryOf(target))
                if (!touched) return
                when (analysis.dirRef(target.condition, k, creations = creations)) {
                    Dir.TOWARD_TRUE -> if (target.leaving) return
                    Dir.TOWARD_FALSE -> if (!target.leaving) return
                    Dir.NONE -> return
                    Dir.UNKNOWN -> {}
                }
            }
            emit(from, target.name, listOfNotNull(kindDesc, timing(target), guard), dotted = timing(target) != null)
        }

        /**
         * Whether a rule's condition holds for the record a commit creates: for every creation
         * statement of the source rule, with the values each gives, or, for an act arriving,
         * with the `initially` values alone. Null when it differs between creations or is unknown.
         */
        private fun conditionAtBirth(condition: RefExpr, creations: List<Creation>): Boolean? {
            if (creations.isEmpty()) return analysis.initTruthRef(condition)
            return creations.map { creation -> analysis.initTruthRef(condition, creation.givenValues()) }.distinct().singleOrNull()
        }

        private fun selfEdge(rule: RuleDecl, k: CommitKind, kindDesc: String, creations: List<Creation>) {
            if (!catalog.touches(k, summaryOf(rule))) return
            val direction = analysis.dirRef(rule.condition, k, creations = creations)
            val verb = when (direction) {
                Dir.TOWARD_FALSE -> "disarms its own guard"
                Dir.TOWARD_TRUE -> "re-arms its own condition"
                Dir.UNKNOWN -> "feeds its own condition"
                Dir.NONE -> return
            }
            // the same test [edge] prunes by: a write moving the condition away from running the rule
            val awayFromRunning = if (rule.leaving) Dir.TOWARD_TRUE else Dir.TOWARD_FALSE
            emit(rule.name, rule.name, listOf(kindDesc, verb), dotted = timing(rule) != null, causesRun = direction != awayFromRunning)
        }

        private fun guardText(rule: RuleDecl): String? {
            val cond = rule.condition
            if (!rule.leaving && cond is RefName && cond.where == null && cond.name in model.shapes) return null
            val leaving = rule.trigger.keyword
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

        private fun emit(from: String, to: String, parts: List<String>, dotted: Boolean, causesRun: Boolean = true) {
            val fact = MayFire(from, to, parts, dotted, causesRun)
            mayFire.add(fact)
            lines.add(drawn(fact))
        }

        private fun drawn(fact: MayFire): String {
            val arrow = if (fact.crossesBoundary) "-.->" else "-->"
            val label = fact.parts.joinToString(" — ")
            return if (label.isEmpty()) "${fact.source} $arrow ${fact.rule}" else "${fact.source} $arrow|\"${esc(label)}\"| ${fact.rule}"
        }

        private fun summaryOf(rule: RuleDecl): ReadSummary =
            summaries.getOrPut(rule.name) { model.summaryOfRefExpr(rule.condition) }

        private fun esc(s: String) = mermaidEsc(s).replace("\"", "'")
    }
}
