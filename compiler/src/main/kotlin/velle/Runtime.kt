package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The v0 runtime: evaluation.md executed over a store (Hydration.kt). The
 * harness surface: [commit], [tick], [advance]/[setTime], and the query
 * methods — the engine under the typed accessors codegen will wrap. A system
 * is connected to a store from birth, a [MemoryStore] until [connect] says
 * otherwise; the state is always the store's, and what the runtime holds is
 * one unit of work's working set per thread ([Session]).
 *
 * This class is the entry point and holds what lasts across calls: the model,
 * the clock, the store, and the listeners. Each concern of running the spec is
 * its own collaborator, which reaches the others through this class:
 * - [WorkingSet]: the records faulted in from the store, and the handle tables.
 * - [ActInput]: an act's raw input read as one commit's creation list, or refused.
 * - [ActCommitter]: an act committed through its door, and a record seeded.
 * - [Envelope]: one transaction, its commit set, its rollback, its after-commit queue.
 * - [QueueKeys]: the keys an envelope is serialized on.
 * - [CommitProcessor]: one commit applied, and the rules it fires pinned.
 * - [RuleFiring]: a rule's body run for one subject.
 * - [Watchers]: the conditions watched across a commit.
 * - [Prohibitions]: `never` checked at the door and at the transaction end.
 * - [TickRunner]: a schedule's rules run over their members.
 * - [Visibility]: which records and fields a viewer is returned.
 */
