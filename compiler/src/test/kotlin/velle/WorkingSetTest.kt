package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How long the runtime remembers a row it read: the working set lives from one
 * read entry (`openReadSnapshot`: `query`, `count`, `sum`, `exists`,
 * `currentShapeOf`, `visibleCurrent`) to the next on one thread, and never past
 * the end of a commit, a seed, or a tick. Observed from the store's side through a `MemoryStore` that counts
 * the by-key fetches and lets the test write a row beside the runtime, as a
 * database trigger or the engineer's own SQL would.
 */
class WorkingSetTest {

    private val spec = """
        act Customer { name: text, tier: integer initially 1 }
        act Promote { customer: one Customer }
        rule ApplyPromote when Promote { customer.tier = customer.tier + 1 }
    """.trimIndent()

    /** The in-memory store with every by-key fetch counted and a side door for writes the runtime did not make. */
    private class Counting(private val inner: MemoryStore) : StateResolver by inner, CommitCallback by inner {
        val fetchedByKey = mutableListOf<StoreKey>()
        override fun fetchByKey(shape: String, key: StoreKey): Row? {
            fetchedByKey.add(key)
            return inner.fetchByKey(shape, key)
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
        sys.connect(store, store)
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
    fun `within one read entry a row is fetched once, and the next entry fetches it again`() {
        val (sys, store) = system()
        val ada = sys.scope { accepted(sys.commit("Customer", mapOf("name" to "Ada"))) }
        sys.scope {
            val id = sys.idOf(ada)
            sys.currentShapeOf(id) // the entry: clears the working set, opens the read unit
            sys.get(id, "name"); sys.get(id, "tier"); sys.get(id, "name")
            assertEquals(1, store.fetchedByKey.size, "three field reads in one entry share one fetch")
            sys.currentShapeOf(id) // the next entry
            sys.get(id, "name")
            assertEquals(2, store.fetchedByKey.size, "a new entry starts from an empty working set and fetches the row again")
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
