package velle.generated.orders

import java.time.LocalDate
import velle.generated.OrdersSystem

/**
 * The human-owned scenarios the generated orders specs demand (testgen.md).
 * An OrderLine enters state only through the exposure closure — an order
 * committed with one inline line (README §6, "Inline part creation").
 */
class Givens(private val sys: OrdersSystem) : RequiredGivens {

    override fun orderLine(): OrdersSystem.OrderLineView {
        sys.commitCustomer("Ada")
        sys.commitProduct("SKU-G")
        sys.commitOrder(
            customer = sys.customers().last(),
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(OrdersSystem.NewOrderLine(product = sys.products().last(), quantity = 1)),
        )
        return sys.orderLines().last()
    }
}
