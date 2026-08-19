package velle

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The generated commutation sweep (OQ42 item 3, option A): the derivation's
 * guarantee — two envelopes conflict iff their domains intersect — quantified
 * mechanically instead of at hand-picked points. For each spec in the corpus
 * the sweep grows a small deterministic world, synthesizes concrete commits
 * for every exposed act, lets the *harness* compute each pair's verdict by
 * resolving the derived keys against concrete rows ([DomainKeys]), and
 * order-swaps every pair the derivation calls disjoint: identical final
 * fingerprints or it is a soundness bug.
 *
 * Small-scope by design: two rows per shape and two commits per act is where
 * this class of bug lives (the transfer-and-audit bug needed exactly two
 * accounts and two acts). Every row is salted structurally unique so
 * attribution differences stay visible to the fingerprint.
 *
 * Degradations only ever widen: an unevaluable domain (a widening, an
 * unresolvable hop, an uncarried value key) is treated as conflicting with
 * everything and the pair is skipped *and counted* — the report names what
 * was not checked, so silent narrowing cannot read as coverage.
 */
class CommutationSweepTest {

    // ── the corpus: in-file specs plus every example spec ────────────────────

    private val deposits = """
        shape Account {
            balance: decimal initially 0
        }
        expose Account

        expose shape Deposit {
            account: one Account
            amount: decimal
        }

        shape DepositApplication {
            deposit: one Deposit
            appliedOn: DateTime
        }

        shape UnappliedDeposit = Deposit where not exists DepositApplication for this

        rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
            account.balance = account.balance + amount
            DepositApplication from { deposit: this, appliedOn: now }
        }
    """.trimIndent()

    private val audits = """
        shape Account {
            balance: decimal initially 0
        }
        expose Account

        expose shape Transfer {
            source: one Account
            target: one Account
            amount: decimal
        }

        expose shape AuditRequest {
            account: one Account
        }

        shape AuditReport {
            request: one AuditRequest
            outbound: decimal
        }

        rule ReportAudit when AuditRequest {
            AuditReport from { request: this, outbound: sum(Transfer where source == this.account, amount) }
        }
    """.trimIndent()

    private val uniqueness = """
        expose shape Customer {
            email: text
            name: text
        }

        never (Customer where exists (Customer as other where other.email == this.email and not (other == this)))
    """.trimIndent()

    @Test
    fun `every derivation-disjoint pair in the corpus commutes`() {
        val corpus = listOf("deposits" to deposits, "audits" to audits, "uniqueness" to uniqueness) +
            exampleSpecs()
        val reports = corpus.map { (name, src) ->
            try { sweep(name, src) } catch (e: Exception) {
                throw AssertionError("sweep[$name] died: ${e.message}", e)
            }
        }
        reports.forEach { println(it.summary()) }
        val violations = reports.flatMap { it.violations }
        assertTrue(violations.isEmpty(),
            "derivation-disjoint pairs failed to commute:\n" + violations.joinToString("\n"))
    }

