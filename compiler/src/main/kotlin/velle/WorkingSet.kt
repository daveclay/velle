package velle

import java.util.concurrent.atomic.AtomicLong

/**
 * The unit of work's working set over the store (Hydration.kt; evaluation.md "The
 * universal transaction"): the records faulted in on demand, memoized for the
 * envelope, and the handle tables that translate the store's keys to this
 * thread's session handles. Every read the evaluator makes of a record, a
 * shape's records, or a capture comes through here, and fetches from the store
 * only what the working set does not already hold.
 */
internal class WorkingSet(private val system: VelleSystem) {
    private val model get() = system.model
    private val session get() = system.session
    private val storedValues = StoredValueReader(system.model) { ref -> handleForRef(ref) }

    /** Never reset: a handle that leaks past its scope fails as "no instance" instead of naming a stranger. */
    private val nextHandle = AtomicLong(1)

    val instances: MutableMap<Long, Instance> get() = session.instances
    val byShape: MutableMap<String, MutableList<Long>> get() = session.byShape
    val pendingDeleted: MutableSet<Long> get() = session.pendingDeleted
    val captures: MutableMap<Pair<Long, String>, Map<String, Value>> get() = session.captures
    val captureMisses: MutableSet<Pair<Long, String>> get() = session.captureMisses
    val idShape: MutableMap<Long, String> get() = session.idShape
    val keyOf: MutableMap<Long, StoreKey> get() = session.keyOf
    val handleOf: MutableMap<Ref.Persisted, Long> get() = session.handleOf

    /** A fresh session handle for a record this envelope creates. */
    fun mintHandle(): Long = nextHandle.getAndIncrement()

    fun beginSnapshot() = session.beginSnapshot()

    /**
     * A read is a read-only snapshot (evaluation.md): outside a transaction it
     * starts from a fresh snapshot of settled state, so a query never reads an
     * envelope's working set left over from the last commit. Every read of one
     * scope is answered from one snapshot: the scope's first read opens it, and
     * the reads after it keep the rows already fetched until a commit, a seed, or
     * a tick empties them. Inside a transaction (a query issued from engineer
     * code while a commit runs) a read reads the envelope's state, like any read
     * there.
     */
    fun openReadSnapshot() {
        val currentSession = session
        if (currentSession.transaction != null || currentSession.scopeSnapshotOpen) return
        currentSession.beginSnapshot()
        currentSession.scopeSnapshotOpen = currentSession.scopesOpen > 0
    }

    // ── handles ──────────────────────────────────────────────────────────────

    fun handleForRef(ref: Ref.Persisted): Long {
        handleOf[ref]?.let { existingHandle -> return existingHandle }
        val handle = mintHandle()
        handleOf[ref] = handle
        keyOf[handle] = ref.key
        idShape[handle] = ref.shape
        return handle
    }

    /** Bind a handle this envelope created to the key the store assigned its row. */
    fun bindStoreKey(handle: Long, key: StoreKey) {
        keyOf[handle] = key
        handleOf[Ref.Persisted(idShape.getValue(handle), key)] = handle
    }

    /** The store's reference for a persisted handle; null for a record not yet in the store. */
    fun persistedRefOf(handle: Long): Ref.Persisted? =
        keyOf[handle]?.let { key -> idShape[handle]?.let { shape -> Ref.Persisted(shape, key) } }

    /** The record a persisted handle names, for a report; null for a record not yet in the store. */
    fun recordRefOf(handle: Long): RecordRef? =
        keyOf[handle]?.let { key -> RecordRef(idShape.getValue(handle), key) }

    /**
     * The reference an engineer holds for [handle] (Reference §22): the row's store key.
     * A row the open envelope is still creating has no key yet; its handle stands
     * in, which only a rejection's own rendering ever reads.
     */
    fun refFor(handle: Long): Ref.Persisted {
        val shape = idShape[handle] ?: instance(handle)?.shape ?: throw IllegalArgumentException("no instance $handle")
        return Ref.Persisted(shape, keyOf[handle] ?: StoreKey(handle))
    }

    /** The hidden tie-break (R2): the store key when the row has one, else the session handle. */
    @Suppress("UNCHECKED_CAST")
    fun orderKeyOf(handle: Long): Comparable<Any> =
        (keyOf[handle]?.value as? Comparable<Any>) ?: (handle as Comparable<Any>)

    // ── reads ────────────────────────────────────────────────────────────────

