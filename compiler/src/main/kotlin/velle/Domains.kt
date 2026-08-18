package velle

/**
 * Serialization-domain derivation (OQ40): per exposed act — and per
 * schedule-fired rule, per firing — the set of **queue keys** the envelope's
 * work revolves around. Two envelopes conflict iff their domains intersect;
 * U3 obligates serialization exactly between conflicting envelopes, so the
 * domain is the complete statement of what an implementation must queue
 * conflicting work on, and everything outside it is safe to run in parallel.
 *
 * The derivation walks the envelope's whole may-fire cascade (the act commit,
 * every commit-watched rule it can wake — `after commit` firings included,
 * since the act's contract covers the work it causes — plus the invariants
 * and capture refinements evaluated inside the envelope), attributing every
 * read and write to a key. The soundness structure is fail-closed, the same
 * shape ReadSummary's `opaque` already has: **every step either attributes a
 * key or widens — no branch may silently narrow.** Erring wide only makes
 * envelopes wait that didn't need to; erring narrow is the double deposit.
 *
 * Key attribution:
 *  - An instance reached from the envelope's subject by stored to-one hops
 *    keys as that path ([QueueKey.Path]) — evaluated per call, it names the
 *    row conflicting work queues on ("deposits queue per account").
 *  - An instance created inside the envelope is conflict-free by construction
 *    (no concurrent envelope can reference an uncommitted row), but each
 *    keyed instance its to-one fields reference joins the domain — the
 *    writer's half of the phantom conflict, meeting the guard read
 *    (`not exists Witness for X`) on X's key.
 *  - A correlated scan collapses to its key: reading a relationship
 *    collection (an inferred inverse), or a shape correlated back to a keyed
 *    instance (`for this`, `field == <keyed path>`), keys on the correlating
 *    instance, never on the whole shape.
 *  - A value correlation (`other.email == this.email` under a uniqueness
 *    `never`) keys on the committed **value** ([QueueKey.ValueOf]) — there is
 *    no shared row to lock, because neither row exists yet.
 *  - Everything else — an aggregate rooted at a bare shape with no path back
 *    to any key, an opaque read, a path the walker cannot resolve — widens to
 *    the whole shape ([Widening]), and the widening names the read that
 *    caused it. `tolerates contention` on the declaration carrying the read
 *    marks the width deliberate (severity ruling 2026-08-18: advisory, A5).
 *
 * A tick-fired rule's member *scan* is deliberately outside the firing's
 * domain: subject selection runs against settled state and stragglers heal at
 * the next tick (the guard-self-healing design, OQ40 "Ticks and sweeps");
 * each firing's own footprint — subject row included, it is persisted — is in.
 */
class DomainAnalysis(private val model: Model) {

    /** Per exposed act: the derived domain of its commit envelope. */
    val actDomains: Map<String, SerializationDomain> by lazy {
        model.exposed.associateWith { act ->
            val acc = Acc()
            // the act's own creation is the writer's half of any phantom
            // conflict: a reader correlating on one of its to-one references
            // (`exists Issuance for this`) keys on the referenced row, so the
            // commit keys each reference someone can correlate on — even when
            // no rule fires in the envelope at all
            for ((field, anchor) in actAnchor(act).fields) {
                val target = (model.shapes.getValue(act).members.filterIsInstance<StoredProp>()
                    .first { it.name == field }.type as RelType).shape
                if (correlatable(act, field, target))
                    acc.key(anchor, act, "creates $act with an unkeyable '$field'", "commit$act", tolerated = false)
            }
            process(mutableListOf(Event.Create(act, actAnchor(act))), acc)
            acc.toDomain()
        }
    }

    /** Per schedule-fired rule: the derived domain of one firing, keys
     *  relative to the swept subject (`this`, `this.account`, ...). */
    val scheduledRuleDomains: Map<String, SerializationDomain> by lazy {
        model.rules.values
            .filter { r -> r.triggers.any { it != "commit" } }
            .associate { r ->
                val acc = Acc()
                val events = mutableListOf<Event>()
                fireRule(r, Anchor.Reach(emptyList()), acc, events)
                process(events, acc)
                r.name to acc.toDomain()
            }
    }

    /** Widenings that price per *commit* — act-envelope widths only, grouped
     *  by the declaration whose read caused them: the input to A5 and to
     *  dead-tolerance detection. A width living only in a schedule-fired
     *  rule's own firing is priced at the cadence, not per commit — that is
     *  the cadence discharge (OQ40), so it does not warn. */
    fun commitWidenings(): Map<String, List<Widening>> =
        actDomains.values.flatMap { it.widenings }.groupBy { it.declaration }

    // ── anchors: how the walker names an instance ────────────────────────────

