package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How long the runtime remembers a row it read. Inside a `scope { }` the working set
 * lives from the scope's first read entry (`openReadSnapshot`: `query`, `count`, `sum`,
 * `exists`, `existsFor`, `currentShapeOf`, `visibleCurrent`) until a commit, a seed, or
 * a tick made in that scope, or the scope's end: every read of the scope is answered
 * from one snapshot and a row is fetched once. A read made outside any scope is a unit
 * of work of its own and holds nothing when it returns. Observed from the store's side
 * through a `MemoryStore` that counts the by-key fetches and lets the test write a row
 * beside the runtime, as a database trigger or the engineer's own SQL would.
 */
class WorkingSetTest {

    private val spec = """
        act Customer { name: text, tier: integer initially 1 }
        act Promote { customer: one Customer }
        rule ApplyPromote when Promote { customer.tier = customer.tier + 1 }
    """.trimIndent()

    /** The in-memory store with every by-key fetch counted and a side door for writes the runtime did not make. */
    private class Counting(private val inner: MemoryStore) : StateResolver by inner, CommitCallback by inner, TickMemory by inner {
        val fetchedByKey = mutableListOf<StoreKey>()
        /** Units of work begun and not yet ended. */
        var openUnits = 0
        override fun begin(kind: WorkKind): UnitOfWork {
            val u = inner.begin(kind)
            openUnits++
            return object : UnitOfWork {
                override fun serialize(keys: Set<AdmissionKey>) = u.serialize(keys)
                override fun end(outcome: Outcome) { openUnits--; u.end(outcome) }
            }
        }
        override fun fetchByKey(shape: String, key: StoreKey): Row? {
            fetchedByKey.add(key)
            return inner.fetchByKey(shape, key)
        }
        /** The keys of each several-keys fetch, one entry per call. */
        val fetchedByKeys = mutableListOf<List<StoreKey>>()
        override fun fetchByKeys(shape: String, keys: List<StoreKey>): List<Row> {
            fetchedByKeys.add(keys)
            return inner.fetchByKeys(shape, keys)
        }
        /** The targets of each fetch of the records pointing at one record or at several, one entry per call. */
        val fetchedReferencing = mutableListOf<List<Ref.Persisted>>()
        override fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row> {
            fetchedReferencing.add(listOf(target))
            return inner.fetchReferencing(shape, field, target)
        }
        override fun fetchReferencingAny(shape: String, field: String, targets: List<Ref.Persisted>): List<Row> {
            fetchedReferencing.add(targets)
            return inner.fetchReferencingAny(shape, field, targets)
        }
        /** A write beside the runtime: what a trigger or hand-written SQL does to a row. */
        fun sideWrite(target: Ref.Persisted, field: String, value: Any?) =
            inner.onCommit(CommitSet(created = emptyList(), assigned = listOf(CommitSet.Assign(target, field, value))))
    }

    private fun system(): Pair<VelleSystem, Counting> {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val sys = VelleSystem(model)
        val store = Counting(MemoryStore(model))
        sys.connect(store, store, store)
        return sys to store
    }

    private fun accepted(r: CommitResult): Ref.Persisted =
        assertIs<CommitResult.Accepted>(r, "refused: $r").ref ?: error("transient")

    /** What the generated `read(ref)` does (Binding.kt, `fetch`): one `currentShapeOf`, then the fields through `get`. */
    private fun VelleSystem.readName(ref: Ref.Persisted): String {
        val id = idOf(ref)
        currentShapeOf(id) ?: error("gone")
        return get(id, "name") as String
    }

