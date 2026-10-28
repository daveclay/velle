package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A firing checks its rule's condition again once it holds its locks (OQ71 D1).
 * Two instances of one system over one store both tick at the same moment; both
 * select the overdue invoice with no reminder; the second to take the invoice's
 * lock finds the first one's reminder and does nothing. Observed by a store
 * whose write unit runs the other instance's whole tick between selecting the
 * subject and locking it.
 */
class DuplicateFiringTest {

    private val spec = """
        act Customer { name: text }
        act Invoice { customer: one Customer  due: Date }
        shape Reminder { invoice: one Invoice  sentOn: Date }
        shape Unreminded = Invoice where due < today and not exists (Reminder where invoice == this)
        rule Remind when Unreminded on Daily { Reminder from { invoice: this, sentOn: today } }
    """.trimIndent()

    /** The in-memory store; before a write unit takes its keys, [beforeLocking] runs once. */
    private class Interleaving(private val inner: MemoryStore) : StateResolver by inner, CommitCallback by inner {
        var beforeLocking: (() -> Unit)? = null
        override fun begin(kind: WorkKind): UnitOfWork {
            val u = inner.begin(kind)
            if (kind != WorkKind.WRITE) return u
            return object : UnitOfWork {
                override fun serialize(keys: Set<AdmissionKey>) {
                    beforeLocking?.let { beforeLocking = null; it() }
                    u.serialize(keys)
                }
                override fun end(outcome: Outcome) = u.end(outcome)
            }
        }
    }

    @Test
    fun `the second instance to lock the subject finds the first one's reminder and does not fire`() {
        val model = Model(Parser.parse(spec))
        check(Validator.validate(spec).none { !it.advisory }) { Validator.validate(spec).toString() }
        val start = Instant.parse("2026-10-28T06:00:00Z")
        val store = MemoryStore(model)
        val interleaving = Interleaving(store)
        val first = VelleSystem(Model(Parser.parse(spec)), startTime = start).also { it.connect(interleaving, store) }
        val other = VelleSystem(Model(Parser.parse(spec)), startTime = start).also { it.connect(store, store) }

        first.scope {
            val customer = assertIs<CommitResult.Accepted>(first.commit("Customer", mapOf("name" to "Ada"))).id
            assertIs<CommitResult.Accepted>(first.commit("Invoice", mapOf("customer" to customer, "due" to LocalDate.of(2026, 10, 1))))
        }
        assertEquals(0, store.fetchAll("Reminder").size)

        // the first instance selects the invoice, then, before it locks it, the other instance's tick runs to completion
        interleaving.beforeLocking = { other.tick("Daily") }
        first.tick("Daily")

        assertEquals(1, store.fetchAll("Reminder").size, "one reminder, not one per instance")
        assertEquals(null, interleaving.beforeLocking, "the other instance did tick in the gap")
    }
}
