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
 * order, and a cancellation for a shipped order. The first half of each test
 * pins what the runtime says today. The second half builds, by hand from the
 * typed surface, the message the person should hear, to show that every
 * ingredient is already in the state at the moment of the refusal. The gap
 * between the two halves is what OQ47 Part 2 asks the runtime to close.
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
        val shipment = sys.shipments().single()

        val refusal = refuse(sys.commitCancellation(order, seventh))

        // Nothing landed: the refusal is whole.
        assertEquals(emptyList(), sys.cancellations(), "the cancellation did not land")
        assertEquals(listOf(shipment), sys.shipments(), "the shipment stands")
        with(sys) { assertEquals(false, order.isCancelledOrder()) }

        // What the runtime says today: a rule number and the shape it is over.
        // The shape named is Shipment, the record protected, not Cancellation,
        // the act refused.
        assertEquals("never #1 over Shipment is violated", refusal.reason)

        // What the person should hear, built here by hand from the state as it
        // stands at the refusal. The pivot is the order: the one record the act
        // references that the prohibition's predicate reads through.
        val wanted = with(sys) {
            buildString {
                appendLine("Cancellation for order ${order.reference} refused.")
                append("Order ${order.reference} has a Shipment (shipped ${shipment.shippedOn}), ")
                append("and a Shipment is never on an order that has a Cancellation.")
            }
        }
        assertEquals(
            "Cancellation for order 4471 refused.\n" +
                "Order 4471 has a Shipment (shipped 2026-10-06), " +
                "and a Shipment is never on an order that has a Cancellation.",
            wanted,
        )
        // Every ingredient used above is in the state: the act's fields (from
        // the call), the order it references, the shipment on that order, and
        // the prohibition's text. What is missing is the runtime handing them
        // over as the rejection instead of a string.
    }

    @Test
    fun `shipping an order that is cancelled is refused - the act refused is the record in violation`() {
        val order = order("4471")
        accept(sys.commitCancellation(order, sixth))
        val cancellation = sys.cancellations().single()
        with(sys) { assertEquals(true, order.isCancelledOrder()) }

        val refusal = refuse(sys.commitShipment(order, seventh))

        assertEquals(emptyList(), sys.shipments(), "the shipment did not land")
        assertEquals("never #1 over Shipment is violated", refusal.reason)

        // Same prohibition, same shape named, and this time the shape named is
        // the act refused, so today's message is less wrong. The witness is a
        // record that already exists: the cancellation of the 6th.
        val wanted = with(sys) {
            "Shipment for order ${order.reference} refused.\n" +
                "Order ${order.reference} is a CancelledOrder (cancellation requested ${cancellation.requestedOn}), " +
                "and a Shipment is never on an order that has a Cancellation."
        }
        assertEquals(
            "Shipment for order 4471 refused.\n" +
                "Order 4471 is a CancelledOrder (cancellation requested 2026-10-06), " +
                "and a Shipment is never on an order that has a Cancellation.",
            wanted,
        )
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
