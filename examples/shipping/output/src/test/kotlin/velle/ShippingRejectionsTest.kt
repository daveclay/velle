package velle.generated.shipping

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import velle.CommitResult
import velle.EvalStep
import velle.RecordRef
import velle.Rejection
import velle.generated.ShippingSystem

/**
 * What a rejection carries (OQ47, "Rejections").
 *
 * One constraint on the books, `never (Shipment where order is
 * CancelledOrder)`, and the two acts it rejects: a shipment for a cancelled
 * order, and a cancellation for a shipped order. Each refusal comes back as a
 * [Rejection]: the act, the records that would be in the forbidden state, the
 * record the act references that the prohibition read through (the order,
 * which is what the person was acting on), any other record it read, and the
 * evaluation step by step — plus the rendered message, so no binding code is
 * needed to get a readable one. Nothing here is fetched from the state by
 * hand; every assertion reads the result.
 */
class ShippingRejectionsTest {

    private val sys = ShippingSystem()

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")
    private fun rejection(r: CommitResult): Rejection =
        assertNotNull(assertIs<CommitResult.Refused>(r, "accepted: $r").rejection, "a never refusal carries its rejection")

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

        val r = rejection(sys.commitCancellation(order, seventh))

        // The refusal is whole: nothing landed.
        assertEquals(emptyList(), sys.cancellations(), "the cancellation did not land")

        // The prohibition, in the author's words.
        assertEquals(1, r.never)
        assertEquals("Shipment where order is CancelledOrder", r.prohibition)

        // The act refused, with its fields as submitted.
        val act = assertNotNull(r.act)
        assertEquals("Cancellation", act.shape)
        assertEquals(RecordRef("Order", order.id), act.fields["order"])
        assertEquals(seventh, act.fields["requestedOn"])

        // The record protected is not the act: the shipment already on file.
        val shipment = r.violating.single()
        assertEquals("Shipment", shipment.shape)
        assertEquals(sixth, shipment.fields["shippedOn"])
        assertEquals(RecordRef("Order", order.id), shipment.fields["order"])

        // The pivot: the order, the one record the act references that the
        // prohibition read through — what the person was acting on.
        val pivot = r.pivots.single()
        assertEquals(RecordRef("Order", order.id), pivot.ref)
        assertEquals("4471", pivot.fields["reference"])
        assertEquals(emptyList(), r.witnesses, "nothing else was read")

        // The evaluation: how the order came to be a CancelledOrder — because
        // of this very act.
        assertTrue(EvalStep.Membership(pivot.ref, "CancelledOrder", true) in r.evaluation)
        assertTrue(EvalStep.Read(pivot.ref, "cancellations", listOf(act.ref)) in r.evaluation)

        // The default rendering, from the same data.
        assertEquals(
            """
            Cancellation refused: Shipment#2 would be a Shipment where order is CancelledOrder.
              never #1: Shipment where order is CancelledOrder
              this Cancellation: order = Order#1, requestedOn = 2026-10-07
              Shipment#2: order = Order#1, shippedOn = 2026-10-06
              through Order#1: reference = "4471"
              evaluation:
                Shipment#2.order = Order#1
                Order#1.cancellations = [this Cancellation]
                Order#1 is CancelledOrder
            """.trimIndent(),
            r.message,
        )
    }

    @Test
    fun `shipping an order that is cancelled is refused - the act refused is the record in violation`() {
        val order = order("4471")
        accept(sys.commitCancellation(order, sixth))

        val r = rejection(sys.commitShipment(order, seventh))

        assertEquals(emptyList(), sys.shipments(), "the shipment did not land")

        // This time the act is the record in violation.
        val act = assertNotNull(r.act)
        assertEquals("Shipment", act.shape)
        assertEquals(listOf(act.ref), r.violating.map { it.ref })

        // The pivot is still the order; the witness is a record that already
        // exists, the cancellation of the 6th.
        assertEquals(RecordRef("Order", order.id), r.pivots.single().ref)
        val cancellation = r.witnesses.single()
        assertEquals("Cancellation", cancellation.shape)
        assertEquals(sixth, cancellation.fields["requestedOn"])
        assertTrue(EvalStep.Read(r.pivots.single().ref, "cancellations", listOf(cancellation.ref)) in r.evaluation)

        assertEquals(
            """
            Shipment refused: this Shipment would be a Shipment where order is CancelledOrder.
              never #1: Shipment where order is CancelledOrder
              this Shipment: order = Order#1, shippedOn = 2026-10-07
              through Order#1: reference = "4471"
              also Cancellation#2: order = Order#1, requestedOn = 2026-10-06
              evaluation:
                this Shipment.order = Order#1
                Order#1.cancellations = [Cancellation#2]
                Order#1 is CancelledOrder
            """.trimIndent(),
            r.message,
        )
    }

    @Test
    fun `the prohibition reaches only the order it is about`() {
        val shipped = order("4471")
        val other = order("4472")
        accept(sys.commitShipment(shipped, sixth))

        accept(sys.commitCancellation(other, seventh))
        val r = rejection(sys.commitShipment(other, seventh))
        assertEquals(RecordRef("Order", other.id), r.pivots.single().ref, "the rejection is about the other order")

        with(sys) {
            assertEquals(true, other.isCancelledOrder())
            assertEquals(true, shipped.isShippedOrder())
            assertEquals(false, shipped.isCancelledOrder())
        }
    }
}