class VelleSystem(
    val model: Model,
    /** The moment the system starts at: the clock's first reading, and the previous tick before a schedule's first. */
    internal val startTime: Instant = Instant.parse("2026-01-01T09:00:00Z"),
    val zone: ZoneId = ZoneId.of("UTC"),
    /** The text of the spec [model] was compiled from, comments included, when the system was built from one; null otherwise. */
    val source: String? = null,
) {
    @Volatile
    var now: Instant = startTime
        private set

    // ── the thread's session ─────────────────────────────────────────────────

    private val sessions = ThreadLocal<Session?>()

    /** This thread's session, created at first use and dropped when the outermost [scope] exits. */
    internal val session: Session get() = sessions.get() ?: Session().also { created -> sessions.set(created) }

    // ── the collaborators ────────────────────────────────────────────────────

    internal val store = StoreConnection(model) { session }
    internal val workingSet = WorkingSet(this)
    internal val watchers = Watchers(this)
    internal val queueKeys = QueueKeys(this)
    internal val envelope = Envelope(this)
    internal val actInput = ActInput(this)
    internal val commitProcessor = CommitProcessor(this)
    internal val ruleFiring = RuleFiring(this)
    internal val prohibitions = Prohibitions(this)
    private val actCommitter = ActCommitter(this)
    private val tickRunner = TickRunner(this)
    private val visibility = Visibility(this)
    val evaluator = Evaluator(this)
    private val queries by lazy { QueryEngine(this) }

    // ── the clock ────────────────────────────────────────────────────────────

    /** A past clock a membership is briefly evaluated against (tick-exit detection). */
    internal var evalTime: Instant?
        get() = session.evalTime
        set(moment) { session.evalTime = moment }

    /** The clock as the outermost [scope] found it, or as this thread set it since ([setTime]), so `today` and `now` hold still for one call. */
    internal val effectiveNow: Instant get() = evalTime ?: session.clock ?: now
    internal val effectiveToday: LocalDate get() = effectiveNow.atZone(zone).toLocalDate()

    fun advance(seconds: Long) = setTime(now.plusSeconds(seconds))
    fun advanceDays(days: Long) = setTime(now.plusSeconds(days * 86_400))

    /** Move the clock: for every call from now on, and for this thread's open scope at once. */
    fun setTime(moment: Instant) {
        now = moment
        sessions.get()?.let { openSession -> if (openSession.depth > 0) openSession.clock = moment }
    }

    // ── the store ────────────────────────────────────────────────────────────

    /**
     * How many records one fetch asks the store for when the runtime fetches for the records that
     * arrived together. The owner of the system sets it, for every shape or shape by shape.
     */
    @Volatile
    var fetchTogether: FetchTogether = FetchTogether()
        set(value) {
            val unknown = value.limitByShape.keys.filter { shape -> shape !in model.shapes }
            require(unknown.isEmpty()) { "no shape ${unknown.joinToString { shape -> "'$shape'" }} to set a fetch limit for" }
            field = value
        }

    /** Run over [resolver], [onCommit], and [tickMemory] instead of the in-memory store, the three parts of one store (Hydration.kt). */
    fun connect(resolver: StateResolver, onCommit: CommitCallback, tickMemory: TickMemory) =
        store.connect(resolver, onCommit, tickMemory)

    // ── entering the runtime ─────────────────────────────────────────────────

    /**
     * One entry into the runtime from outside: a commit with its after-commit
     * drain, a tick, a read. Handles minted inside it, or in the gap before it
     * (the generated input converts its references with [idOf] before calling
     * [commit]), are valid until the outermost scope exits; then the thread's
     * [Session], handle maps and hydrated working set with it, is dropped, so
     * memory is bounded by one unit of work and nothing outside ever holds a
     * handle. [commit], [tick], and [seed] open one themselves; the generated
     * read surface and [TypedQuery] open one around each read. A read made
     * outside any scope is a unit of work of its own ([reading]): its unit ends
     * and its rows go as the call returns, while the handles it minted stay for
     * the scope that follows.
     */
    fun <T> scope(body: () -> T): T {
        val enteredSession = session
        enteredSession.scopesOpen++
        try {
            return entered(dropSession = true, body)
        } finally {
            enteredSession.scopesOpen--
        }
    }

    /**
     * One read on the runtime's own surface. Inside a scope or an envelope it is
     * part of that; outside both it opens its own unit of work and closes it on
     * the way out, dropping the rows it read, so a bare call holds no connection,
     * snapshot, or lock after it returns and two bare calls are two snapshots.
     * The handle tables stay: the generated harness converts a reference with
     * [idOf] and hands the handle to the [commit] that follows.
     */
    private inline fun <T> reading(body: () -> T): T = entered(dropSession = false, body)

    private inline fun <T> entered(dropSession: Boolean, body: () -> T): T {
        val enteredSession = session
        if (enteredSession.depth++ == 0) enteredSession.clock = now
        try {
            return body()
        } finally {
            if (--enteredSession.depth == 0 && enteredSession.transaction == null) {
                store.endUnit(Outcome.Committed)
                if (dropSession) sessions.remove() else enteredSession.beginSnapshot()
            }
        }
    }

    /** Diagnostic: how many handles this thread holds. Zero between units of work. */
    val liveHandles: Int get() = sessions.get()?.keyOf?.size ?: 0

    /** The session-local handle for a store row — how an application enters
     *  Velle from its own storage side (a key it read with its own SQL). */
    fun handleFor(shape: String, key: Any): Long = workingSet.handleForRef(Ref.Persisted(shape, StoreKey(key)))

    /** The session handle behind a reference, minted for this thread's session. */
    fun idOf(ref: Keyed): Long = workingSet.handleForRef(Ref.Persisted(ref.shape, ref.key))

    // ── listeners ────────────────────────────────────────────────────────────

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<TickListener>()

    /** The listeners registered with [listen], in registration order. */
    val tickListeners: List<TickListener> get() = listeners.toList()

    /** Hear every tick, and every act whose after-commit firings were refused or failed ([TickListener]). */
    fun listen(listener: TickListener) { listeners.add(listener) }

    /** Tell every listener; the first exception any of them throws is rethrown once all have been told. */
    internal fun tell(message: (TickListener) -> Unit) {
        var firstFailure: Throwable? = null
        for (listener in listeners) {
            try {
                message(listener)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { failure -> throw failure }
    }

    // ── committing ───────────────────────────────────────────────────────────

    /**
     * [actor] is the committer (Reference §22, "The actor"): the handle of a record of
     * the shape the act's `by` clause names, required exactly when the act declares
     * `by` and refused otherwise. It is never a supplied field — a fact about the
     * commit the committer may not claim (Reference §5) — and it lands on the act's
     * instance under the bound name, stored on a durable act like a timestamp. A
     * record outside the base shape is a type-level refusal here; one outside the
     * refinement the clause names is the desugared requirement's refusal (Model).
     */
    fun commit(
        shape: String,
        suppliedFields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>> = emptyMap(),
        actor: Long? = null,
    ): CommitResult = scope { actCommitter.commit(shape, suppliedFields, parts, actor) }

    /**
     * A dry run of [commit]: the act is read, its rules run, and its prohibitions are
     * checked exactly as a commit would, under the same queue keys, and then everything
     * is undone and nothing reaches the store. The answer is what a commit would answer
     * now; another commit can land before the real one, so it is no promise. Firings
     * behind an `after commit` boundary run after a commit is over and are not covered.
     */
    fun check(
        shape: String,
        suppliedFields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>> = emptyMap(),
        actor: Long? = null,
    ): CheckResult = scope { actCommitter.check(shape, suppliedFields, parts, actor) }

    /**
     * Seed a record outside the trust boundary (Reference §22, "The actor";
     * OQ33 §6.2): the in-memory equivalent of a row the engineer's own code or a
     * migration wrote to the store. No act is committed — no door, no rule fires,
     * no prohibition is checked — the record simply exists, as a hydrated row
     * does; with a store connected, it is written through the commit callback.
     * The first administrator of a system whose hiring act is gated on an
     * administrator arrives this way. Any shape, exposed or not; never a
     * transient act, which is not a record.
     */
    fun seed(shape: String, fields: Map<String, Any?>): Ref.Persisted = scope { refFor(actCommitter.seed(shape, fields)) }

    /**
     * Test-only ordering strategy for the choices evaluation.md leaves open:
     * step 6's "in any order" [S3] and the after-commit queue's append order
     * [S2] (FIFO of an order that was itself a step-6 choice). Production
     * leaves it null — declaration order, one arbitrary valid choice. The
     * sibling-confluence audit re-runs scenarios under different strategies
     * and diffs outcomes: a difference on a validated spec is a soundness bug
     * (evaluation.md: "Ordering within step 6 is never observable in a valid
     * spec").
     */
    internal var firingOrder: ((List<Pair<RuleDecl, Long>>) -> List<Pair<RuleDecl, Long>>)? = null

    // ── ticks ────────────────────────────────────────────────────────────────

    /**
     * Runs every rule on [schedule] over its current members, each firing its
     * own transaction. A firing a `never` rejects has no caller to hand the
     * refusal to, so when the sweep is done the tick throws [TickRejected]
     * carrying each firing's [Rejection] — the same object an act's commit
     * function returns in [CommitResult.Refused] (`evaluation.md`, "Ticks"). The other firings
     * stand; the next tick retries the rejected ones.
     */
    fun tick(schedule: String): TickReport = scope { tickRunner.tick(schedule) }

    /**
     * The moment [schedule] last ran, as the store holds it ([TickMemory.latestTick]), or null
     * before its first tick. It survives a restart and is one moment for every process over
     * one store. A tick is not a record: this is the only way to read when one happened.
     */
    fun latestTick(schedule: String): Instant? = reading {
        store.reader()
        store.tickMemory.latestTick(schedule)
    }

    // ── visibility (Visibility.kt) ───────────────────────────────────────────

    /** Is the record [id] returned to [viewer]? The engineer's own view (null) sees everything. */
    fun visibleTo(viewer: Long?, id: Long): Boolean = reading { visibility.visibleTo(viewer, id) }

    /** The members of [id], read as [scope], that [viewer] may not see: a field with
     *  clauses none of which admit the viewer. Empty for the engineer's own view. */
    fun hiddenFields(viewer: Long?, id: Long, scope: String? = null): Set<String> = reading { visibility.hiddenFields(viewer, id, scope) }

    /** The record behind [id] is current and returned to [viewer]: the fetch half of a viewer's read. */
    fun visibleCurrent(viewer: Long?, id: Long): Boolean = reading {
        workingSet.openReadSnapshot()
        id !in workingSet.pendingDeleted && workingSet.instance(id) != null && visibility.visibleTo(viewer, id)
    }

    // ── the read protocol (Reference §22, the composed query; Read.kt) ──────────

    /**
     * The reference an engineer holds for [id] (Reference §22): the row's store key.
     * A row the open envelope is still creating has no key yet; its handle stands
     * in, which only a rejection's own rendering ever reads.
     */
    fun refFor(id: Long): Ref.Persisted = reading { workingSet.refFor(id) }

    /** The concrete shape of a current instance, from a fresh snapshot outside a transaction; null when gone. */
    fun currentShapeOf(id: Long): String? = reading {
        workingSet.openReadSnapshot()
        if (id in workingSet.pendingDeleted) null else workingSet.instance(id)?.shape
    }

    /**
     * Fetch by identity (Reference §22): true when [id] is a current instance of
     * [root] — a shape, or a refinement it is a member of — read from a fresh
     * snapshot outside a transaction, so the data class built next is one
     * consistent read.
     */
    fun exists(root: String, id: Long): Boolean = reading {
        workingSet.openReadSnapshot()
        isCurrentInstanceOf(root, id)
    }

    /**
     * A viewer's fetch by identity as one read entry: true when [id] is a current instance of
     * [root] and is returned to [viewer]. One entry, so the row is fetched once; asking
     * outside a scope, asking [exists] and [visibleCurrent] in turn is two snapshots and fetches it twice.
     */
    fun existsFor(viewer: Long?, root: String, id: Long): Boolean = reading {
        workingSet.openReadSnapshot()
        isCurrentInstanceOf(root, id) && visibility.visibleTo(viewer, id)
    }

    private fun isCurrentInstanceOf(root: String, id: Long): Boolean {
        val current = workingSet.instance(id) ?: return false
        if (id in workingSet.pendingDeleted) return false
        return when {
            root in model.shapes -> current.shape == root
            root in model.refinements -> evaluator.memberOfRefExpr(id, RefName(root))
            else -> throw IllegalArgumentException("unknown shape or refinement '$root'")
        }
    }

    /** The query's diagnostics without running it; empty means it is well-formed. */
    fun check(query: Query): List<Diagnostic> = queries.check(query)

    /** Answers a query: handles in the requested order, plus the next page's cursor. Throws [QueryRefused]. */
    fun query(query: Query): QueryResult = reading { queries.run(query) }

    fun count(query: Query): Int = reading { queries.count(query) }

    /** The one record a filter over a key admits, or null (Reference §21, "Keys"). */
    fun single(query: Query): Long? = reading { queries.single(query) }

    fun sum(query: Query, member: String): BigDecimal = reading { queries.sum(query, member) }
    fun sumValue(query: Query, member: String): Value = reading { queries.sumValue(query, member) }

    // ── query surface (read-only; the typed-accessor codegen wraps this) ─────

    fun instancesOf(name: String): List<Long> = reading {
        when {
            name in model.shapes -> workingSet.idsOf(name)
            name in model.refinements -> membersOfRefinement(name)
            else -> throw IllegalArgumentException("unknown shape or refinement '$name'")
        }
    }

    private fun membersOfRefinement(name: String): List<Long> {
        val bases = model.basesOf(name)
        val candidates =
            if (bases.size == 1) workingSet.idsOfCandidates(bases.single(), queryCompiler().filterFor(RefName(name)))
            else bases.flatMap { base -> workingSet.idsOf(base) } // a cross-base union: every operand's instances (Reference §9)
        return candidates.filter { id -> evaluator.memberOfRefExpr(id, RefName(name)) }
    }

    fun isMember(id: Long, refinement: String): Boolean = reading {
        evaluator.memberOfRefExpr(id, RefName(refinement))
    }

    /** The concrete base shape of an instance — which operand a union-typed reference points at (Reference §9). */
    fun shapeOf(id: Long): String = reading {
        workingSet.instance(id)?.shape ?: throw IllegalArgumentException("no instance $id")
    }

    /** Read a field — stored, derived, timestamp, capture, or inverse collection — unwrapped. */
    fun get(id: Long, field: String): Any? = reading {
        val instance = workingSet.instance(id) ?: throw IllegalArgumentException("no instance $id")
        unwrapped(evaluator.readMember(id, instance.shape, field))
    }

    /** Read a refinement-scoped member (a capture or refinement-derived property). */
    fun getAs(id: Long, refinement: String, field: String): Any? = reading {
        unwrapped(evaluator.readMember(id, refinement, field))
    }

    // ── the working set, as the evaluator and the query engine read it (WorkingSet.kt) ──

    internal val instances: MutableMap<Long, Instance> get() = workingSet.instances
    internal val byShape: MutableMap<String, MutableList<Long>> get() = workingSet.byShape
    internal val pendingDeleted: MutableSet<Long> get() = workingSet.pendingDeleted
    internal val captures: MutableMap<Pair<Long, String>, Map<String, Value>> get() = workingSet.captures
    internal fun instance(id: Long): Instance? = workingSet.instance(id)
    internal fun liveIds(shape: String): List<Long> = workingSet.liveIds(shape)
    internal fun idsOf(shape: String): List<Long> = workingSet.idsOf(shape)
    internal fun idsOfCandidates(shape: String, filter: QF): List<Long> = workingSet.idsOfCandidates(shape, filter)
    internal fun ensureReferencing(shape: String, field: String, targetId: Long) = workingSet.ensureReferencing(shape, field, targetId)
    internal fun normalizeRead(value: Value): Value = workingSet.normalizeRead(value)
    internal fun captureValues(id: Long, refinement: String): Map<String, Value>? = workingSet.captureValues(id, refinement)
    internal fun openReadSnapshot() = workingSet.openReadSnapshot()
    internal fun orderKeyOf(id: Long): Comparable<Any> = workingSet.orderKeyOf(id)

    /** Filters fold `today`/`now` to constants, so a compiler is built per evaluation moment. */
    internal fun queryCompiler() = QueryCompiler(model, effectiveToday, effectiveNow) { id -> workingSet.persistedRefOf(id) }

    companion object {
        /** The key a raw nested value carries to name its concrete shape when the field's type is a union (OQ63 §6.1). */
        const val SHAPE_KEY = "@shape"
        /** The key a raw nested value carries with its [NewRegistry] token, so a sibling's [NewRef] can name it (OQ63 §6.2.2). */
        const val NEW_KEY = "@new"
    }
}
