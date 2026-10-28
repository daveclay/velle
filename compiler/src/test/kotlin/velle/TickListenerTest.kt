package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The listener registered on a system (OQ71 D5, 5.3) hears every tick once the
 * tick is on record, and every accepted act whose after-commit firings were
 * refused or failed. A listener that throws undoes nothing: the tick's row and
 * firings stand, and its exception reaches the caller after every listener had
 * its turn.
 */
class TickListenerTest {

    private val spec = """
        act Customer { name: text }
        act Invoice { customer: one Customer  due: Date }
        shape Reminder { invoice: one Invoice  sentOn: Date }
        shape Receipt { invoice: one Invoice }
        shape Unreminded = Invoice where due < today and not exists (Reminder where invoice == this)
        shape Unreceipted = Invoice where not exists (Receipt where invoice == this)
        rule Remind when Unreminded on Daily { Reminder from { invoice: this, sentOn: today } }
        rule Acknowledge when Unreceipted after commit { Receipt from { invoice: this } }
    """.trimIndent()

    private class Breaking(private val inner: MemoryStore, private val failing: String, private val cause: RuntimeException) : StateResolver by inner, CommitCallback by inner {
        override fun onCommit(set: CommitSet): List<StoreKey> {
            if (set.created.any { it.shape == failing }) throw cause
            return inner.onCommit(set)
        }
    }

    private class Heard : TickListener {
        val ticks = mutableListOf<Pair<TickReport, List<Rejection>>>()
        val afters = mutableListOf<Triple<RecordRef?, List<Rejection>, List<FiringFailure>>>()
        override fun ticked(report: TickReport, rejections: List<Rejection>) { ticks += report to rejections }
        override fun afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>) { afters += Triple(act, rejections, failures) }
    }

    private fun model() = Model(Parser.parse(spec))
    private val start = Instant.parse("2026-10-28T06:00:00Z")

    @Test
    fun `every tick is heard with its report, and a clean commit is not`() {
        val store = MemoryStore(model())
        val sys = VelleSystem(model(), startTime = start).also { it.connect(store, store) }
        val heard = Heard().also(sys::listen)
        sys.scope {
            val customer = assertIs<CommitResult.Accepted>(sys.commit("Customer", mapOf("name" to "Ada"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Invoice", mapOf("customer" to customer, "due" to LocalDate.of(2026, 10, 1))))
        }
        assertEquals(emptyList(), heard.afters, "the receipt landed: nothing to tell")
        val report = sys.tick("Daily")
        assertEquals(listOf(report to emptyList<Rejection>()), heard.ticks)
        assertEquals(mapOf("Remind" to 1), report.firings)
    }

    @Test
    fun `an act whose after-commit firing fails is heard, with the act and the failure, and the act stands`() {
        val boom = IllegalStateException("receipts offline")
        val memory = MemoryStore(model())
        val store = Breaking(memory, "Receipt", boom)
        val sys = VelleSystem(model(), startTime = start).also { it.connect(store, store) }
        val heard = Heard().also(sys::listen)
        val accepted = sys.scope {
            val customer = assertIs<CommitResult.Accepted>(sys.commit("Customer", mapOf("name" to "Ada"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Invoice", mapOf("customer" to customer, "due" to LocalDate.of(2026, 10, 1))))
        }
        val (act, rejections, failures) = heard.afters.single()
        assertEquals(RecordRef("Invoice", accepted.ref!!.key), act)
        assertEquals(emptyList(), rejections)
        assertEquals("Acknowledge", failures.single().rule)
        assertSame(boom, failures.single().cause)
        assertEquals(failures, accepted.afterCommitFailures, "the caller's result carries the same")
        assertEquals(1, memory.fetchAll("Invoice").size, "the act stood")
    }

    @Test
    fun `a listener that throws undoes nothing and is heard by the caller after the others had their turn`() {
        val store = MemoryStore(model())
        val sys = VelleSystem(model(), startTime = start).also { it.connect(store, store) }
        val heard = Heard()
        sys.listen(object : TickListener { override fun ticked(report: TickReport, rejections: List<Rejection>) = throw IllegalStateException("pager down") })
        sys.listen(heard)
        sys.scope {
            val customer = assertIs<CommitResult.Accepted>(sys.commit("Customer", mapOf("name" to "Ada"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Invoice", mapOf("customer" to customer, "due" to LocalDate.of(2026, 10, 1))))
        }
        val e = assertFailsWith<IllegalStateException> { sys.tick("Daily") }
        assertEquals("pager down", e.message)
        assertEquals(1, heard.ticks.size, "the second listener still heard it")
        assertEquals(1, store.fetchAll("Reminder").size, "the firing stood")
        assertEquals(1, store.fetchAll(Model.TICK).size, "the tick is on record")
        assertFailsWith<IllegalStateException> { sys.tick("Daily") } // the listener is still there, and so is the next tick
        assertEquals(2, store.fetchAll(Model.TICK).size, "the next tick ran")
    }
}
