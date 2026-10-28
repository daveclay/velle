package velle.generated.bookings

import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.AdmissionKey
import velle.Commit
import velle.CommitCallback
import velle.MemoryStore
import velle.Outcome
import velle.StateResolver
import velle.UnitOfWork
import velle.WorkKind
import velle.generated.*

/**
 * What the spec says about running at the same time, shown happening (the wiki
 * guide Concurrency): eight requests for one slot on eight threads leave one
 * booking; requests for different slots run together; and the keys each commit
 * takes before its work begins, read off a store that records them.
 */
class BookingsConcurrencyTest {

    private val ten: Instant = Instant.parse("2026-01-05T10:00:00Z")
    private val eleven: Instant = Instant.parse("2026-01-05T11:00:00Z")

    /** The in-memory store with every write unit's queue keys recorded, and a hook run while they are held. */
    private class Recording(private val inner: MemoryStore) : StateResolver by inner, CommitCallback by inner {
        val serialized: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
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

    private fun recording(): Pair<BookingsSystem, Recording> {
        val sys = BookingsSystem()
        val store = Recording(MemoryStore(sys.system.model))
        sys.system.connect(store, store)
        return sys to store
    }

    private fun accept(r: Commit<*, *>) = assertIs<Commit.Accepted<*, *>>(r, "refused: $r")

    private fun inThreads(n: Int, work: (Int) -> Unit) {
        val gate = CountDownLatch(1)
        val threads = (0 until n).map { i -> Thread { gate.await(); work(i) } }
        threads.forEach { it.start() }
        gate.countDown()
        threads.forEach { it.join(30_000) }
        assertTrue(threads.none { it.isAlive }, "a thread did not finish")
    }

    @Test
    fun `eight requests for one slot at once leave one booking`() {
        val sys = BookingsSystem()
        val results = Collections.synchronizedList(mutableListOf<Commit<*, *>>())
        inThreads(8) { i -> results.add(sys.commit(NewBookNow(ten, "Walk-in $i"))) }
        assertEquals(1, results.count { it is Commit.Accepted<*, *> }, results.toString())
        assertEquals(7, results.count { it is Commit.Refused<*, *> })
        assertEquals(1, sys.standingBookings().size)
    }

    @Test
    fun `requests for eight different slots at once are all accepted`() {
        val sys = BookingsSystem()
        val results = Collections.synchronizedList(mutableListOf<Commit<*, *>>())
        inThreads(8) { i -> results.add(sys.commit(NewBookNow(ten.plusSeconds(3600L * i), "Walk-in $i"))) }
        assertEquals(8, results.count { it is Commit.Accepted<*, *> }, results.toString())
        assertEquals(8, sys.standingBookings().size)
    }

    @Test
    fun `the keys each commit takes before its work begins`() {
        val (sys, store) = recording()
        accept(sys.commit(NewBookNow(ten, "Ada")))
        assertEquals(listOf("Booking.slot=$ten"), store.serialized.last(), "a booking: the slot the request carries")

        val booking = sys.bookings().single()
        accept(sys.commit(NewCancel(booking)))
        assertEquals(listOf("Booking#${booking.key.value}", "Booking.slot=$ten"), store.serialized.last(),
            "a cancellation: the booking it cancels, and the slot read through it")

        accept(sys.commit(NewWaitlist(eleven, "Bob")))
        assertEquals(listOf("Waitlist"), store.serialized.last(), "joining the waitlist: the whole shape, as the spec tolerates")

        val bob = sys.waitlists().single()
        store.serialized.clear()
        sys.tickHourly()
        assertEquals(listOf(listOf("Booking.slot=$eleven", "Waitlist#${bob.key.value}"), listOf("Tick")), store.serialized,
            "one firing: the entry it is for, and the slot it waits on")
        assertEquals(1, sys.notices().size)
    }

    @Test
    fun `bookings of two slots hold their keys at the same time, and bookings of one slot do not`() {
        val (sys, store) = recording()
        val both = CountDownLatch(2)
        val metTheOther = Collections.synchronizedList(mutableListOf<Boolean>())
        // the first commit to hold its keys waits for the other to hold its own
        store.holding = { both.countDown(); metTheOther.add(both.await(2, TimeUnit.SECONDS)) }
        inThreads(2) { i -> accept(sys.commit(NewBookNow(if (i == 0) ten else eleven, "Walk-in $i"))) }
        assertEquals(listOf(true, true), metTheOther, "two slots: both held their keys at once")

        val again = CountDownLatch(2)
        metTheOther.clear()
        store.holding = { again.countDown(); metTheOther.add(again.await(300, TimeUnit.MILLISECONDS)) }
        val twelve = ten.plusSeconds(7200)
        inThreads(2) { sys.commit(NewBookNow(twelve, "Walk-in")) }
        assertEquals(listOf(false, true), metTheOther, "one slot: the second held its key only after the first was done")
        assertEquals(1, sys.standingBookings().count { it.slot == twelve })
    }
}