    /** Membership-facing instance list: [byShape] minus the current transaction's
     *  deletions. Every scan/exists/aggregate read goes through this. */
    fun liveIds(shape: String): List<Long> {
        val ids = byShape[shape].orEmpty()
        return if (pendingDeleted.isEmpty()) ids.toList() else ids.filter { id -> id !in pendingDeleted }
    }

    /** The absorbing-reference read (OQ37-R10): a stored reference at a deleted
     *  instance reads as absent — `is none` true, `?.` propagates — with no
     *  write anywhere; a collection silently loses the member. Applied to
     *  values read out of fields, never to a subject read by id. */
    fun normalizeRead(value: Value): Value = when (value) {
        is Value.VRef -> if (pointsAtARecord(value.id)) value else Value.VNone
        is Value.VColl -> {
            val live = value.ids.filter { memberId -> pointsAtARecord(memberId) }
            if (live.size == value.ids.size) value else Value.VColl(live, value.shape)
        }
        else -> value
    }

    /**
     * Whether a stored reference to [id] still points at a record. A record of a shape no rule
     * deletes (`Model.deletedShapes`) cannot have been deleted, so a reference to one is taken
     * as it stands and its record is not fetched to find out. For a shape some rule deletes,
     * the record is looked for.
     */
    private fun pointsAtARecord(id: Long): Boolean {
        if (id in pendingDeleted) return false
        if (id in instances) return true
        val shape = idShape[id] ?: return false
        if (keyOf[id] == null) return false
        if (shape !in model.deletedShapes) return true
        return instance(id) != null
    }

    /**
     * Capture read, faulting in from the resolver's capture channel when the
     * envelope hasn't seen this membership yet. Null means no current
     * membership anywhere — the caller's "read outside membership" error path.
     */
    fun captureValues(id: Long, refinement: String): Map<String, Value>? {
        val membership = id to refinement
        captures[membership]?.let { cached -> return cached }
        if (membership in captureMisses) return null
        val key = keyOf[id] ?: return null // unpersisted: no membership storage could hold
        val shape = idShape[id] ?: model.baseOf(refinement) ?: return null
        val raw = system.store.reader().fetchCaptures(Ref.Persisted(shape, key), refinement)
        if (raw == null) {
            captureMisses.add(membership)
            return null
        }
        val capturedProps = model.refinements.getValue(refinement).members
            .filterIsInstance<DerivedProp>().filter { prop -> prop.captured }
        val values = capturedProps.associate { prop -> prop.name to captureValue(raw[prop.name], refinement, prop.name) }
        captures[membership] = values
        return values
    }

    private fun captureValue(raw: Any?, refinement: String, propName: String): Value {
        if (raw == null) return Value.VNone
        val type = model.membersOf(refinement).getValue(propName).type
        return storedValues.read(raw, type, "$refinement.$propName (capture)")
    }

    /** By-handle read, faulting in from the resolver via the store's key. */
    fun instance(id: Long): Instance? {
        instances[id]?.let { cached -> return cached }
        val shape = idShape[id] ?: return null
        if (keyOf[id] == null) return null // no key: never persisted (rolled back, or another session's handle)
        if (id in fetchMisses) return null
        val together = arrivedWithNeeding(id, system.fetchTogether.limitFor(shape)) { other ->
            idShape[other] == shape && keyOf[other] != null && other !in instances && other !in fetchMisses
        }
        hydrateTogether(system.store.reader().fetchByKeys(shape, together.map { other -> keyOf.getValue(other) }))
        fetchMisses.addAll(together.filter { other -> other !in instances })
        return instances[id]
    }

    private val fetchMisses: MutableSet<Long> get() = session.fetchMisses

