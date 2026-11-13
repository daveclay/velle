package velle.generated.orders

import java.time.LocalDate
import velle.generated.*
import velle.generated.OrdersSystem

/**
 * The human-owned scenarios the generated orders specs demand (testgen.md).
 * An OrderLine enters state only through the exposure closure — an order
 * committed with one inline line (Reference §6, "Inline part creation").
 * An order carries the card it is paid with, which is never stored, and a
 * price request is answered with a quote and not kept (Reference §4).
 */
class Givens(private val sys: OrdersSystem) : RequiredGivens {

    override fun order(): Order {
        sys.commit(NewCustomer("Ada"))
        sys.commit(NewOrder(
            customer = sys.customers().last(),
            placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111111111111111", cardholder = "Ada Lovelace",
        ))
        return sys.orders().last()
    }

    override fun priceRequest() {
        sys.commit(NewCustomer("Ada"))
        sys.commit(NewProduct("SKU-Q", unitPrice = 10))
        sys.commit(NewPriceRequest(
            customer = sys.customers().last(),
            requestedItems = listOf(NewRequestedItem(product = sys.products().last(), quantity = 3)),
        ))
    }

    override fun orderLine(): OrderLine {
        sys.commit(NewCustomer("Ada"))
        sys.commit(NewProduct("SKU-G", unitPrice = 10))
        sys.commit(NewOrder(
            customer = sys.customers().last(),
            placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111111111111111", cardholder = "Ada Lovelace",
            orderLines = listOf(NewOrderLine(product = sys.products().last(), quantity = 1)),
        ))
        return sys.orderLines().last()
    }
}
