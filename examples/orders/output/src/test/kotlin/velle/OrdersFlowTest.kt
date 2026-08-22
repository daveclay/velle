package velle

import velle.generated.OrdersSystem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives orders.velle through the generated typed surface: the exposure
 * closure (README §6, "Inline part creation") end to end — one commit landing
 * container and parts, language-populated back-references, nested parts,
 * per-part rule firings, the derived aggregate on the container, bag
 * semantics for inline values, and the boundary refusal rolling back whole.
 */
class OrdersFlowTest {

    private fun newSystem() = OrdersSystem()

    @Test
    fun `an order lands with its lines and their customizations in one commit`() {
        val sys = newSystem()
        val ada = sys.customer(assertIs<CommitResult.Accepted>(sys.commitCustomer("Ada")).id)
        val prod = sys.product(assertIs<CommitResult.Accepted>(sys.commitProduct("SKU-1")).id)

        val r = sys.commitOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(
                OrdersSystem.NewOrderLine(
                    product = prod, quantity = 2,
                    customizations = listOf(OrdersSystem.NewCustomization(note = "gift wrap")),
                ),
                OrdersSystem.NewOrderLine(product = prod, quantity = 3),
            ),
        )
        val order = sys.order(assertIs<CommitResult.Accepted>(r).id)

        // the parts landed, back-references populated by the language
        assertEquals(2, sys.orderLines().size)
        sys.orderLines().forEach { assertEquals(order, it.order) }
        assertEquals(setOf(2, 3), sys.orderLines().map { it.quantity }.toSet())

        // the nested part points at its own level
        val custz = sys.customizations().single()
        assertEquals("gift wrap", custz.note)
        assertEquals(2, custz.line.quantity)

        // the aggregate is derived on the container, never part-written
        assertEquals(5, order.total)

        // the per-part rule fired once per inline line, in the act's transaction
        assertEquals(setOf(2, 3), sys.reservations().map { it.quantity }.toSet())
    }

    @Test
    fun `identical inline part values are a bag - two distinct lines`() {
        val sys = newSystem()
        val ada = sys.customer(assertIs<CommitResult.Accepted>(sys.commitCustomer("Ada")).id)
        val prod = sys.product(assertIs<CommitResult.Accepted>(sys.commitProduct("SKU-1")).id)

        val r = sys.commitOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(
                OrdersSystem.NewOrderLine(product = prod, quantity = 1),
                OrdersSystem.NewOrderLine(product = prod, quantity = 1), // 1 twice ≠ 2 once
            ),
        )
        assertIs<CommitResult.Accepted>(r)
        assertEquals(2, sys.orderLines().size)
        assertEquals(2, sys.order(r.id).total)
    }

    @Test
    fun `a never over a part refuses the whole closure - nothing lands`() {
        val sys = newSystem()
        val ada = sys.customer(assertIs<CommitResult.Accepted>(sys.commitCustomer("Ada")).id)
        val prod = sys.product(assertIs<CommitResult.Accepted>(sys.commitProduct("SKU-1")).id)

        val r = sys.commitOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(
                OrdersSystem.NewOrderLine(product = prod, quantity = 2),
                OrdersSystem.NewOrderLine(product = prod, quantity = 0), // violates the never
            ),
        )
        assertIs<CommitResult.Refused>(r)
        assertTrue(sys.orders().isEmpty())
        assertTrue(sys.orderLines().isEmpty())
        assertTrue(sys.reservations().isEmpty())
    }

    @Test
    fun `the empty part collection is the absence`() {
        val sys = newSystem()
        val ada = sys.customer(assertIs<CommitResult.Accepted>(sys.commitCustomer("Ada")).id)
        val r = sys.commitOrder(customer = ada, placedOn = LocalDate.parse("2026-08-21"))
        val order = sys.order(assertIs<CommitResult.Accepted>(r).id)
        assertTrue(order.orderLines.isEmpty())
        assertEquals(0, order.total)
    }
}
