package velle.generated.shipping

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import velle.CommitResult
import velle.generated.ShippingSystem

/**
 * What a rejection can say (OQ47, "Rejections").
 *
 * One constraint on the books, `never (Shipment where order is
 * CancelledOrder)`, and the two acts it rejects: a shipment for a cancelled
 * order, and a cancellation for a shipped order. Each test pins what the
 * runtime says today, then asserts that every ingredient of the message the
 * person should hear is still in the state after the refusal. The wanted
 * message is written out in a comment, not asserted: nothing produces it yet,
 * and that is the gap OQ47 Part 2 asks the runtime to close.
 */
class ShippingRejectionsTest {

    private val sys = ShippingSystem()

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")
    private fun refuse(r: CommitResult) = assertIs<CommitResult.Refused>(r, "accepted: $r")

    private fun order(reference: String): ShippingSystem.OrderView {
        accept(sys.commitOrder(reference))
        return sys.orders().last()
    }

    private val sixth = LocalDate.of(2026, 10, 6)
    private val seventh = LocalDate.of(2026, 10, 7)

    @Test
    fun `cancelling an order that has shipped is refused - the act refused is not the record protected`() {
        val order = order("4471")
        accept(sys.commitShipment(order, sixth))

        val refusal = refuse(sys.commitCancellation(order, seventh))

        // What the runtime says today: a rule number and the shape it is over.
        // The shape named is Shipment, the record protected, not Cancellation,
        // the act refused.
        assertEquals("never #1 over Shipment is violated", refusal.reason)

        // The refusal is whole: nothing landed.
        assertEquals(emptyList(), sys.cancellations(), "the cancellation did not land")
        with(sys) { assertEquals(false, order.isCancelledOrder()) }

        // What the person should hear:
        //
        //     Cancellation for order 4471 refused.
        //     Order 4471 has a Shipment (shipped 2026-10-06), and a Shipment
        //     is never on an order that has a Cancellation.
        //
        // Its ingredients, and where each one is after the refusal. The act's
        // fields are the call's arguments. The pivot is the order: the one
        // record the act references that the prohibition reads through.
        val shipment = sys.shipments().single()
        assertEquals(order, shipment.order, "the shipment on the pivot order is on file")
        assertEquals(sixth, shipment.shippedOn, "with the date the message cites")
        with(sys) { assertEquals(true, order.isShippedOrder(), "the pivot's state, in the author's name") }
        assertEquals(1, sys.system.model.nevers.size, "the prohibition is in the model, for the last clause")
        // Missing: the runtime handing these over as the rejection instead of a string.
    }

    @Test
    fun `shipping an order that is cancelled is refused - the act refused is the record in violation`() {
        val order = order("4471")
        accept(sys.commitCancellation(order, sixth))

        val refusal = refuse(sys.commitShipment(order, seventh))

        // Same prohibition, same message, and this time the shape named is
        // the act refused, so today's message is less wrong.
        assertEquals("never #1 over Shipment is violated", refusal.reason)
        assertEquals(emptyList(), sys.shipments(), "the shipment did not land")

        // What the person should hear:
        //
        //     Shipment for order 4471 refused.
        //     Order 4471 is a CancelledOrder (cancellation requested
        //     2026-10-06), and a Shipment is never on an order that has a
        //     Cancellation.
        //
        // The witness is a record that already exists, not the act.
        val cancellation = sys.cancellations().single()
        assertEquals(order, cancellation.order, "the cancellation on the pivot order is on file")
        assertEquals(sixth, cancellation.requestedOn, "with the date the message cites")
        with(sys) { assertEquals(true, order.isCancelledOrder(), "the pivot's state, in the author's name") }
    }

    @Test
    fun `the prohibition reaches only the order it is about`() {
        val shipped = order("4471")
        val other = order("4472")
        accept(sys.commitShipment(shipped, sixth))

        accept(sys.commitCancellation(other, seventh))
        refuse(sys.commitShipment(other, seventh))

        with(sys) {
            assertEquals(true, other.isCancelledOrder())
            assertEquals(true, shipped.isShippedOrder())
            assertEquals(false, shipped.isCancelledOrder())
        }
    }
}
