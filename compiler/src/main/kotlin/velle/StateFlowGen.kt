package velle

/**
 * State-flow emission (diagrams.md): one section per base
 * shape that has refinements, one Mermaid state diagram per membership *axis*.
 *
 * A state is membership in a refinement — and memberships overlap (an Invoice
 * can be Issued and Overdue and Archived at once), so the honest rendering is
 * not one machine but one small machine per axis. Refinements share an axis
 * only when their predicates are provably disjoint (whole-expression
 * complements, or complementary comparisons on the same reads); everything
 * else gets its own two-state axis. That is the state-flow analog of the
 * sequence diagrams' may-fire stance: where a proof is missing the diagram
 * gets weaker, never wrong.
 *
 * Arrow directions are proven, not guessed:
 *  - an `exists X for this` atom can only be satisfied, never unsatisfied —
 *    v0 has no delete (OQ37) — so its edges are one-way, and a conjunction of
 *    a positive and a negated atom yields the once-through chain: never a
 *    member, then a member, then out permanently;
 *  - a comparison moves one way when the fold behind it has a pinned sign:
 *    an input-constrained `never` proving every contribution positive is what
 *    licenses drawing the act's arrow in a single direction;
 *  - everything else is an honest "may flip" edge, drawn in both directions.
 *
 * A transient act renders as UML's choice pseudostate instead: its partitions
 * are decided once, at the act's commit, and the instance is not kept — the
 * final state *is* the removal at the envelope's close. When the partition
 * guards are complements, the diagram states the totality outright: every
 * commit takes exactly one branch.
 */
internal object StateFlowGen {

    fun render(model: Model, catalog: KindCatalog): String {
        val byBase = linkedMapOf<String, MutableList<RefinementDecl>>()
        for (r in model.refinements.values) {
            val base = model.baseOf(r.name) ?: continue
            byBase.getOrPut(base) { mutableListOf() }.add(r)
        }
        if (byBase.isEmpty()) return ""
        return buildString {
            appendLine("## State flow")
            appendLine()
            appendLine(
                "A state is membership in a refinement, and memberships overlap — so each diagram below " +
                    "is one *axis* of membership, varying independently of its siblings. Arrow directions " +
                    "are proven from the spec (an `exists` can only be satisfied — v0 never deletes — and " +
                    "a fold moves one way when the input-constrained refusals pin the sign of every " +
                    "contribution); where no proof exists, the edge says **may flip** and is drawn in both " +
                    "directions."
            )
            for ((base, refs) in byBase) {
                appendLine()
                appendLine("### $base")
                appendLine()
                if (base in model.transients) append(TransientFlow(model, base, refs).render())
                else append(ShapeFlow(model, catalog, base, refs).render())
            }
            appendLine()
        }
    }
}

internal fun RefinementDecl.whereExpr(): Expr? = (expr as? RefName)?.where

/** How a commit kind can move a predicate's truth value. */
internal enum class Dir { TOWARD_TRUE, TOWARD_FALSE, NONE, UNKNOWN }

/** How a commit kind can move a numeric expression's value. */
private enum class SD { ZERO, NONNEG, NONPOS, UNKNOWN }

/** The provable value sign of a field across every instance. */
private enum class SS { NONNEG, NONPOS, UNKNOWN }

/** How a predicate's truth value can evolve over an instance's lifetime. */
internal enum class Mono { UP, DOWN, FLAT, UPDOWN, UNKNOWN }

// ── the ordinary (non-transient) shape: axes of membership ───────────────────

