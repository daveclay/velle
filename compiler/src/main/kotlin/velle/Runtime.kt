package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Runtime values. All numerics normalize to BigDecimal — a spike simplification. */
sealed interface Value {
    data class VNum(val v: BigDecimal) : Value
    data class VText(val v: String) : Value
    data class VBool(val v: Boolean) : Value
    data class VDate(val v: LocalDate) : Value
    data class VDateTime(val v: Instant) : Value
    data class VRef(val id: Long) : Value
    data class VColl(val ids: List<Long>, val shape: String) : Value
    /** An owned collection of scalar values (`many text`) — a set (README §6). */
    data class VVals(val values: List<Value>) : Value
    /** The `empty` literal before its collection kind is known; coerced at the write site. */
    data object VEmpty : Value
    data class VDuration(val amount: Long, val unit: String) : Value
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
    /** [id] is the session-local handle (what typed views wrap); [storeKey] is
     *  the store-assigned row key when a store is connected and the act
     *  persisted — what the application's own queries key on. */
    data class Accepted(val id: Long, val storeKey: Any? = null) : CommitResult
    data class Refused(val reason: String) : CommitResult
}

/**
 * The common face of every generated typed view (README §5: identity as a
 * readable value): a view is an instance reference the typed surface wraps.
 */
interface View {
    val id: Long
}

class VelleRuntimeError(message: String) : Exception(message)
private class NeverViolation(val reason: String) : Exception(reason)

/**
 * The v0 runtime: evaluation.md executed over in-memory state [S1].
 * The harness surface: [commit], [tick], [advance]/[setTime], and the query
 * methods — the engine under the typed accessors codegen will wrap.
 */
