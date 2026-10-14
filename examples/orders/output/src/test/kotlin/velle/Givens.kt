package velle.generated.orders

import java.time.LocalDate
import velle.generated.*
import velle.generated.OrdersSystem

/**
 * The human-owned scenarios the generated orders specs demand (testgen.md).
 * An OrderLine enters state only through the exposure closure — an order
 * committed with one inline line (README §6, "Inline part creation").
 */
class Givens(private val sys: OrdersSystem) : RequiredGivens {

    override fun orderLine(): OrderLine {
        sys.commit(NewCustomer("Ada"))
        sys.commit(NewProduct("SKU-G"))
        sys.commit(NewOrder(
            customer = sys.customers().last(),
            placedOn = LocalDate.parse("2026-08-21"),
            orderLines = listOf(NewOrderLine(product = sys.products().last(), quantity = 1)),
        ))
        return sys.orderLines().last()
    }
}