private class ShapeFlow(
    val model: Model,
    val catalog: KindCatalog,
    val base: String,
    refs: List<RefinementDecl>,
) {
    /** Refinements of the form `base where <predicate>` become states; anything
     *  else (compositions, refinements of refinements) is a derived view. */
    val atomics = refs.filter { (it.expr as? RefName)?.let { e -> e.name == base && e.where != null } == true }
    val composites = refs - atomics.toSet()
    val analysis = FlowAnalysis(model, catalog)

    fun render(): String = buildString {
        val axes = groupIntoAxes()
        var wroteProse = false
        if (axes.size > 1) {
            appendLine(
                "${atomics.size} refinements form ${axes.size} independent membership axes — " +
                    "each $base holds a position on every axis at once."
            )
            wroteProse = true
        }
        for (c in composites) {
            appendLine(
                "- **${c.name}** = ${Printer.refExpr(c.expr)} — a view composed across the axes " +
                    "below, not an axis of its own."
            )
            wroteProse = true
        }
        val atomicNames = atomics.map { it.name }.toSet()
        for (rule in model.rules.values) {
            if (model.baseOfExpr(rule.condition) != base) continue
            val exact = exactCondition(rule)
            if (exact != null && exact in atomicNames) continue
            appendLine(
                "- **${rule.name}** — ${triggerPhrase(rule)}: when " +
                    "${Printer.refExpr(rule.condition)} — ${ruleActions(rule)}."
            )
            wroteProse = true
        }
        for ((i, axis) in axes.withIndex()) {
            if (wroteProse || i > 0) appendLine()
            if (axis.size == 1) append(renderSingle(axis.single()))
            else append(renderGrouped(axis))
        }
    }

    /** Rule condition's refinement name when it is exactly `when <Name>`. */
    private fun exactCondition(rule: RuleDecl): String? =
        (rule.condition as? RefName)?.takeIf { it.where == null }?.name

    /** Greedy pairwise-disjoint grouping: a refinement joins an axis only when
     *  it is provably disjoint with every member already there. */
    private fun groupIntoAxes(): List<List<RefinementDecl>> {
        val axes = mutableListOf<MutableList<RefinementDecl>>()
        for (r in atomics) {
            val home = axes.firstOrNull { axis -> axis.all { analysis.disjoint(it, r) } }
            if (home != null) home.add(r) else axes.add(mutableListOf(r))
        }
        return axes
    }

    // one refinement, classified by how its membership can evolve
    private fun renderSingle(r: RefinementDecl): String {
        val where = r.whereExpr()!!
        val mono = analysis.mono(where, base)
        val init = analysis.initTruth(where, base)
        val causes = causesFor(r)
        return when {
            mono == Mono.UP && init == false -> oneWayIn(r, causes)
            mono == Mono.DOWN && init == true -> bornMember(r, causes)
            mono == Mono.UPDOWN && init == false -> onceThrough(r, causes)
            else -> churnSingle(r, causes, init)
        }
    }

    private fun oneWayIn(r: RefinementDecl, c: Causes): String = diagram(
        "**${r.name}** — one-way: entry is permanent."
    ) {
        appendLine("    state \"not yet ${r.name}\" as pre")
        appendLine("    [*] --> pre : a new $base")
        edges(this, "pre", r.name, c)
        note(this, r.name, "one-way — ${Printer.expr(r.whereExpr()!!)} can never stop holding (v0 never deletes)")
        stateNotes(this, r)
    }

    private fun bornMember(r: RefinementDecl, c: Causes): String = diagram(
        "**${r.name}** — born a member; leaving is permanent."
    ) {
        appendLine("    state \"no longer ${r.name}\" as post")
        appendLine(
            "    [*] --> ${r.name} : a new $base is born a member — " +
                "${mermaidEsc(Printer.expr(r.whereExpr()!!))} already holds"
        )
        edges(this, "post", r.name, c, exitOnly = true)
        val settled = (r.whereExpr() as? NotExpr)?.inner?.let { Printer.expr(it) }
        note(this, "post", "one-way — " + (settled?.let { "$it now holds forever (v0 never deletes)" }
            ?: "membership cannot return"))
        stateNotes(this, r)
    }

    private fun onceThrough(r: RefinementDecl, c: Causes): String = diagram(
        "**${r.name}** — once through: enter once, leave once, never again."
    ) {
        appendLine("    state \"never a member\" as pre")
        appendLine("    state \"left ${r.name} — permanently\" as post")
        appendLine("    [*] --> pre : a new $base")
        for (label in c.enter) edge(this, "pre", r.name, label)
        for (label in c.exit) edge(this, r.name, "post", label)
        for (label in c.flip) { // no direction proof: draw the hedge on both legs
            edge(this, "pre", r.name, "$label — may flip")
            edge(this, r.name, "post", "$label — may flip")
        }
        val down = analysis.conjuncts(r.whereExpr()!!)
            .firstOrNull { analysis.mono(it, base) == Mono.DOWN }
        val settled = ((down as? NotExpr)?.inner ?: down)?.let { Printer.expr(it) }
        note(this, "post", "terminal — " + (settled?.let {
            "$it now holds forever (v0 never deletes), so membership can never be regained"
        } ?: "membership can never be regained"))
        stateNotes(this, r)
    }

    private fun churnSingle(r: RefinementDecl, c: Causes, init: Boolean?): String = diagram(
        "**${r.name}** — may enter and leave."
    ) {
        appendLine("    state \"not ${r.name}\" as out")
        when (init) {
            false -> appendLine("    [*] --> out : a new $base")
            true -> appendLine("    [*] --> ${r.name} : a new $base is born a member")
            null -> note(this, "out", "which side a new $base starts on depends on its committed values")
        }
        edges(this, "out", r.name, c)
        stateNotes(this, r)
    }

    // several provably disjoint refinements share one axis, with a "neither" state
    private fun renderGrouped(axis: List<RefinementDecl>): String = diagram(
        "**${axis.joinToString(" / ") { it.name }}** — provably disjoint, one axis. Arrows are " +
            "drawn through *neither* for legibility; a single commit may move an instance straight " +
            "between named states."
    ) {
        appendLine("    state \"neither\" as otherwise")
        if (axis.all { analysis.initTruth(it.whereExpr()!!, base) == false })
            appendLine("    [*] --> otherwise : a new $base")
        for (r in axis) {
            edges(this, "otherwise", r.name, causesFor(r))
            stateNotes(this, r)
        }
    }

    // ── edge and note plumbing ───────────────────────────────────────────────

    private class Causes(val enter: List<String>, val exit: List<String>, val flip: List<String>)

    private fun causesFor(r: RefinementDecl): Causes {
        val where = r.whereExpr()!!
        val summary = model.predicateSummary(r.name)
        val enter = mutableListOf<String>()
        val exit = mutableListOf<String>()
        val flip = mutableListOf<String>()
        for (cause in catalog.causes) {
            val k = cause.kind
            if (k is CommitKind.Create && k.shape == base) continue // birth is the [*] arrow
            if (!catalog.touches(k, summary)) continue
            when (analysis.dir(where, k, base)) {
                Dir.TOWARD_TRUE -> enter.add(cause.label)
                Dir.TOWARD_FALSE -> exit.add(cause.label)
                Dir.UNKNOWN -> flip.add(cause.label)
                Dir.NONE -> {}
            }
        }
        return Causes(enter.distinct(), exit.distinct(), flip.distinct())
    }

    /** exitOnly: the axis only models leaving (born a member) — entries have
     *  nowhere to come from, so only exits and hedges render. */
    private fun edges(sb: StringBuilder, outState: String, member: String, c: Causes, exitOnly: Boolean = false) {
        for (label in c.enter) if (!exitOnly) edge(sb, outState, member, label)
        for (label in c.exit) edge(sb, member, outState, label)
        for (label in c.flip) {
            if (!exitOnly) edge(sb, outState, member, "$label — may flip")
            edge(sb, member, outState, "$label — may flip")
        }
    }

    private fun edge(sb: StringBuilder, from: String, to: String, label: String) =
        sb.appendLine("    $from --> $to : ${mermaidEsc(label)}")

    // a second ':' inside a state note breaks Mermaid's note grammar
    private fun note(sb: StringBuilder, state: String, text: String) =
        sb.appendLine("    note right of $state : ${mermaidEsc(text).replace(":", " —")}")

    /** Captured fields, frozen fields, and the refinement's entry/exit rules. */
    private fun stateNotes(sb: StringBuilder, r: RefinementDecl) {
        for (m in r.members) when (m) {
            is DerivedProp -> if (m.captured)
                note(sb, r.name, "on entry — captured ${m.name} = ${Printer.expr(m.expr)}, readable even after leaving")
            is FrozenClause ->
                note(sb, r.name, "while a member — " +
                    (m.fields.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "every stored field") +
                    " frozen, writes are refused")
            else -> {}
        }
        for (rule in model.rules.values) {
            if (exactCondition(rule) != r.name) continue
            val moment = if (rule.leaving) "on exit" else "on entry"
            note(sb, r.name, "$moment — ${rule.name} ${ruleActions(rule)} (${triggerPhrase(rule)})")
        }
    }

    private fun diagram(title: String, body: StringBuilder.() -> Unit): String = buildString {
        appendLine(title)
        appendLine()
        appendLine("```mermaid")
        appendLine("stateDiagram-v2")
        body()
        appendLine("```")
    }
}

