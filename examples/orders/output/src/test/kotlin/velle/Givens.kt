package velle.generated.orders

import java.time.LocalDate
import velle.generated.*
import velle.generated.OrdersSystem

/**
 * The human-owned scenarios the generated orders specs demand (testgen.md).
 * An OrderLine enters state only through the exposure closure — an order
 * committed with one inline line (Reference §6, "Inline part creation").
 * An order carries the card it is paid with, which is never stored, and a
 * price request is answered with a quote and not kept (Reference §4). A
 * catalogue update arrives as one list of changes of two kinds, each change
 * its own class, and is not kept either (Reference §6, "A request that is
 * not kept, with its parts").
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

    override fun catalogueUpdate() {
        sys.commit(NewCatalogueUpdate(
            source = "feed",
            changes = listOf(NewListing(sku = "SKU-N", unitPrice = 5, name = "Widget")),
        ))
    }

    override fun priceChange() {
        sys.commit(NewProduct("SKU-P", unitPrice = 10))
        sys.commit(NewCatalogueUpdate(
            source = "feed",
            changes = listOf(NewPriceChange(sku = "SKU-P", unitPrice = 12, product = sys.products().last())),
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