    /** What the derivation knows about which instance an expression reaches. */
    private sealed interface Anchor {
        /** A persisted instance the subject's stored to-one path reaches; empty = the subject itself. */
        data class Reach(val segs: List<String>) : Anchor
        /** Created inside this envelope — conflict-free; to-one fields carry their anchors. */
        data class Fresh(val fields: Map<String, Anchor>) : Anchor
        /** An element of a scan correlated to [of] — keys to [of]. When [back]
         *  names the correlating to-one field, hopping it lands exactly on
         *  [of]; every other hop is unknowable. */
        data class Corr(val of: Anchor, val back: String? = null) : Anchor
        /** Correlated on a committed value, not a row — reads of exactly that field are covered. */
        data class ValCorr(val shape: String, val field: String) : Anchor
        /** No attribution — every read here widens. */
        data object Unknown : Anchor
    }

    private val maxPath = 8
    private val maxFreshDepth = 4

    private fun anchorKey(a: Anchor): String = when (a) {
        is Anchor.Reach -> "r:" + a.segs.joinToString(".")
        is Anchor.Fresh -> "f(" + a.fields.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${anchorKey(it.value)}" } + ")"
        is Anchor.Corr -> "c(${anchorKey(a.of)}|${a.back})"
        is Anchor.ValCorr -> "v:${a.shape}.${a.field}"
        Anchor.Unknown -> "?"
    }

    /** Hop a to-one field off an anchored instance. */
    private fun extend(a: Anchor, field: String): Anchor = when (a) {
        is Anchor.Reach -> if (a.segs.size < maxPath) Anchor.Reach(a.segs + field) else Anchor.Unknown
        is Anchor.Fresh -> a.fields[field] ?: Anchor.Unknown
        is Anchor.Corr -> if (field == a.back) a.of else Anchor.Unknown
        else -> Anchor.Unknown
    }

    private fun freshDepth(a: Anchor): Int = when (a) {
        is Anchor.Fresh -> 1 + (a.fields.values.maxOfOrNull { freshDepth(it) } ?: 0)
        is Anchor.Corr -> freshDepth(a.of)
        else -> 0
    }

    /** A created instance's anchor, with a depth cap so creation chains in
     *  rule cycles cannot mint unboundedly-nested anchors (beyond the cap the
     *  references degrade to Unknown — wide, never wrong). */
    private fun freshOf(fields: Map<String, Anchor>): Anchor.Fresh =
        if (fields.values.any { freshDepth(it) >= maxFreshDepth })
            Anchor.Fresh(fields.mapValues { Anchor.Unknown })
        else Anchor.Fresh(fields)

    /** The act's own instance: fresh, with each supplied to-one reference
     *  anchored as the path that reads it off the input. */
    private fun actAnchor(act: String): Anchor.Fresh {
        val fields = model.shapes.getValue(act).members.filterIsInstance<StoredProp>()
            .mapNotNull { p ->
                (p.type as? RelType)?.takeIf { !it.many }
                    ?.let { p.name to Anchor.Reach(listOf(p.name)) as Anchor }
            }.toMap()
        return Anchor.Fresh(fields)
    }

    // ── the cascade: events, watchers, subjects ──────────────────────────────

    private sealed interface Event {
        val shape: String

        data class Create(override val shape: String, val anchor: Anchor) : Event {
            val fieldAnchors: Map<String, Anchor>
                get() = (anchor as? Anchor.Fresh)?.fields ?: emptyMap()
        }
        data class Assign(override val shape: String, val field: String, val owner: Anchor) : Event
    }

    private class Watcher(
        val label: String,          // "rule X" | "never #1" | "refinement R"
        val condition: RefExpr,
        val base: String,
        val summary: ReadSummary,
        val sensShapes: Set<String>,
        val rule: RuleDecl?,        // null for nevers / capture refinements
        val tolerated: Boolean,
        val capturedProps: List<Pair<String, DerivedProp>> = emptyList(), // (owner refinement, prop)
    )

    /** Everything evaluated inside a commit envelope: commit-watched rules
     *  (after-commit included — the act's contract covers the work it causes),
     *  the invariants, and the capture-carrying refinements. */
    private val watchers: List<Watcher> by lazy {
        buildList {
            for (r in model.rules.values) {
                if (!(r.preposition == null || "commit" in r.triggers)) continue
                val base = model.baseOfExpr(r.condition) ?: continue
                add(watcherOf("rule ${r.name}", r.condition, base, r, r.toleratesContention))
            }
            model.nevers.forEachIndexed { i, n ->
                val base = model.baseOfExpr(n.target) ?: return@forEachIndexed
                add(watcherOf("never #${i + 1}", n.target, base, null, n.toleratesContention))
            }
            for ((name, r) in model.refinements) {
                val props = r.members.filterIsInstance<DerivedProp>().filter { it.captured }
                if (props.isEmpty()) continue
                val base = model.baseOf(name) ?: continue
                add(watcherOf("refinement $name", RefName(name), base, null, tolerated = false,
                    captured = props.map { name to it }))
            }
        }
    }

    private fun watcherOf(
        label: String, condition: RefExpr, base: String, rule: RuleDecl?,
        tolerated: Boolean, captured: List<Pair<String, DerivedProp>> = emptyList(),
    ): Watcher {
        val s = model.summaryOfRefExpr(condition)
        val sens = (s.existsShapes + s.collShapes).mapNotNull { model.baseOf(it) }.toSet()
        return Watcher(label, condition, base, s, sens, rule, tolerated, captured)
    }

    /** Can this event change the watcher's member set? Mirrors the runtime's
     *  relevance gating (Runtime.relevant) — an over-approximation. */
    private fun relevant(w: Watcher, e: Event): Boolean {
        if (w.summary.opaque) return true
        return when (e) {
            is Event.Create ->
                e.shape == w.base || e.shape in w.sensShapes ||
                    w.summary.fields.any { it.first == e.shape } ||
                    w.summary.collFields.any { it.first == e.shape }
            is Event.Assign ->
                e.shape in w.sensShapes ||
                    (e.shape to e.field) in w.summary.fields ||
                    (e.shape to e.field) in w.summary.collFields
        }
    }

    /**
     * The subject anchors a watcher can be evaluated at, given the event that
     * woke it. Same base: the event's own instance (cross-envelope coverage of
     * sibling subjects rests on the symmetric-evaluation argument — any
     * envelope hosting an affected sibling evaluates this watcher too and
     * records the mirrored access). Foreign shape, two correlation
     * directions:
     *  - **reverse** — the watcher consults the event's shape through a field
     *    of that shape (an inferred inverse, `for this`, `f == this`): each
     *    affected subject is the instance the field names, read off the event;
     *  - **forward** — the watcher's base points at the event's shape through
     *    its own to-one field g (`this.g is Locked`): the affected subjects
     *    are exactly the base rows whose g references the touched instance —
     *    a correlated set, anchored [Anchor.Corr] with g as the back-link.
     * No recognized correlation → [Anchor.Unknown]: the walk then widens
     * every read, which is the honest answer.
     */
    private fun subjectAnchors(w: Watcher, e: Event): List<Anchor> {
        val eventBase = model.baseOf(e.shape) ?: e.shape
        val eventAnchor = when (e) {
            is Event.Create -> e.anchor
            is Event.Assign -> e.owner
        }
        if (eventBase == w.base) return listOf(eventAnchor)
        val subjects = mutableListOf<Anchor>()
        for ((_, field) in correlationFields(w).filter { it.first == eventBase }) {
            subjects.add(
                when (e) {
                    is Event.Create -> e.fieldAnchors[field] ?: Anchor.Unknown
                    is Event.Assign -> extend(e.owner, field)
                }
            )
        }
        model.shapes[w.base]?.members?.filterIsInstance<StoredProp>()
            ?.filter { (it.type as? RelType)?.let { t -> !t.many && model.baseOf(t.shape) == eventBase } == true }
            ?.forEach { subjects.add(Anchor.Corr(eventAnchor, it.name)) }
        if (subjects.isEmpty()) subjects.add(Anchor.Unknown)
        return subjects
    }

    /**
     * (foreign shape, to-one field of it) pairs through which the watcher's
     * condition consults that shape *correlated to the subject*: an inferred
     * inverse collection read off the subject, `exists T for this`,
     * `(T for this)`, or a scan filtered by `field == this`. Collected
     * syntactically; a correlation this walk misses just leaves the subject
     * Unknown — wide, never wrong.
     */
    private val correlationCache = mutableMapOf<String, Set<Pair<String, String>>>()

    private fun correlationFields(w: Watcher): Set<Pair<String, String>> =
        correlationCache.getOrPut(w.label) {
            val out = mutableSetOf<Pair<String, String>>()
            collectCorrelations(w.condition, out, mutableSetOf())
            out
        }

    private fun collectCorrelations(e: RefExpr, out: MutableSet<Pair<String, String>>, seen: MutableSet<String>) {
        when (e) {
            is RefName -> {
                if (e.name in model.refinements && seen.add(e.name))
                    collectCorrelations(model.refinements.getValue(e.name).expr, out, seen)
                e.where?.let { corrExpr(it, e.name, out, seen) }
            }
            is RefNot -> collectCorrelations(e.inner, out, seen)
            is RefAnd -> { collectCorrelations(e.left, out, seen); collectCorrelations(e.right, out, seen) }
            is RefOr -> { collectCorrelations(e.left, out, seen); collectCorrelations(e.right, out, seen) }
        }
    }

    private fun corrExpr(e: Expr, scope: String, out: MutableSet<Pair<String, String>>, seen: MutableSet<String>) {
        fun inverseAt(p: PathExpr) {
            // a subject-rooted read of an inferred inverse collection: `loans` /
            // `this.loans` — including one hidden behind a derived property
            // (`balance` → `total` → `sum(lineItems, …)`), which is expanded
            val name = when {
                p.root == "this" && p.segs.size == 1 -> p.segs[0].name
                p.segs.isEmpty() && p.root != "this" -> p.root
                else -> return
            }
            val m = model.membersOf(scope)[name] ?: return
            m.inverse?.let { inv -> if (!inv.many) out.add(inv.shape to inv.field) }
            m.derived?.let { d ->
                if (seen.add("d:${m.owner}.${m.name}")) corrExpr(d.expr, m.owner, out, seen)
            }
        }
        fun forThis(shape: String?, forExpr: Expr?) {
            if (shape == null || forExpr != PathExpr("this")) return
            val base = model.baseOf(shape) ?: return
            val subjBase = model.baseOf(scope) ?: return
            model.shapes[base]?.members?.filterIsInstance<StoredProp>()
                ?.filter { (it.type as? RelType)?.let { t -> !t.many && t.shape == subjBase } == true }
                ?.singleOrNull()?.let { out.add(base to it.name) }
        }
        fun collection(c: CollectionExpr) {
            for (b in c.bindings) when (val src = b.source) {
                is PathExpr ->
                    if ((src.root in model.shapes || src.root in model.refinements) && src.segs.isEmpty()) {
                        val base = model.baseOf(src.root) ?: continue
                        for (conj in conjuncts(c.where ?: continue)) {
                            val cmp = conj as? Binary ?: continue
                            if (cmp.op != "==") continue
                            for ((l, r) in listOf(cmp.left to cmp.right, cmp.right to cmp.left)) {
                                val f = (l as? PathExpr)?.takeIf { it.segs.isEmpty() && it.root != "this" }?.root ?: continue
                                val m = model.membersOf(base)[f] ?: continue
                                if (!m.stored || m.type.instanceShape() == null) continue
                                if ((r as? PathExpr)?.root == "this") out.add(base to f)
                            }
                        }
                    } else inverseAt(src)
                is ShapeForSource -> forThis(src.shape, src.forExpr)
                else -> {}
            }
            c.where?.let { corrExpr(it, scope, out, seen) }
        }
        when (e) {
            is PathExpr -> inverseAt(e)
            is Binary -> { corrExpr(e.left, scope, out, seen); corrExpr(e.right, scope, out, seen) }
            is NotExpr -> corrExpr(e.inner, scope, out, seen)
            is UnaryMinus -> corrExpr(e.inner, scope, out, seen)
            is IfExpr -> { corrExpr(e.condition, scope, out, seen); corrExpr(e.thenExpr, scope, out, seen); corrExpr(e.elseExpr, scope, out, seen) }
            is ExistsExpr -> {
                forThis(e.shape, e.forExpr)
                e.collection?.let { collection(it) }
            }
            is SingularFor -> forThis(e.shape, e.forExpr)
            is AggCall -> collection(e.collection)
            is SetExpr -> collection(e.collection)
            is FunCall -> e.args.forEach { corrExpr(it, scope, out, seen) }
            is Access -> corrExpr(e.target, scope, out, seen)
            else -> {}
        }
    }

    private fun conjuncts(e: Expr): List<Expr> =
        if (e is Binary && e.op == "and") conjuncts(e.left) + conjuncts(e.right) else listOf(e)

    /** Can any reader correlate rows of [shape] on [field]? True when the
     *  target shape carries the inferred inverse of this field, or when some
     *  watcher's condition correlates through it — the cases where a write to
     *  such a row must also key the referenced instance. */
    private fun correlatable(shape: String, field: String, target: String): Boolean =
        model.membersOf(target).values.any {
            it.inverse?.let { inv -> inv.shape == shape && inv.field == field && !inv.many } == true
        } || watchers.any { (shape to field) in correlationFields(it) }

    // ── the derivation driver ────────────────────────────────────────────────

    private fun process(worklist: MutableList<Event>, acc: Acc) {
        val seenEvents = mutableSetOf<String>()
        val firedAt = mutableSetOf<String>()
        var budget = 10_000
        while (worklist.isNotEmpty()) {
            if (budget-- <= 0) {
                // a runaway cascade cannot be attributed: fail closed, everywhere
                for (shape in model.shapes.keys)
                    acc.widen(shape, "the cascade exceeded the derivation's work budget", "derivation", tolerated = false)
                return
            }
            val e = worklist.removeFirst()
            val ek = when (e) {
                is Event.Create -> "c:${e.shape}:${anchorKey(e.anchor)}"
                is Event.Assign -> "a:${e.shape}.${e.field}:${anchorKey(e.owner)}"
            }
            if (!seenEvents.add(ek)) continue
            for (w in watchers) {
                // a transient act's partitions are decided exactly once, at its
                // creation commit (README §4); no other event can fire them
                if (w.base in model.transients && !(e is Event.Create && e.shape == w.base)) continue
                if (!relevant(w, e)) continue
                for (subject in subjectAnchors(w, e)) {
                    if (!firedAt.add("${w.label}@${anchorKey(subject)}")) continue
                    if (w.rule != null) fireRule(w.rule, subject, acc, worklist)
                    else {
                        val walker = Walker(acc, w.label, w.tolerated)
                        walker.walkRef(w.condition, walker.ctxOf(conditionScope(w.condition), subject))
                        for ((owner, prop) in w.capturedProps)
                            walker.walkExpr(prop.expr, walker.ctxOf(owner, subject))
                    }
                }
            }
        }
    }

    private fun conditionScope(c: RefExpr): String =
        if (c is RefName && (c.name in model.shapes || c.name in model.refinements)) c.name
        else model.baseOfExpr(c) ?: "?"

    private fun fireRule(rule: RuleDecl, subject: Anchor, acc: Acc, events: MutableList<Event>) {
        val walker = Walker(acc, "rule ${rule.name}", rule.toleratesContention)
        val ctx = walker.ctxOf(conditionScope(rule.condition), subject)
        walker.walkRef(rule.condition, ctx)
        for (item in rule.body) when (item) {
            is Assignment -> walker.walkAssignment(item, ctx, events)
            is Creation -> walker.walkCreation(item, ctx, events)
            ThenMarker -> {}
        }
    }

    // ── the accumulator ──────────────────────────────────────────────────────

    private inner class Acc {
        val paths = mutableSetOf<List<String>>()
        val values = mutableSetOf<Pair<String, String>>()
        val widenings = LinkedHashMap<Pair<String, String>, Widening>()

        fun key(a: Anchor, shape: String, cause: String, decl: String, tolerated: Boolean) {
            when (a) {
                is Anchor.Reach -> paths.add(a.segs)
                is Anchor.Fresh -> {} // in-envelope creation: conflict-free by construction
                is Anchor.Corr -> key(a.of, shape, cause, decl, tolerated)
                is Anchor.ValCorr -> values.add(a.shape to a.field)
                Anchor.Unknown -> widen(shape, cause, decl, tolerated)
            }
        }

        fun widen(shape: String, cause: String, decl: String, tolerated: Boolean) {
            widenings.putIfAbsent(decl to shape, Widening(shape, cause, decl, tolerated))
        }

        fun toDomain() = SerializationDomain(
            paths = paths.sortedBy { it.joinToString(".") }.map { QueueKey.Path(it) }.toSet(),
            valueKeys = values.map { QueueKey.ValueOf(it.first, it.second) }.toSet(),
            widenings = widenings.values.toList(),
        )
    }

    // ── the keyed expression walker ──────────────────────────────────────────

    private inner class Walker(val acc: Acc, val decl: String, val tolerated: Boolean) {

        private val expanding = mutableSetOf<String>() // refinement / derived-property cycle guard

        inner class Scope(val name: String, val anchor: Anchor)

        inner class Ctx(
            val subject: Scope,
            val elements: List<Scope> = emptyList(),
            val aliases: Map<String, Scope> = emptyMap(),
        ) {
            fun innermost(): Scope = elements.lastOrNull() ?: subject
            fun push(s: Scope, alias: String?) = Ctx(
                subject, elements + s,
                if (alias != null) aliases + (alias to s) else aliases,
            )
        }

        fun ctxOf(scope: String, anchor: Anchor) = Ctx(Scope(scope, anchor))

        // ── refinement expressions ───────────────────────────────────────────

        fun walkRef(e: RefExpr, ctx: Ctx) {
            when (e) {
                is RefName -> {
                    val at = ctx.innermost().anchor
                    if (e.name in model.refinements && expanding.add("ref:${e.name}"))
                        walkRef(model.refinements.getValue(e.name).expr, ctxOf(e.name, at))
                    e.where?.let { walkExpr(it, ctxOf(e.name, at)) }
                }
                is RefNot -> walkRef(e.inner, ctx)
                is RefAnd -> { walkRef(e.left, ctx); walkRef(e.right, ctx) }
                is RefOr -> { walkRef(e.left, ctx); walkRef(e.right, ctx) }
            }
        }

        // ── expressions ──────────────────────────────────────────────────────

        /** Walks [e], recording keyed reads; returns the instance scope the
         *  expression denotes when it denotes one (for hop continuation). */
        fun walkExpr(e: Expr, ctx: Ctx): Scope? {
            when (e) {
                is PathExpr -> return walkPath(e, ctx)
                is UnaryMinus -> walkExpr(e.inner, ctx)
                is Binary -> { walkExpr(e.left, ctx); walkExpr(e.right, ctx) }
                is NotExpr -> walkExpr(e.inner, ctx)
                is IfExpr -> { walkExpr(e.condition, ctx); walkExpr(e.thenExpr, ctx); walkExpr(e.elseExpr, ctx) }
                is IsExpr -> {
                    val subj = walkExpr(e.subject, ctx)
                    e.refinement?.let { r ->
                        if (r in model.refinements && expanding.add("is:$r"))
                            walkRef(model.refinements.getValue(r).expr,
                                ctxOf(r, subj?.anchor ?: ctx.innermost().anchor))
                    }
                }
                is ExistsExpr -> {
                    if (e.shape != null) correlatedSource(e.shape, e.forExpr!!, ctx)
                    e.collection?.let { walkCollection(it, ctx) }
                }
                is AggCall -> {
                    val elem = walkCollection(e.collection, ctx)
                    for (f in listOfNotNull(e.field) + e.orderBy) {
                        val m = elem?.let { model.membersOf(it.name)[f] }
                        if (m != null) read(m, elem.anchor)
                        else acc.widen(elem?.name ?: ctx.innermost().name,
                            "an aggregate selects '$f', which the derivation cannot attribute", decl, tolerated)
                    }
                    return if (e.name == "latest" || e.name == "first") elem else null
                }
                is FunCall -> e.args.forEach { walkExpr(it, ctx) }
                is SingularFor -> return correlatedSource(e.shape, e.forExpr, ctx)
                is Access -> {
                    var scope = walkExpr(e.target, ctx)
                    for (seg in e.segs) scope = hop(scope, seg.name)
                    return scope
                }
                is ShapeForSource -> return correlatedSource(e.shape, e.forExpr, ctx)
                is SetExpr -> return walkCollection(e.collection, ctx)
                else -> {}
            }
            return null
        }

        /** `exists T for x` / `(T for x)`: consults T's instances correlated to
         *  x — keys on x's anchor; elements are correlated rows of T. */
        private fun correlatedSource(shape: String, forExpr: Expr, ctx: Ctx): Scope {
            val target = walkExpr(forExpr, ctx)
            val anchor = target?.anchor ?: Anchor.Unknown
            val base = model.baseOf(shape) ?: shape
            acc.key(anchor, base,
                "consults $shape through a path the derivation cannot key", decl, tolerated)
            val back = target?.let { t ->
                model.shapes[base]?.members?.filterIsInstance<StoredProp>()
                    ?.singleOrNull { p ->
                        (p.type as? RelType)?.let { r -> !r.many && r.shape == model.baseOf(t.name) } == true
                    }?.name
            }
            val elem = Scope(shape, Anchor.Corr(anchor, back))
            if (shape in model.refinements && expanding.add("src:$shape"))
                walkRef(model.refinements.getValue(shape).expr, Ctx(elem))
            return elem
        }

        private fun walkPath(p: PathExpr, ctx: Ctx): Scope? {
            var scope: Scope? = when {
                p.root == "this" -> ctx.subject
                p.root in ctx.aliases -> ctx.aliases.getValue(p.root)
                p.root in model.shapes || p.root in model.refinements -> {
                    // bare name as a membership atom of the innermost element
                    if (p.root in model.refinements && expanding.add("atom:${p.root}"))
                        walkRef(model.refinements.getValue(p.root).expr,
                            ctxOf(p.root, ctx.innermost().anchor))
                    if (p.segs.isNotEmpty())
                        acc.widen(model.baseOf(p.root) ?: p.root,
                            "'${Printer.expr(p)}' reads through a bare shape name", decl, tolerated)
                    return null
                }
                else -> {
                    val inner = ctx.innermost()
                    val m = model.membersOf(inner.name)[p.root]
                    if (m == null) {
                        acc.widen(model.baseOf(inner.name) ?: inner.name,
                            "'${p.root}' cannot be resolved on '${inner.name}'", decl, tolerated)
                        return null
                    }
                    memberScope(m, inner)
                }
            }
            for (seg in p.segs) scope = hop(scope, seg.name)
            return scope
        }

        /** Read a member and step to its instance scope (to-one) or its
         *  correlated element scope (collection). */
        private fun memberScope(m: MemberInfo, at: Scope): Scope? {
            read(m, at.anchor)
            m.type.instanceShape()?.let { return Scope(it, extend(at.anchor, m.name)) }
            (m.type as? VType.Coll)?.let { coll ->
                // a relationship collection is a correlated scan keyed by its owner
                acc.key(at.anchor, coll.shape,
                    "consults ${coll.shape} through '${m.name}' with no keyable owner", decl, tolerated)
                val back = m.inverse?.takeIf { !it.many }?.field
                return Scope(coll.shape, Anchor.Corr(at.anchor, back))
            }
            return null
        }

        private fun hop(scope: Scope?, field: String): Scope? {
            if (scope == null) return null
            val m = model.membersOf(scope.name)[field]
            if (m == null) {
                acc.widen(model.baseOf(scope.name) ?: scope.name,
                    "'$field' cannot be resolved on '${scope.name}'", decl, tolerated)
                return null
            }
            return memberScope(m, scope)
        }

        /** Record one member read at an anchor — the attribution point. Every
         *  stored/timestamp read either keys or widens; derived members expand. */
        private fun read(m: MemberInfo, anchor: Anchor) {
            if (m.stored || m.timestamp) {
                if (anchor is Anchor.ValCorr && anchor.shape == m.owner && anchor.field == m.name)
                    return // the correlating field itself: covered by the value key
                acc.key(anchor, m.owner,
                    "reads ${m.owner}.${m.name} with no path back to any key", decl, tolerated)
            }
            m.derived?.let { d ->
                if (expanding.add("d:${m.owner}.${m.name}"))
                    walkExpr(d.expr, ctxOf(m.owner, anchor))
            }
        }

        // ── collections: where correlation is found or width is born ─────────

        /** Returns the last binding's element scope. */
        fun walkCollection(c: CollectionExpr, ctx: Ctx): Scope? {
            var elem: Scope? = null
            var inner = ctx
            for (b in c.bindings) {
                val bScope: Scope = when (val src = b.source) {
                    is PathExpr ->
                        if ((src.root in model.shapes || src.root in model.refinements) && src.segs.isEmpty())
                            bareShapeSource(src.root, b, c, ctx)
                        else walkPath(src, inner) ?: unknownScope()
                    is ShapeForSource -> correlatedSource(src.shape, src.forExpr, inner)
                    else -> {
                        walkExpr(src, inner)
                        unknownScope()
                    }
                }
                inner = inner.push(bScope, b.alias)
                elem = bScope
            }
            c.where?.let { walkExpr(it, inner) }
            return elem
        }

        /** Fail-closed element scope for a binding the walker cannot attribute:
         *  no members resolve on it, so every read under it widens. */
        private fun unknownScope() = Scope("?", Anchor.Unknown)

        /**
         * A bare-shape binding quantifies over the whole shape — the one place
         * width is born. A correlation conjunct in the shared `where` rescues
         * it: `field == <keyed path>` collapses the scan to the keyed instance;
         * `elemField == <subject scalar>` keys on the committed value. With no
         * correlation the domain honestly widens to the whole shape, and the
         * widening names this read.
         */
        private fun bareShapeSource(root: String, b: Binding, c: CollectionExpr, ctx: Ctx): Scope {
            val base = model.baseOf(root) ?: root
            var anchor: Anchor? = null
            if (c.bindings.size == 1 && c.where != null) {
                outer@ for (conj in conjuncts(c.where)) {
                    val cmp = conj as? Binary ?: continue
                    if (cmp.op != "==") continue
                    for ((l, r) in listOf(cmp.left to cmp.right, cmp.right to cmp.left)) {
                        val fieldName = elemField(l, base, b.alias) ?: continue
                        val m = model.membersOf(base)[fieldName] ?: continue
                        if (!m.stored) continue
                        val rp = r as? PathExpr ?: continue
                        if (rp.root != "this" && rp.root !in ctx.aliases) continue
                        if (m.type.instanceShape() != null) {
                            // instance correlation: the scan collapses to the keyed row
                            val target = walkExpr(rp, ctx)
                            val ta = target?.anchor ?: Anchor.Unknown
                            acc.key(ta, base, "consults $base correlated through '$fieldName'", decl, tolerated)
                            anchor = Anchor.Corr(ta, fieldName)
                        } else {
                            // value correlation: conflict keys on the committed value
                            walkExpr(rp, ctx)
                            anchor = Anchor.ValCorr(base, fieldName)
                            acc.key(anchor, base, "", decl, tolerated)
                        }
                        break@outer
                    }
                }
            }
            if (anchor == null) {
                acc.widen(base,
                    "`${Printer.expr(PathExpr(root))}` reads every $base and correlates to no key",
                    decl, tolerated)
                anchor = Anchor.Unknown
            }
            val elem = Scope(root, anchor)
            if (root in model.refinements && expanding.add("src:$root"))
                walkRef(model.refinements.getValue(root).expr, Ctx(elem))
            return elem
        }

        /** The element-field half of a correlation conjunct: `f` (bare, a member
         *  of the element base) or `<alias>.f`. */
        private fun elemField(e: Expr, base: String, alias: String?): String? {
            val p = e as? PathExpr ?: return null
            return when {
                p.root == alias && p.segs.size == 1 -> p.segs[0].name
                p.segs.isEmpty() && p.root != "this" && model.membersOf(base).containsKey(p.root) -> p.root
                else -> null
            }
        }

        // ── rule bodies: the write side ──────────────────────────────────────

        fun walkAssignment(a: Assignment, ctx: Ctx, events: MutableList<Event>) {
            walkExpr(a.value, ctx)
            // resolve the route to the written field; a collection at the
            // penultimate hop fans out — memberScope keys the collection's
            // owner and yields the correlated element scope, so the final
            // write keys back to the owner
            val route = if (a.target.root == "this") a.target.segs.map { it.name }
            else listOf(a.target.root) + a.target.segs.map { it.name }
            var scope: Scope? = ctx.subject
            for ((i, name) in route.withIndex()) {
                if (i == route.lastIndex) {
                    val sc = scope ?: return
                    val m = model.membersOf(sc.name)[name]
                    val owner = m?.owner ?: model.baseOf(sc.name) ?: sc.name
                    acc.key(sc.anchor, owner,
                        "writes $owner.$name with no path back to any key", decl, tolerated)
                    // the writer's half of a correlated scan: a predicate that
                    // reads "rows of $owner correlated to X via g" keys on X, so
                    // a write to this row also keys the row's correlatable
                    // to-one references — where they can be correlated on at all
                    model.shapes[owner]?.members?.filterIsInstance<StoredProp>()
                        ?.filter { p -> (p.type as? RelType)?.let { t -> !t.many && correlatable(owner, p.name, t.shape) } == true }
                        ?.forEach { p ->
                            acc.key(extend(sc.anchor, p.name), owner,
                                "writes a $owner whose '${p.name}' reference the derivation cannot key", decl, tolerated)
                        }
                    events.add(Event.Assign(owner, name, sc.anchor))
                    // any stored write also advances the shape's `on update` timestamps
                    model.shapes[owner]?.members?.filterIsInstance<TimestampProp>()
                        ?.filter { it.on == "update" }
                        ?.forEach { events.add(Event.Assign(owner, it.name, sc.anchor)) }
                    return
                }
                scope = hop(scope, name)
            }
        }

        fun walkCreation(cr: Creation, ctx: Ctx, events: MutableList<Event>) {
            val fresh = mutableMapOf<String, Anchor>()
            cr.forExpr?.let { forE ->
                val target = walkExpr(forE, ctx)
                val anchor = target?.anchor ?: Anchor.Unknown
                val targetBase = target?.let { model.baseOf(it.name) }
                val matched = model.shapes[cr.shape]?.members?.filterIsInstance<StoredProp>()
                    ?.singleOrNull { p ->
                        (p.type as? RelType)?.let { t -> !t.many && t.shape == targetBase } == true
                    }
                if (matched != null) fresh[matched.name] = anchor
                // the writer's half of the phantom conflict: creating a row
                // referencing X meets any guard read correlated on X
                acc.key(anchor, cr.shape,
                    "creates ${cr.shape} referencing an instance the derivation cannot key", decl, tolerated)
            }
            for (f in cr.fields) {
                val v = walkExpr(f.value, ctx)
                val member = model.membersOf(cr.shape)[f.name]
                if (member?.type?.instanceShape() != null) {
                    val anchor = v?.anchor ?: Anchor.Unknown
                    fresh[f.name] = anchor
                    acc.key(anchor, cr.shape,
                        "creates ${cr.shape} referencing an instance the derivation cannot key", decl, tolerated)
                }
            }
            events.add(Event.Create(cr.shape, freshOf(fresh)))
        }
    }
}