// ── the transient act: partitions decided once, at the commit ────────────────

private class TransientFlow(val model: Model, val base: String, refs: List<RefinementDecl>) {
    val partitions = refs.filter { (it.expr as? RefName)?.let { e -> e.name == base && e.where != null } == true }

    fun render(): String = buildString {
        appendLine(
            "A transient act is an input, not state: its partitions are decided once, at the " +
                "act's commit, and the instance is not kept — the final state is its removal at the " +
                "envelope's close. Only its consequences persist."
        )
        appendLine()
        appendLine("```mermaid")
        appendLine("stateDiagram-v2")
        appendLine("    state decide <<choice>>")
        appendLine("    [*] --> decide : commit$base")
        val total = partitions.size == 2 && complements(partitions[0], partitions[1])
        for (p in partitions) {
            appendLine("    decide --> ${p.name} : ${mermaidEsc(Printer.expr(p.whereExpr()!!))}")
            val rules = model.rules.values.filter {
                (it.condition as? RefName)?.let { c -> c.name == p.name && c.where == null } == true
            }
            if (rules.isEmpty()) appendLine("    ${p.name} --> [*]")
            for (rule in rules)
                appendLine("    ${p.name} --> [*] : ${mermaidEsc("${rule.name} — ${ruleActions(rule)}")}")
        }
        if (!total) appendLine("    decide --> [*] : no partition applies")
        appendLine("```")
        if (total) {
            appendLine()
            appendLine(
                "The two guards are complements, so every $base takes exactly one branch — the " +
                    "structural proof that each request gets a decision."
            )
        }
    }

