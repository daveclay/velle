package velle

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * The store a system runs over when no other is connected: rows in maps, keys
 * from a counter, nothing durable. It implements the same two halves of the
 * store contract (Hydration.kt) as a database store, so the runtime has one
 * code path: every system is connected to a store from birth, and what the
 * runtime keeps between calls is this store's rows, never a working set of
 * its own.
 *
 * Units of work: a read unit holds the store's read lock from [begin] to
 * [UnitOfWork.end], so it reads one consistent state and no commit lands
 * halfway through it; readers run beside each other. A write unit takes a
 * lock per queue key in [UnitOfWork.serialize], sorted, so two envelopes
 * sharing a key take turns and two with disjoint keys run together; its
 * reads take the read lock one fetch at a time, and [onCommit] applies the
 * whole set under the write lock, so no reader sees part of it.
 *
 * Keys are one counter across every shape, so a key names one row in the
 * whole store and is never reissued (Hydration.kt, [CommitSet.Deletion]).
 */
class MemoryStore(private val model: Model) : StateResolver, CommitCallback {

    private val rows = HashMap<String, LinkedHashMap<StoreKey, Row>>()
    private val captures = HashMap<Pair<Ref.Persisted, String>, Map<String, Any?>>()
    private var nextKey = 1L

    private val state = ReentrantReadWriteLock()
    private val keyLocks = ConcurrentHashMap<String, ReentrantLock>()

    private inner class Unit(val kind: WorkKind) : UnitOfWork {
        private val held = ArrayList<ReentrantLock>()

        override fun serialize(keys: Set<AdmissionKey>) {
            check(kind == WorkKind.WRITE) { "a read unit of work takes no queue key" }
            for (k in keys.sorted()) {
                val lock = keyLocks.computeIfAbsent(k.text) { ReentrantLock() }
                lock.lock()
                held.add(lock)
            }
        }

        override fun end(outcome: Outcome) {
            for (lock in held.asReversed()) lock.unlock()
            held.clear()
            if (kind == WorkKind.READ) state.readLock().unlock()
        }
    }

    override fun begin(kind: WorkKind): UnitOfWork {
        if (kind == WorkKind.READ) state.readLock().lock()
        return Unit(kind)
    }

    private inline fun <T> reading(body: () -> T): T = state.readLock().withLock(body)

    override fun fetchByKey(shape: String, key: StoreKey): Row? = reading { rows[shape]?.get(key) }

    override fun fetchAll(shape: String): List<Row> = reading { rows[shape]?.values?.toList().orEmpty() }

    override fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row> = reading {
        rows[shape]?.values?.filter { it.fields[field] == target }.orEmpty()
    }

    override fun fetchCaptures(instance: Ref.Persisted, refinement: String): Map<String, Any?>? =
        reading { captures[instance to refinement] }

    /** Every capture the store holds, as (instance, refinement) to values: a test's view of the capture channel. */
    fun allCaptures(): Map<Pair<Ref.Persisted, String>, Map<String, Any?>> = reading { captures.toMap() }

    override fun onCommit(commit: CommitSet): List<StoreKey> = state.writeLock().withLock {
        val minted = ArrayList<StoreKey>(commit.created.size)
        fun landed(v: Any?): Any? = when (v) {
            is Ref.Pending -> Ref.Persisted(v.shape, minted[v.index])
            is List<*> -> v.map { landed(it) }
            else -> v
        }
        for (c in commit.created) {
            val key = StoreKey(nextKey++)
            minted.add(key)
            rows.getOrPut(c.shape) { LinkedHashMap() }[key] = Row(c.shape, key, c.fields.mapValues { landed(it.value) })
        }
        for (a in commit.assigned) {
            val table = rows[a.target.shape] ?: throw VelleRuntimeError("assign to unknown shape '${a.target.shape}'")
            val row = table[a.target.key] ?: throw VelleRuntimeError("assign to missing row ${a.target.shape}#${a.target.key.value}")
            table[a.target.key] = row.copy(fields = row.fields + (a.field to landed(a.value)))
        }
        for (c in commit.captured) {
            val instance = landed(c.instance) as Ref.Persisted
            captures[instance to c.refinement] = c.values.mapValues { landed(it.value) }
        }
        for (r in commit.retracted) captures.remove(r.instance to r.refinement)
        for (d in commit.deleted) {
            rows[d.target.shape]?.remove(d.target.key)
            captures.keys.removeIf { it.first == d.target }
        }
        minted
    }
}
