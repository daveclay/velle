package velle

import velle.validation.subExpressionsOf
import velle.spec.partsJoinedByAnd

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
 *    when no rule deletes the witness (OQ37) — so its edges are one-way, and a conjunction of
 *    a positive and a negated atom yields the once-through chain: never a
 *    member, then a member, then out permanently;
 *  - a comparison moves one way when the fold behind it has a pinned sign:
 *    a door-checked `never` proving every contribution positive is what
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
        val byBase = StateFlowFacts.refinementsByBase(model)
        if (byBase.isEmpty()) return ""
        return buildString {
            appendLine("## State flow")
            appendLine()
            appendLine(
                "A state is membership in a refinement, and memberships overlap — so each diagram below " +
                    "is one *axis* of membership, varying independently of its siblings. Arrow directions " +
                    "are proven from the spec (an `exists` can only be satisfied — " +
                    (if (model.deletedShapes.isEmpty()) "v0 never deletes" else "where no rule deletes the witness (OQ37)") +
                    " — and " +
                    "a fold moves one way when the door-checked prohibitions pin the sign of every " +
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

/** The values a creation statement gives the record's fields, by field name. */
internal fun Creation.givenValues(): Map<String, Expr> = fields.associate { field -> field.name to field.value }

/**
 * One fact the state-flow diagrams draw as an arrow: a commit of [cause] can move a record of
 * [base] into the refinement [refinement] ([Dir.TOWARD_TRUE]), out of it ([Dir.TOWARD_FALSE]),
 * or either way with no proof of which ([Dir.UNKNOWN], drawn "may flip"). The diagrams draw
 * these and the comparison of two specs compares them.
 */
internal data class MembershipMove(val base: String, val refinement: String, val cause: Cause, val direction: Dir)

/** The state-flow facts: what the diagrams draw, before any drawing. */
internal object StateFlowFacts {

    /** The refinements of [base] that are states of their own, `base where <predicate>`;
     *  the others are views composed of them. */
    fun atomicRefinements(model: Model, base: String, refinements: List<RefinementDecl>): List<RefinementDecl> =
        refinements.filter { refinement ->
            val narrowed = refinement.expr as? RefName ?: return@filter false
            (narrowed.name == base || model.isStaticUnion(narrowed.name)) && narrowed.where != null
        }

    /** Every move of every state of every shape that is kept, the births excepted (a birth is the diagram's start arrow). */
    fun moves(model: Model, catalog: KindCatalog): List<MembershipMove> {
        val analysis = FlowAnalysis(model, catalog)
        return refinementsByBase(model).filterKeys { base -> base !in model.transients }
            .flatMap { (base, refinements) ->
                atomicRefinements(model, base, refinements).flatMap { refinement -> movesOf(model, catalog, analysis, base, refinement) }
            }
    }

    fun refinementsByBase(model: Model): Map<String, List<RefinementDecl>> {
        val byBase = linkedMapOf<String, MutableList<RefinementDecl>>()
        for (refinement in model.refinements.values) {
            if (model.isStaticUnion(refinement.name)) continue
            // a refinement over a union is an axis of each operand (Reference §9)
            for (base in model.basesOf(refinement.name)) byBase.getOrPut(base) { mutableListOf() }.add(refinement)
        }
        return byBase
    }

    fun movesOf(model: Model, catalog: KindCatalog, analysis: FlowAnalysis, base: String, refinement: RefinementDecl): List<MembershipMove> {
        val where = refinement.whereExpr()!!
        val summary = model.predicateSummary(refinement.name)
        return catalog.causes
            .filterNot { cause -> cause.kind is CommitKind.Create && cause.kind.shape == base }
            .filter { cause -> catalog.touches(cause.kind, summary) }
            .map { cause -> MembershipMove(base, refinement.name, cause, analysis.dir(where, cause.kind, base, creations = cause.creations)) }
            .filter { move -> move.direction != Dir.NONE }
    }
}

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
    val atomics = StateFlowFacts.atomicRefinements(model, base, refs)
    val composites = refs - atomics.toSet()
    val analysis = FlowAnalysis(model, catalog)

    /** The one-way notes' justification: the mono proof only holds where the
     *  witness has no deleter, and the phrasing says which claim is being made. */
    val noDelete = if (model.deletedShapes.isEmpty()) "v0 never deletes" else "no rule deletes the witness (OQ37)"

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
        note(this, r.name, "one-way — ${Printer.expr(r.whereExpr()!!)} can never stop holding ($noDelete)")
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
        note(this, "post", "one-way — " + (settled?.let { "$it now holds forever ($noDelete)" }
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
        val down = partsJoinedByAnd(r.whereExpr()!!)
            .firstOrNull { analysis.mono(it, base) == Mono.DOWN }
        val settled = ((down as? NotExpr)?.inner ?: down)?.let { Printer.expr(it) }
        note(this, "post", "terminal — " + (settled?.let {
            "$it now holds forever ($noDelete), so membership can never be regained"
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
        val moves = StateFlowFacts.movesOf(model, catalog, analysis, base, r)
        fun labels(direction: Dir) = moves.filter { move -> move.direction == direction }.map { move -> move.cause.label }.distinct()
        return Causes(labels(Dir.TOWARD_TRUE), labels(Dir.TOWARD_FALSE), labels(Dir.UNKNOWN))
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
            val moment = when (rule.trigger) {
                Trigger.ENTRY -> "on entry"
                Trigger.DELETED -> "on deletion"
                Trigger.LEAVING_OR_DELETED -> "on exit or deletion"
                else -> "on exit"
            }
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

    /** Provable disjointness: some conjunct of one is the complement of some
     *  conjunct of the other. */
    fun disjoint(a: RefinementDecl, b: RefinementDecl): Boolean {
        val wa = a.whereExpr() ?: return false
        val wb = b.whereExpr() ?: return false
        return partsJoinedByAnd(wa).any { ca -> partsJoinedByAnd(wb).any { cb -> complementary(ca, cb) } }
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
            // one-way only while nothing deletes the witness (OQ37): a deleter
            // makes the exists un-satisfiable again, so the edge may flip
            if (elem != null && elem in model.shapes && elem !in model.transients &&
                elem !in model.deletedShapes) Mono.UP else Mono.UNKNOWN
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

    /**
     * Which way a commit of kind [k] can move [e], read with [subject] as its scope. [creations]
     * are the creation statements by which a rule's commit of [k] creates its records, read for
     * the values they give; they are empty when the records come from an act arriving, whose
     * caller supplies the values, and for any other kind.
     */
    fun dir(e: Expr, k: CommitKind, subject: String, guard: MutableSet<String> = mutableSetOf(), creations: List<Creation> = emptyList()): Dir = when (e) {
        is NotExpr -> {
            val d = dir(e.inner, k, subject, guard, creations)
            when (d) {
                Dir.TOWARD_TRUE -> Dir.TOWARD_FALSE
                Dir.TOWARD_FALSE -> Dir.TOWARD_TRUE
                else -> d
            }
        }
        is Binary -> when (e.op) {
            "and", "or" -> combineDir(dir(e.left, k, subject, guard, creations), dir(e.right, k, subject, guard, creations))
            "<", "<=", ">", ">=" -> cmpDir(e, k, subject)
            else -> opaqueDir(e, k, subject)
        }
        is ExistsExpr -> existsDir(e, k, subject, guard, creations)
        is IsExpr -> isDir(e, k, subject, guard, creations)
        else -> opaqueDir(e, k, subject)
    }

    private fun existsDir(e: ExistsExpr, k: CommitKind, subject: String, guard: MutableSet<String>, creations: List<Creation>): Dir {
        if (!touchesExpr(e, k, subject)) return Dir.NONE
        val elementShape = e.shape ?: e.collection?.let { collection -> elementShapeOf(collection, subject) }
        // a record of any shape a taken shape or a static union covers makes it exist (Reference §9)
        if (k is CommitKind.Create && elementShape != null && model.typeAccepts(elementShape, k.shape))
            return creationDir(e.collection?.where, k.shape, creations)
        val fixedCollection = fixedFilteredCollection(e.collection, k, subject) ?: return Dir.UNKNOWN
        // the same elements before and after: the exists moves the way the filter moves on each one
        return dir(fixedCollection.where, k, fixedCollection.elementShape, guard, creations)
    }

    /**
     * Which way a creation of a [createdShape] record moves an `exists` over a collection
     * filtered by [where]. A new element can only make it hold, and does not when [where] is
     * provably false, at the moment of creation, for every record the rule's [creations] make.
     * That reading needs [where] to read only the new record's own fields, so that creating it
     * changes nothing [where] reads of the elements already there.
     */
    private fun creationDir(where: Expr?, createdShape: String, creations: List<Creation>): Dir {
        if (where == null || creations.isEmpty() || !readsOnlyOwnFields(where, createdShape)) return Dir.TOWARD_TRUE
        val mayHold = creations.any { creation -> initTruth(where, createdShape, creation.givenValues()) != false }
        return if (mayHold) Dir.TOWARD_TRUE else Dir.NONE
    }

    private fun readsOnlyOwnFields(where: Expr, shape: String): Boolean {
        if (mentionsThis(where)) return false
        val reads = ReadSummary().also { summary -> model.collectExpr(where, shape, summary) }
        val ownShapes = setOfNotNull(shape, model.baseOf(shape))
        return !reads.opaque && reads.existsShapes.isEmpty() && reads.collShapes.isEmpty() && reads.collFields.isEmpty() &&
            reads.fields.all { (owner, _) -> owner in ownShapes }
    }

    /** An `exists` over [elementShape] records filtered by [where], whose elements a commit cannot change. */
    private class FixedCollection(val where: Expr, val elementShape: String)

    /**
     * [collection] as a [FixedCollection] when it is filtered, reads one relationship of
     * [subject] or the records of one shape, and a commit of kind [k] cannot add an element to
     * it, remove one, or re-point one in or out. Null for every collection this cannot be proven for.
     */
    private fun fixedFilteredCollection(collection: CollectionExpr?, k: CommitKind, subject: String): FixedCollection? {
        val where = collection?.where ?: return null
        // `this` names the subject, whose fields a read of one element does not see
        if (!readsThisOnlyAsItself(where)) return null
        val source = collection.bindings.singleOrNull()?.takeIf { binding -> binding.alias == null }?.source ?: return null
        val relationship = soleRelationshipOf(source, subject)
        if (relationship != null) {
            // a static union's records are fixed by declarations, as a shape's are (Reference §9)
            val elementShape = (relationship.type as? VType.Coll)?.shape
                ?.takeIf { shape -> shape in model.shapes || model.isStaticUnion(shape) } ?: return null
            if (canChangeElements(k, subject, relationship, elementShape)) return null
            return FixedCollection(where, elementShape)
        }
        val recordShape = recordsOfOneShape(source) ?: return null
        if (canChangeRecords(k, source, recordShape, subject)) return null
        return FixedCollection(where, recordShape)
    }

    /** Whether every `this` in [expression] stands alone on one side of `==` or `!=`: the subject
     *  record itself, compared with a field of the element, and no field of the subject read. */
    private fun readsThisOnlyAsItself(expression: Expr): Boolean {
        if (expression is PathExpr) return expression.root != "this"
        val subjectItself = PathExpr("this")
        if (expression is Binary && (expression.op == "==" || expression.op == "!=")) {
            if (expression.left == subjectItself) return readsThisOnlyAsItself(expression.right)
            if (expression.right == subjectItself) return readsThisOnlyAsItself(expression.left)
        }
        val parts = if (expression is ListLit) expression.items else subExpressionsOf(expression)
        return parts.all { part -> readsThisOnlyAsItself(part) }
    }

    /** The shape of the records [source] holds when it is every record of one shape, `Loan`, or
     *  the records of one shape that name the subject, `Loan for this`. Null for anything else. */
    private fun recordsOfOneShape(source: Expr): String? = when {
        source is PathExpr && source.segs.isEmpty() -> source.root.takeIf { name -> name in model.shapes }
        source is ShapeForSource && source.forExpr == PathExpr("this") -> source.shape.takeIf { name -> name in model.shapes }
        else -> null
    }

    /** Whether a commit of kind [k] can change which [recordShape] records [source] holds. */
    private fun canChangeRecords(k: CommitKind, source: Expr, recordShape: String, subject: String): Boolean {
        val shapesThatMatter = model.basesOf(recordShape) + recordShape + setOfNotNull(model.baseOf(subject))
        return when (k) {
            is CommitKind.Create -> k.shape in shapesThatMatter || model.typeAccepts(recordShape, k.shape)
            is CommitKind.Delete -> k.shape in shapesThatMatter || model.typeAccepts(recordShape, k.shape)
            // `for` finds the records by a reference field, which a write can re-point
            is CommitKind.Assign -> source is ShapeForSource && touchesExpr(source, k, subject)
            CommitKind.TimePasses -> false
        }
    }

    /** The one stored or inverse relationship of [subject] that [source] names bare, so that
     *  the filter's bare names name the element's fields. Null for anything else. */
    private fun soleRelationshipOf(source: Expr, subject: String): MemberInfo? {
        if (source !is PathExpr) return null
        if (source.root == "this" || source.segs.isNotEmpty()) return null
        val relationship = model.membersOf(subject)[source.root] ?: return null
        val recorded = relationship.derived == null && (relationship.stored || relationship.inverse != null)
        return relationship.takeIf { recorded }
    }

    /** Whether a commit of kind [k] can change which records [relationship] of [subject] holds. */
    private fun canChangeElements(k: CommitKind, subject: String, relationship: MemberInfo, elementShape: String): Boolean {
        val referenceField = relationship.inverse?.field
        val shapesThatMatter = model.basesOf(elementShape) + elementShape + setOfNotNull(model.baseOf(subject), relationship.inverse?.shape)
        return when (k) {
            is CommitKind.Create -> k.shape in shapesThatMatter || model.typeAccepts(elementShape, k.shape)
            is CommitKind.Delete -> k.shape in shapesThatMatter || model.typeAccepts(elementShape, k.shape)
            // a write of the list itself, or of the reference an inverse is the view of, on any shape
            is CommitKind.Assign -> k.field == relationship.name || k.field == referenceField
            CommitKind.TimePasses -> false
        }
    }

    private fun mentionsThis(e: Expr): Boolean {
        if (e is PathExpr) return e.root == "this"
        val parts = if (e is ListLit) e.items else subExpressionsOf(e)
        return parts.any { part -> mentionsThis(part) }
    }

    private fun combineDir(a: Dir, b: Dir): Dir =
        if (a == Dir.NONE) b else if (b == Dir.NONE) a else if (a == b) a else Dir.UNKNOWN

    /** [dir] lifted to refinement expressions (rule conditions, never targets). */
    fun dirRef(e: RefExpr, k: CommitKind, guard: MutableSet<String> = mutableSetOf(), creations: List<Creation> = emptyList()): Dir = when (e) {
        is RefName -> {
            val nameDir = when {
                e.name in model.refinements ->
                    if (!guard.add(e.name)) Dir.UNKNOWN
                    else dirRef(model.refinements.getValue(e.name).expr, k, guard, creations)
                else -> Dir.NONE // base-shape membership itself only changes at birth
            }
            combineDir(nameDir, e.where?.let { dir(it, k, e.name, creations = creations) } ?: Dir.NONE)
        }
        is RefNot -> when (val d = dirRef(e.inner, k, guard, creations)) {
            Dir.TOWARD_TRUE -> Dir.TOWARD_FALSE
            Dir.TOWARD_FALSE -> Dir.TOWARD_TRUE
            else -> d
        }
        is RefAnd -> combineDir(dirRef(e.left, k, guard, creations), dirRef(e.right, k, guard, creations))
        is RefOr -> combineDir(dirRef(e.left, k, guard, creations), dirRef(e.right, k, guard, creations))
    }

    /** [initTruth] lifted to refinement expressions: does the condition hold for
     *  a brand-new instance of its base shape, created with the [given] values? */
    fun initTruthRef(e: RefExpr, given: Map<String, Expr> = emptyMap(), guard: MutableSet<String> = mutableSetOf()): Boolean? = when (e) {
        is RefName -> {
            val nameTruth: Boolean? = when {
                e.name in model.shapes -> true // a new instance is trivially a member of its own base
                e.name in model.refinements ->
                    if (!guard.add(e.name)) null
                    else initTruthRef(model.refinements.getValue(e.name).expr, given, guard)
                else -> null
            }
            val whereTruth: Boolean? = e.where?.let { initTruth(it, e.name, given) } ?: true
            when {
                nameTruth == false || whereTruth == false -> false
                nameTruth == true && whereTruth == true -> true
                else -> null
            }
        }
        is RefNot -> initTruthRef(e.inner, given, guard)?.not()
        is RefAnd -> {
            val l = initTruthRef(e.left, given, guard)
            val r = initTruthRef(e.right, given, guard)
            if (l == false || r == false) false else if (l == true && r == true) true else null
        }
        is RefOr -> {
            val l = initTruthRef(e.left, given, guard)
            val r = initTruthRef(e.right, given, guard)
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

    private fun isDir(e: IsExpr, k: CommitKind, subject: String, guard: MutableSet<String>, creations: List<Creation>): Dir {
        if (!touchesExpr(e, k, subject)) return Dir.NONE
        val name = e.refinement ?: return Dir.UNKNOWN
        if (name !in model.refinements || !guard.add(name)) return Dir.UNKNOWN
        val where = (model.refinements.getValue(name).expr as? RefName)?.where ?: return Dir.UNKNOWN
        val refBase = model.baseOf(name) ?: return Dir.UNKNOWN
        // a write that can re-point the subject path breaks the proof
        if (touchesExpr(e.subject, k, subject)) return Dir.UNKNOWN
        return dir(where, k, refBase, guard, creations)
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
     *  door-checked nevers pin its sign. Anything else is unproven. */
    private fun aggDelta(e: AggCall, k: CommitKind, subject: String): SD {
        val elem = elementShapeOf(e.collection, subject) ?: return SD.UNKNOWN
        if (k !is CommitKind.Create || !model.typeAccepts(elem, k.shape)) return SD.UNKNOWN
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

    // ── static field signs, proven by the door-checked nevers ────────────────

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

    /**
     * Whether [e] holds for a brand-new record of [scope] at the moment it is created: true,
     * false, or null when the analysis cannot tell. [given] holds the values the creation gives
     * the record's fields, by field name, as a rule's creation spells them; a field it does not
     * give has its `initially` value.
     */
    fun initTruth(e: Expr, scope: String, given: Map<String, Expr> = emptyMap()): Boolean? = when (e) {
        is BoolLit -> e.value
        is NotExpr -> initTruth(e.inner, scope, given)?.not()
        is Binary -> when (e.op) {
            "and" -> {
                val l = initTruth(e.left, scope, given)
                val r = initTruth(e.right, scope, given)
                if (l == false || r == false) false else if (l == true && r == true) true else null
            }
            "or" -> {
                val l = initTruth(e.left, scope, given)
                val r = initTruth(e.right, scope, given)
                if (l == true || r == true) true else if (l == false && r == false) false else null
            }
            "<", "<=", ">", ">=", "==", "!=" -> {
                val newRecord = NewRecord(scope, given)
                BirthValues.compared(e.op, initVal(e.left, newRecord), initVal(e.right, newRecord))
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

    /** The record being created, whose fields [initVal] reads: of shape [scope], with the [given] values. */
    private class NewRecord(val scope: String, val given: Map<String, Expr>)

    /** The value of [e] at the moment a record is created. With no [newRecord], [e] is read
     *  outside the record, as a creation's given value is, and every read of a field is unknown. */
    private fun initVal(e: Expr, newRecord: NewRecord?): BirthValue? = when (e) {
        is IntLit -> BirthValue.number(e.value.toDouble())
        is DecLit -> e.text.toDoubleOrNull()?.let(BirthValue::number)
        TodayLit -> BirthValue(BirthValueKind.FROM_TODAY, 0.0)
        NowLit -> BirthValue(BirthValueKind.FROM_NOW, 0.0)
        is DurationLit -> BirthValues.duration(e)
        is UnaryMinus -> initVal(e.inner, newRecord)?.let(BirthValues::negated)
        is Binary -> BirthValues.arithmetic(e.op, initVal(e.left, newRecord), initVal(e.right, newRecord))
        is PathExpr -> newRecord?.let { record -> fieldAtBirth(e, record) }
        is AggCall -> if (newRecord != null && (e.name == "sum" || e.name == "count") && birthEmpty(e.collection, newRecord.scope)) BirthValue.number(0.0) else null
        // a registered function over constants folds at compile time through its own body
        is FunCall -> model.functionScope[e.name]?.let { function ->
            val numbers = e.args.map { argument -> initVal(argument, newRecord)?.asNumber() ?: return null }
            FunctionResolver.foldNumbers(model.registry, function, numbers.map { number -> java.math.BigDecimal(number) })
                ?.toDouble()?.let(BirthValue::number)
        }
        else -> null
    }

    /** A field of the record being created: the value the creation gives it, else its `initially`. */
    private fun fieldAtBirth(path: PathExpr, newRecord: NewRecord): BirthValue? {
        if (path.segs.isNotEmpty()) return null
        val member = model.membersOf(newRecord.scope)[path.root] ?: return null
        if (member.derived != null && !member.captured) return initVal(member.derived.expr, NewRecord(member.owner, newRecord.given))
        if (!member.stored) return null
        val givenValue = newRecord.given[path.root]
        if (givenValue != null) return initVal(givenValue, null)
        val declared = model.shapes[newRecord.scope]?.members?.filterIsInstance<StoredProp>()?.firstOrNull { field -> field.name == path.root }
        return declared?.initially?.let { initially -> initVal(initially, newRecord) }
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

private fun ruleActions(rule: RuleDecl): String = rule.body.statements().mapNotNull {
    when (it) {
        is ForEach -> "for each " + it.bindings.joinToString(", ") { b -> Printer.loopBinding(b) }
        is Creation -> "inserts ${it.shape}"
        is Assignment -> "sets ${Printer.assignmentTarget(it)} = ${Printer.expr(it.value)}"
        is DeleteStmt -> "deletes ${Printer.expr(it.target)}"
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