    private fun complements(a: RefinementDecl, b: RefinementDecl): Boolean {
        val wa = a.whereExpr() ?: return false
        val wb = b.whereExpr() ?: return false
        return wa == NotExpr(wb) || wb == NotExpr(wa)
    }
}

// ── the analyses: disjointness, monotonicity, direction, value at birth ──────
// Shared with RuleGraphGen, which asks the same questions of rule conditions.

internal class FlowAnalysis(val model: Model, val catalog: KindCatalog) {

    fun conjuncts(e: Expr): List<Expr> =
        if (e is Binary && e.op == "and") conjuncts(e.left) + conjuncts(e.right) else listOf(e)

    /** Provable disjointness: some conjunct of one is the complement of some
     *  conjunct of the other. */
    fun disjoint(a: RefinementDecl, b: RefinementDecl): Boolean {
        val wa = a.whereExpr() ?: return false
        val wb = b.whereExpr() ?: return false
        return conjuncts(wa).any { ca -> conjuncts(wb).any { cb -> complementary(ca, cb) } }
    }

    private val complementOps = setOf("<=" to ">", "<" to ">=", "==" to "!=")

    private fun complementary(a: Expr, b: Expr): Boolean {
        if (a == NotExpr(b) || b == NotExpr(a)) return true
        if (a is Binary && b is Binary && a.left == b.left && a.right == b.right)
            return (a.op to b.op) in complementOps || (b.op to a.op) in complementOps
        return false
    }

    // ── monotonicity: can the predicate's truth value ever come back? ────────

    fun mono(e: Expr, subject: String, guard: MutableSet<String> = mutableSetOf()): Mono = when (e) {
        is BoolLit -> Mono.FLAT
        is NotExpr -> when (mono(e.inner, subject, guard)) {
            Mono.UP -> Mono.DOWN
            Mono.DOWN -> Mono.UP
            Mono.FLAT -> Mono.FLAT
            else -> Mono.UNKNOWN
        }
        is Binary -> when (e.op) {
            "and" -> monoAnd(mono(e.left, subject, guard), mono(e.right, subject, guard))
            "or" -> monoOr(mono(e.left, subject, guard), mono(e.right, subject, guard))
            else -> Mono.UNKNOWN
        }
        is ExistsExpr -> {
            val elem = e.shape ?: e.collection?.takeIf { it.where == null }?.let { elementShapeOf(it, subject) }
            if (elem != null && elem in model.shapes && elem !in model.transients) Mono.UP else Mono.UNKNOWN
        }
        is IsExpr -> e.refinement?.let { name ->
            if (!guard.add(name)) Mono.UNKNOWN
            else (model.refinements[name]?.expr as? RefName)?.where
                ?.let { w -> model.baseOf(name)?.let { mono(w, it, guard) } } ?: Mono.UNKNOWN
        } ?: Mono.UNKNOWN
        else -> Mono.UNKNOWN
    }