class VelleSystem(
    val model: Model,
    startTime: Instant = Instant.parse("2026-01-01T09:00:00Z"),
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    var now: Instant = startTime
        private set

    /** membershipAt() briefly evaluates against a past clock (tick-exit detection) */
    internal var evalTime: Instant? = null
    internal val effectiveNow: Instant get() = evalTime ?: now
    internal val effectiveToday: LocalDate get() = effectiveNow.atZone(zone).toLocalDate()

    internal val instances = LinkedHashMap<Long, Instance>()
    internal val byShape = HashMap<String, MutableList<Long>>()
    /** (instance id, refinement name) → captured values for the current membership */
    internal val captures = HashMap<Pair<Long, String>, Map<String, Value>>()

    private var nextId = 1L

    // ── hydration (investigate_runtime.md §2–3; Hydration.kt) ────────────────
    // With a resolver connected, [instances]/[byShape] become the per-envelope
    // working set over engineer-owned storage: each transaction opens a fresh
    // snapshot, faults state in on demand (memoized for the envelope), and hands
    // its mutation set to [onCommit] inside the envelope. Without one, they are
    // the whole state, as before [S1].

    private var resolver: StateResolver? = null
    private var onCommit: CommitCallback? = null
    /** id → shape, so a bare VRef id can be fetched from per-shape storage.
     *  An index, not state: populated at creation, hydration, and reference
     *  conversion; survives snapshot clears. */
    private val idShape = HashMap<Long, String>()

    // Identity is the store's (investigate_runtime.md §8): rows are keyed by
    // whatever the store assigned, and the runtime's instance ids are
    // session-local handles that never persist. This bimap is the translation
    // at the boundary; like idShape it is an index, surviving snapshot clears.
    // Keys are only unique per shape (a table's rowids restart at 1), so the
    // handle side keys on the full typed ref, never the bare key.
    private val keyOf = HashMap<Long, StoreKey>()
    private val handleOf = HashMap<Ref.Persisted, Long>()

    private val fetchedAll = mutableSetOf<String>()
    private val fetchedRefs = mutableSetOf<Triple<String, String, Long>>()
    private val fetchedFilters = mutableSetOf<Pair<String, QF>>()

    fun connect(resolver: StateResolver, onCommit: CommitCallback) {
        this.resolver = resolver
        this.onCommit = onCommit
    }

    /** The session-local handle for a store row — how an application enters
     *  Velle from its own storage side (a key it read with its own SQL). */
    fun handleFor(shape: String, key: Any): Long = handleForRef(Ref.Persisted(shape, StoreKey(key)))

    private fun handleForRef(ref: Ref.Persisted): Long {
        handleOf[ref]?.let { return it }
        val h = nextId++
        handleOf[ref] = h
        keyOf[h] = ref.key
        idShape[h] = ref.shape
        return h
    }

    /** Each transaction evaluates against one consistent snapshot: drop the
     *  previous envelope's hydrated state so storage is re-consulted. Captures
     *  included — with a resolver connected they are a cache over the store's
     *  capture channel (investigate_runtime.md §7), hydrated on demand like
     *  rows; without one, the map is the store and survives. */
    private fun beginSnapshot() {
        if (resolver == null) return
        instances.clear()
        byShape.clear()
        fetchedAll.clear()
        fetchedRefs.clear()
        fetchedFilters.clear()
        captures.clear()
        captureMisses.clear()
    }

    private val captureMisses = mutableSetOf<Pair<Long, String>>()

    /**
     * Capture read, faulting in from the resolver's capture channel when the
     * envelope hasn't seen this membership yet. Null means no current
     * membership anywhere — the caller's "read outside membership" error path.
     */
    internal fun captureValues(id: Long, refinement: String): Map<String, Value>? {
        captures[id to refinement]?.let { return it }
        val r = resolver ?: return null
        if ((id to refinement) in captureMisses) return null
        val key = keyOf[id] ?: return null // unpersisted: no membership storage could hold
        val shape = idShape[id] ?: model.baseOf(refinement) ?: return null
        val raw = r.fetchCaptures(Ref.Persisted(shape, key), refinement)
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
        val r = resolver ?: return null
        val shape = idShape[id] ?: return null
        val key = keyOf[id] ?: return null // no key: never persisted (rolled back, or another session's handle)
        val row = r.fetchByKey(shape, key) ?: return null
        return hydrateIfAbsent(row)
    }

    /** Scan read: every current instance of [shape], faulting in the full set once per envelope. */
    internal fun idsOf(shape: String): List<Long> {
        val r = resolver
        if (r != null && shape !in model.transients && fetchedAll.add(shape))
            r.fetchAll(shape).forEach { hydrateIfAbsent(it) }
        return byShape[shape].orEmpty().toList()
    }

    /**
     * Filtered scan read: candidates of a condition over [shape], faulting in the
     * resolver's candidate superset for [filter] instead of the whole table. The
     * return is everything of [shape] in the working set — candidates unioned
     * with rows this envelope already touched — and the caller re-checks the
     * authoritative predicate in memory on all of it (investigate_runtime.md §2:
     * the filter is a pre-filter, never the evaluation).
     */
    internal fun idsOfCandidates(shape: String, filter: QF): List<Long> {
        val r = resolver
        if (r == null || shape in model.transients || shape in fetchedAll || filter == QF.True)
            return idsOf(shape)
        if (fetchedFilters.add(shape to filter))
            r.fetchCandidates(shape, filter).forEach { hydrateIfAbsent(it) }
        return byShape[shape].orEmpty().toList()
    }

    /** Filters fold `today`/`now` to constants, so a compiler is built per evaluation moment. */
    private fun queryCompiler() = QueryCompiler(model, effectiveToday, effectiveNow)

    /** Join read: fault in the instances of [shape] whose [field] references [targetId]. */
    internal fun ensureReferencing(shape: String, field: String, targetId: Long) {
        val r = resolver ?: return
        if (shape in model.transients || shape in fetchedAll) return
        // an unpersisted target (created this envelope) can't be referenced by
        // anything storage holds — nothing to fetch
        val key = keyOf[targetId] ?: return
        val targetShape = idShape[targetId] ?: return
        if (fetchedRefs.add(Triple(shape, field, targetId)))
            r.fetchReferencing(shape, field, Ref.Persisted(targetShape, key)).forEach { hydrateIfAbsent(it) }
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
                else -> continue
            }
            val raw = row.fields[name] ?: continue
            fields[name] = rawToValue(raw, vtype, "${row.shape}.$name")
        }
        val inst = Instance(handle, row.shape, handle, fields)
        instances[handle] = inst
        byShape.getOrPut(row.shape) { mutableListOf() }.add(handle)
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
                .map { el -> rawToValue(el ?: throw VelleRuntimeError("hydrating $at: null element"), scalarVType(t.name), at) }
        )
        else -> throw VelleRuntimeError("hydrating $at: unsupported type for $raw")
    }

    private fun scalarVType(name: String): VType = when (name) {
        "text" -> VType.Text
        "boolean" -> VType.Bool
        "Date" -> VType.DateT
        "DateTime" -> VType.DateTimeT
        else -> VType.Num(name)
    }
    private val lastTick = HashMap<String, Instant>()
    private val startInstant = startTime
    val evaluator = Evaluator(this)
    /** failures of after-commit / tick firings — the act stands, the gap is recorded [S4] */
    val failures = mutableListOf<String>()

    private val maxDepth = 500 // [S3] backstop behind the static quiescence proof

    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    fun advanceDays(days: Long) { now = now.plusSeconds(days * 86_400) }
    fun setTime(t: Instant) { now = t }

    // ── transactions ─────────────────────────────────────────────────────────

    private class Txn {
        val created = mutableListOf<Long>()
        val oldValues = mutableListOf<Triple<Long, String, Value?>>()
        val afterQueue = mutableListOf<Pair<RuleDecl, Long>>()
        var depth = 0

        /** Cumulative footprint of every commit in the envelope (never gating). */
        val createdShapes = mutableSetOf<String>()
        val assignedFields = mutableSetOf<Pair<String, String>>()

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

    private var txn: Txn? = null

    private fun <T> inTransaction(body: () -> T): Result<T> {
        check(txn == null) { "nested harness transaction" }
        beginSnapshot()
        val t = Txn()
        txn = t
        return try {
            val out = body()
            checkNevers(t)
            // the commit callback runs inside the envelope: its writes join the
            // engineer's storage transaction, and a failure rolls the whole
            // commit back (investigate_runtime.md §3, in-envelope failure).
            // The store assigns row identity and reports it back (§8) — the
            // returned keys bind this session's handles to storage.
            onCommit?.let { cb ->
                val pending = buildCommitSet(t)
                val set = pending.set
                if (set.created.isNotEmpty() || set.assigned.isNotEmpty() ||
                    set.captured.isNotEmpty() || set.retracted.isNotEmpty()
                ) {
                    val keys = cb.onCommit(set)
                    if (keys.size != set.created.size)
                        throw VelleRuntimeError(
                            "commit callback returned ${keys.size} keys for ${set.created.size} created rows"
                        )
                    pending.createdHandles.forEachIndexed { i, h ->
                        keyOf[h] = keys[i]
                        handleOf[Ref.Persisted(idShape.getValue(h), keys[i])] = h
                    }
                }
            }
            txn = null
            drainAfterQueue(t)
            Result.success(out)
        } catch (e: Exception) {
            rollback(t)
            txn = null
            Result.failure(e)
        }
    }

    private class PendingCommit(val set: CommitSet, val createdHandles: List<Long>)

    /** The transaction's whole mutation set — creates with their final fields
     *  (initially/generator/timestamp values included) in creation order, so
     *  pending refs point backward; assigns collapsed to the final value per
     *  (instance, field). References cross as typed [Ref]s: persisted rows by
     *  their store key, same-set creates as [Ref.Pending] indices. Transient
     *  acts are excluded: only their consequences persist (README §4). */
    private fun buildCommitSet(t: Txn): PendingCommit {
        val createdSet = t.created.toSet()
        val createdHandles = t.created.filter { instances.getValue(it).shape !in model.transients }
        val indexOfHandle = createdHandles.withIndex().associate { (i, h) -> h to i }

        fun refOf(handle: Long): Ref {
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
                    else -> {}
                }
            }
            CommitSet.Creation(inst.shape, fields)
        }
        val assigned = t.oldValues.asSequence()
            .map { (id, field, _) -> id to field }
            .distinct()
            .filter { (id, _) -> id !in createdSet }
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
            val values = captures[id to refName]
            if (values != null)
                capturedOps.add(CommitSet.Capture(refOf(id), refName, values.mapValues { storeValue(it.value) }))
            else {
                val key = keyOf[id] ?: continue
                retractedOps.add(CommitSet.Retraction(Ref.Persisted(idShape.getValue(id), key), refName))
            }
        }
        return PendingCommit(CommitSet(created, assigned, capturedOps, retractedOps), createdHandles)
    }

    private fun rollback(t: Txn) {
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
            val result = inTransaction { fire(rule, subject) }
            result.exceptionOrNull()?.let {
                failures.add("after-commit firing of '${rule.name}' failed: ${it.message}")
            }
        }
    }

    // ── the exposed commit surface ────────────────────────────────────────────

    fun commit(shape: String, suppliedFields: Map<String, Any?>): CommitResult {
        val decl = model.shapes[shape]
            ?: return CommitResult.Refused("type: unknown shape '$shape'")
        if (shape !in model.exposed)
            return CommitResult.Refused("type: shape '$shape' is not exposed — it enters state only as a rule's effect")

        // a committed collection is a set: a duplicate is a caller bug or a
        // multiplicity claim `many` cannot express — refused, with the fix named
        // (multiplicity that matters is data on an edge shape; README §6)
        for (m in decl.members.filterIsInstance<StoredProp>()) {
            val isMany = (m.type as? RelType)?.many == true || (m.type as? ScalarType)?.many == true
            if (!isMany) continue
            val raw = suppliedFields[m.name] as? List<*> ?: continue
            if (raw.size != raw.distinct().size)
                return CommitResult.Refused("type: duplicate in '$shape.${m.name}' — a `many` is a set; " +
                    "if multiplicity is meaningful, it is data on an edge shape (README §6)")
        }

        val converted = convertFields(decl, suppliedFields)
            ?: return CommitResult.Refused(typeFailure(decl, suppliedFields))

        val result = inTransaction { applyCommit(listOf(Mutation.Create(shape, converted))).single() }
        return result.fold(
            onSuccess = { id ->
                // a transient act is an input to the state, not a member of it: at
                // the close of its transaction the instance is removed — only its
                // consequences persist (README §4 "Transient acts"; evaluation.md).
                // Removing after the transaction (nevers checked, after-queue
                // drained) is observably equivalent to removing at its close: V17
                // bans every read of the act from anything that runs later.
                if (shape in model.transients) {
                    instances.remove(id)
                    byShape[shape]?.remove(id)
                    captures.keys.removeIf { it.first == id }
                }
                CommitResult.Accepted(id, keyOf[id]?.value)
            },
            onFailure = { e ->
                if (e is NeverViolation) CommitResult.Refused(e.reason) else throw e
            }
        )
    }

    private fun convertFields(decl: ShapeDecl, supplied: Map<String, Any?>): Map<String, Value>? {
        val out = mutableMapOf<String, Value>()
        for (m in decl.members.filterIsInstance<StoredProp>()) {
            val raw = supplied[m.name]
            if (raw == null) {
                // an absent `many` is the empty collection — the absence (README §6)
                when {
                    (m.type as? RelType)?.many == true ->
                        out[m.name] = Value.VColl(emptyList(), (m.type as RelType).shape)
                    (m.type as? ScalarType)?.many == true ->
                        out[m.name] = Value.VVals(emptyList())
                    m.initially == null && (m.type as? ScalarType)?.optional != true &&
                        (m.type as? RelType)?.optional != true -> return null
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
                ?.map { el ->
                    (el as? Long)
                        ?.also { idShape.putIfAbsent(it, type.shape) }
                        ?.takeIf { instance(it) != null } ?: return null
                }
                ?.let { Value.VColl(it, type.shape) }
            else (raw as? Long)
                ?.also { idShape.putIfAbsent(it, type.shape) }
                ?.takeIf { instance(it) != null }
                ?.let { Value.VRef(it) }
        is ScalarType ->
            if (type.many) (raw as? List<*>)
                ?.map { el -> el?.let { convertScalar(it, type.name) } ?: return null }
                ?.let { Value.VVals(it) }
            else convertScalar(raw, type.name)
    }

    private fun convertScalar(raw: Any, name: String): Value? = when (name) {
        "text" -> (raw as? String)?.let { Value.VText(it) }
        "boolean" -> (raw as? Boolean)?.let { Value.VBool(it) }
        "Date" -> (raw as? LocalDate)?.let { Value.VDate(it) }
        "DateTime" -> (raw as? Instant)?.let { Value.VDateTime(it) }
        else -> runCatching { Value.num(raw) }.getOrNull()
    }

    private fun typeFailure(decl: ShapeDecl, supplied: Map<String, Any?>): String {
        val required = decl.members.filterIsInstance<StoredProp>()
            .filter { it.initially == null }
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
        val base: String,
        val summary: ReadSummary,
        val sensShapes: Set<String>,
        val selfContained: Boolean,
    )

    private fun watcherOf(key: String, condition: RefExpr, base: String): Watcher {
        val s = model.summaryOfRefExpr(condition)
        val sens = (s.existsShapes + s.collShapes).mapNotNull { model.baseOf(it) }.toSet()
        val selfContained = !s.opaque && sens.isEmpty() &&
            (s.fields + s.collFields).all { it.first == base }
        return Watcher(key, condition, base, s, sens, selfContained)
    }

    /** Watched conditions: every commit-triggered rule plus capture-carrying
     *  refinements. Schedule-only rules are deliberately absent — their subjects
     *  come from the tick's own member scan, never from a commit diff (§16/§17). */
    private val watchers: List<Watcher> by lazy {
        val ws = mutableListOf<Watcher>()
        for (r in model.rules.values) {
            if (!(r.preposition == null || "commit" in r.triggers)) continue
            val base = model.baseOfExpr(r.condition) ?: continue
            ws.add(watcherOf("rule:${r.name}", r.condition, base))
        }
        for ((name, r) in model.refinements) {
            if (r.members.any { it is DerivedProp && it.captured }) {
                val base = model.baseOf(name) ?: continue
                ws.add(watcherOf("capture:$name", RefName(name), base))
            }
        }
        ws
    }

    private val watcherByKey: Map<String, Watcher> by lazy { watchers.associateBy { it.key } }

    /** The mutation footprint of one commit: what it creates and writes. */
    private class Footprint {
        val created = mutableSetOf<String>()
        val assigned = mutableSetOf<Pair<String, String>>()
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
        }
        return f
    }

    /**
     * Can this commit change the watcher's member set? The runtime sibling of
     * the compiler's derived trigger set (README §11, "Rules ground in
     * commits"): membership flips only when data the predicate reads changes,
     * and the footprint says exactly what changed. `today`/`now` are constant
     * within an envelope, so time reads never make a commit relevant here —
     * entry by aging belongs to ticks.
     */
    private fun relevant(w: Watcher, fp: Footprint): Boolean {
        if (w.summary.opaque) return true
        if (fp.created.any { it == w.base || it in w.sensShapes }) return true
        return fp.assigned.any { (sh, f) ->
            sh in w.sensShapes || (sh to f) in w.summary.fields || (sh to f) in w.summary.collFields
        }
    }

    private fun memberSet(w: Watcher): Set<Long> {
        val ids = if (w.selfContained) idsOfCandidates(w.base, queryCompiler().filterFor(w.condition))
        else idsOf(w.base)
        return ids.filter { evaluator.memberOfRefExpr(it, w.condition) }.toSet()
    }

    private fun applyCommit(mutations: List<Mutation>): List<Long> {
        val t = txn ?: error("commit outside transaction")
        if (t.depth++ > maxDepth)
            throw VelleRuntimeError("cascade depth exceeded $maxDepth — quiescence backstop [S3]")

        val fp = footprintOf(mutations)
        t.createdShapes.addAll(fp.created)
        t.assignedFields.addAll(fp.assigned)
        // only watchers this commit's footprint can affect get their member
        // sets evaluated — for the rest, no membership can have flipped
        val active = watchers.filter { relevant(it, fp) }
        val pre = active.associateWith { memberSet(it) }

        val createdIds = mutableListOf<Long>()
        var wroteStored = mutableSetOf<Long>()
        for (m in mutations) when (m) {
            is Mutation.Create -> createdIds.add(applyCreate(m, t))
            is Mutation.Assign -> { applyAssign(m, t, pre); wroteStored.add(m.id) }
        }
        // `on update` timestamps advance at every commit writing a stored field
        for (id in wroteStored) {
            val inst = instances[id] ?: continue
            model.shapes[inst.shape]?.members?.filterIsInstance<TimestampProp>()
                ?.filter { it.on == "update" }
                ?.forEach { setField(inst, it.name, Value.VDateTime(now), t) }
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

        // firings: entrants for `when R`, leavers for `when leaving R` — the
        // whole firing set is pinned from this commit's pre/post diff before
        // any sibling runs (evaluation.md step 5), then executed in an order
        // the spec must not observe (step 6, [S3])
        val pending = mutableListOf<Pair<RuleDecl, Long>>()
        for (rule in model.rules.values) {
            if (!(rule.preposition == null || "commit" in rule.triggers)) continue
            val w = watcherByKey["rule:${rule.name}"] ?: continue
            if (w !in active) continue // this commit provably cannot flip its condition
            var subjects =
                if (rule.leaving) pre.getValue(w) - post.getValue(w)
                else post.getValue(w) - pre.getValue(w)
            // a transient act's partitions are decided exactly once, at its
            // creation commit — consequence commits within the transaction
            // never re-partition it (README §4, "Transient acts")
            if (w.base in model.transients) subjects = subjects intersect createdIds.toSet()
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
        val id = nextId++
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
                // an unsupplied `many` starts empty — the empty collection is the absence (README §6)
                coerceCollection(Value.VEmpty, p.type).takeIf { it != Value.VEmpty }
                    ?.let { inst.fields[p.name] = it }
                continue
            }
            inst.fields[p.name] =
                if (p.initially == PathExpr("randomUUID")) Value.VText(UUID.randomUUID().toString())
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

    /** Fit a collection value to its declared field type: type the bare `empty`
     *  literal, and dedupe — a `many` is a set (README §6). Non-collections pass through. */
    internal fun coerceCollection(v: Value, type: TypeRef): Value = when {
        v == Value.VEmpty && type is RelType && type.many -> Value.VColl(emptyList(), type.shape)
        v == Value.VEmpty && type is ScalarType && type.many -> Value.VVals(emptyList())
        v is Value.VColl -> Value.VColl(v.ids.distinct(), v.shape)
        v is Value.VVals -> Value.VVals(v.values.distinct())
        else -> v
    }

    private fun fieldTypeOf(shape: String, field: String): TypeRef? =
        model.shapes[shape]?.members?.filterIsInstance<StoredProp>()?.find { it.name == field }?.type

    // ── firing a rule ────────────────────────────────────────────────────────

    private fun fire(rule: RuleDecl, subject: Long) {
        val scope = conditionScopeName(rule)
        val ctx = Evaluator.Ctx(scope, subject)
        val mutations = mutableListOf<Mutation>()
        for (item in rule.body) when (item) {
            is Assignment -> {
                // a collection path fans out: one write per member, the same value
                // (README §6, "fan-out assignment"); a plain path is one target
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
                    val ref = evaluator.eval(forE, ctx) as? Value.VRef
                        ?: throw VelleRuntimeError("'for' target is not an instance")
                    val target = (instance(ref.id) ?: throw VelleRuntimeError("missing instance ${ref.id}")).shape
                    val matched = model.shapes.getValue(item.shape).members
                        .filterIsInstance<StoredProp>()
                        .single { (it.type as? RelType)?.let { t -> !t.many && t.shape == target } == true }
                    fields[matched.name] = ref
                }
                item.fields.forEach { f -> fields[f.name] = evaluator.eval(f.value, ctx) }
                mutations.add(Mutation.Create(item.shape, fields))
            }
            ThenMarker -> {} // ordering within one commit is a compilation concern
        }
        if (mutations.isNotEmpty()) applyCommit(mutations)
    }

    internal fun conditionScopeName(rule: RuleDecl): String {
        val c = rule.condition
        return if (c is RefName && (c.name in model.shapes || c.name in model.refinements)) c.name
        else model.baseOfExpr(c) ?: throw VelleRuntimeError("unresolvable condition for '${rule.name}'")
    }

    // ── ticks (evaluation.md, "Ticks") ───────────────────────────────────────

    fun tick(schedule: String) {
        // a tick is a new evaluation moment: select subjects against settled
        // storage, not the previous envelope's leftover working set
        beginSnapshot()
        val previous = lastTick[schedule] ?: startInstant
        for (rule in model.rules.values) {
            if (schedule !in rule.triggers) continue
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
                val result = inTransaction { fire(rule, subject) }
                result.exceptionOrNull()?.let {
                    failures.add("tick($schedule) firing of '${rule.name}' failed: ${it.message}")
                }
            }
        }
        lastTick[schedule] = now
    }

    private fun membershipAt(time: Instant, id: Long, condition: RefExpr): Boolean {
        evalTime = time
        try { return evaluator.memberOfRefExpr(id, condition) } finally { evalTime = null }
    }

    // ── never enforcement at transaction end [S5] ────────────────────────────

    /** Never targets with their read summaries, index-aligned with model.nevers. */
    private val neverWatchers: List<Watcher?> by lazy {
        model.nevers.map { n ->
            model.baseOfExpr(n.target)?.let { base -> watcherOf("never", n.target, base) }
        }
    }

    /**
     * A `never` constrains every transaction's *final* state (README §21), and
     * state changes only through commits — so an invariant reading nothing this
     * envelope wrote (and not reading the clock, which moves between envelopes)
     * held at the last transaction end and still holds. The relevance test is
     * [relevant] over the envelope's cumulative footprint, plus time reads.
     */
    private fun checkNevers(t: Txn) {
        val fp = Footprint().apply {
            created.addAll(t.createdShapes)
            assigned.addAll(t.assignedFields)
        }
        for ((i, n) in model.nevers.withIndex()) {
            val w = neverWatchers[i] ?: continue
            if (!w.summary.readsTime && !relevant(w, fp)) continue
            val ids = if (w.selfContained) idsOfCandidates(w.base, queryCompiler().filterFor(n.target))
            else idsOf(w.base)
            for (id in ids) {
                if (evaluator.memberOfRefExpr(id, n.target))
                    throw NeverViolation("never #${i + 1} over ${w.base} is violated")
            }
        }
    }

    // ── query surface (read-only; the typed-accessor codegen wraps this) ─────

    fun instancesOf(name: String): List<Long> = when {
        name in model.shapes -> idsOf(name)
        name in model.refinements -> {
            val base = model.baseOf(name) ?: return emptyList()
            idsOfCandidates(base, queryCompiler().filterFor(RefName(name)))
                .filter { evaluator.memberOfRefExpr(it, RefName(name)) }
        }
        else -> throw IllegalArgumentException("unknown shape or refinement '$name'")
    }

    fun isMember(id: Long, refinement: String): Boolean =
        evaluator.memberOfRefExpr(id, RefName(refinement))

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
        is Value.VDuration -> v.amount to v.unit
        Value.VNone -> null
    }
}