    @Test
    fun `a read after a commit in the same scope goes to the store and sees a write the runtime did not make`() {
        val (sys, store) = system()
        sys.scope {
            val ada = accepted(sys.commit("Customer", mapOf("name" to "Ada")))
            assertEquals(0, store.fetchedByKey.size, "the commit built the row itself and fetched nothing")
            // the engineer's trigger fires on the committed row, after the commit's own unit of work ended
            store.sideWrite(ada, "name", "Adaline")
            assertEquals("Adaline", sys.readName(ada), "the read did not answer from what the commit built")
            assertEquals(1, store.fetchedByKey.size, "one by-key fetch: the row was read back from the store")
        }
    }

    @Test
    fun `a scope's reads are one snapshot, so the next read in the scope does not fetch the row again`() {
        val (sys, store) = system()
        val ada = sys.scope { accepted(sys.commit("Customer", mapOf("name" to "Ada"))) }
        sys.scope {
            val id = sys.idOf(ada)
            sys.currentShapeOf(id) // the scope's first read entry: opens the snapshot
            sys.get(id, "name"); sys.get(id, "tier"); sys.get(id, "name")
            assertEquals(1, store.fetchedByKey.size, "three field reads share one fetch")
            sys.currentShapeOf(id) // the next read entry in the same scope
            assertEquals("Ada", sys.get(id, "name"))
            assertEquals(1, store.fetchedByKey.size, "the second entry reused the row the first had fetched")
        }
        assertEquals("Ada", sys.scope { sys.readName(ada) })
        assertEquals(2, store.fetchedByKey.size, "the next scope is a new snapshot and fetches the row for itself")
    }

    @Test
    fun `a viewer's fetch by identity is one read entry, so the row is fetched once`() {
        val (sys, store) = system()
        val ada = sys.scope { accepted(sys.commit("Customer", mapOf("name" to "Ada"))) }
        sys.scope {
            val id = sys.idOf(ada)
            assertEquals(true, sys.existsFor(viewer = null, root = "Customer", id = id))
            sys.get(id, "name")
            assertEquals(1, store.fetchedByKey.size, "the check and the field read after it share one fetch")
            assertEquals(false, sys.existsFor(viewer = null, root = "Promote", id = id), "a Customer is no Promote")
        }
    }

    /** Three customers, the first promoted twice and the second once, each in a scope of its own; the counts start after them. */
    private fun threeCustomers(sys: VelleSystem, store: Counting): List<Ref.Persisted> {
        val customers = listOf("Ada", "Bo", "Cy").map { name -> sys.scope { accepted(sys.commit("Customer", mapOf("name" to name))) } }
        for (promoted in listOf(customers[0], customers[1], customers[0]))
            sys.scope { accepted(sys.commit("Promote", mapOf("customer" to sys.idOf(promoted)))) }
        store.fetchedByKey.clear()
        store.fetchedByKeys.clear()
        store.fetchedReferencing.clear()
        return customers
    }

    @Test
    fun `the records pointing at the rows of one fetch are fetched together, for all of them at once`() {
        val (sys, store) = system()
        val customers = threeCustomers(sys, store)
        sys.scope {
            val listed = sys.instancesOf("Customer") // one fetch returns the three rows
            assertEquals(listOf(2, 1, 0), listed.map { customer -> (sys.get(customer, "promotes") as List<*>).size })
            assertEquals(listOf(customers), store.fetchedReferencing, "one fetch asked for the promotions of all three")
        }
    }

    @Test
    fun `the references the rows of one fetch hold in one field are fetched together`() {
        val (sys, store) = system()
        val customers = threeCustomers(sys, store)
        sys.scope {
            val promotions = sys.instancesOf("Promote") // three rows, naming two customers
            val names = promotions.map { promotion -> sys.get(sys.get(promotion, "customer") as Long, "name") }
            assertEquals(listOf("Ada", "Bo", "Ada"), names)
            assertEquals(listOf(listOf(customers[0].key, customers[1].key)), store.fetchedByKeys, "one fetch for the two customers named")
            assertEquals(0, store.fetchedByKey.size)
        }
    }