    private fun monoAnd(a: Mono, b: Mono): Mono = when {
        a == Mono.FLAT -> b
        b == Mono.FLAT -> a
        a == b && (a == Mono.UP || a == Mono.DOWN) -> a
        (a == Mono.UP && b == Mono.DOWN) || (a == Mono.DOWN && b == Mono.UP) -> Mono.UPDOWN
        else -> Mono.UNKNOWN
    }

    private fun monoOr(a: Mono, b: Mono): Mono = when {
        a == Mono.FLAT -> b
        b == Mono.FLAT -> a
        a == b && (a == Mono.UP || a == Mono.DOWN) -> a
        else -> Mono.UNKNOWN
    }

    // ── direction: which way can this kind move the predicate? ───────────────

    fun dir(e: Expr, k: CommitKind, subject: String, guard: MutableSet<String> = mutableSetOf()): Dir = when (e) {
        is NotExpr -> {
            val d = dir(e.inner, k, subject, guard)
            when (d) {
                Dir.TOWARD_TRUE -> Dir.TOWARD_FALSE
                Dir.TOWARD_FALSE -> Dir.TOWARD_TRUE
                else -> d
            }
        }
        is Binary -> when (e.op) {
            "and", "or" -> combineDir(dir(e.left, k, subject, guard), dir(e.right, k, subject, guard))
            "<", "<=", ">", ">=" -> cmpDir(e, k, subject)
            else -> opaqueDir(e, k, subject)
        }
        is ExistsExpr -> {
            if (!touchesExpr(e, k, subject)) Dir.NONE
            else {
                val elem = e.shape ?: e.collection?.let { elementShapeOf(it, subject) }
                if (k is CommitKind.Create && k.shape == elem) Dir.TOWARD_TRUE else Dir.UNKNOWN
            }
        }
        is IsExpr -> isDir(e, k, subject, guard)
        else -> opaqueDir(e, k, subject)
    }

    private fun combineDir(a: Dir, b: Dir): Dir =
        if (a == Dir.NONE) b else if (b == Dir.NONE) a else if (a == b) a else Dir.UNKNOWN

    /** [dir] lifted to refinement expressions (rule conditions, never targets). */
    fun dirRef(e: RefExpr, k: CommitKind, guard: MutableSet<String> = mutableSetOf()): Dir = when (e) {
        is RefName -> {
            val nameDir = when {
                e.name in model.refinements ->
                    if (!guard.add(e.name)) Dir.UNKNOWN
                    else dirRef(model.refinements.getValue(e.name).expr, k, guard)
                else -> Dir.NONE // base-shape membership itself only changes at birth
            }
            combineDir(nameDir, e.where?.let { dir(it, k, e.name) } ?: Dir.NONE)
        }
        is RefNot -> when (val d = dirRef(e.inner, k, guard)) {
            Dir.TOWARD_TRUE -> Dir.TOWARD_FALSE
            Dir.TOWARD_FALSE -> Dir.TOWARD_TRUE
            else -> d
        }
        is RefAnd -> combineDir(dirRef(e.left, k, guard), dirRef(e.right, k, guard))
        is RefOr -> combineDir(dirRef(e.left, k, guard), dirRef(e.right, k, guard))
    }

    /** [initTruth] lifted to refinement expressions: does the condition hold for
     *  a brand-new instance of its base shape? */
    fun initTruthRef(e: RefExpr, guard: MutableSet<String> = mutableSetOf()): Boolean? = when (e) {
        is RefName -> {
            val nameTruth: Boolean? = when {
                e.name in model.shapes -> true // a new instance is trivially a member of its own base
                e.name in model.refinements ->
                    if (!guard.add(e.name)) null
                    else initTruthRef(model.refinements.getValue(e.name).expr, guard)
                else -> null
            }
            val whereTruth: Boolean? = e.where?.let { initTruth(it, e.name) } ?: true
            when {
                nameTruth == false || whereTruth == false -> false
                nameTruth == true && whereTruth == true -> true
                else -> null
            }
        }
        is RefNot -> initTruthRef(e.inner, guard)?.not()
        is RefAnd -> {
            val l = initTruthRef(e.left, guard)
            val r = initTruthRef(e.right, guard)
            if (l == false || r == false) false else if (l == true && r == true) true else null
        }
        is RefOr -> {
            val l = initTruthRef(e.left, guard)
            val r = initTruthRef(e.right, guard)
            if (l == true || r == true) true else if (l == false && r == false) false else null
        }
    }