    /**
     * Scan read: every current instance of [shape], faulting in the full set
     * once per envelope. The order is the store's: [byShape] is kept as the
     * rows storage reported, in the order it reported them, ahead of anything
     * this envelope created that storage does not hold yet — so the query
     * surface reads the same order over a store as in memory (creation order,
     * for a store whose scan order is insertion order).
     */
    fun idsOf(shape: String): List<Long> {
        if (shape !in model.transients && session.fetchedAll.add(shape)) {
            val fetched = hydrateTogether(system.store.reader().fetchAll(shape)).map { instance -> instance.id }
            val shapeIds = byShape.getOrPut(shape) { mutableListOf() }
            val fetchedSet = fetched.toSet()
            val (persisted, unpersisted) = shapeIds.filter { id -> id !in fetchedSet }.partition { id -> keyOf[id] != null }
            shapeIds.clear()
            shapeIds.addAll(fetched)
            shapeIds.addAll(persisted)
            shapeIds.addAll(unpersisted)
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
    fun idsOfCandidates(shape: String, filter: QF): List<Long> {
        if (shape in model.transients || shape in session.fetchedAll || filter == QF.True)
            return idsOf(shape)
        if (session.fetchedFilters.add(shape to filter))
            hydrateTogether(system.store.reader().fetchCandidates(shape, filter))
        return liveIds(shape)
    }

    /** Join read: fault in the instances of [shape] whose [field] references [targetId]. */
    fun ensureReferencing(shape: String, field: String, targetId: Long) {
        if (shape in model.transients || shape in session.fetchedAll) return
        // an unpersisted target (created this envelope) can't be referenced by
        // anything storage holds — nothing to fetch
        if (keyOf[targetId] == null) return
        val targetShape = idShape[targetId] ?: return
        val fetchedRefs = session.fetchedRefs
        if (Triple(shape, field, targetId) in fetchedRefs) return
        val together = arrivedWithNeeding(targetId, system.fetchTogether.limitFor(shape)) { other ->
            idShape[other] == targetShape && keyOf[other] != null && Triple(shape, field, other) !in fetchedRefs
        }
        for (other in together) fetchedRefs.add(Triple(shape, field, other))
        val targets = together.map { other -> Ref.Persisted(targetShape, keyOf.getValue(other)) }
        hydrateTogether(system.store.reader().fetchReferencingAny(shape, field, targets))
    }

    // ── hydration ────────────────────────────────────────────────────────────

    /**
     * [id] and the handles that arrived with it for which [needsFetch] holds, [id] first and then
     * in the order they arrived, at most [limit] of them: what one fetch asks for, so the related
     * records of a list are read in one trip to the store and not one trip per record. The handles
     * left out are fetched for when one of them is needed.
     */
    private fun arrivedWithNeeding(id: Long, limit: Int, needsFetch: (Long) -> Boolean): List<Long> {
        val group = session.arrivedWith[id] ?: return listOf(id)
        if (limit <= 1) return listOf(id)
        val position = group.indexOf(id)
        val fromHere = group.subList(position + 1, group.size).asSequence() + group.subList(0, position).asSequence()
        return listOf(id) + fromHere.filter(needsFetch).take(limit - 1).toList()
    }

    /** Hydrates the rows one fetch returned and notes that they, and the references they hold in each field, arrived together. */
    private fun hydrateTogether(rows: List<Row>): List<Instance> {
        val hydrated = rows.map { row -> hydrateIfAbsent(row) }
        if (hydrated.size > 1) {
            val handles = hydrated.map { instance -> instance.id }
            for (handle in handles) session.arrivedWith[handle] = handles
        }
        for (field in hydrated.flatMap { instance -> instance.fields.keys }.distinct()) noteReferencesTogether(hydrated, field)
        return hydrated
    }

    private fun noteReferencesTogether(hydrated: List<Instance>, field: String) {
        val referenced = hydrated.flatMap { instance -> referencedHandles(instance.fields[field]) }.distinct()
        if (referenced.size < 2) return
        for (handle in referenced) if (handle !in instances) session.arrivedWith[handle] = referenced
    }

    private fun hydrateIfAbsent(row: Row): Instance {
        val handle = handleForRef(Ref.Persisted(row.shape, row.key))
        instances[handle]?.let { cached -> return cached }
        val decl = model.shapes[row.shape]
            ?: throw VelleRuntimeError("resolver returned unknown shape '${row.shape}'")
        val fields = mutableMapOf<String, Value>()
        for (member in decl.members) {
            val (name, type) = storedTypeOf(member) ?: continue
            val raw = row.fields[name] ?: continue
            fields[name] = storedValues.read(raw, type, "${row.shape}.$name")
        }
        val instance = Instance(handle, row.shape, handle, fields)
        instances[handle] = instance
        // a hydrated row goes behind the rows already hydrated and ahead of this
        // envelope's unpersisted creations (see idsOf on order)
        val shapeIds = byShape.getOrPut(row.shape) { mutableListOf() }
        val firstUnpersisted = shapeIds.indexOfFirst { id -> keyOf[id] == null }
        if (firstUnpersisted < 0) shapeIds.add(handle) else shapeIds.add(firstUnpersisted, handle)
        return instance
    }

    /** The name and value type of a member a row stores; null for a member no row holds. */
    private fun storedTypeOf(member: Member): Pair<String, VType>? = when (member) {
        is StoredProp -> member.name to model.typeOf(member.type)
        is TimestampProp -> member.name to VType.DateTimeT
        is ActorProp -> member.name to VType.Inst(model.baseOf(member.shape) ?: member.shape)
        else -> null
    }
}