    private fun exampleSpecs(): List<Pair<String, String>> {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null && !Files.isDirectory(dir.resolve("examples"))) dir = dir.parent
        val examples = dir?.resolve("examples") ?: error("examples/ not found above the working directory")
        return Files.list(examples).use { dirs ->
            dirs.filter { Files.isDirectory(it) }.sorted().map { d ->
                val spec = Files.list(d).use { files ->
                    files.filter { it.toString().endsWith(".velle") }.sorted().findFirst().orElse(null)
                }
                spec?.let { d.fileName.toString() to Files.readString(it) }
            }.toList().filterNotNull()
        }
    }

    // ── the sweep ────────────────────────────────────────────────────────────

    private class Report(val spec: String) {
        var disjointChecked = 0
        var conflictSkipped = 0
        var unevaluableSkipped = 0
        var failureSkipped = 0
        val droppedActs = mutableListOf<String>()
        val unevaluableActs = mutableListOf<String>()
        val runtimeErrors = mutableListOf<String>()
        val violations = mutableListOf<String>()
        fun summary() = "sweep[$spec]: $disjointChecked disjoint pairs order-swapped, " +
            "$conflictSkipped conflicting skipped, $unevaluableSkipped unevaluable treated as conflicting, " +
            "$failureSkipped skipped on run failures" +
            (if (droppedActs.isEmpty()) "" else "; no viable commit for ${droppedActs.distinct()}") +
            (if (unevaluableActs.isEmpty()) "" else "; unevaluable: ${unevaluableActs.distinct()}") +
            (if (runtimeErrors.isEmpty()) "" else "; RUNTIME ERRORS: ${runtimeErrors.distinct()}")
    }

    private fun sweep(name: String, src: String): Report {
        val decls = Parser.parse(src)
        val model = Model(decls)
        check(model.diagnostics.isEmpty()) { "$name: ${model.diagnostics}" }
        val analysis = DomainAnalysis(model)
        val pools = textPools(decls)
        val report = Report(name)

        // every candidate must be committable on its own — a never refusing a
        // synthesized commit is the spec working, not a sweep finding
        val candidates = (0..1).flatMap { variant ->
            model.exposed.map { act -> candidate(model, act, variant, 1000 + variant, pools) }
        }.filter { c ->
            val sys = grown(model, pools)
            val fields = resolve(sys, c)
            // a cascade throwing (not refusing) is a finding of its own —
            // recorded, and the candidate dropped so the sweep continues
            val ok = fields != null && runCatching { sys.commit(c.act, fields) }
                .onFailure { report.runtimeErrors.add("${c.label}: ${it.message}") }
                .getOrNull() is CommitResult.Accepted
            if (!ok) report.droppedActs.add(c.act)
            ok
        }

        // verdicts against one untouched post-setup system: the state both
        // order-runs deterministically rebuild
        val probe = grown(model, pools)
        val tokens = candidates.map { c ->
            resolve(probe, c)?.let { fields ->
                DomainKeys.evaluate(probe, c.act, analysis.actDomains.getValue(c.act), fields)
            }
        }
        candidates.forEachIndexed { i, c ->
            if (tokens[i] == null) {
                val d = analysis.actDomains.getValue(c.act)
                report.unevaluableActs.add(c.act + if (d.wide) " (wide)" else " (unresolved key)")
            }
        }

        for (i in candidates.indices) for (j in i until candidates.size) {
            val ta = tokens[i]
            val tb = tokens[j]
            if (ta == null || tb == null) { report.unevaluableSkipped++; continue }
            if (ta intersects tb) { report.conflictSkipped++; continue }
            checkPair(model, pools, candidates[i], candidates[j], report)
        }
        return report
    }

    private fun checkPair(model: Model, pools: Map<String, List<String>>, a: Candidate, b: Candidate, report: Report) {
        fun run(first: Candidate, second: Candidate): Map<String, List<String>>? {
            val sys = grown(model, pools)
            // both field maps resolve against the setup state — the state the
            // verdict was computed on; a refusal mid-pair is state too, and
            // the fingerprint decides whether it was order-dependent
            val fa = resolve(sys, first) ?: return null
            val fb = resolve(sys, second) ?: return null
            runCatching { sys.commit(first.act, fa); sys.commit(second.act, fb) }
                .onFailure { report.runtimeErrors.add("${first.label}, ${second.label}: ${it.message}"); return null }
            if (sys.failures.isNotEmpty()) return null
            return fingerprint(sys, model)
        }
        val ab = run(a, b)
        val ba = run(b, a)
        if (ab == null || ba == null) { report.failureSkipped++; return }
        report.disjointChecked++
        if (ab != ba) report.violations.add(
            "${report.spec}: derivation-disjoint pair does not commute: ${a.label} × ${b.label}\n" +
                "  a-then-b: $ab\n  b-then-a: $ba")
    }

    // ── the world: deterministic small-scope growth ──────────────────────────

    /** A fresh system carrying the spec's small world: three passes over the
     *  exposed acts, two variants each, so reference chains (an act whose
     *  target only exists once an earlier act's rules ran) fill in by the
     *  later passes. Deterministic — ids and values are identical on every
     *  rebuild, which is what lets verdicts transfer to the order-runs. */
    private fun grown(model: Model, pools: Map<String, List<String>>): VelleSystem {
        val sys = VelleSystem(model)
        var salt = 0
        repeat(3) {
            for (act in model.exposed) for (variant in 0..1) {
                // the clock moves between world commits so `on create`
                // timestamps discriminate (`latest ... by respondedOn` refuses
                // ties, README §10) — but never between a *pair's* commits,
                // where a moving clock would break commutation trivially
                sys.advance(60)
                val c = candidate(model, act, variant, salt++, pools)
                resolve(sys, c)?.let { sys.commit(act, it) }
            }
        }
        return sys
    }

    private data class Ref(val target: String, val idx: Int, val required: Boolean)
    private data class Candidate(
        val act: String,
        val refs: Map<String, Ref>,
        val scalars: Map<String, Any>,
        val label: String,
    )

    /** Synthesize one concrete commit of [act]: references aimed at the
     *  variant's rows (spread per field, so a two-reference act reaches two
     *  rows), scalars salted structurally unique, text fields drawn from the
     *  spec's own compared literals where it has any. */
    private fun candidate(
        model: Model, act: String, variant: Int, salt: Int, pools: Map<String, List<String>>,
    ): Candidate {
        val refs = mutableMapOf<String, Ref>()
        val scalars = mutableMapOf<String, Any>()
        val stored = model.shapes.getValue(act).members.filterIsInstance<StoredProp>()
        stored.forEachIndexed { ord, m ->
            if (m.initially != null) return@forEachIndexed
            when (val t = m.type) {
                is RelType -> if (!t.many)
                    refs[m.name] = Ref(model.baseOf(t.shape) ?: t.shape, (variant + ord) % 2, !t.optional)
                is ScalarType -> if (!t.many && !t.optional)
                    scalars[m.name] = scalarFor(t.name, m.name, variant, salt, pools)
            }
        }
        return Candidate(act, refs, scalars, "$act#$variant$scalars")
    }

    private fun scalarFor(type: String, field: String, variant: Int, salt: Int, pools: Map<String, List<String>>): Any =
        when (type) {
            "text" -> pools[field]?.let { it[variant % it.size] } ?: "$field-$salt"
            "boolean" -> salt % 2 == 0
            "Date" -> LocalDate.of(2026, 1, 1).plusDays((salt % 28).toLong())
            "DateTime" -> Instant.parse("2026-01-01T09:00:00Z").plusSeconds(salt * 60L)
            else -> BigDecimal(salt % 90 + 10) // positive and distinct-ish: clears `amount <= 0` nevers
        }

    /** Aim the candidate's references at concrete rows of the system's world.
     *  A required reference with no row yet makes the candidate unresolvable
     *  (this round — growth may fill the target in later); an optional one is
     *  simply omitted. */
    private fun resolve(sys: VelleSystem, c: Candidate): Map<String, Any?>? {
        val out = mutableMapOf<String, Any?>()
        out.putAll(c.scalars)
        for ((field, ref) in c.refs) {
            val rows = sys.instancesOf(ref.target)
            if (rows.isEmpty()) { if (ref.required) return null else continue }
            out[field] = rows[ref.idx.coerceAtMost(rows.size - 1)]
        }
        return out
    }

    // ── text pools: values the spec itself compares fields to ────────────────

    /** field name → text literals the spec compares it to (`outcome ==
     *  "approved"`), so enum-like fields get values the nevers accept instead
     *  of salted strings every guard refuses. */
    private fun textPools(decls: List<Decl>): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        fun add(field: String, value: String) {
            out.getOrPut(field) { mutableListOf() }.let { if (value !in it) it.add(value) }
        }
        fun expr(e: Expr?) {
            when (e) {
                is Binary -> {
                    if (e.op == "==" || e.op == "!=") {
                        val pair = when {
                            e.left is PathExpr && e.right is TextLit -> e.left as PathExpr to e.right as TextLit
                            e.right is PathExpr && e.left is TextLit -> e.right as PathExpr to e.left as TextLit
                            else -> null
                        }
                        pair?.let { (p, lit) -> add(p.segs.lastOrNull()?.name ?: p.root, lit.value) }
                    }
                    expr(e.left); expr(e.right)
                }
                is NotExpr -> expr(e.inner)
                is UnaryMinus -> expr(e.inner)
                is IfExpr -> { expr(e.condition); expr(e.thenExpr); expr(e.elseExpr) }
                is IsExpr -> expr(e.subject)
                is ExistsExpr -> { expr(e.forExpr); e.collection?.bindings?.forEach { expr(it.source) }; expr(e.collection?.where) }
                is AggCall -> { e.collection.bindings.forEach { expr(it.source) }; expr(e.collection.where) }
                is SetExpr -> { e.collection.bindings.forEach { expr(it.source) }; expr(e.collection.where) }
                is FunCall -> e.args.forEach { expr(it) }
                is SingularFor -> expr(e.forExpr)
                is ShapeForSource -> expr(e.forExpr)
                is Access -> expr(e.target)
                else -> {}
            }
        }
        fun ref(r: RefExpr) {
            when (r) {
                is RefName -> expr(r.where)
                is RefNot -> ref(r.inner)
                is RefAnd -> { ref(r.left); ref(r.right) }
                is RefOr -> { ref(r.left); ref(r.right) }
            }
        }
        for (d in decls) when (d) {
            is ShapeDecl -> d.members.forEach { m ->
                when (m) { is DerivedProp -> expr(m.expr); is StoredProp -> expr(m.initially); else -> {} }
            }
            is RefinementDecl -> { ref(d.expr); d.members.filterIsInstance<DerivedProp>().forEach { expr(it.expr) } }
            is RuleDecl -> {
                ref(d.condition)
                d.body.forEach { b ->
                    when (b) {
                        is Assignment -> expr(b.value)
                        is Creation -> { expr(b.forExpr); b.fields.forEach { expr(it.value) } }
                        ThenMarker -> {}
                    }
                }
            }
            is NeverDecl -> ref(d.target)
            else -> {}
        }
        return out
    }

    // ── fingerprints (as CommutationTest, plus masking of minted uuids) ──────

    /** Id-insensitive structural fingerprint; `initially randomUUID` fields
     *  are masked — they differ between the two rebuilds by construction and
     *  say nothing about commutation. */
    private fun fingerprint(sys: VelleSystem, model: Model): Map<String, List<String>> {
        val masked = model.shapes.values.flatMap { s ->
            s.members.filterIsInstance<StoredProp>()
                .filter { it.initially == PathExpr("randomUUID") }.map { s.name to it.name }
        }.toSet()
        return sys.model.shapes.keys.filterNot { it in sys.model.transients }.associateWith { shape ->
            sys.instancesOf(shape).map { canonical(sys, it, depth = 4, masked) }.sorted()
        }
    }

    private fun canonical(sys: VelleSystem, id: Long, depth: Int, masked: Set<Pair<String, String>>): String {
        val inst = sys.instances[id] ?: return "missing"
        if (depth == 0) return inst.shape
        val fields = inst.fields.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
            "$k=" + when {
                inst.shape to k in masked -> "«uuid»"
                v is Value.VRef -> canonical(sys, v.id, depth - 1, masked)
                v is Value.VColl -> v.ids.map { canonical(sys, it, depth - 1, masked) }.sorted().toString()
                v is Value.VNum -> v.v.stripTrailingZeros().toPlainString()
                else -> v.toString()
            }
        }
        return "${inst.shape}{$fields}"
    }
}
