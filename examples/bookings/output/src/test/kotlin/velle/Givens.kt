package velle.generated.bookings

import java.time.Instant
import velle.generated.*

/**
 * The human-owned scenarios the generated bookings specs demand (testgen.md).
 */
class Givens(private val sys: BookingsSystem) : RequiredGivens {

    private val slot: Instant = Instant.parse("2026-01-05T10:00:00Z")

    /** A walk-in books the ten o'clock slot. */
    override fun bookNow() {
        sys.commit(NewBookNow(slot, "Ada"))
    }

    /** A booking is made and then given back. */
    override fun cancel() {
        sys.commit(NewBookNow(slot, "Ada"))
        sys.commit(NewCancel(sys.bookings().last()))
    }

    /** A name on the waitlist for a slot nobody holds, not yet told. */
    override fun untold(): Waitlist {
        sys.commit(NewWaitlist(slot, "Bob"))
        return sys.waitlists().last()
    }
}
