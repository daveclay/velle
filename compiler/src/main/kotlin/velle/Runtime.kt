package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Runtime values. All numerics normalize to BigDecimal — a spike simplification. */
sealed interface Value {
    data class VNum(val v: BigDecimal) : Value
    data class VText(val v: String) : Value
    data class VBool(val v: Boolean) : Value
    data class VDate(val v: LocalDate) : Value
    data class VDateTime(val v: Instant) : Value
    data class VRef(val id: Long) : Value
    data class VColl(val ids: List<Long>, val shape: String) : Value
    /** An owned collection of scalar values (`many text`, Reference §5, "Collections of
     *  values"): each value once, or, when [ordered] (`tolerates duplication`), kept as
     *  given, duplicates and order included. The kind comes from the field's declared type. */
    data class VVals(val values: List<Value>, val ordered: Boolean = false) : Value
    /** The `empty` literal before its collection kind is known; coerced at the write site. */
    data object VEmpty : Value
    /** A closure part's back-reference before its target exists: index into the
     *  commit's creation list, resolved to a VRef as creations land (Reference §6,
     *  "Inline part creation"). Never escapes applyCommit. */
    data class VPendingRef(val index: Int) : Value
    /** A `many` field some of whose elements are records this commit creates
     *  ([VPendingRef] among [VRef]s); resolved to a [VColl] as creations land. */
    data class VPendingColl(val items: List<Value>, val shape: String) : Value
    /** A span of time (Reference §5, `duration`): the literal's amount and unit, or a
     *  stored value read back in the largest unit that divides it evenly. */
    data class VDuration(val amount: Long, val unit: String) : Value {
        fun seconds(): Long = amount * unitSeconds(unit)
        fun toJava(): java.time.Duration = java.time.Duration.ofSeconds(seconds())
        companion object {
            fun ofJava(d: java.time.Duration): VDuration = ofSeconds(d.seconds)
            fun ofSeconds(s: Long): VDuration {
                for ((unit, n) in UNITS) if (s != 0L && s % n == 0L) return VDuration(s / n, unit)
                return VDuration(s, "seconds")
            }
            fun unitSeconds(unit: String): Long = UNITS.firstOrNull { it.first == unit }?.second
                ?: throw VelleRuntimeError("unknown duration unit '$unit'")
            private val UNITS = listOf("weeks" to 604_800L, "days" to 86_400L, "hours" to 3_600L, "minutes" to 60L, "seconds" to 1L)
        }
    }
    /** A `uuid` value (Reference §5). */
    data class VUuid(val v: UUID) : Value
    /** A `time` value (Reference §5): a time of day. */
    data class VTime(val v: java.time.LocalTime) : Value
    /** A `bytes` value (Reference §5): equal by content, ordered byte by byte as SQLite orders a BLOB. */
    class VBytes(val v: ByteArray) : Value {
        override fun equals(other: Any?) = other is VBytes && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
        override fun toString() = "VBytes(${v.size} bytes)"
        fun compareTo(o: VBytes): Int {
            for (i in 0 until minOf(v.size, o.v.size)) {
                val c = (v[i].toInt() and 0xff).compareTo(o.v[i].toInt() and 0xff)
                if (c != 0) return c
            }
            return v.size.compareTo(o.v.size)
        }
    }
    /** A value of a registered extension type (Reference §5; OQ65): the type's own Kotlin object. */
    data class VExt(val type: velle.ext.VelleType<*>, val v: Any) : Value {
        override fun equals(other: Any?): Boolean = other is VExt && other.type === type && type.equalAny(v, other.v)
        override fun hashCode(): Int = type.canonicalAny(v).hashCode()
        override fun toString(): String = "VExt(${type.name} ${type.canonicalAny(v)})"
    }
    data object VNone : Value

    companion object {
        fun num(v: Any): Value = VNum(
            when (v) {
                is BigDecimal -> v
                is Int -> BigDecimal(v)
                is Long -> BigDecimal(v)
                is Double -> BigDecimal.valueOf(v)
                else -> throw IllegalArgumentException("not numeric: $v")
            }
        )
    }
}

class Instance(
    val id: Long,
    val shape: String,
    val seq: Long,
    val fields: MutableMap<String, Value>,
)

sealed interface CommitResult {
    /** [ref] names the record the act became, null for a transient act, which
     *  leaves none. [id] is the runtime's handle for it: in memory, with no
     *  store, that is the identity the raw surface works in; over a store it
     *  is dead once the commit returns (see [VelleSystem.scope]), so hold [ref]. */
    data class Accepted(val id: Long, val ref: Ref.Persisted?) : CommitResult
    /** [reason] is the rendered message; [rejection] is the same as data when a
     *  `never` refused the act (Reference §21), null for a type-level refusal. */
    data class Refused(val reason: String, val rejection: Rejection? = null) : CommitResult
}

/**
 * A typed commit's outcome (Reference §22, "External input"): the generated
 * surface commits one typed input value per act and hands back the same
 * value with the verdict, so a refusal holds what was refused as the
 * engineer's own type — never a map — and an acceptance holds what was
 * accepted beside the reference to the record it made. [R] is that
 * reference's type: `Ref<Shape>` for a durable act, `Nothing?` for a
 * transient act (Reference §4), which leaves no record to refer to. The
 * runtime's session handle never appears here: the engineer holds a
 * reference (Hydration.kt, Ref), which is the store's identity, and reads
 * it back with `read(ref)`. The runtime stays generic in [I]: it never
 * depends on generated code.
 */
sealed interface Commit<out I, out R> {
    /** The input value the caller committed, exactly as it was handed in. */
    val input: I
    /** [ref] names the record the act became: a `Ref<Shape>` for a durable act, null for a transient one. */
    data class Accepted<out I, out R>(override val input: I, val ref: R) : Commit<I, R>
    /** [reason] is the rendered message; [rejection] is the same as data when a
     *  `never` refused the act (Reference §21), null for a type-level refusal. */
    data class Refused<out I, out R>(override val input: I, val reason: String, val rejection: Rejection? = null) : Commit<I, R>
}

/** Attach the typed input to the runtime's untyped verdict, with the reference to the durable record it made. */
fun <I, T> CommitResult.carrying(input: I): Commit<I, Ref<T>> = when (this) {
    is CommitResult.Accepted -> Commit.Accepted(input, ref ?: throw VelleRuntimeError("a durable act's acceptance carries no reference"))
    is CommitResult.Refused -> Commit.Refused(input, reason, rejection)
}

/** Attach the typed input to the runtime's untyped verdict for a transient act, which leaves no record to refer to. */
fun <I> CommitResult.carryingNoRecord(input: I): Commit<I, Nothing?> = when (this) {
    is CommitResult.Accepted -> Commit.Accepted(input, null)
    is CommitResult.Refused -> Commit.Refused(input, reason, rejection)
}

class VelleRuntimeError(message: String) : Exception(message)

/**
 * In a raw commit, a `one` field's reference to a new record arriving
 * elsewhere in the same act (Reference §6; OQ63 §6.2.2): [token] is the
 * number that record's raw value carries under [VelleSystem.NEW_KEY]. The
 * boundary resolves it to the record's id before anything is written.
 */
data class NewRef(val token: Int)

/**
 * The boundary's registry of the new values one act carries (OQ63 §6.2.3.3):
 * every value in a creating position (a part, or a `one` field's `New`) gets
 * a token by object identity, and a field pointing at a sibling names that
 * token. A value in two creating positions, or a reference to a value in
 * none, is a type-level refusal of the whole act ([problem]).
 */
class NewRegistry {
    private val tokens = java.util.IdentityHashMap<Any, Int>()
    private val duplicates = mutableListOf<Any>()
    private val unknown = mutableListOf<Any>()

    /** Register a value in a creating position; returns its token. */
    fun add(value: Any): Int {
        tokens[value]?.let { duplicates.add(value); return it }
        val t = tokens.size + 1
        tokens[value] = t
        return t
    }

    /** The token of a value some field points at; records it as unknown when it is in no creating position. */
    fun tokenOf(value: Any): Int = tokens[value] ?: run { unknown.add(value); -1 }

    fun tokenOrNull(value: Any): Int? = tokens[value]

    /** Null when every reference resolves and no value is created twice. */
    fun problem(): String? = when {
        duplicates.isNotEmpty() -> "type: ${duplicates.first().javaClass.simpleName} appears in two creating positions of this act — one new value, one record"
        unknown.isNotEmpty() -> "type: a field points at a ${unknown.first().javaClass.simpleName} that is not among this act's new values — " +
            "put it in a part list or at a `one` field named in `with`, or pass a handle to an existing record"
        else -> null
    }
}
private class NeverViolation(val rejection: Rejection) : Exception(rejection.message)

/**
 * The v0 runtime: evaluation.md executed over a store (Hydration.kt). The
 * harness surface: [commit], [tick], [advance]/[setTime], and the query
 * methods — the engine under the typed accessors codegen will wrap. A system
 * is connected to a store from birth, a [MemoryStore] until [connect] says
 * otherwise; the state is always the store's, and what the runtime holds is
 * one unit of work's working set per thread ([Session]).
 */