    private fun opaqueDir(e: Expr, k: CommitKind, subject: String): Dir =
        if (touchesExpr(e, k, subject)) Dir.UNKNOWN else Dir.NONE

    private fun cmpDir(e: Binary, k: CommitKind, subject: String): Dir {
        val delta = sdAdd(signDelta(e.left, k, subject), sdNeg(signDelta(e.right, k, subject)))
        return when (delta) {
            SD.ZERO -> Dir.NONE
            SD.UNKNOWN -> Dir.UNKNOWN
            // left − right can only rise: "<"-shaped comparisons can only turn false
            SD.NONNEG -> if (e.op == "<" || e.op == "<=") Dir.TOWARD_FALSE else Dir.TOWARD_TRUE
            SD.NONPOS -> if (e.op == "<" || e.op == "<=") Dir.TOWARD_TRUE else Dir.TOWARD_FALSE
        }
    }

    private fun isDir(e: IsExpr, k: CommitKind, subject: String, guard: MutableSet<String>): Dir {
        if (!touchesExpr(e, k, subject)) return Dir.NONE
        val name = e.refinement ?: return Dir.UNKNOWN
        if (name !in model.refinements || !guard.add(name)) return Dir.UNKNOWN
        val where = (model.refinements.getValue(name).expr as? RefName)?.where ?: return Dir.UNKNOWN
        val refBase = model.baseOf(name) ?: return Dir.UNKNOWN
        // a write that can re-point the subject path breaks the proof
        if (touchesExpr(e.subject, k, subject)) return Dir.UNKNOWN
        return dir(where, k, refBase, guard)
    }

    // ── numeric deltas: which way can this kind move a value? ────────────────

    private fun signDelta(e: Expr, k: CommitKind, subject: String): SD {
        if (!touchesExpr(e, k, subject)) return SD.ZERO
        return when (e) {
            TodayLit, NowLit -> SD.NONNEG // touched only by the passage of time
            is PathExpr -> pathDelta(e, k, subject)
            is UnaryMinus -> sdNeg(signDelta(e.inner, k, subject))
            is Binary -> when (e.op) {
                "+" -> sdAdd(signDelta(e.left, k, subject), signDelta(e.right, k, subject))
                "-" -> sdAdd(signDelta(e.left, k, subject), sdNeg(signDelta(e.right, k, subject)))
                else -> SD.UNKNOWN
            }
            is AggCall -> aggDelta(e, k, subject)
            is FunCall -> if ((e.name == "max" || e.name == "min") && e.args.isNotEmpty())
                e.args.map { signDelta(it, k, subject) }.reduce(::sdJoin) else SD.UNKNOWN
            else -> SD.UNKNOWN
        }
    }

    private fun pathDelta(p: PathExpr, k: CommitKind, subject: String): SD {
        var scope = subject
        val names = (if (p.root == "this") emptyList() else listOf(p.root)) + p.segs.map { it.name }
        for ((i, name) in names.withIndex()) {
            val m = model.membersOf(scope)[name] ?: return SD.UNKNOWN
            if (i == names.lastIndex)
                return m.derived?.let { signDelta(it.expr, k, m.owner) } ?: SD.UNKNOWN
            if (k is CommitKind.Assign && k.shape == model.baseOf(scope) && k.field == name) return SD.UNKNOWN
            scope = m.type.instanceShape() ?: return SD.UNKNOWN
        }
        return SD.UNKNOWN
    }

    /** A create of the fold's element shape adds one contribution; the
     *  input-constrained nevers pin its sign. Anything else is unproven. */
    private fun aggDelta(e: AggCall, k: CommitKind, subject: String): SD {
        val elem = elementShapeOf(e.collection, subject) ?: return SD.UNKNOWN
        if (k !is CommitKind.Create || k.shape != elem) return SD.UNKNOWN
        return when (e.name) {
            "count" -> SD.NONNEG
            "sum" -> when (staticFieldSign(elem, e.field ?: return SD.UNKNOWN, mutableSetOf())) {
                SS.NONNEG -> SD.NONNEG
                SS.NONPOS -> SD.NONPOS
                SS.UNKNOWN -> SD.UNKNOWN
            }
            else -> SD.UNKNOWN
        }
    }

