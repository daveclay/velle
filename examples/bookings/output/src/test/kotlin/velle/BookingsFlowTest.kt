package velle.generated.bookings

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.*

/**
 * The counter's day through the generated typed surface: a slot booked once, a
 * second request for it refused, a cancellation freeing it, and the waitlist
 * told on the hourly sweep exactly once.
 */
open class BookingsFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): BookingsSystem = BookingsSystem()
    protected val sys: BookingsSystem by lazy { newSystem() }

    protected val ten: Instant = Instant.parse("2026-01-05T10:00:00Z")
    protected val eleven: Instant = Instant.parse("2026-01-05T11:00:00Z")

    private fun accept(r: Commit<*, *>) = assertIs<Commit.Accepted<*, *>>(r, "refused: $r")

    @Test
    fun `a slot is booked once - the second request for it is refused and the first booking stands`() {
        accept(sys.commit(NewBookNow(ten, "Ada")))
        val refused = assertIs<Commit.Refused<*, *>>(sys.commit(NewBookNow(ten, "Bob")))
        val message = refused.rejection?.message ?: refused.toString()
        assertTrue("StandingBooking" in message, message)
        assertEquals(listOf("Ada"), sys.bookings().map { it.bookedFor })
        accept(sys.commit(NewBookNow(eleven, "Bob")))
        assertEquals(setOf("Ada", "Bob"), sys.standingBookings().map { it.bookedFor }.toSet())
    }

    @Test
    fun `a cancellation frees the slot, stays on record, and cannot be given twice`() {
        accept(sys.commit(NewBookNow(ten, "Ada")))
        val booking = sys.bookings().single()
        accept(sys.commit(NewCancel(booking)))
        assertEquals(emptyList(), sys.standingBookings())
        assertEquals(1, sys.bookings().size, "the cancelled booking is kept")
        assertEquals(booking, sys.read(sys.cancellations().single().booking))
        assertIs<Commit.Refused<*, *>>(sys.commit(NewCancel(booking)), "a cancelled booking is not cancelled again")
        accept(sys.commit(NewBookNow(ten, "Bob")))
        assertEquals(listOf("Bob"), sys.standingBookings().map { it.bookedFor })
    }

    @Test
    fun `the waitlist is told on the hour once the slot is free, and told once`() {
        accept(sys.commit(NewBookNow(ten, "Ada")))
        assertIs<Commit.Refused<*, *>>(sys.commit(NewBookNow(ten, "Bob")))
        accept(sys.commit(NewWaitlist(ten, "Bob")))
        val bob = sys.waitlists().single()

        sys.tickHourly()
        assertEquals(emptyList(), sys.notices(), "the slot is still taken")
        assertEquals(false, sys.read(bob).isUnserved)

        accept(sys.commit(NewCancel(sys.bookings().single())))
        assertEquals(true, sys.read(bob).isUnserved)
        sys.tickHourly()
        assertEquals(bob, sys.read(sys.notices().single().entry))

        sys.tickHourly()
        assertEquals(1, sys.notices().size, "told once")
        accept(sys.commit(NewBookNow(ten, "Bob")))
        assertEquals(emptyList(), sys.untolds())
    }

    @Test
    fun `the waitlist holds twenty names`() {
        repeat(20) { accept(sys.commit(NewWaitlist(ten, "Name $it"))) }
        assertIs<Commit.Refused<*, *>>(sys.commit(NewWaitlist(eleven, "One too many")))
        assertEquals(20, sys.waitlists().size)
    }
}
