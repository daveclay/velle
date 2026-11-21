package velle

import java.time.Instant

/**
 * What one thread holds between entering the runtime and leaving it: the
 * working set of the unit of work in progress (the rows faulted in, the
 * captures read, the fetches already made), the transaction, and the
 * handle tables that translate the store's keys to this thread's session
 * handles. One per thread, created at first use and dropped when the
 * outermost [VelleSystem.scope] exits, so two callers on two threads share
 * nothing but the store, and memory is bounded by one call's work.
 */
internal class Session {
    val instances = LinkedHashMap<Long, Instance>()
    val byShape = HashMap<String, MutableList<Long>>()
    /** Instances deleted by the current transaction (OQ37): no longer members of
     *  the state — every existence/collection read excludes them from the delete
     *  commit on — but still directly readable by id (the last-reader contract).
     *  Physically removed at transaction close; cleared on rollback. */
    val pendingDeleted = mutableSetOf<Long>()
    /** (instance id, refinement name) → captured values for the current membership */
    val captures = HashMap<Pair<Long, String>, Map<String, Value>>()
    /** Memberships whose capture the store was asked for and does not hold, this snapshot. */
    val captureMisses = mutableSetOf<Pair<Long, String>>()
    /** Shapes whose every row this snapshot has fetched. */
    val fetchedAll = mutableSetOf<String>()
    /** (shape, field, target handle): the rows of a shape referencing a target, already fetched this snapshot. */
    val fetchedRefs = mutableSetOf<Triple<String, String, Long>>()
    /** (shape, filter): the candidate rows already fetched this snapshot. */
    val fetchedFilters = mutableSetOf<Pair<String, QF>>()
    /** Handles whose by-key fetch found no row this envelope — a reference at a
     *  deleted row (Reference §12) is read as absent every time, and one snapshot
     *  answers it once. */
    val fetchMisses = mutableSetOf<Long>()
    /** Handle to the handles it arrived with: the rows one fetch returned, or the references those rows hold in one field. */
    val arrivedWith = HashMap<Long, List<Long>>()

    /** id → shape, so a bare VRef id can be fetched from per-shape storage.
     *  An index, not state: populated at creation, hydration, and reference
     *  conversion; survives snapshot clears. */
    val idShape = HashMap<Long, String>()

    // Identity is the store's (Hydration.kt, Ref/StoreKey): rows are keyed by
    // whatever the store assigned, and the runtime's instance ids are
    // session-local handles that never persist. This bimap is the translation
    // at the boundary; like idShape it is an index, surviving snapshot clears.
    // Keys are only unique per shape (a table's rowids restart at 1), so the
    // handle side keys on the full typed ref, never the bare key.
    val keyOf = HashMap<Long, StoreKey>()
    val handleOf = HashMap<Ref.Persisted, Long>()

    /**
     * The unit of work open against the store (Hydration.kt, [UnitOfWork]): a read
     * unit, opened at the first fetch of a read and closed when the outermost
     * scope exits or an envelope opens; or the write unit of the envelope in
     * progress, opened before the queue keys are valued and closed with the
     * envelope's outcome. Null between units.
     */
    var unitOfWork: UnitOfWork? = null
    /** The envelope in progress on this thread; null between envelopes. */
    var transaction: Transaction? = null
    /** How many runtime entries (scopes and reads) are open on this thread. */
    var depth = 0
    /** How many [VelleSystem.scope] calls are open on this thread. */
    var scopesOpen = 0
    /** A read entry has opened this scope's snapshot, and nothing has emptied the working set since. */
    var scopeSnapshotOpen = false
    /** The clock as the outermost entry found it, or as this thread set it since. */
    var clock: Instant? = null
    /** A past clock a membership is briefly evaluated against (tick-exit detection). */
    var evalTime: Instant? = null

    /** Each transaction evaluates against one consistent snapshot: drop the
     *  previous envelope's hydrated state so storage is re-consulted. Captures
     *  included: they are a cache over the store's capture channel
     *  (Hydration.kt, CommitSet), hydrated on demand like rows. The handle
     *  tables stay. */
    fun beginSnapshot() {
        scopeSnapshotOpen = false
        instances.clear()
        byShape.clear()
        fetchedAll.clear()
        fetchedRefs.clear()
        fetchedFilters.clear()
        fetchMisses.clear()
        arrivedWith.clear()
        captures.clear()
        captureMisses.clear()
    }
}