    private fun sdNeg(s: SD): SD = when (s) {
        SD.NONNEG -> SD.NONPOS
        SD.NONPOS -> SD.NONNEG
        else -> s
    }

    private fun sdAdd(a: SD, b: SD): SD = when {
        a == SD.ZERO -> b
        b == SD.ZERO -> a
        a == b -> a
        else -> SD.UNKNOWN
    }

    private fun sdJoin(a: SD, b: SD): SD = when {
        a == b -> a
        a == SD.ZERO && b != SD.UNKNOWN -> b
        b == SD.ZERO && a != SD.UNKNOWN -> a
        else -> SD.UNKNOWN
    }

    // ── static field signs, proven by the input-constrained nevers ───────────

    private fun staticFieldSign(shape: String, field: String, guard: MutableSet<String>): SS {
        if (!guard.add("$shape.$field")) return SS.UNKNOWN
        val m = model.membersOf(shape)[field] ?: return SS.UNKNOWN
        m.derived?.let { return staticExprSign(it.expr, m.owner, guard) }
        if (!m.stored) return SS.UNKNOWN
        for (never in model.nevers) {
            val t = never.target as? RefName ?: continue
            if (t.name != shape) continue
            val w = t.where as? Binary ?: continue
            val p = w.left as? PathExpr ?: continue
            if (p.root != field || p.segs.isNotEmpty() || !isZero(w.right)) continue
            when (w.op) {
                "<=", "<" -> return SS.NONNEG // never (f <= 0): every f is positive
                ">=", ">" -> return SS.NONPOS
            }
        }
        return SS.UNKNOWN
    }

    private fun staticExprSign(e: Expr, scope: String, guard: MutableSet<String>): SS = when (e) {
        is IntLit -> if (e.value >= 0) SS.NONNEG else SS.NONPOS
        is DecLit -> e.text.toDoubleOrNull()?.let { if (it >= 0) SS.NONNEG else SS.NONPOS } ?: SS.UNKNOWN
        is PathExpr -> staticPathSign(e, scope, guard)
        is Binary -> {
            val l = staticExprSign(e.left, scope, guard)
            val r = staticExprSign(e.right, scope, guard)
            when (e.op) {
                "+" -> if (l == r) l else SS.UNKNOWN
                "-" -> when {
                    l == SS.NONNEG && r == SS.NONPOS -> SS.NONNEG
                    l == SS.NONPOS && r == SS.NONNEG -> SS.NONPOS
                    else -> SS.UNKNOWN
                }
                "*" -> when {
                    l == SS.UNKNOWN || r == SS.UNKNOWN -> SS.UNKNOWN
                    l == r -> SS.NONNEG
                    else -> SS.NONPOS
                }
                else -> SS.UNKNOWN
            }
        }
        is FunCall -> if ((e.name == "max" || e.name == "min") && e.args.isNotEmpty())
            e.args.map { staticExprSign(it, scope, guard) }.reduce { a, b -> if (a == b) a else SS.UNKNOWN }
        else SS.UNKNOWN
        is AggCall -> when (e.name) {
            "count" -> SS.NONNEG
            "sum" -> elementShapeOf(e.collection, scope)
                ?.let { elem -> e.field?.let { staticFieldSign(elem, it, guard) } } ?: SS.UNKNOWN
            else -> SS.UNKNOWN
        }
        else -> SS.UNKNOWN
    }

    private fun staticPathSign(p: PathExpr, scope: String, guard: MutableSet<String>): SS {
        var s = scope
        val names = (if (p.root == "this") emptyList() else listOf(p.root)) + p.segs.map { it.name }
        for ((i, name) in names.withIndex()) {
            if (i == names.lastIndex) return staticFieldSign(s, name, guard)
            s = model.membersOf(s)[name]?.type?.instanceShape() ?: return SS.UNKNOWN
        }
        return SS.UNKNOWN
    }

    private fun isZero(e: Expr): Boolean =
        (e is IntLit && e.value == 0L) || (e is DecLit && e.text.toDoubleOrNull() == 0.0)

    // ── the value at birth: what holds for a brand-new instance ──────────────

