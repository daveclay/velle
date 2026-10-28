package velle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the runtime hands the store's unit of work at admission, before an
 * envelope's work runs: a value key is valued where the compiler found the
 * subject side reads it (the act's own field, a hop from one of its references,
 * the swept row's field), and takes the shape's width only where no such place
 * exists. Observed through a store that records every `serialize` call.
 */
class AdmissionKeysTest {

    /** The in-memory store with every write unit's admission keys recorded. */
    private class Recording(private val inner: MemoryStore) : StateResolver by inner, CommitCallback by inner {
        val serialized = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        /** Called after a write unit holds its keys; the test may make the unit wait there. */
        @Volatile var holding: (Set<AdmissionKey>) -> Unit = {}
        override fun begin(kind: WorkKind): UnitOfWork {
            val u = inner.begin(kind)
            if (kind != WorkKind.WRITE) return u
            return object : UnitOfWork {
                override fun serialize(keys: Set<AdmissionKey>) {
                    serialized.add(keys.map { it.text }.sorted())
                    u.serialize(keys)
                    holding(keys)
                }
                override fun end(outcome: Outcome) = u.end(outcome)
            }
        }
    }

    private fun system(spec: String): Pair<VelleSystem, Recording> {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val sys = VelleSystem(model)
        val store = Recording(MemoryStore(model))
        sys.connect(store, store)
        return sys to store
    }

    private fun accepted(r: CommitResult): Ref.Persisted =
        assertIs<CommitResult.Accepted>(r, "refused: $r").ref ?: error("transient")

    private val bookings = """
        act BookNow { slot: text }
        shape Reservation { slot: text }
        never (Reservation where exists (Reservation as other where other.slot == this.slot and not (other == this)))
        rule ApplyBooking when BookNow { Reservation from { slot: slot } }

        act Waitlist { slot: text }
        shape Unserved = Waitlist where not exists (Reservation where slot == this.slot)
        shape Nagged { waitlist: one Waitlist }
        rule NagWaitlist when (Unserved where not exists (Nagged where waitlist == this)) on Hourly { Nagged from { waitlist: this } }
    """.trimIndent()

    @Test
    fun `an act carrying the unique field itself keys the value it commits`() {
        val (sys, store) = system("""
            act Customer { email: text }
            never (Customer where exists (Customer as other where other.email == this.email and not (other == this)))
        """.trimIndent())
        sys.scope { accepted(sys.commit("Customer", mapOf("email" to "ada@example.com"))) }
        assertEquals(listOf(listOf("Customer.email=ada@example.com")), store.serialized)
    }

    @Test
    fun `an act whose rule creates the unique row keys the value the rule will copy from the act`() {
        val (sys, store) = system(bookings)
        sys.scope { accepted(sys.commit("BookNow", mapOf("slot" to "Tue 10:00"))) }
        assertEquals(listOf(listOf("Reservation.slot=Tue 10:00")), store.serialized, "the slot, not the whole Reservation shape")
    }

    @Test
    fun `a value the rule reads through one of the act's references is read at admission the same way`() {
        val (sys, store) = system("""
            act Account { code: text }
            act Register { account: one Account }
            shape Tag { code: text }
            never (Tag where exists (Tag as other where other.code == this.code and not (other == this)))
            rule MakeTag when Register { Tag from { code: account.code } }
        """.trimIndent())
        sys.scope {
            val acme = accepted(sys.commit("Account", mapOf("code" to "ACME")))
            store.serialized.clear()
            accepted(sys.commit("Register", mapOf("account" to sys.idOf(acme))))
            // the account row the rule reads, and the code it will copy into the Tag
            assertEquals(listOf(listOf("Account#${acme.key.value}", "Tag.code=ACME")), store.serialized)
        }
    }

    @Test
    fun `a value the rule computes keeps the shape's width`() {
        val (sys, store) = system("""
            act Register { name: text }
            shape Tag { code: text }
            never (Tag where exists (Tag as other where other.code == this.code and not (other == this)))
            rule MakeTag when Register { Tag from { code: "fixed" } }
        """.trimIndent())
        sys.scope { accepted(sys.commit("Register", mapOf("name" to "x"))) }
        assertEquals(listOf(listOf("Tag")), store.serialized, "nothing on the act says what code the rule writes")
    }

    @Test
    fun `a firing correlating on the swept row's field keys that row and the value it holds`() {
        val (sys, store) = system(bookings)
        val wed = sys.scope { accepted(sys.commit("Waitlist", mapOf("slot" to "Wed 14:00"))) }
        store.serialized.clear()
        sys.tick("Hourly")
        val key = wed.key.value
        // the firing's keys, then the tick's own record under its shape's key (Reference §17)
        assertEquals(listOf(listOf("Reservation.slot=Wed 14:00", "Waitlist#$key"), listOf("Tick")), store.serialized)
        sys.scope { assertEquals(1, sys.instancesOf("Nagged").size) }
    }

    @Test
    fun `bookings of two different slots hold their keys at the same time`() {
        val (sys, store) = system(bookings)
        val bothHolding = CountDownLatch(2)
        val together = java.util.concurrent.atomic.AtomicBoolean(true)
        store.holding = { bothHolding.countDown(); if (!bothHolding.await(5, TimeUnit.SECONDS)) together.set(false) }
        val threads = listOf("Tue 10:00", "Wed 14:00").map { slot ->
            Thread { sys.scope { accepted(sys.commit("BookNow", mapOf("slot" to slot))) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertTrue(together.get(), "a booking waited for a booking of another slot: ${store.serialized}")
        sys.scope { assertEquals(2, sys.instancesOf("Reservation").size) }
    }

    @Test
    fun `bookings of one slot take turns`() {
        val (sys, store) = system(bookings)
        val bothHolding = CountDownLatch(2)
        val metTheOther = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        // the first to hold its key waits for the other to arrive; the other cannot while the key is held
        store.holding = { bothHolding.countDown(); metTheOther.add(bothHolding.await(300, TimeUnit.MILLISECONDS)) }
        val threads = (1..2).map { Thread { sys.scope { sys.commit("BookNow", mapOf("slot" to "Tue 10:00")) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertEquals(listOf(false, true), metTheOther, "the second booking held its key only after the first was done")
        sys.scope { assertEquals(1, sys.instancesOf("Reservation").size) }
    }
}