class VelleSystem(
    val model: Model,
    startTime: Instant = Instant.parse("2026-01-01T09:00:00Z"),
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    @Volatile
    var now: Instant = startTime
        private set

    /** membershipAt() briefly evaluates against a past clock (tick-exit detection) */
    internal var evalTime: Instant?
        get() = s.evalTime
        set(v) { s.evalTime = v }
    /** The clock as the outermost [scope] found it, or as this thread set it since ([setTime]), so `today` and `now` hold still for one call. */
    internal val effectiveNow: Instant get() = evalTime ?: s.clock ?: now
    internal val effectiveToday: LocalDate get() = effectiveNow.atZone(zone).toLocalDate()

    /**
     * What one thread holds between entering the runtime and leaving it: the
     * working set of the unit of work in progress (the rows faulted in, the
     * captures read, the fetches already made), the transaction, and the
     * handle tables that translate the store's keys to this thread's session
     * handles. One per thread, created at first use and dropped when the
     * outermost [scope] exits, so two callers on two threads share nothing but
     * the store, and memory is bounded by one call's work.
     */
    private class Session {
        val instances = LinkedHashMap<Long, Instance>()
        val byShape = HashMap<String, MutableList<Long>>()
        /** Instances deleted by the current transaction (OQ37): no longer members of
         *  the state — every existence/collection read excludes them from the delete
         *  commit on — but still directly readable by id (the last-reader contract).
         *  Physically removed at transaction close; cleared on rollback. */
        val pendingDeleted = mutableSetOf<Long>()
        /** (instance id, refinement name) → captured values for the current membership */
        val captures = HashMap<Pair<Long, String>, Map<String, Value>>()
        val captureMisses = mutableSetOf<Pair<Long, String>>()
        val fetchedAll = mutableSetOf<String>()
        val fetchedRefs = mutableSetOf<Triple<String, String, Long>>()
        val fetchedFilters = mutableSetOf<Pair<String, QF>>()
        val fetchMisses = mutableSetOf<Long>()
        val idShape = HashMap<Long, String>()
        val keyOf = HashMap<Long, StoreKey>()
        val handleOf = HashMap<Ref.Persisted, Long>()
        var unit: UnitOfWork? = null
        var txn: Txn? = null
        var depth = 0
        var clock: Instant? = null
        var evalTime: Instant? = null
    }

    private val sessions = ThreadLocal<Session?>()
    private val s: Session get() = sessions.get() ?: Session().also { sessions.set(it) }

    internal val instances: MutableMap<Long, Instance> get() = s.instances
    internal val byShape: MutableMap<String, MutableList<Long>> get() = s.byShape
    internal val pendingDeleted: MutableSet<Long> get() = s.pendingDeleted

    /** Membership-facing instance list: [byShape] minus the current transaction's
     *  deletions. Every scan/exists/aggregate read goes through this. */
    internal fun liveIds(shape: String): List<Long> {
        val ids = byShape[shape].orEmpty()
        return if (pendingDeleted.isEmpty()) ids.toList() else ids.filter { it !in pendingDeleted }
    }

    /** The absorbing-reference read (OQ37-R10): a stored reference at a deleted
     *  instance reads as absent — `is none` true, `?.` propagates — with no
     *  write anywhere; a collection silently loses the member. Applied to
     *  values read out of fields, never to a subject read by id. */
    internal fun normalizeRead(v: Value): Value = when (v) {
        is Value.VRef -> if (v.id in pendingDeleted || instance(v.id) == null) Value.VNone else v
        is Value.VColl -> {
            val live = v.ids.filter { it !in pendingDeleted && instance(it) != null }
            if (live.size == v.ids.size) v else Value.VColl(live, v.shape)
        }
        else -> v
    }
    internal val captures: MutableMap<Pair<Long, String>, Map<String, Value>> get() = s.captures

    /** Never reset: a handle that leaks past its scope fails as "no instance" instead of naming a stranger. */
    private val nextId = AtomicLong(1)

    // ── hydration (Hydration.kt; evaluation.md "The universal transaction") ──
    // [instances]/[byShape] are the unit of work's working set over the store:
    // each transaction opens a fresh snapshot, faults state in on demand
    // (memoized for the envelope), and hands its mutation set to [onCommit]
    // inside the envelope.

    private val memory = MemoryStore(model)
    private var resolver: StateResolver = memory
    private var onCommit: CommitCallback = memory

    /**
     * The unit of work open against the store (Hydration.kt, [UnitOfWork]): a read
     * unit, opened at the first fetch of a read and closed when the outermost
     * [scope] exits or an envelope opens; or the write unit of the envelope in
     * progress, opened in [inTransaction] before the queue keys are valued and
     * closed with the envelope's outcome. Null between units.
     */
    private var unit: UnitOfWork?
        get() = s.unit
        set(v) { s.unit = v }

    /** The resolver, with a read unit open when no unit is: every fetch goes through here. */
    private fun reader(): StateResolver {
        if (unit == null) unit = resolver.begin(WorkKind.READ)
        return resolver
    }

    private fun endUnit(outcome: Outcome) {
        val u = unit ?: return
        unit = null
        u.end(outcome)
    }

    /** The serialization domains (OQ40, `evaluation.md`): the compiler's derivation, valued here at admission. */
    private val domains: DomainAnalysis by lazy { DomainAnalysis(model) }
    /** id → shape, so a bare VRef id can be fetched from per-shape storage.
     *  An index, not state: populated at creation, hydration, and reference
     *  conversion; survives snapshot clears. */
    private val idShape: MutableMap<Long, String> get() = s.idShape

    // Identity is the store's (Hydration.kt, Ref/StoreKey): rows are keyed by
    // whatever the store assigned, and the runtime's instance ids are
    // session-local handles that never persist. This bimap is the translation
    // at the boundary; like idShape it is an index, surviving snapshot clears.
    // Keys are only unique per shape (a table's rowids restart at 1), so the
    // handle side keys on the full typed ref, never the bare key.
    private val keyOf: MutableMap<Long, StoreKey> get() = s.keyOf
    private val handleOf: MutableMap<Ref.Persisted, Long> get() = s.handleOf

    private val fetchedAll: MutableSet<String> get() = s.fetchedAll
    private val fetchedRefs: MutableSet<Triple<String, String, Long>> get() = s.fetchedRefs
    private val fetchedFilters: MutableSet<Pair<String, QF>> get() = s.fetchedFilters
    /** Handles whose by-key fetch found no row this envelope — a reference at a
     *  deleted row (Reference §12) is read as absent every time, and one snapshot
     *  answers it once. */
    private val fetchMisses: MutableSet<Long> get() = s.fetchMisses

    /** Run over [resolver] and [onCommit] instead of the in-memory store, both halves of one store (Hydration.kt). */
    fun connect(resolver: StateResolver, onCommit: CommitCallback) {
        this.resolver = resolver
        this.onCommit = onCommit
    }

    /**
     * One entry into the runtime from outside: a commit with its after-commit
     * drain, a tick, a read. Handles minted inside it, or in the gap before it
     * (the generated input converts its references with [idOf] before calling
     * [commit]), are valid until the outermost scope exits; then the thread's
     * [Session], handle maps and hydrated working set with it, is dropped, so
     * memory is bounded by one unit of work and nothing outside ever holds a
     * handle. [commit], [tick], and [seed] open one themselves; the generated
     * read surface and [TypedQuery] open one around each read. A read made
     * outside any scope keeps its unit open until the next outermost scope on
     * its thread exits.
     */
    fun <T> scope(body: () -> T): T {
        val session = s
        if (session.depth++ == 0) session.clock = now
        try {
            return body()
        } finally {
            if (--session.depth == 0 && session.txn == null) {
                endUnit(Outcome.Committed)
                sessions.remove()
            }
        }
    }

    /** Diagnostic: how many handles this thread holds. Zero between units of work. */
    val liveHandles: Int get() = sessions.get()?.keyOf?.size ?: 0

    /** The session-local handle for a store row — how an application enters
     *  Velle from its own storage side (a key it read with its own SQL). */
    fun handleFor(shape: String, key: Any): Long = handleForRef(Ref.Persisted(shape, StoreKey(key)))

    private fun handleForRef(ref: Ref.Persisted): Long {
        handleOf[ref]?.let { return it }
        val h = nextId.getAndIncrement()
        handleOf[ref] = h
        keyOf[h] = ref.key
        idShape[h] = ref.shape
        return h
    }

    /** Each transaction evaluates against one consistent snapshot: drop the
     *  previous envelope's hydrated state so storage is re-consulted. Captures
     *  included: they are a cache over the store's capture channel
     *  (Hydration.kt, CommitSet), hydrated on demand like rows. */
    private fun beginSnapshot() {
        instances.clear()
        byShape.clear()
        fetchedAll.clear()
        fetchedRefs.clear()
        fetchedFilters.clear()
        fetchMisses.clear()
        captures.clear()
        captureMisses.clear()
    }

    private val captureMisses: MutableSet<Pair<Long, String>> get() = s.captureMisses

    /**
     * Capture read, faulting in from the resolver's capture channel when the
     * envelope hasn't seen this membership yet. Null means no current
     * membership anywhere — the caller's "read outside membership" error path.
     */
    internal fun captureValues(id: Long, refinement: String): Map<String, Value>? {
        captures[id to refinement]?.let { return it }
        if ((id to refinement) in captureMisses) return null
        val key = keyOf[id] ?: return null // unpersisted: no membership storage could hold
        val shape = idShape[id] ?: model.baseOf(refinement) ?: return null
        val raw = reader().fetchCaptures(Ref.Persisted(shape, key), refinement)
        if (raw == null) {
            captureMisses.add(id to refinement)
            return null
        }
        val props = model.refinements.getValue(refinement).members
            .filterIsInstance<DerivedProp>().filter { it.captured }
        val values = props.associate { p ->
            val v = raw[p.name]
            p.name to if (v == null) Value.VNone
            else rawToValue(v, model.typeOf(p.type), "$refinement.${p.name} (capture)")
        }
        captures[id to refinement] = values
        return values
    }

    /** By-handle read, faulting in from the resolver via the store's key. */
    internal fun instance(id: Long): Instance? {
        instances[id]?.let { return it }
        val shape = idShape[id] ?: return null
        val key = keyOf[id] ?: return null // no key: never persisted (rolled back, or another session's handle)
        if (id in fetchMisses) return null
        val row = reader().fetchByKey(shape, key) ?: return null.also { fetchMisses.add(id) }
        return hydrateIfAbsent(row)
    }

    /**
     * Scan read: every current instance of [shape], faulting in the full set
     * once per envelope. The order is the store's: [byShape] is kept as the
     * rows storage reported, in the order it reported them, ahead of anything
     * this envelope created that storage does not hold yet — so the query
     * surface reads the same order over a store as in memory (creation order,
     * for a store whose scan order is insertion order).
     */
    internal fun idsOf(shape: String): List<Long> {
        if (shape !in model.transients && fetchedAll.add(shape)) {
            val fetched = reader().fetchAll(shape).map { hydrateIfAbsent(it).id }
            val list = byShape.getOrPut(shape) { mutableListOf() }
            val fetchedSet = fetched.toSet()
            val (persisted, unpersisted) = list.filter { it !in fetchedSet }.partition { keyOf[it] != null }
            list.clear()
            list.addAll(fetched)
            list.addAll(persisted)
            list.addAll(unpersisted)
        }
        return liveIds(shape)
    }

    /**
     * Filtered scan read: candidates of a condition over [shape], faulting in the
     * resolver's candidate superset for [filter] instead of the whole table. The
     * return is everything of [shape] in the working set — candidates unioned
     * with rows this envelope already touched — and the caller re-checks the
     * authoritative predicate in memory on all of it (the filter is a
     * pre-filter under the superset contract, never the evaluation).
     */
    internal fun idsOfCandidates(shape: String, filter: QF): List<Long> {
        if (shape in model.transients || shape in fetchedAll || filter == QF.True)
            return idsOf(shape)
        if (fetchedFilters.add(shape to filter))
            reader().fetchCandidates(shape, filter).forEach { hydrateIfAbsent(it) }
        return liveIds(shape)
    }

    /** Filters fold `today`/`now` to constants, so a compiler is built per evaluation moment. */
    internal fun queryCompiler() = QueryCompiler(model, effectiveToday, effectiveNow) { id ->
        keyOf[id]?.let { key -> idShape[id]?.let { Ref.Persisted(it, key) } }
    }

    /** Join read: fault in the instances of [shape] whose [field] references [targetId]. */
    internal fun ensureReferencing(shape: String, field: String, targetId: Long) {
        if (shape in model.transients || shape in fetchedAll) return
        // an unpersisted target (created this envelope) can't be referenced by
        // anything storage holds — nothing to fetch
        val key = keyOf[targetId] ?: return
        val targetShape = idShape[targetId] ?: return
        if (fetchedRefs.add(Triple(shape, field, targetId)))
            reader().fetchReferencing(shape, field, Ref.Persisted(targetShape, key)).forEach { hydrateIfAbsent(it) }
    }

    private fun hydrateIfAbsent(row: Row): Instance {
        val handle = handleForRef(Ref.Persisted(row.shape, row.key))
        instances[handle]?.let { return it }
        val decl = model.shapes[row.shape]
            ?: throw VelleRuntimeError("resolver returned unknown shape '${row.shape}'")
        val fields = mutableMapOf<String, Value>()
        for (m in decl.members) {
            val (name, vtype) = when (m) {
                is StoredProp -> m.name to model.typeOf(m.type)
                is TimestampProp -> m.name to VType.DateTimeT
                is ActorProp -> m.name to VType.Inst(model.baseOf(m.shape) ?: m.shape)
                else -> continue
            }
            val raw = row.fields[name] ?: continue
            fields[name] = rawToValue(raw, vtype, "${row.shape}.$name")
        }
        val inst = Instance(handle, row.shape, handle, fields)
        instances[handle] = inst
        // a hydrated row goes behind the rows already hydrated and ahead of this
        // envelope's unpersisted creations (see idsOf on order)
        val list = byShape.getOrPut(row.shape) { mutableListOf() }
        val firstUnpersisted = list.indexOfFirst { keyOf[it] == null }
        if (firstUnpersisted < 0) list.add(handle) else list.add(firstUnpersisted, handle)
        return inst
    }

    private fun rawToValue(raw: Any, t: VType, at: String): Value = when (t) {
        is VType.Optional -> rawToValue(raw, t.inner, at)
        is VType.Inst -> {
            val ref = raw as? Ref.Persisted
                ?: throw VelleRuntimeError("hydrating $at: reference is not a Ref.Persisted ($raw)")
            Value.VRef(handleForRef(ref))
        }
        is VType.Num -> Value.num(raw)
        VType.Text -> Value.VText(raw as? String ?: throw VelleRuntimeError("hydrating $at: not text ($raw)"))
        VType.Bool -> Value.VBool(
            when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> throw VelleRuntimeError("hydrating $at: not a boolean ($raw)")
            }
        )
        VType.DateT -> Value.VDate(raw as? LocalDate ?: throw VelleRuntimeError("hydrating $at: not a Date ($raw)"))
        VType.DateTimeT -> Value.VDateTime(raw as? Instant ?: throw VelleRuntimeError("hydrating $at: not a DateTime ($raw)"))
        VType.UuidT -> Value.VUuid(raw as? UUID ?: throw VelleRuntimeError("hydrating $at: not a uuid ($raw)"))
        VType.DurationT -> Value.VDuration.ofJava(raw as? java.time.Duration ?: throw VelleRuntimeError("hydrating $at: not a duration ($raw)"))
        VType.TimeT -> Value.VTime(raw as? java.time.LocalTime ?: throw VelleRuntimeError("hydrating $at: not a time ($raw)"))
        VType.BytesT -> Value.VBytes(raw as? ByteArray ?: throw VelleRuntimeError("hydrating $at: not bytes ($raw)"))
        // the Row contract: a field of an extension type holds the type's Kotlin object
        is VType.Ext -> {
            val type = model.registry[t.qualifiedName] ?: throw VelleRuntimeError("hydrating $at: no registered type '${t.qualifiedName}'")
            if (!type.kotlinClass.isInstance(raw))
                throw VelleRuntimeError("hydrating $at: not a ${type.kotlinClass.qualifiedName} ($raw)")
            Value.VExt(type, raw)
        }
        is VType.Coll -> Value.VColl(
            (raw as? List<*> ?: throw VelleRuntimeError("hydrating $at: not a collection ($raw)"))
                .map { el ->
                    val ref = el as? Ref.Persisted
                        ?: throw VelleRuntimeError("hydrating $at: collection element is not a Ref.Persisted ($el)")
                    handleForRef(ref)
                },
            t.shape,
        )
        is VType.CollS -> Value.VVals(
            (raw as? List<*> ?: throw VelleRuntimeError("hydrating $at: not a collection ($raw)"))
                .map { el -> rawToValue(el ?: throw VelleRuntimeError("hydrating $at: null element"), scalarVType(t.name), at) },
            ordered = t.ordered,
        )
        else -> throw VelleRuntimeError("hydrating $at: unsupported type for $raw")
    }

    private fun scalarVType(name: String): VType = model.scalarOf(name)
    private val lastTick = ConcurrentHashMap<String, Instant>()
    private val startInstant = startTime
    val evaluator = Evaluator(this)
    /** failures of after-commit / tick firings — the act stands, the gap is recorded [S4] */
    val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** A `never` rejection of an after-commit firing, carried as the same data a
     *  tick's rejection is thrown with (`evaluation.md`, "Ticks"): the act stood,
     *  the firing rolled back, and nobody was asking. Reachable only through the
     *  runtime backstop, since V29 and V30 refuse the spec-visible cases. */
    val afterCommitRejections: MutableList<Rejection> = Collections.synchronizedList(mutableListOf())

    private val maxDepth = 500 // [S3] backstop behind the static quiescence proof

    fun advance(seconds: Long) = setTime(now.plusSeconds(seconds))
    fun advanceDays(days: Long) = setTime(now.plusSeconds(days * 86_400))
    /** Move the clock: for every call from now on, and for this thread's open scope at once. */
    fun setTime(t: Instant) {
        now = t
        sessions.get()?.let { if (it.depth > 0) it.clock = t }
    }

    // ── transactions ─────────────────────────────────────────────────────────

    private class Txn {
        val created = mutableListOf<Long>()
        val oldValues = mutableListOf<Triple<Long, String, Value?>>()
        val afterQueue = mutableListOf<Pair<RuleDecl, Long>>()
        /** Instances this transaction deletes (OQ37), in deletion order —
         *  physically removed at close, unmarked on rollback. */
        val deleted = mutableListOf<Long>()
        var depth = 0
        /** The act this transaction is for, once inserted; null for a tick or after-commit firing. */
        var act: Long? = null
        /** The act's actor (Reference §22, "The actor"): the committer of the root act, read by
         *  a rule declared `by`; null for a tick, an after-commit firing, or an act without `by`. */
        var actor: Long? = null
        /** The rule firing this transaction is, and its subject; null for an act. */
        var firing: Pair<RuleDecl, Long>? = null
        /** The schedule whose tick this transaction serves; null otherwise. */
        var schedule: String? = null

        /** Cumulative footprint of every commit in the envelope (never gating). */
        val createdShapes = mutableSetOf<String>()
        val assignedFields = mutableSetOf<Pair<String, String>>()
        val deletedShapes = mutableSetOf<String>()

        /** (id, refinement) → prior values, recorded before each capture write
         *  or retraction — rollback restores them, buildCommitSet reads the
         *  touched keys' end state. */
        val captureOld = mutableListOf<Pair<Pair<Long, String>, Map<String, Value>?>>()
    }

    /** The one writer of the capture map inside a transaction. */
    private fun setCapture(key: Pair<Long, String>, values: Map<String, Value>?, t: Txn) {
        t.captureOld.add(key to captures[key])
        if (values == null) captures.remove(key) else captures[key] = values
        captureMisses.remove(key)
    }

    private var txn: Txn?
        get() = s.txn
        set(v) { s.txn = v }

    /**
     * One envelope (evaluation.md, "The universal transaction"). Over a store it is
     * one write unit of work ([StateResolver.begin]): any read unit open in this
     * scope is closed first, the write unit opens, the envelope's queue keys are
     * valued against pre-commit state and handed to the store ([UnitOfWork.serialize]),
     * the working set is dropped so everything the envelope evaluates is read after
     * the locks were taken, and the unit ends with the outcome once the commit
     * callback has run, or on the way out of a refusal or a failure.
     */
    private fun <T> inTransaction(actor: Long? = null, keys: () -> Set<AdmissionKey> = { emptySet() }, body: () -> T): Result<T> {
        check(txn == null) { "nested harness transaction" }
        beginSnapshot()
        endUnit(Outcome.Committed)
        val u = resolver.begin(WorkKind.WRITE)
        unit = u
        val t = Txn()
        t.actor = actor
        txn = t
        return try {
            u.serialize(keys())
            beginSnapshot() // the keys were valued against pre-lock state; the envelope reads post-lock state
            val out = body()
            checkNevers(t)
            // the commit callback runs inside the envelope: its writes join the
            // engineer's storage transaction, and a failure rolls the whole
            // commit back (evaluation.md U2, in-envelope failure).
            // The store assigns row identity and reports it back — the
            // returned keys bind this session's handles to storage.
            val pending = buildCommitSet(t)
            val set = pending.set
            if (set.created.isNotEmpty() || set.assigned.isNotEmpty() ||
                set.captured.isNotEmpty() || set.retracted.isNotEmpty() ||
                set.deleted.isNotEmpty()
            ) {
                val keys = onCommit.onCommit(set)
                if (keys.size != set.created.size)
                    throw VelleRuntimeError(
                        "commit callback returned ${keys.size} keys for ${set.created.size} created rows"
                    )
                pending.createdHandles.forEachIndexed { i, h ->
                    keyOf[h] = keys[i]
                    handleOf[Ref.Persisted(idShape.getValue(h), keys[i])] = h
                }
            }
            txn = null
            endUnit(Outcome.Committed)
            // deletions land now: the store already saw them in the commit set;
            // after-commit firings are separate transactions and never see the
            // instance (OQ37 — the transaction close is the removal)
            finalizeDeletes(t)
            drainAfterQueue(t)
            Result.success(out)
        } catch (e: Exception) {
            rollback(t)
            txn = null
            endUnit(if (e is NeverViolation) Outcome.Refused else Outcome.Failed(e))
            Result.failure(e)
        }
    }

    private class PendingCommit(val set: CommitSet, val createdHandles: List<Long>)

    /** The transaction's whole mutation set — creates with their final fields
     *  (initially/generator/timestamp values included) in creation order, so
     *  pending refs point backward; assigns collapsed to the final value per
     *  (instance, field). References cross as typed [Ref]s: persisted rows by
     *  their store key, same-set creates as [Ref.Pending] indices. Transient
     *  acts are excluded: only their consequences persist (Reference §4). */
    private fun buildCommitSet(t: Txn): PendingCommit {
        val createdSet = t.created.toSet()
        // created-then-deleted inside one envelope nets to nothing (OQ37)
        val createdHandles = t.created.filter {
            it !in pendingDeleted && instances.getValue(it).shape !in model.transients
        }
        val indexOfHandle = createdHandles.withIndex().associate { (i, h) -> h to i }

        fun refOf(handle: Long): RowRef {
            keyOf[handle]?.let { return Ref.Persisted(idShape.getValue(handle), it) }
            indexOfHandle[handle]?.let { return Ref.Pending(idShape.getValue(handle), it) }
            throw VelleRuntimeError("reference to unpersisted instance $handle escapes the commit set")
        }

        fun storeValue(v: Value): Any? = when (v) {
            is Value.VRef -> refOf(v.id)
            is Value.VColl -> v.ids.map { refOf(it) }
            is Value.VVals -> v.values.map { unwrap(it) }
            else -> unwrap(v)
        }

        val created = createdHandles.map { id ->
            val inst = instances.getValue(id)
            val decl = model.shapes.getValue(inst.shape)
            val fields = buildMap {
                for (m in decl.members) when (m) {
                    is StoredProp -> put(m.name, storeValue(inst.fields[m.name] ?: Value.VNone))
                    is TimestampProp -> put(m.name, storeValue(inst.fields[m.name] ?: Value.VNone))
                    is ActorProp -> put(m.name, storeValue(inst.fields[m.name] ?: Value.VNone))
                    else -> {}
                }
            }
            CommitSet.Creation(inst.shape, fields)
        }
        val assigned = t.oldValues.asSequence()
            .map { (id, field, _) -> id to field }
            .distinct()
            .filter { (id, _) -> id !in createdSet && id !in pendingDeleted }
            .map { (id, field) ->
                val inst = instances.getValue(id)
                val target = refOf(id) as? Ref.Persisted
                    ?: throw VelleRuntimeError("assign targets unpersisted instance $id")
                CommitSet.Assign(target, field, storeValue(inst.fields[field] ?: Value.VNone))
            }
            .toList()
        // capture channel: net end-state per touched membership — present at
        // envelope close is an upsert, absent a retraction (delete); a
        // create-then-exit inside one envelope persisted nothing and nets out
        val capturedOps = mutableListOf<CommitSet.Capture>()
        val retractedOps = mutableListOf<CommitSet.Retraction>()
        for ((id, refName) in t.captureOld.map { it.first }.distinct()) {
            val base = model.baseOf(refName) ?: continue
            if (base in model.transients) continue
            // a deleted instance's touched memberships net to retraction; its
            // untouched capture rows go with the row itself (the Deletion op)
            val values = if (id in pendingDeleted) null else captures[id to refName]
            if (values != null)
                capturedOps.add(CommitSet.Capture(refOf(id), refName, values.mapValues { storeValue(it.value) }))
            else {
                val key = keyOf[id] ?: continue
                retractedOps.add(CommitSet.Retraction(Ref.Persisted(idShape.getValue(id), key), refName))
            }
        }
        // the deletions (OQ37): persisted rows this transaction removed; a
        // created-then-deleted instance has no key and appears nowhere
        val deletions = t.deleted.distinct().mapNotNull { id ->
            keyOf[id]?.let { CommitSet.Deletion(Ref.Persisted(idShape.getValue(id), it)) }
        }
        return PendingCommit(CommitSet(created, assigned, capturedOps, retractedOps, deletions), createdHandles)
    }

    private fun rollback(t: Txn) {
        pendingDeleted.removeAll(t.deleted.toSet()) // deletions were only marks until close
        for ((id, field, old) in t.oldValues.asReversed()) {
            if (old == null) instances[id]?.fields?.remove(field)
            else instances[id]?.fields?.put(field, old)
        }
        for ((key, old) in t.captureOld.asReversed()) {
            if (old == null) captures.remove(key) else captures[key] = old
        }
        for (id in t.created.asReversed()) {
            val inst = instances.remove(id) ?: continue
            byShape[inst.shape]?.remove(id)
            captures.keys.removeIf { it.first == id }
        }
    }

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

    private fun ordered(pending: List<Pair<RuleDecl, Long>>): List<Pair<RuleDecl, Long>> =
        firingOrder?.invoke(pending) ?: pending

    private fun drainAfterQueue(t: Txn) {
        // [S2] synchronous, FIFO, each entry its own transaction
        for ((rule, subject) in ordered(t.afterQueue)) {
            if (instance(subject) == null) continue
            if (!evaluator.memberOfRefExpr(subject, rule.condition)) continue
            val result = inTransaction(keys = { firingKeys(rule, subject) }) { txn!!.firing = rule to subject; fire(rule, subject) }
            result.exceptionOrNull()?.let {
                if (it is NeverViolation) afterCommitRejections.add(it.rejection)
                failures.add("after-commit firing of '${rule.name}' failed: ${it.message}")
            }
        }
    }

    // ── queue keys valued at admission (evaluation.md, "serialization domain") ──

    /**
     * The queue keys of an act's envelope, valued against the act's own inputs
     * and pre-commit state: a path key is the row the act's stored to-one path
     * reaches, hopping through stored and captured members; a value key is the
     * value the envelope commits; a widening is the shape. A path that reaches
     * no row contributes no key. Valued before the envelope's work runs, inside
     * the write unit, so the reads it needs happen on the unit's connection.
     */
    private fun actKeys(act: String, mutations: List<Mutation>, rootIndex: Int): Set<AdmissionKey> {
        val domain = domains.actDomains[act] ?: return emptySet()
        val root = mutations.getOrNull(rootIndex) as? Mutation.Create
        val keys = linkedSetOf<AdmissionKey>()
        for (path in domain.paths) {
            val first = path.segs.firstOrNull() ?: continue // the act's own row is fresh and conflict-free
            var id = (root?.fields?.get(first) as? Value.VRef)?.id ?: continue
            var reached = true
            for (seg in path.segs.drop(1)) {
                id = hop(id, seg) ?: run { reached = false; -1L }
                if (!reached) break
            }
            if (reached) rowKey(id)?.let(keys::add)
        }
        for (vk in domain.valueKeys) {
            for (m in mutations) {
                if (m !is Mutation.Create || m.shape != vk.shape) continue
                val v = m.fields[vk.field] ?: continue
                canonical(v)?.let { keys.add(AdmissionKey.Value(vk.shape, vk.field, it)) }
            }
        }
        for (w in domain.widenings) keys.add(AdmissionKey.Shape(w.shape))
        return keys
    }

    /**
     * The queue keys of one rule firing that is its own envelope (a tick's or the
     * after-commit queue's), valued from the swept subject: an empty path is the
     * subject's own row, a longer one hops from it. A value key's value is not
     * known until the firing runs, so it takes the shape's width instead.
     */
    private fun firingKeys(rule: RuleDecl, subject: Long): Set<AdmissionKey> {
        val domain = domains.firingDomains[rule.name] ?: return emptySet()
        val keys = linkedSetOf<AdmissionKey>()
        for (path in domain.paths) {
            var id: Long? = subject
            for (seg in path.segs) { id = id?.let { hop(it, seg) } ?: break }
            id?.let { rowKey(it) }?.let(keys::add)
        }
        for (vk in domain.valueKeys) keys.add(AdmissionKey.Shape(vk.shape))
        for (w in domain.widenings) keys.add(AdmissionKey.Shape(w.shape))
        return keys
    }

    /** One hop of a key path: the row [id]'s stored to-one member [seg], or the captured member of that name (Reference §13). */
    private fun hop(id: Long, seg: String): Long? {
        val inst = instance(id) ?: return null
        (inst.fields[seg] as? Value.VRef)?.let { return it.id }
        for (cs in model.captureSchemas) {
            if (cs.base != inst.shape || cs.props.none { it.name == seg }) continue
            (captureValues(id, cs.refinement)?.get(seg) as? Value.VRef)?.let { return it.id }
        }
        return null
    }

    /** The row key for a persisted handle; null for a row this envelope is creating, which nothing else can hold yet. */
    private fun rowKey(id: Long): AdmissionKey? {
        val key = keyOf[id] ?: return null
        val shape = idShape[id] ?: return null
        return AdmissionKey.Row(shape, key)
    }

    /** The one spelling of a committed value a store can queue on; null for a value no envelope correlates on. */
    private fun canonical(v: Value): String? = when (v) {
        is Value.VNum -> v.v.stripTrailingZeros().toPlainString()
        is Value.VText -> v.v
        is Value.VBool -> v.v.toString()
        is Value.VDate -> v.v.toString()
        is Value.VDateTime -> v.v.toString()
        is Value.VTime -> v.v.toString()
        is Value.VUuid -> v.v.toString()
        is Value.VDuration -> v.seconds().toString()
        is Value.VExt -> v.type.canonicalAny(v.v)
        is Value.VRef -> rowKey(v.id)?.text
        else -> null
    }

    // ── the exposed commit surface ────────────────────────────────────────────

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
    ): CommitResult = scope { commitInScope(shape, suppliedFields, parts, actor) }

    private fun commitInScope(
        shape: String,
        suppliedFields: Map<String, Any?>,
        parts: Map<String, List<Map<String, Any?>>>,
        actor: Long?,
    ): CommitResult {
        val decl = model.shapes[shape]
            ?: return CommitResult.Refused("type: unknown shape '$shape'")
        if (shape !in model.exposed)
            return CommitResult.Refused("type: shape '$shape' is not an act — it enters state only as a rule's effect")

        duplicateRefusal(decl, suppliedFields)?.let { return CommitResult.Refused(it) }

        val actorProp = decl.actor
        if (actorProp != null && actorProp.name in suppliedFields)
            return CommitResult.Refused("type: '$shape.${actorProp.name}' is the actor — a fact of the commit the committer cannot claim (Reference §22)")
        if (actorProp == null && actor != null)
            return CommitResult.Refused("type: '$shape' declares no actor (no `by` clause), yet one was given")
        if (actorProp != null && actor == null)
            return CommitResult.Refused("type: '$shape' is committed by a ${actorProp.shape} (`by ${actorProp.shape}`), and none was given")
        if (actorProp != null) {
            val bases = model.basesOf(actorProp.shape)
            if (bases.none { acceptedReference(actor!!, it) })
                return CommitResult.Refused("type: the actor of '$shape' is not a ${bases.joinToString(" or ")}")
        }

        // the exposure closure (Reference §6, "Inline part creation"): the act plus
        // its declared inline parts land as ONE commit — parts are values created
        // here, back-references language-populated, entrants container-and-parts.
        // A `one` field named in `with` may arrive by value instead of by handle
        // (OQ63 §6.1): the nested record is created in the same commit, first.
        val edges = model.closures[shape].orEmpty()
        parts.keys.find { key -> edges.none { it.prop == key } }?.let {
            return CommitResult.Refused("type: '$shape' declares no closure edge '$it'")
        }
        val byValueHere = edges.filter { it.byValue && parts[it.prop]?.isNotEmpty() == true }.map { it.prop }.toSet()
        // a `one` field pointing at a new record elsewhere in this act (OQ63 §6.2.2)
        // is resolved after every value is appended, below
        val (own, siblingRefs) = splitNewRefs(suppliedFields)
        val supplied = try {
            convertFields(decl, own, skipRequired = byValueHere + siblingRefs.map { it.first })
                ?: return CommitResult.Refused(typeFailure(decl, own))
        } catch (e: PartRefusal) {
            return CommitResult.Refused(e.reason) // an extension type's `validate` refused a value
        }
        val converted = if (actorProp != null) supplied + (actorProp.name to Value.VRef(actor!!)) else supplied

        val build = ClosureBuild()
        var rootIndex = try {
            appendRecord(shape, converted, parts, edges, build).also { idx ->
                siblingRefs.forEach { (field, token) -> build.refs.add(Triple(idx, field, token)) }
            }
        } catch (e: PartRefusal) {
            return CommitResult.Refused(e.reason)
        }
        val mutations = try {
            resolveSiblings(build).also { (_, remap) -> rootIndex = remap[rootIndex] }.first
        } catch (e: PartRefusal) {
            return CommitResult.Refused(e.reason)
        }

        val result = inTransaction(actor, keys = { actKeys(shape, mutations, rootIndex) }) { applyCommit(mutations, actCommit = true)[rootIndex] }
        return result.fold(
            onSuccess = { id ->
                // a transient act is an input to the state, not a member of it: at
                // the close of its transaction the instance is removed — only its
                // consequences persist (Reference §4 "Transient acts"; evaluation.md).
                // Removing after the transaction (nevers checked, after-queue
                // drained) is observably equivalent to removing at its close: V17
                // bans every read of the act from anything that runs later.
                if (shape in model.transients) {
                    instances.remove(id)
                    byShape[shape]?.remove(id)
                    captures.keys.removeIf { it.first == id }
                }
                CommitResult.Accepted(id, if (shape in model.transients) null else refFor(id))
            },
            onFailure = { e ->
                if (e is NeverViolation) CommitResult.Refused(e.rejection.message, e.rejection) else throw e
            }
        )
    }

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
    fun seed(shape: String, fields: Map<String, Any?>): Ref.Persisted = scope { refFor(seedInScope(shape, fields)) }

    private fun seedInScope(shape: String, fields: Map<String, Any?>): Long {
        val decl = model.shapes[shape] ?: throw VelleRuntimeError("seed: unknown shape '$shape'")
        if (shape in model.transients) throw VelleRuntimeError("seed: '$shape' is a transient act, not a record")
        duplicateRefusal(decl, fields)?.let { throw VelleRuntimeError("seed: $it") }
        val converted = convertFields(decl, fields) ?: throw VelleRuntimeError("seed: " + typeFailure(decl, fields))
        check(txn == null) { "nested harness transaction" }
        beginSnapshot()
        endUnit(Outcome.Committed)
        val u = resolver.begin(WorkKind.WRITE)
        unit = u
        val t = Txn()
        txn = t
        try {
            // a seed bypasses the rules, so no domain was derived for it: it takes the shape's width
            u.serialize(setOf(AdmissionKey.Shape(shape)))
            beginSnapshot()
            val id = applyCreate(Mutation.Create(shape, converted), t)
            val pending = buildCommitSet(t)
            val keys = onCommit.onCommit(pending.set)
            pending.createdHandles.forEachIndexed { i, h ->
                keyOf[h] = keys[i]
                handleOf[Ref.Persisted(idShape.getValue(h), keys[i])] = h
            }
            txn = null
            endUnit(Outcome.Committed)
            return id
        } catch (e: Exception) {
            rollback(t)
            txn = null
            endUnit(Outcome.Failed(e))
            throw e
        }
    }

    /** The set refusal: a duplicate in a committed `many` is a caller bug or a
     *  multiplicity claim a set cannot express (Reference §6; §5, "Collections of
     *  values"). A `many` of values that `tolerates duplication` keeps them. Inline
     *  part *values* are a bag — two identical values mint two distinct instances —
     *  so this applies to reference collections only. */
    private fun duplicateRefusal(decl: ShapeDecl, supplied: Map<String, Any?>): String? {
        for (m in decl.members.filterIsInstance<StoredProp>()) {
            val t = m.type
            val raw = supplied[m.name] as? List<*> ?: continue
            if (raw.size == raw.distinct().size) continue
            if (t is RelType && t.many)
                return "type: duplicate in '${decl.name}.${m.name}' — a `many` is a set; " +
                    "if multiplicity is meaningful, it is data on an edge shape (Reference §6)"
            if (t is ScalarType && t.many && !t.ordered)
                return "type: duplicate in '${decl.name}.${m.name}' — a `many` holds each value once; " +
                    "`tolerates duplication` on the property keeps duplicates (Reference §5)"
        }
        return null
    }

    private class PartRefusal(val reason: String) : Exception(reason)

    companion object {
        /** The key a raw nested value carries to name its concrete shape when the field's type is a union (OQ63 §6.1). */
        const val SHAPE_KEY = "@shape"
        /** The key a raw nested value carries with its [NewRegistry] token, so a sibling's [NewRef] can name it (OQ63 §6.2.2). */
        const val NEW_KEY = "@new"
    }

    /** The creation list of one act under construction, with the sibling references still to resolve. */
    private class ClosureBuild {
        val out = mutableListOf<Mutation>()
        /** registry token → index of the record it names */
        val tokens = mutableMapOf<Int, Int>()
        /** (index, field, token): a `one` field pointing at a sibling new record */
        val refs = mutableListOf<Triple<Int, String, Int>>()
    }

    /**
     * Separate a raw value's [NewRef]s from the rest: a `one` field holding one,
     * and the [NewRef] elements of a `many` field (the handles stay in the raw
     * list). Returns the remaining raw fields and (field, token) pairs.
     */
    private fun splitNewRefs(raw: Map<String, Any?>): Pair<Map<String, Any?>, List<Pair<String, Int>>> {
        val refs = mutableListOf<Pair<String, Int>>()
        val rest = mutableMapOf<String, Any?>()
        for ((k, v) in raw) when {
            v is NewRef -> refs.add(k to v.token)
            v is List<*> && v.any { it is NewRef } -> {
                v.filterIsInstance<NewRef>().forEach { refs.add(k to it.token) }
                rest[k] = v.filter { it !is NewRef }
            }
            else -> rest[k] = v
        }
        return rest to refs
    }

    /**
     * Resolve every sibling reference to a pending reference and order the
     * creation list so each pending reference points backward (Hydration.kt,
     * [Ref.Pending]). Returns the ordered list and the old-to-new index map.
     */
    private fun resolveSiblings(build: ClosureBuild): Pair<List<Mutation>, IntArray> {
        val out = build.out
        for ((idx, field, token) in build.refs) {
            val m = out[idx] as Mutation.Create
            val target = build.tokens[token]
                ?: throw PartRefusal("type: '${m.shape}.$field' points at a new record that is not among this act's values")
            val t = fieldTypeOf(m.shape, field) as? RelType
            val targetShape = (out[target] as Mutation.Create).shape
            if (t == null || !model.typeAccepts(t.shape, targetShape))
                throw PartRefusal("type: '${m.shape}.$field' takes a ${t?.shape ?: "scalar"}, not a new $targetShape")
            val value: Value = if (!t.many) Value.VPendingRef(target) else {
                // a `many` field: the handles already converted, plus this new element
                val items = when (val cur = m.fields[field]) {
                    is Value.VColl -> cur.ids.map { Value.VRef(it) }
                    is Value.VPendingColl -> cur.items
                    else -> emptyList()
                }
                if (items.any { it is Value.VPendingRef && it.index == target })
                    throw PartRefusal("type: duplicate in '${m.shape}.$field' — a `many` is a set (Reference §6)")
                Value.VPendingColl(items + Value.VPendingRef(target), t.shape)
            }
            out[idx] = m.copy(fields = m.fields + (field to value))
        }
        // stable topological order: the earliest record whose references are all placed
        fun deps(i: Int): List<Int> = (out[i] as Mutation.Create).fields.values.flatMap { v ->
            when (v) {
                is Value.VPendingRef -> listOf(v.index)
                is Value.VPendingColl -> v.items.filterIsInstance<Value.VPendingRef>().map { it.index }
                else -> emptyList()
            }
        }
        val order = mutableListOf<Int>()
        val placed = BooleanArray(out.size)
        while (order.size < out.size) {
            val next = out.indices.firstOrNull { i -> !placed[i] && deps(i).all { placed[it] } }
                ?: throw PartRefusal("type: the new records of this act point at each other in a cycle; no record can be created first")
            placed[next] = true
            order.add(next)
        }
        val remap = IntArray(out.size)
        order.forEachIndexed { newIdx, oldIdx -> remap[oldIdx] = newIdx }
        fun remapped(v: Value): Value = when (v) {
            is Value.VPendingRef -> Value.VPendingRef(remap[v.index])
            is Value.VPendingColl -> Value.VPendingColl(v.items.map { remapped(it) }, v.shape)
            else -> v
        }
        val sorted = order.map { oldIdx ->
            val m = out[oldIdx] as Mutation.Create
            m.copy(fields = m.fields.mapValues { (_, v) -> remapped(v) })
        }
        return sorted to remap
    }

    /**
     * Append one record and its closure to the commit's creation list, returning
     * the record's index. Order is dependency order, so every pending reference
     * points backward (Hydration.kt, [Ref.Pending]): a value nested at a `one`
     * field named in `with` is appended before the record that points at it, and
     * a part is appended after the record its back-reference points at.
     */
    private fun appendRecord(
        shape: String,
        converted: Map<String, Value>,
        supplied: Map<String, List<Map<String, Any?>>>,
        edges: List<ClosureEdge>,
        build: ClosureBuild,
    ): Int {
        val out = build.out
        val nested = mutableMapOf<String, Value>()
        for (edge in edges.filter { it.byValue }) {
            val raws = supplied[edge.prop].orEmpty()
            if (raws.size > 1)
                throw PartRefusal("type: '$shape.${edge.prop}' is a `one` field — one value may arrive there, ${raws.size} were given")
            val raw = raws.singleOrNull() ?: continue
            if (edge.prop in converted)
                throw PartRefusal("type: '$shape.${edge.prop}' was given both as a reference to an existing record and as a new value")
            val concrete = (raw[SHAPE_KEY] as? String) ?: edge.partShape
            if (concrete !in model.shapes || !model.typeAccepts(edge.partShape, concrete))
                throw PartRefusal("type: a value of '$concrete' cannot arrive at '$shape.${edge.prop}', which takes a ${edge.partShape}")
            nested[edge.prop] = Value.VPendingRef(appendValue(concrete, raw, edge.childrenFor(concrete), build, "new value for '$shape.${edge.prop}'"))
        }
        val index = out.size
        out.add(Mutation.Create(shape, converted + nested))
        for (edge in edges.filter { !it.byValue }) {
            for (raw in supplied[edge.prop].orEmpty()) {
                if (edge.backRef in raw)
                    throw PartRefusal("type: '${edge.partShape}.${edge.backRef}' is language-populated — " +
                        "a back-reference cannot be claimed by the committer (Reference §6)")
                appendValue(edge.partShape, raw, edge.childrenFor(edge.partShape), build, "inline part",
                    extra = mapOf(edge.backRef to Value.VPendingRef(index)), skipRequired = setOf(edge.backRef))
            }
        }
        return index
    }

    /** Convert one raw nested value (a part or a by-value record) and append it with its own closure. */
    @Suppress("UNCHECKED_CAST")
    private fun appendValue(
        shape: String,
        raw: Map<String, Any?>,
        children: List<ClosureEdge>,
        build: ClosureBuild,
        what: String,
        extra: Map<String, Value> = emptyMap(),
        skipRequired: Set<String> = emptySet(),
    ): Int {
        val decl = model.shapes[shape] ?: throw PartRefusal("type: unknown shape '$shape'")
        val childKeys = children.map { it.prop }.toSet()
        val (own, siblingRefs) = splitNewRefs(raw.filterKeys { it !in childKeys && it != SHAPE_KEY && it != NEW_KEY })
        duplicateRefusal(decl, own)?.let { throw PartRefusal(it) }
        val nestedParts = children.associate { c -> c.prop to ((raw[c.prop] as? List<Map<String, Any?>>).orEmpty()) }
        val byValueHere = children.filter { it.byValue && nestedParts[it.prop]?.isNotEmpty() == true }.map { it.prop }.toSet()
        val converted = convertFields(decl, own, skipRequired = skipRequired + byValueHere + siblingRefs.map { it.first })
            ?: throw PartRefusal(typeFailure(decl, own) + " ($what)")
        val index = appendRecord(shape, converted + extra, nestedParts, children, build)
        (raw[NEW_KEY] as? Number)?.toInt()?.let { token ->
            if (build.tokens.put(token, index) != null)
                throw PartRefusal("type: a new $shape appears in two creating positions of this act — one new value, one record")
        }
        siblingRefs.forEach { (field, token) -> build.refs.add(Triple(index, field, token)) }
        return index
    }

    private fun convertFields(decl: ShapeDecl, supplied: Map<String, Any?>, skipRequired: Set<String> = emptySet()): Map<String, Value>? {
        val out = mutableMapOf<String, Value>()
        for (m in decl.members.filterIsInstance<StoredProp>()) {
            val raw = supplied[m.name]
            if (raw == null) {
                // an absent `many` is the empty collection — the absence (Reference §6)
                when {
                    (m.type as? RelType)?.many == true ->
                        out[m.name] = Value.VColl(emptyList(), (m.type as RelType).shape)
                    (m.type as? ScalarType)?.many == true ->
                        out[m.name] = Value.VVals(emptyList(), ordered = (m.type as ScalarType).ordered)
                    // `? initially required` (OQ37-R10): read-optional, but the
                    // creation must supply a value — absence is refused here
                    m.name !in skipRequired && m.initially == null &&
                        (m.initiallyRequired ||
                            ((m.type as? ScalarType)?.optional != true &&
                                (m.type as? RelType)?.optional != true)) -> return null
                }
                continue
            }
            out[m.name] = convert(raw, m.type) ?: return null
        }
        val known = decl.members.filterIsInstance<StoredProp>().map { it.name }.toSet()
        if (supplied.keys.any { it !in known }) return null // timestamps/id/derived/unknown
        return out
    }

    private fun convert(raw: Any, type: TypeRef): Value? = when (type) {
        is RelType ->
            if (type.many) (raw as? List<*>)
                ?.map { el -> (el as? Long)?.takeIf { acceptedReference(it, type.shape) } ?: return null }
                ?.let { Value.VColl(it, type.shape) }
            else (raw as? Long)
                ?.takeIf { acceptedReference(it, type.shape) }
                ?.let { Value.VRef(it) }
        is ScalarType ->
            if (type.many) (raw as? List<*>)
                ?.map { el -> el?.let { convertScalar(it, type.name) } ?: return null }
                ?.let { Value.VVals(it, ordered = type.ordered) }
            else convertScalar(raw, type.name)
    }

    /** A committed reference names an existing instance the field's declared
     *  type accepts — the shape itself, or an operand of a static union (Reference §9):
     *  a handle of the wrong shape can't inhabit the type, a boundary refusal. */
    private fun acceptedReference(handle: Long, declared: String): Boolean {
        if (declared in model.shapes) idShape.putIfAbsent(handle, declared)
        val inst = instance(handle) ?: return false
        return model.typeAccepts(declared, inst.shape)
    }

    private fun convertScalar(raw: Any, name: String): Value? = when (name) {
        "text" -> (raw as? String)?.let { Value.VText(it) }
        "boolean" -> (raw as? Boolean)?.let { Value.VBool(it) }
        "Date" -> (raw as? LocalDate)?.let { Value.VDate(it) }
        "DateTime" -> (raw as? Instant)?.let { Value.VDateTime(it) }
        "uuid" -> (raw as? UUID)?.let { Value.VUuid(it) }
        "duration" -> (raw as? java.time.Duration)?.let { Value.VDuration.ofJava(it) }
        "time" -> (raw as? java.time.LocalTime)?.let { Value.VTime(it) }
        "bytes" -> (raw as? ByteArray)?.let { Value.VBytes(it) }
        in TypeRegistry.NUMBER_NAMES -> runCatching { Value.num(raw) }.getOrNull()
        else -> {
            // an extension type (Reference §5; OQ65): the value is the type's own class and
            // passes its `validate`; anything else is a type-level refusal at the boundary
            val type = model.typeScope[name] ?: return null
            if (!type.kotlinClass.isInstance(raw)) null
            else type.validateAny(raw)?.let { reason -> throw PartRefusal("type: ${model.specNameOf(type)} — $reason") } ?: Value.VExt(type, raw)
        }
    }

    private fun typeFailure(decl: ShapeDecl, supplied: Map<String, Any?>): String {
        val required = decl.members.filterIsInstance<StoredProp>()
            .filter { it.initially == null }
            .filter { it.initiallyRequired ||
                ((it.type as? RelType)?.optional != true && (it.type as? ScalarType)?.optional != true) }
            .filterNot { (it.type as? RelType)?.many == true || (it.type as? ScalarType)?.many == true }
            .map { it.name }
        val missing = required - supplied.keys
        return if (missing.isNotEmpty()) "type: '${decl.name}' requires $missing"
        else "type: unacceptable fields for '${decl.name}'"
    }

    // ── one commit (evaluation.md, "Processing one commit") ──────────────────

    internal sealed interface Mutation {
        data class Create(val shape: String, val fields: Map<String, Value>) : Mutation
        data class Assign(val id: Long, val field: String, val value: Value) : Mutation
        /** OQ37: the instance becomes transient at this, its final commit — fully
         *  present within the deleting transaction (last readers), removed at close. */
        data class Delete(val id: Long) : Mutation
    }

    /**
     * A watched condition: a commit-triggered rule's condition or a
     * capture-carrying refinement, with its static read summary. [sensShapes]
     * is the shape-granular sensitivity set (existence/collection consults,
     * base-normalized); [selfContained] means the predicate reads nothing
     * beyond the base shape's own columns — the criterion under which a
     * storage-side pre-filter is sound mid-envelope (any instance whose
     * membership the envelope changed was itself written, so it is already in
     * the working set; every untouched instance's row still matches its
     * in-memory state).
     */
    private class Watcher(
        val key: String,
        val condition: RefExpr,
        /** the base shapes the condition's subjects are drawn from — several for a cross-base union (Reference §9) */
        val bases: Set<String>,
        val summary: ReadSummary,
        val sensShapes: Set<String>,
        val selfContained: Boolean,
        /** Shapes targeted by reference-typed fields the predicate reads — a
         *  deletion of one flips those reads to absent (OQ37-R10). */
        val refTargets: Set<String>,
    )

    private fun watcherOf(key: String, condition: RefExpr, bases: Set<String>): Watcher {
        val s = model.summaryOfRefExpr(condition)
        val sens = (s.existsShapes + s.collShapes).flatMap { model.basesOf(it) }.toSet()
        val selfContained = !s.opaque && sens.isEmpty() && bases.size == 1 &&
            (s.fields + s.collFields).all { it.first in bases }
        val refTargets = (s.fields + s.collFields).flatMap { (o, f) ->
            model.membersOf(o)[f]?.type?.instanceShape()?.let { model.basesOf(it) } ?: emptySet()
        }.toSet()
        return Watcher(key, condition, bases, s, sens, selfContained, refTargets)
    }

    /** Watched conditions: every commit-triggered rule plus capture-carrying
     *  refinements. Schedule-only rules are deliberately absent — their subjects
     *  come from the tick's own member scan, never from a commit diff (§16/§17). */
    private val watchers: List<Watcher> by lazy {
        val ws = mutableListOf<Watcher>()
        for (r in model.rules.values) {
            if (!(r.preposition == null || "commit" in r.triggers)) continue
            val bases = model.basesOfExpr(r.condition)
            if (bases.isEmpty()) continue
            ws.add(watcherOf("rule:${r.name}", r.condition, bases))
        }
        for ((name, r) in model.refinements) {
            if (r.members.any { it is DerivedProp && it.captured }) {
                val base = model.baseOf(name) ?: continue
                ws.add(watcherOf("capture:$name", RefName(name), setOf(base)))
            }
        }
        ws
    }

    private val watcherByKey: Map<String, Watcher> by lazy { watchers.associateBy { it.key } }

    /** The mutation footprint of one commit: what it creates, writes, and deletes. */
    private class Footprint {
        val created = mutableSetOf<String>()
        val assigned = mutableSetOf<Pair<String, String>>()
        val deleted = mutableSetOf<String>()
    }

    private fun footprintOf(mutations: List<Mutation>): Footprint {
        val f = Footprint()
        for (m in mutations) when (m) {
            is Mutation.Create -> f.created.add(m.shape)
            is Mutation.Assign -> {
                val shape = instance(m.id)?.shape ?: continue
                f.assigned.add(shape to m.field)
                // any stored write also advances the shape's `on update` timestamps
                model.shapes[shape]?.members?.filterIsInstance<TimestampProp>()
                    ?.filter { it.on == "update" }
                    ?.forEach { f.assigned.add(shape to it.name) }
            }
            is Mutation.Delete -> instance(m.id)?.shape?.let { f.deleted.add(it) }
        }
        return f
    }

    /**
     * Can this commit change the watcher's member set? The runtime sibling of
     * the compiler's derived trigger set (Reference §11, "Rules ground in
     * commits"): membership flips only when data the predicate reads changes,
     * and the footprint says exactly what changed. `today`/`now` are constant
     * within an envelope, so time reads never make a commit relevant here —
     * entry by aging belongs to ticks.
     */
    private fun relevant(w: Watcher, fp: Footprint): Boolean {
        if (w.summary.opaque) return true
        if (fp.created.any { it in w.bases || it in w.sensShapes }) return true
        // a deletion flips existence/aggregate consults, removes the deleted
        // instance from every refinement of its base, and turns reference reads
        // at it absent (OQ37)
        if (fp.deleted.any { it in w.bases || it in w.sensShapes || it in w.refTargets }) return true
        return fp.assigned.any { (sh, f) ->
            sh in w.sensShapes || (sh to f) in w.summary.fields || (sh to f) in w.summary.collFields
        }
    }

    private fun memberSet(w: Watcher): Set<Long> {
        val ids = if (w.selfContained) idsOfCandidates(w.bases.single(), queryCompiler().filterFor(w.condition))
        else w.bases.flatMap { idsOf(it) }
        return ids.filter { evaluator.memberOfRefExpr(it, w.condition) }.toSet()
    }

    /** The door set (Reference §21, ruled 2026-10-14): the prohibitions whose verdict
     *  no rule can change, evaluated once at the act's own commit and skipped at
     *  the transaction end. Computed by the validator from the rules' writes,
     *  creations, and deletions against each predicate's reads. */
    private val doorNevers: Set<Int> by lazy { Validator(model).doorNevers }

    private fun applyCommit(mutations: List<Mutation>, actCommit: Boolean = false): List<Long> {
        val t = txn ?: error("commit outside transaction")
        if (t.depth++ > maxDepth)
            throw VelleRuntimeError("cascade depth exceeded $maxDepth — quiescence backstop [S3]")

        val fp = footprintOf(mutations)
        t.createdShapes.addAll(fp.created)
        t.assignedFields.addAll(fp.assigned)
        t.deletedShapes.addAll(fp.deleted)
        // only watchers this commit's footprint can affect get their member
        // sets evaluated — for the rest, no membership can have flipped
        val active = watchers.filter { relevant(it, fp) }
        val pre = active.associateWith { memberSet(it) }

        val createdIds = mutableListOf<Long>()
        var wroteStored = mutableSetOf<Long>()
        for (m in mutations) when (m) {
            is Mutation.Create -> {
                // a closure part's back-reference lands here: pending index →
                // the already-created container's id (Reference §6, "Inline part
                // creation" — materialization order is topological, never observable)
                fun landed(v: Value): Value = when (v) {
                    is Value.VPendingRef -> Value.VRef(createdIds[v.index])
                    is Value.VPendingColl -> Value.VColl(v.items.map { (landed(it) as Value.VRef).id }, v.shape)
                    else -> v
                }
                val resolved =
                    if (m.fields.values.any { it is Value.VPendingRef || it is Value.VPendingColl })
                        m.copy(fields = m.fields.mapValues { (_, v) -> landed(v) })
                    else m
                createdIds.add(applyCreate(resolved, t))
            }
            is Mutation.Assign -> { applyAssign(m, t, pre); wroteStored.add(m.id) }
            is Mutation.Delete -> applyDelete(m, t)
        }
        // `on update` timestamps advance at every commit writing a stored field
        for (id in wroteStored) {
            val inst = instances[id] ?: continue
            model.shapes[inst.shape]?.members?.filterIsInstance<TimestampProp>()
                ?.filter { it.on == "update" }
                ?.forEach { setField(inst, it.name, Value.VDateTime(now), t) }
        }

        if (actCommit) {
            // the door (Reference §21; evaluation.md "An exposed call", step 1): the
            // act's closure is in, nothing is processed, and the prohibitions no
            // rule can move are evaluated here — over this commit's footprint
            // alone, with the act named — and not again at the end. A refusal
            // reaches the caller before any rule reads the new state.
            t.act = createdIds.first()
            checkNevers(t, door = fp)
        }

        val post = active.associateWith { memberSet(it) }

        // captures: evaluate at entry, mark leavers for retraction at close
        val retractions = mutableListOf<Pair<Long, String>>()
        for (w in active.filter { it.key.startsWith("capture:") }) {
            val refName = w.key.removePrefix("capture:")
            val props = model.refinements.getValue(refName).members
                .filterIsInstance<DerivedProp>().filter { it.captured }
            for (id in post.getValue(w) - pre.getValue(w))
                setCapture(id to refName, props.associate { it.name to evaluator.evalMember(id, refName, it) }, t)
            for (id in pre.getValue(w) - post.getValue(w))
                retractions.add(id to refName)
        }

        // firings: entrants for `when R`, leavers for the exit forms — the
        // whole firing set is pinned from this commit's pre/post diff before
        // any sibling runs (evaluation.md step 5), then executed in an order
        // the spec must not observe (step 6, [S3]). A leaver left either
        // because its predicate flipped or because this commit deleted it —
        // different exit causes, and each rule says which it reacts to
        // (Reference §13, "Deletion as a second exit cause"): bare `when leaving`
        // never fires at deletion
        val deletedNow = mutations.filterIsInstance<Mutation.Delete>().map { it.id }.toSet()
        val pending = mutableListOf<Pair<RuleDecl, Long>>()
        for (rule in model.rules.values) {
            if (!(rule.preposition == null || "commit" in rule.triggers)) continue
            val w = watcherByKey["rule:${rule.name}"] ?: continue
            if (w !in active) continue // this commit provably cannot flip its condition
            var subjects: Set<Long> =
                if (!rule.leaving) post.getValue(w) - pre.getValue(w)
                else {
                    val gone = pre.getValue(w) - post.getValue(w)
                    buildSet {
                        if (rule.trigger.onPredicateExit) addAll(gone - deletedNow)
                        if (rule.trigger.onDeletion) addAll(gone intersect deletedNow)
                    }
                }
            // a transient act's partitions are decided exactly once, at its
            // creation commit — consequence commits within the transaction
            // never re-partition it (Reference §4, "Transient acts")
            if (w.bases.any { it in model.transients }) subjects = subjects intersect createdIds.toSet()
            for (subject in subjects) {
                if (rule.preposition == "after") t.afterQueue.add(rule to subject)
                else pending.add(rule to subject)
            }
        }
        for ((rule, subject) in ordered(pending)) fire(rule, subject)

        // close of the commit: leaver captures retract (exit rules were their last readers)
        retractions.forEach { setCapture(it, null, t) }
        return createdIds
    }

    private fun applyCreate(m: Mutation.Create, t: Txn): Long {
        val decl = model.shapes[m.shape] ?: throw VelleRuntimeError("unknown shape '${m.shape}'")
        val id = nextId.getAndIncrement()
        val inst = Instance(id, m.shape, id, m.fields.toMutableMap())
        instances[id] = inst
        byShape.getOrPut(m.shape) { mutableListOf() }.add(id)
        idShape[id] = m.shape
        t.created.add(id)
        for (p in decl.members.filterIsInstance<StoredProp>()) {
            if (p.name in inst.fields) {
                inst.fields[p.name] = coerceCollection(inst.fields.getValue(p.name), p.type)
                continue
            }
            if (p.initially == null) {
                // an unsupplied `many` starts empty — the empty collection is the absence (Reference §6)
                coerceCollection(Value.VEmpty, p.type).takeIf { it != Value.VEmpty }
                    ?.let { inst.fields[p.name] = it }
                continue
            }
            inst.fields[p.name] =
                if (p.initially == PathExpr("randomUUID"))
                    // a `uuid` field gets the value; a `text` field its canonical spelling (Reference §5)
                    UUID.randomUUID().let { if ((p.type as? ScalarType)?.name == "uuid") Value.VUuid(it) else Value.VText(it.toString()) }
                else coerceCollection(evaluator.eval(p.initially, Evaluator.Ctx(m.shape, id)), p.type)
        }
        for (p in decl.members.filterIsInstance<TimestampProp>())
            inst.fields[p.name] = Value.VDateTime(now) // create and update both start here
        return id
    }

    private fun applyAssign(m: Mutation.Assign, t: Txn, pre: Map<*, Set<Long>>) {
        val inst = instance(m.id) ?: throw VelleRuntimeError("assign to missing instance ${m.id}")
        // frozen-field tripwire: the validator proved this impossible; assert anyway [S5 spirit]
        for ((refName, r) in model.refinements) {
            val frozen = r.members.filterIsInstance<FrozenClause>().singleOrNull() ?: continue
            if (model.baseOf(refName) != inst.shape) continue
            val fields = frozen.fields.ifEmpty {
                model.shapes[inst.shape]?.members?.filterIsInstance<StoredProp>()?.map { it.name } ?: emptyList()
            }
            if (m.field in fields) {
                val wasMember = watchers.find { it.key == "capture:$refName" }
                    ?.let { pre[it]?.contains(m.id) }
                    ?: evaluator.memberOfRefExpr(m.id, RefName(refName))
                if (wasMember)
                    throw VelleRuntimeError("write to ${inst.shape}.${m.field}, frozen by '$refName' — validator gap")
            }
        }
        setField(inst, m.field, m.value, t)
    }

    private fun setField(inst: Instance, field: String, value: Value, t: Txn) {
        t.oldValues.add(Triple(inst.id, field, inst.fields[field]))
        inst.fields[field] = value
    }

    /** The deleting commit (OQ37): the instance leaves the state's member sets
     *  now — post-state membership excludes it, exit rules fire as its last
     *  readers — and is physically removed at transaction close. */
    private fun applyDelete(m: Mutation.Delete, t: Txn) {
        val inst = instance(m.id) ?: throw VelleRuntimeError("delete of missing instance ${m.id}")
        if (inst.shape in model.transients)
            throw VelleRuntimeError("delete of transient act '${inst.shape}' — validator gap (V23)")
        // undeletable tripwire: the validator proved this impossible; assert anyway [S5 spirit]
        for ((refName, r) in model.refinements) {
            if (r.members.none { it is UndeletableClause }) continue
            if (model.baseOf(refName) != inst.shape) continue
            if (m.id !in pendingDeleted && evaluator.memberOfRefExpr(m.id, RefName(refName)))
                throw VelleRuntimeError("delete of ${inst.shape} ${m.id}, undeletable in '$refName' — validator gap")
        }
        if (pendingDeleted.add(m.id)) t.deleted.add(m.id)
    }

    /** Physical removal at the deleting transaction's close: after the commit
     *  callback (the store saw the deletions), before the after-queue drains
     *  (its firings are separate transactions and must not see the instance). */
    private fun finalizeDeletes(t: Txn) {
        for (id in t.deleted) {
            val inst = instances.remove(id) ?: continue
            byShape[inst.shape]?.remove(id)
            captures.keys.removeIf { it.first == id }
            keyOf.remove(id)?.let { handleOf.remove(Ref.Persisted(inst.shape, it)) }
            idShape.remove(id)
            pendingDeleted.remove(id)
        }
    }

    /** Fit a value to its declared field type: type the bare `empty` literal, dedupe a
     *  `many` (a set; one that `tolerates duplication` keeps its duplicates and its
     *  order, Reference §5), and read a number or text constant as the field's
     *  extension type (Reference §5; OQ65 D2). Everything else passes through. */
    internal fun coerceCollection(v: Value, type: TypeRef): Value = when {
        v == Value.VEmpty && type is RelType && type.many -> Value.VColl(emptyList(), type.shape)
        v == Value.VEmpty && type is ScalarType && type.many -> Value.VVals(emptyList(), ordered = type.ordered)
        v is Value.VColl -> Value.VColl(v.ids.distinct(), v.shape)
        v is Value.VVals -> {
            val ordered = (type as? ScalarType)?.ordered == true
            val values = if (ordered) v.values else v.values.distinct()
            Value.VVals(values.map { el -> (type as? ScalarType)?.let { evaluator.coerceLiteral(el, model.typeScope[it.name]) } ?: el }, ordered)
        }
        type is ScalarType && !type.many -> evaluator.coerceLiteral(v, model.typeScope[type.name])
        else -> v
    }

    private fun fieldTypeOf(shape: String, field: String): TypeRef? =
        model.shapes[shape]?.members?.filterIsInstance<StoredProp>()?.find { it.name == field }?.type

    // ── firing a rule ────────────────────────────────────────────────────────

    private fun fire(rule: RuleDecl, subject: Long) {
        val scope = conditionScopeName(rule)
        // a rule declared `by` reads the transaction's root actor (Reference §22):
        // present when the root act carried one, absent on the paths V31 made
        // the clause say `?` for
        val ctx = Evaluator.Ctx(scope, subject, actor = rule.by?.let { it.name to txn?.actor })
        val mutations = mutableListOf<Mutation>()
        execute(rule.body, ctx, subject, mutations)
        if (mutations.isNotEmpty()) applyCommit(mutations)
    }

    /**
     * Runs one level of a body: the rule's, or a `for each` block's once per
     * element (Reference §14). An `as` name bound here is visible to the rest
     * of this level and to the blocks inside it, never outside.
     */
    private fun execute(items: List<BodyItem>, start: Evaluator.Ctx, subject: Long, mutations: MutableList<Mutation>) {
        var ctx = start
        for (item in orderedItems(items)) when (item) {
            is ForEach -> {
                // nested for a product; a later source may read an earlier name
                fun iterate(i: Int, c: Evaluator.Ctx) {
                    if (i == item.bindings.size) { execute(item.body, c, subject, mutations); return }
                    val b = item.bindings[i]
                    for (v in loopValues(b.source, c)) iterate(i + 1, c.copy(locals = c.locals + (b.name to v)))
                }
                iterate(0, ctx)
            }
            is Assignment -> {
                // a collection path fans out: one write per member, the same value
                // (Reference §6, "fan-out assignment"); a plain path is one target
                val (targetIds, field) = evaluator.resolveTargets(item.target, ctx)
                val value = evaluator.eval(item.value, ctx)
                for (targetId in targetIds) {
                    val shape = instance(targetId)?.shape
                    val coerced = shape?.let { fieldTypeOf(it, field) }?.let { coerceCollection(value, it) } ?: value
                    mutations.add(Mutation.Assign(targetId, field, coerced))
                }
            }
            is Creation -> {
                val fields = mutableMapOf<String, Value>()
                item.forExpr?.let { forE ->
                    val ref = evaluator.eval(forE, ctx)
                    // the target's shape: read off the instance, or, for a record this
                    // firing creates (`as name`), off the creation that named it
                    val target = when (ref) {
                        is Value.VRef -> (instance(ref.id) ?: throw VelleRuntimeError("missing instance ${ref.id}")).shape
                        is Value.VPendingRef -> (mutations[ref.index] as Mutation.Create).shape
                        else -> throw VelleRuntimeError("'for' target is not an instance")
                    }
                    val matched = model.shapes.getValue(item.shape).members
                        .filterIsInstance<StoredProp>()
                        .single { (it.type as? RelType)?.let { t -> !t.many && model.typeAccepts(t.shape, target) } == true }
                    fields[matched.name] = ref
                }
                item.fields.forEach { f -> fields[f.name] = evaluator.eval(f.value, ctx) }
                // `as name` (Reference §14): later statements reference this record by its
                // index in the creation list; applyCommit resolves the pending reference
                item.alias?.let { ctx = ctx.copy(pending = ctx.pending + (it to mutations.size)) }
                mutations.add(Mutation.Create(item.shape, fields))
            }
            is DeleteStmt -> {
                // the target is a literal static path (OQ37): the subject itself,
                // or a to-one route from it
                val id = if (item.target.root == "this" && item.target.segs.isEmpty()) subject
                else (evaluator.eval(item.target, ctx) as? Value.VRef)?.id
                    ?: throw VelleRuntimeError(
                        "delete target '${Printer.expr(item.target)}' is not a present instance")
                mutations.add(Mutation.Delete(id))
            }
            ThenMarker -> {} // ordering within one commit is a compilation concern
        }
    }

    /** The elements one `for each` name runs over, in the current iteration's context. */
    private fun loopValues(source: LoopSource, ctx: Evaluator.Ctx): List<Value> = when (source) {
        is LoopSource.Over -> evaluator.collectionValues(source.expr, ctx)
        is LoopSource.Range -> {
            fun bound(e: Expr): Long {
                val n = (evaluator.eval(e, ctx) as? Value.VNum)?.v
                    ?: throw VelleRuntimeError("range bound '${Printer.expr(e)}' is not a number")
                if (n.stripTrailingZeros().scale() > 0)
                    throw VelleRuntimeError("range bound '${Printer.expr(e)}' is $n, not a whole number")
                return n.toLong()
            }
            val from = bound(source.from); val to = bound(source.to)
            if (to < from) emptyList() else (from..to).map { Value.VNum(java.math.BigDecimal(it)) }
        }
    }

    /**
     * One level of a body in an order every `as` name bound at that level is
     * bound before it is read (Reference §15: a read orders the two statements).
     * The validator refused cycles (V33), so a stable topological order exists;
     * statements without such reads keep their written order. A block counts as
     * reading every name its statements read.
     */
    private fun orderedItems(items: List<BodyItem>): List<BodyItem> {
        val creations = items.filterIsInstance<Creation>().filter { it.alias != null }
        if (creations.isEmpty()) return items
        val names = creations.map { it.alias!! }.toSet()
        fun reads(item: BodyItem): Set<String> = when (item) {
            is Creation -> (item.fields.map { it.value } + listOfNotNull(item.forExpr)).flatMap { rootsOf(it) }.filter { it in names }.toSet()
            is ForEach -> item.body.flatMap { reads(it) }.toSet()
            else -> emptySet()
        }
        val out = mutableListOf<BodyItem>()
        val placed = mutableSetOf<BodyItem>()
        fun place(item: BodyItem) {
            if (item in placed) return
            placed.add(item)
            for (dep in reads(item)) place(creations.first { it.alias == dep })
            out.add(item)
        }
        items.forEach { place(it) }
        return out
    }

    private fun rootsOf(e: Expr): List<String> {
        val out = mutableListOf<String>()
        fun walk(x: Expr) {
            when (x) {
                is PathExpr -> out.add(x.root)
                is Binary -> { walk(x.left); walk(x.right) }
                is UnaryMinus -> walk(x.inner)
                is NotExpr -> walk(x.inner)
                is IfExpr -> { walk(x.condition); walk(x.thenExpr); walk(x.elseExpr) }
                is IsExpr -> walk(x.subject)
                is Access -> walk(x.target)
                is FunCall -> x.args.forEach { walk(it) }
                is ExistsExpr -> { x.forExpr?.let { walk(it) }; x.collection?.let { c -> c.bindings.forEach { walk(it.source) }; c.where?.let { walk(it) } } }
                is AggCall -> { x.collection.bindings.forEach { walk(it.source) }; x.collection.where?.let { walk(it) } }
                is SingularFor -> walk(x.forExpr)
                is ShapeForSource -> walk(x.forExpr)
                is SetExpr -> { x.collection.bindings.forEach { walk(it.source) }; x.collection.where?.let { walk(it) } }
                else -> {}
            }
        }
        walk(e)
        return out
    }

    internal fun conditionScopeName(rule: RuleDecl): String {
        val c = rule.condition
        return if (c is RefName && (c.name in model.shapes || c.name in model.refinements)) c.name
        else model.baseOfExpr(c) ?: throw VelleRuntimeError("unresolvable condition for '${rule.name}'")
    }

    // ── ticks (evaluation.md, "Ticks") ───────────────────────────────────────

    /**
     * Runs every rule on [schedule] over its current members, each firing its
     * own transaction. A firing a `never` rejects has no caller to hand the
     * refusal to, so when the sweep is done the tick throws [TickRejected]
     * carrying each firing's [Rejection] — the same object an act's commit
     * function returns in [CommitResult.Refused] (`evaluation.md`, "Ticks"). The other firings
     * stand; the next tick retries the rejected ones.
     */
    fun tick(schedule: String) = scope { tickInScope(schedule) }

    private fun tickInScope(schedule: String) {
        // a tick is a new evaluation moment: select subjects against settled
        // storage, not the previous envelope's leftover working set
        beginSnapshot()
        val previous = lastTick[schedule] ?: startInstant
        val rejected = mutableListOf<Rejection>()
        for (rule in model.rules.values) {
            if (schedule !in rule.triggers) continue
            // a tick deletes nothing: the deletion-side forms are transaction-bound
            // and the validator refuses them a schedule (V28)
            if (rule.trigger == Trigger.DELETED) continue
            val subjects: List<Long> =
                if (rule.leaving) {
                    // leavers at the tick commit itself: member under the previous
                    // tick's clock, not a member now
                    val base = model.baseOfExpr(rule.condition) ?: continue
                    idsOf(base).filter {
                        membershipAt(previous, it, rule.condition) &&
                            !evaluator.memberOfRefExpr(it, rule.condition)
                    }
                } else {
                    // subject selection runs against settled state (each firing is
                    // its own later transaction), so the pre-filter is sound with
                    // no self-containment caveat: the resolver's candidate superset
                    // is re-checked by the authoritative predicate right here
                    val base = model.baseOfExpr(rule.condition) ?: continue
                    idsOfCandidates(base, queryCompiler().filterFor(rule.condition))
                        .filter { evaluator.memberOfRefExpr(it, rule.condition) }
                }
            for (subject in subjects) {
                // each firing is its own transaction; a straggler blocks only itself
                if (!rule.leaving && !evaluator.memberOfRefExpr(subject, rule.condition)) continue
                val result = inTransaction(keys = { firingKeys(rule, subject) }) {
                    txn!!.firing = rule to subject
                    txn!!.schedule = schedule
                    fire(rule, subject)
                }
                result.exceptionOrNull()?.let {
                    if (it is NeverViolation) rejected.add(it.rejection)
                    else failures.add("tick($schedule) firing of '${rule.name}' failed: ${it.message}")
                }
            }
        }
        lastTick[schedule] = now
        if (rejected.isNotEmpty()) throw TickRejected(schedule, now, rejected)
    }

    private fun membershipAt(time: Instant, id: Long, condition: RefExpr): Boolean {
        evalTime = time
        try { return evaluator.memberOfRefExpr(id, condition) } finally { evalTime = null }
    }

    // ── never enforcement at transaction end [S5] ────────────────────────────

    /** Never targets with their read summaries, index-aligned with model.nevers. */
    private val neverWatchers: List<Watcher?> by lazy {
        model.nevers.map { n ->
            model.basesOfExpr(n.target).takeIf { it.isNotEmpty() }?.let { bases -> watcherOf("never", n.target, bases) }
        }
    }

    /**
     * A `never` constrains every transaction's *final* state (Reference §21), and
     * state changes only through commits — so a prohibition reading nothing this
     * envelope wrote (and not reading the clock, which moves between envelopes)
     * held at the last transaction end and still holds. The relevance test is
     * [relevant] over the envelope's cumulative footprint, plus time reads.
     *
     * With [door] set this is the act's door instead: only the door set, only
     * over the act's own commit, no time bypass (time never makes a commit
     * relevant). At the end of an act's transaction the door set is skipped —
     * it was evaluated at the door and no rule can have moved it — except a
     * clock-reading constraint, which V30 refuses and the backstop still
     * catches here when an unvalidated spec runs.
     */
    private fun checkNevers(t: Txn, door: Footprint? = null) {
        val fp = door ?: Footprint().apply {
            created.addAll(t.createdShapes)
            assigned.addAll(t.assignedFields)
            deleted.addAll(t.deletedShapes)
        }
        for ((i, n) in model.nevers.withIndex()) {
            val w = neverWatchers[i] ?: continue
            if (door != null) {
                if (i !in doorNevers || !relevant(w, fp)) continue
            } else {
                val refusal = w.bases.all { it in model.transients }
                if (t.act != null && i in doorNevers && (refusal || !w.summary.readsTime)) continue
                if (!w.summary.readsTime && !relevant(w, fp)) continue
            }
            val ids = if (w.selfContained) idsOfCandidates(w.bases.single(), queryCompiler().filterFor(n.target))
            else w.bases.flatMap { idsOf(it) }
            val violating = ids.filter { evaluator.memberOfRefExpr(it, n.target) }
            if (violating.isNotEmpty()) throw NeverViolation(rejectionFor(i + 1, n, w, violating, t))
        }
    }

    /**
     * Everything in hand at the refusal, as data (`evaluation.md`, "Errors, refusals, and retry"): the prohibition is
     * re-evaluated over the violating records with the evaluator's trace on,
     * and the records the trace touched are snapshotted. The act's referenced
     * records that the trace read through are the pivots — what the person
     * was acting on.
     */
    private fun rejectionFor(ordinal: Int, n: NeverDecl, w: Watcher, violating: List<Long>, t: Txn): Rejection {
        val steps = mutableListOf<Evaluator.Trace>()
        evaluator.trace = steps
        try { for (id in violating) evaluator.memberOfRefExpr(id, n.target) } finally { evaluator.trace = null }

        val handleOfRef = HashMap<RecordRef, Long>() // the rejection names records by key; the visibility filter below needs the handle back
        fun refOf(id: Long) = refFor(id).let { RecordRef(it.shape, it.key) }.also { handleOfRef[it] = id }
        fun plain(v: Value): Any? = when (v) {
            is Value.VNum -> v.v
            is Value.VText -> v.v
            is Value.VBool -> v.v
            is Value.VDate -> v.v
            is Value.VDateTime -> v.v
            is Value.VUuid -> v.v
            is Value.VTime -> v.v
            is Value.VBytes -> v.v
            is Value.VRef -> if (instance(v.id) != null) refOf(v.id) else null
            is Value.VColl -> v.ids.filter { instance(it) != null }.map { refOf(it) }
            is Value.VVals -> v.values.map { plain(it) }
            is Value.VDuration -> "${v.amount} ${v.unit}"
            is Value.VExt -> v.v // rendered through the type's `display` by Rejection.show
            Value.VEmpty -> emptyList<Any?>()
            Value.VNone, is Value.VPendingRef, is Value.VPendingColl -> null
        }
        fun snapshot(id: Long) = RecordSnapshot(refOf(id), instance(id)!!.fields
            .filterValues { it !is Value.VPendingRef && it !is Value.VPendingColl }.mapValues { (_, v) -> plain(v) })
        fun idsIn(v: Value): List<Long> = when (v) {
            is Value.VRef -> listOf(v.id)
            is Value.VColl -> v.ids
            else -> emptyList()
        }

        val actId = t.act?.takeIf { instance(it) != null }
        val subjectId = t.firing?.second?.takeIf { instance(it) != null }
        val produced = t.created.filter { it != actId && instance(it) != null }
        // what the transaction was about: the act's references, or the firing's
        // subject, its references, and the references of what it produced
        val originRefs = (listOfNotNull(actId, subjectId) + produced)
            .flatMap { instance(it)!!.fields.values.flatMap(::idsIn) }.toSet() + listOfNotNull(subjectId)
        val evaluation = steps.distinct().mapNotNull { step ->
            when (step) {
                is Evaluator.Trace.Read -> instance(step.id)?.let { EvalStep.Read(refOf(step.id), step.member, plain(step.value)) }
                is Evaluator.Trace.Membership -> instance(step.id)?.let { EvalStep.Membership(refOf(step.id), step.refinement, step.member) }
            }
        }
        val touched = steps.flatMap { step ->
            when (step) {
                is Evaluator.Trace.Read -> listOf(step.id) + idsIn(step.value)
                is Evaluator.Trace.Membership -> listOf(step.id)
            }
        }.distinct().filter { instance(it) != null }
        val pivots = touched.filter { it in originRefs && it !in violating && it != subjectId && it !in produced }
        val witnesses = touched.filter {
            it != actId && it != subjectId && it !in produced && it !in violating && it !in pivots
        }

        // a refusal is handed back through the same surface a read is (Reference §22,
        // "The actor"; OQ33 §5.10): the records it carries are projected for the actor
        // as a read would project them — invisible records dropped, hidden fields
        // absent — while the act itself, which the actor committed, stays whole
        val actor = t.actor?.takeIf { model.hasAuthorization }
        fun visible(id: Long) = actor == null || visibleTo(actor, id)
        fun project(id: Long): RecordSnapshot {
            val s = snapshot(id)
            if (actor == null || id == actId) return s
            val hidden = hiddenFields(actor, id)
            return if (hidden.isEmpty()) s else RecordSnapshot(s.ref, s.fields.filterKeys { it !in hidden })
        }
        val shownEvaluation = if (actor == null) evaluation else evaluation.filter { step ->
            when (step) {
                is EvalStep.Read -> handleOfRef.getValue(step.record).let { it == actId || (visible(it) && step.member !in hiddenFields(actor, it)) }
                is EvalStep.Membership -> handleOfRef.getValue(step.record).let { it == actId || visible(it) }
            }
        }

        return Rejection(
            never = ordinal,
            prohibition = Printer.refExpr(n.target),
            declaration = Printer.prohibition(n),
            requirement = n.requirement?.let { Printer.expr(it.predicate) },
            targetShape = w.bases.joinToString(" or "),
            act = actId?.let(::snapshot),
            rule = t.firing?.first?.name,
            schedule = t.schedule,
            subject = subjectId?.takeIf(::visible)?.let(::project),
            produced = produced.filter(::visible).map(::project),
            violating = violating.filter { it == actId || visible(it) }.map(::project),
            pivots = pivots.filter(::visible).map(::project),
            witnesses = witnesses.filter(::visible).map(::project),
            evaluation = shownEvaluation,
            today = if (w.summary.readsTime) effectiveToday.toString() else null,
        )
    }

    // ── visibility (Reference §22, "The actor"; OQ33 §5.7–§5.10) ─────────────
    //
    // Applied by the read surface only: a query with a viewer admits the records
    // the shape's clauses admit, a fetch of an invisible record resolves absent,
    // and a model object is projected with its hidden fields absent. Evaluation
    // never consults any of this — a derived property is calculated over every
    // input whether or not the viewer may see it.

    /** Does one of [c]'s clauses admit [viewer] to the record [id] of [scope]? */
    private fun admits(c: VisibleClause, viewer: Long, scope: String, id: Long): Boolean {
        val shape = c.shape ?: return true
        val v = instance(viewer) ?: return false
        if (v.shape !in model.basesOf(shape)) return false
        if (shape in model.refinements && !evaluator.memberOfRefExpr(viewer, RefName(shape))) return false
        val p = c.where ?: return true
        return evaluator.truthy(evaluator.eval(p, Evaluator.Ctx(scope, id, actor = c.name to viewer)))
    }

    /** Is the record [id] returned to [viewer]? The engineer's own view (null) sees everything. */
    fun visibleTo(viewer: Long?, id: Long): Boolean {
        if (viewer == null) return true
        val inst = instance(id) ?: return false
        val clauses = model.recordVisibility(inst.shape)
        if (clauses.isEmpty()) return true
        return clauses.any { admits(it, viewer, inst.shape, id) }
    }

    /** The members of [id], read as [scope], that [viewer] may not see: a field with
     *  clauses none of which admit the viewer. Empty for the engineer's own view. */
    fun hiddenFields(viewer: Long?, id: Long, scope: String? = null): Set<String> {
        if (viewer == null) return emptySet()
        val inst = instance(id) ?: return emptySet()
        val sc = scope ?: inst.shape
        return model.membersOf(sc).values
            .filter { m -> m.visible.isNotEmpty() && m.visible.none { admits(it, viewer, sc, id) } }
            .map { it.name }.toSet()
    }

    /** The record behind [id] is current and returned to [viewer]: the fetch half of a viewer's read. */
    fun visibleCurrent(viewer: Long?, id: Long): Boolean {
        openReadSnapshot()
        if (id in pendingDeleted) return false
        return instance(id) != null && visibleTo(viewer, id)
    }

    // ── the read protocol (Reference §22, the composed query; Read.kt) ──────────

    private val queries by lazy { QueryEngine(this) }

    /**
     * A read is a read-only snapshot (evaluation.md): outside a transaction it
     * starts from a fresh snapshot of settled state, so a query never reads an
     * envelope's working set left over from the last commit. Inside one (a
     * query issued from engineer code while a commit runs) it reads the
     * envelope's state, like any read there.
     */
    internal fun openReadSnapshot() { if (txn == null) beginSnapshot() }

    /** The hidden tie-break (R2): the store key when the row has one, else the session handle. */
    @Suppress("UNCHECKED_CAST")
    internal fun orderKeyOf(id: Long): Comparable<Any> =
        (keyOf[id]?.value as? Comparable<Any>) ?: (id as Comparable<Any>)

    /**
     * The reference an engineer holds for [id] (Reference §22): the row's store key.
     * A row the open envelope is still creating has no key yet; its handle stands
     * in, which only a rejection's own rendering ever reads.
     */
    fun refFor(id: Long): Ref.Persisted {
        val shape = idShape[id] ?: instance(id)?.shape ?: throw IllegalArgumentException("no instance $id")
        return Ref.Persisted(shape, keyOf[id] ?: StoreKey(id))
    }

    /** The session handle behind a reference, minted for this thread's session. */
    fun idOf(ref: Keyed): Long = handleForRef(Ref.Persisted(ref.shape, ref.key))

    /** The concrete shape of a current instance, from a fresh snapshot outside a transaction; null when gone. */
    fun currentShapeOf(id: Long): String? {
        openReadSnapshot()
        if (id in pendingDeleted) return null
        return instance(id)?.shape
    }

    /**
     * Fetch by identity (Reference §22): true when [id] is a current instance of
     * [root] — a shape, or a refinement it is a member of — read from a fresh
     * snapshot outside a transaction, so the data class built next is one
     * consistent read.
     */
    fun exists(root: String, id: Long): Boolean {
        openReadSnapshot()
        val inst = instance(id) ?: return false
        if (id in pendingDeleted) return false
        return when {
            root in model.shapes -> inst.shape == root
            root in model.refinements -> evaluator.memberOfRefExpr(id, RefName(root))
            else -> throw IllegalArgumentException("unknown shape or refinement '$root'")
        }
    }

    /** The query's diagnostics without running it; empty means it is well-formed. */
    fun check(q: Query): List<Diagnostic> = queries.check(q)

    /** Answers a query: handles in the requested order, plus the next page's cursor. Throws [QueryRefused]. */
    fun query(q: Query): QueryResult = queries.run(q)

    fun count(q: Query): Int = queries.count(q)

    fun sum(q: Query, member: String): BigDecimal = queries.sum(q, member)
    fun sumValue(q: Query, member: String): Value = queries.sumValue(q, member)

    // ── query surface (read-only; the typed-accessor codegen wraps this) ─────

    fun instancesOf(name: String): List<Long> = when {
        name in model.shapes -> idsOf(name)
        name in model.refinements -> {
            val bases = model.basesOf(name)
            val ids = if (bases.size == 1) idsOfCandidates(bases.single(), queryCompiler().filterFor(RefName(name)))
            else bases.flatMap { idsOf(it) } // a cross-base union: every operand's instances (Reference §9)
            ids.filter { evaluator.memberOfRefExpr(it, RefName(name)) }
        }
        else -> throw IllegalArgumentException("unknown shape or refinement '$name'")
    }

    fun isMember(id: Long, refinement: String): Boolean =
        evaluator.memberOfRefExpr(id, RefName(refinement))

    /** The concrete base shape of an instance — which operand a union-typed reference points at (Reference §9). */
    fun shapeOf(id: Long): String =
        instance(id)?.shape ?: throw IllegalArgumentException("no instance $id")

    /** Read a field — stored, derived, timestamp, capture, or inverse collection — unwrapped. */
    fun get(id: Long, field: String): Any? {
        val inst = instance(id) ?: throw IllegalArgumentException("no instance $id")
        return unwrap(evaluator.readMember(id, inst.shape, field))
    }

    /** Read a refinement-scoped member (a capture or refinement-derived property). */
    fun getAs(id: Long, refinement: String, field: String): Any? =
        unwrap(evaluator.readMember(id, refinement, field))

    private fun unwrap(v: Value): Any? = when (v) {
        is Value.VNum -> v.v
        is Value.VText -> v.v
        is Value.VBool -> v.v
        is Value.VDate -> v.v
        is Value.VDateTime -> v.v
        is Value.VRef -> v.id
        is Value.VColl -> v.ids
        is Value.VVals -> v.values.map { unwrap(it) }
        Value.VEmpty -> emptyList<Any?>()
        is Value.VDuration -> v.toJava()
        is Value.VUuid -> v.v
        is Value.VTime -> v.v
        is Value.VBytes -> v.v
        is Value.VExt -> v.v
        is Value.VPendingRef, is Value.VPendingColl -> throw VelleRuntimeError("pending closure reference escaped its commit")
        Value.VNone -> null
    }
}