// ── the derived object ───────────────────────────────────────────────────────

/** One member of a serialization domain — what conflicting work queues on. */
sealed interface QueueKey {
    /** The instance the subject's stored to-one path reaches at commit time;
     *  empty = the subject row itself (tick firings only — an act's own row is
     *  fresh and conflict-free within its commit envelope). */
    data class Path(val segs: List<String>) : QueueKey
    /** Conflict correlates on a committed value, not an existing row
     *  (the uniqueness case): queue per equal committed value. */
    data class ValueOf(val shape: String, val field: String) : QueueKey
}

/** A whole-shape widening: every commit touching [shape] shares one queue. */
data class Widening(
    val shape: String,
    /** the read that caused the width, in a human sentence */
    val cause: String,
    /** the declaration carrying the read: "rule X" / "never #1" / "refinement R" */
    val declaration: String,
    /** the declaration signs `tolerates contention`: the width is deliberate */
    val tolerated: Boolean,
)

/**
 * The derived serialization domain of one envelope shape (OQ40): its queue
 * keys, plus any whole-shape widenings. Two envelopes conflict iff their
 * domains intersect — path keys by the row they evaluate to, value keys by
 * equal committed values, widenings with anything touching the shape.
 */
class SerializationDomain(
    val paths: Set<QueueKey.Path>,
    val valueKeys: Set<QueueKey.ValueOf>,
    val widenings: List<Widening>,
) {
    val wide: Boolean get() = widenings.isNotEmpty()

    /** Untolerated widenings — the ⚠ rows. */
    val exposed: List<Widening> get() = widenings.filterNot { it.tolerated }

    /** Keys rendered against a subject noun: `deposit.account`, `this`, `email value`. */
    fun renderKeys(subjectNoun: String): List<String> =
        paths.map { p -> if (p.segs.isEmpty()) subjectNoun else "$subjectNoun.${p.segs.joinToString(".")}" } +
            valueKeys.map { "${it.field} value" }
}