    fun initTruth(e: Expr, scope: String): Boolean? = when (e) {
        is BoolLit -> e.value
        is NotExpr -> initTruth(e.inner, scope)?.not()
        is Binary -> when (e.op) {
            "and" -> {
                val l = initTruth(e.left, scope)
                val r = initTruth(e.right, scope)
                if (l == false || r == false) false else if (l == true && r == true) true else null
            }
            "or" -> {
                val l = initTruth(e.left, scope)
                val r = initTruth(e.right, scope)
                if (l == true || r == true) true else if (l == false && r == false) false else null
            }
            "<", "<=", ">", ">=", "==", "!=" -> {
                val l = initVal(e.left, scope)
                val r = initVal(e.right, scope)
                if (l == null || r == null) null else when (e.op) {
                    "<" -> l < r
                    "<=" -> l <= r
                    ">" -> l > r
                    ">=" -> l >= r
                    "==" -> l == r
                    else -> l != r
                }
            }
            else -> null
        }
        // nothing can reference an instance that does not exist yet
        is ExistsExpr -> when {
            e.shape != null -> if (e.forExpr == PathExpr("this")) false else null
            e.collection != null -> if (birthEmpty(e.collection, scope)) false else null
            else -> null
        }
        else -> null
    }

    private fun initVal(e: Expr, scope: String): Double? = when (e) {
        is IntLit -> e.value.toDouble()
        is DecLit -> e.text.toDoubleOrNull()
        is UnaryMinus -> initVal(e.inner, scope)?.let { -it }
        is Binary -> {
            val l = initVal(e.left, scope)
            val r = initVal(e.right, scope)
            if (l == null || r == null) null else when (e.op) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "/" -> if (r == 0.0) null else l / r
                else -> null
            }
        }
        is PathExpr -> if (e.segs.isNotEmpty()) null else {
            val m = model.membersOf(scope)[e.root]
            when {
                m == null -> null
                m.derived != null && !m.captured -> initVal(m.derived.expr, m.owner)
                m.stored -> model.shapes[scope]?.members
                    ?.filterIsInstance<StoredProp>()?.firstOrNull { it.name == e.root }
                    ?.initially?.let { initVal(it, scope) }
                else -> null
            }
        }
        is AggCall -> if ((e.name == "sum" || e.name == "count") && birthEmpty(e.collection, scope)) 0.0 else null
        is FunCall -> when (e.name) {
            "max" -> e.args.map { initVal(it, scope) ?: return null }.maxOrNull()
            "min" -> e.args.map { initVal(it, scope) ?: return null }.minOrNull()
            else -> null
        }
        else -> null
    }

    /** Inverse collections are empty at birth — nothing referenced the
     *  instance before it existed. */
    private fun birthEmpty(c: CollectionExpr, scope: String): Boolean = c.bindings.all { b ->
        (b.source as? PathExpr)?.let { p ->
            p.segs.isEmpty() && model.membersOf(scope)[p.root]?.inverse != null
        } == true
    }

    // ── shared plumbing ──────────────────────────────────────────────────────

    private val summaries = mutableMapOf<Pair<Expr, String>, ReadSummary>()

    private fun touchesExpr(e: Expr, k: CommitKind, subject: String): Boolean {
        val s = summaries.getOrPut(e to subject) {
            ReadSummary().also { model.collectExpr(e, subject, it) }
        }
        return catalog.touches(k, s)
    }

    private fun elementShapeOf(c: CollectionExpr, subject: String): String? =
        when (val src = c.bindings.lastOrNull()?.source) {
            is PathExpr ->
                if (src.segs.isEmpty() && src.root in model.shapes) src.root
                else model.pathElementShape(src, subject)
            is ShapeForSource -> src.shape.takeIf { it in model.shapes }
            else -> null
        }
}

// ── shared phrasing ──────────────────────────────────────────────────────────

private fun ruleActions(rule: RuleDecl): String = rule.body.mapNotNull {
    when (it) {
        is Creation -> "inserts ${it.shape}"
        is Assignment -> "sets ${Printer.expr(it.target)} = ${Printer.expr(it.value)}"
        ThenMarker -> null
    }
}.joinToString(", ")

private fun triggerPhrase(rule: RuleDecl): String {
    val schedules = rule.triggers.filter { it != "commit" }
    val sweep = schedules.joinToString(", ")
    return when {
        rule.preposition == "after" ->
            "fires after commit, in its own transaction" +
                if (schedules.isEmpty()) "" else ", healed at every $sweep tick"
        rule.preposition == "on" && "commit" !in rule.triggers -> "runs at every $sweep tick"
        else -> "fires in the committing transaction" +
            if (schedules.isEmpty()) "" else ", re-checked at every $sweep tick"
    }
}
