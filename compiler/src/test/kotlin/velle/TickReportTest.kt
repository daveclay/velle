package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What a tick reports (OQ71 D5, 5.1): how many firings of each rule landed, and
 * the firings that failed for a reason other than a `never`, as typed records
 * with the rule and the record it ran for. A failure is the store's or the
 * runtime's, not the spec's: the firing rolled back, the tick's own `Tick` row
 * still lands, and the next tick retries. The runtime keeps no list of them.
 */
class TickReportTest {

    private val spec = """
        act Customer { name: text }
        act Invoice { customer: one Customer  due: Date }
        shape Reminder { invoice: one Invoice  sentOn: Date }
        shape Unreminded = Invoice where due < today and not exists (Reminder where invoice == this)
        rule Remind when Unreminded on Daily { Reminder from { invoice: this, sentOn: today } }
    """.trimIndent()

    /** The in-memory store, refusing to write a commit set that creates [failing] with [cause]. */
    private class Breaking(private val inner: MemoryStore, private val failing: String, private val cause: RuntimeException) : StateResolver by inner, CommitCallback by inner {
        override fun onCommit(set: CommitSet): List<StoreKey> {
            if (set.created.any { it.shape == failing }) throw cause
            return inner.onCommit(set)
        }
    }

    private fun model() = Model(Parser.parse(spec))

    private fun overdue(sys: VelleSystem, n: Int) = sys.scope {
        val customer = assertIs<CommitResult.Accepted>(sys.commit("Customer", mapOf("name" to "Ada"))).id
        repeat(n) { assertIs<CommitResult.Accepted>(sys.commit("Invoice", mapOf("customer" to customer, "due" to LocalDate.of(2026, 10, 1)))) }
    }

    @Test
    fun `a clean tick reports its firings per rule and no failures`() {
        val store = MemoryStore(model())
        val sys = VelleSystem(model(), startTime = Instant.parse("2026-10-28T06:00:00Z")).also { it.connect(store, store) }
        overdue(sys, 2)
        val report = sys.tick("Daily")
        assertEquals("Daily", report.schedule)
        assertEquals(Instant.parse("2026-10-28T06:00:00Z"), report.at)
        assertEquals(mapOf("Remind" to 2), report.firings)
        assertEquals(emptyList(), report.failures)
        assertEquals(mapOf<String, Int>(), sys.tick("Daily").firings, "the guards hold: nothing fires the second time")
    }

    @Test
    fun `a firing the store cannot write is a failure on the report, named and heard, and the tick still lands`() {
        val boom = IllegalStateException("disk full")
        val memory = MemoryStore(model())
        val store = Breaking(memory, "Reminder", boom)
        val sys = VelleSystem(model(), startTime = Instant.parse("2026-10-28T06:00:00Z")).also { it.connect(store, store) }
        val heard = FailureCollector()
        sys.listen(heard)
        overdue(sys, 1)

        val report = sys.tick("Daily")
        assertEquals(emptyMap(), report.firings)
        val failure = report.failures.single()
        assertEquals("Remind", failure.rule)
        assertEquals("Invoice", failure.subject?.shape)
        assertSame(boom, failure.cause)
        assertEquals("firing of 'Remind' for Invoice#2 failed: disk full", failure.message)
        assertEquals(listOf(failure), heard.failures)
        assertEquals(0, memory.fetchAll("Reminder").size, "the firing rolled back")
        assertEquals(1, memory.fetchAll(Model.TICK).size, "the tick itself is on record")
    }
}