    @Test
    fun `no fetch asks for more records than the limit, and the rest are fetched when they are needed`() {
        val (sys, store) = system()
        val customers = threeCustomers(sys, store)
        sys.fetchTogether = FetchTogether(limit = 2)
        sys.scope {
            val listed = sys.instancesOf("Customer")
            sys.get(listed[0], "promotes")
            assertEquals(listOf(customers.take(2)), store.fetchedReferencing, "the record needed and one more")
            sys.get(listed[1], "promotes")
            assertEquals(1, store.fetchedReferencing.size, "the second was already fetched for")
            assertEquals(0, (sys.get(listed[2], "promotes") as List<*>).size)
            assertEquals(listOf(customers.take(2), customers.drop(2)), store.fetchedReferencing, "the third is fetched for when it is needed")
        }
    }

    @Test
    fun `the limit can be set for the shape being fetched, and a limit of one fetches one record at a time`() {
        val (sys, store) = system()
        val customers = threeCustomers(sys, store)
        sys.fetchTogether = FetchTogether(limit = 500, limitByShape = mapOf("Promote" to 1))
        sys.scope {
            val listed = sys.instancesOf("Customer")
            sys.get(listed[0], "promotes")
            assertEquals(listOf(listOf(customers[0])), store.fetchedReferencing)
        }
    }

    @Test
    fun `a commit drops what the scope had read, and a read after it starts over`() {
        val (sys, store) = system()
        val ada = sys.scope { accepted(sys.commit("Customer", mapOf("name" to "Ada"))) }
        sys.scope {
            assertEquals("Ada", sys.readName(ada))
            assertEquals(1, store.fetchedByKey.size)
            // the envelope reads the customer on its own unit of work: the scope's working set is not reused
            accepted(sys.commit("Promote", mapOf("customer" to sys.idOf(ada))))
            assertEquals(2, store.fetchedByKey.size, "the envelope fetched the row for itself")
            assertEquals("Ada", sys.readName(ada))
            assertEquals(3, store.fetchedByKey.size, "after the commit the read entry starts from an empty working set")
        }
    }

    @Test
    fun `a read outside any scope is a unit of work of its own and holds nothing when it returns`() {
        val (sys, store) = system()
        val ada = sys.scope { accepted(sys.commit("Customer", mapOf("name" to "Ada"))) }
        assertEquals(0, store.openUnits)
        val id = sys.idOf(ada) // minted outside any scope: the generated harness does this before a commit
        assertEquals("Ada", sys.get(id, "name"))
        assertEquals(0, store.openUnits, "the bare read ended its unit as it returned")
        assertEquals(1, store.fetchedByKey.size)
        assertEquals("Ada", sys.get(id, "name"))
        assertEquals(2, store.fetchedByKey.size, "two bare reads are two units, each starting from the store")
        // the store's read lock went with the unit: a commit on another thread is not kept waiting
        val other = Thread { sys.scope { accepted(sys.commit("Promote", mapOf("customer" to sys.idOf(ada)))) } }
        other.start(); other.join(10_000)
        assertEquals(false, other.isAlive, "a commit on another thread waited on a read that had returned")
        // the handle minted outside the scope is still good for the commit that follows it
        accepted(sys.commit("Promote", mapOf("customer" to id)))
        assertEquals(0, sys.liveHandles, "the commit's scope dropped the handles")
    }

    @Test
    fun `a bare get after a commit, with no read entry, still starts from the store`() {
        // The generic surface's `get` makes no entry and clears nothing itself; the commit cleared what it built
        // on its way out, so even this path has nothing stale to answer from.
        val (sys, store) = system()
        sys.scope {
            val ada = accepted(sys.commit("Customer", mapOf("name" to "Ada")))
            store.sideWrite(ada, "name", "Adaline")
            assertEquals("Adaline", sys.get(sys.idOf(ada), "name"), "the store's row, not the envelope's")
            assertEquals(1, store.fetchedByKey.size, "fetched once")
        }
    }
}
