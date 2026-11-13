package velle

import velle.generated.OrdersSystem
import velle.generated.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives orders.velle through the generated typed surface: the exposure
 * closure (Reference §6, "Inline part creation") end to end — one commit landing
 * container and parts, language-populated back-references, nested parts,
 * per-part rule firings, the derived aggregate on the container, bag
 * semantics for inline values, and the boundary refusal rolling back whole.
 * Then the two transient forms (Reference §4): the card an order carries,
 * in the input and not in the record, and a price request with parts,
 * answered and not kept.
 */
open class OrdersFlowTest {

    /** The acceptance, typed by the act it carries, so `.ref` is the record's own `Ref<T>`. */
    private fun <I, R> accept(r: Commit<I, R>): Commit.Accepted<I, R> {
        if (r !is Commit.Accepted) throw AssertionError("refused: $r")
        return r
    }

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): OrdersSystem = OrdersSystem()

    @Test
    fun `an order lands with its lines and their customizations in one commit`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val prod = sys.read(accept(sys.commit(NewProduct("SKU-1", unitPrice = 10))).ref)

        val r = sys.commit(NewOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111111111111111", cardholder = "Ada Lovelace",
            orderLines = listOf(
                NewOrderLine(
                    product = prod, quantity = 2,
                    customizations = listOf(NewCustomization(note = "gift wrap")),
                ),
                NewOrderLine(product = prod, quantity = 3),
            ),
        ))
        val order = sys.read(accept(r).ref)

        // the parts landed, back-references populated by the language
        assertEquals(2, sys.orderLines().size)
        sys.orderLines().forEach { assertEquals(order, sys.read(it).order) }
        assertEquals(setOf(2, 3), sys.orderLines().map { sys.read(it).quantity }.toSet())

        // the nested part points at its own level
        val custz = sys.customizations().single()
        assertEquals("gift wrap", sys.read(custz).note)
        assertEquals(2, sys.read(sys.read(custz).line).quantity)

        // the aggregate is derived on the container, never part-written
        assertEquals(5, sys.read(order).total)

        // the per-part rule fired once per inline line, in the act's transaction
        assertEquals(setOf(2, 3), sys.reservations().map { sys.read(it).quantity }.toSet())
    }

    @Test
    fun `identical inline part values are a bag - two distinct lines`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val prod = sys.read(accept(sys.commit(NewProduct("SKU-1", unitPrice = 10))).ref)

        val r = sys.commit(NewOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111111111111111", cardholder = "Ada Lovelace",
            orderLines = listOf(
                NewOrderLine(product = prod, quantity = 1),
                NewOrderLine(product = prod, quantity = 1), // 1 twice ≠ 2 once
            ),
        ))
        assertIs<Commit.Accepted<*, *>>(r)
        assertEquals(2, sys.orderLines().size)
        assertEquals(2, sys.read(accept(r).ref).total)
    }

    @Test
    fun `a never over a part refuses the whole closure - nothing lands`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val prod = sys.read(accept(sys.commit(NewProduct("SKU-1", unitPrice = 10))).ref)

        val r = sys.commit(NewOrder(
            customer = ada,
            placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111111111111111", cardholder = "Ada Lovelace",
            orderLines = listOf(
                NewOrderLine(product = prod, quantity = 2),
                NewOrderLine(product = prod, quantity = 0), // violates the never
            ),
        ))
        assertIs<Commit.Refused<*, *>>(r)
        assertTrue(sys.orders().isEmpty())
        assertTrue(sys.orderLines().isEmpty())
        assertTrue(sys.reservations().isEmpty())
    }

    @Test
    fun `the card is read at the commit and is not in the record`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val r = sys.commit(NewOrder(
            customer = ada, placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111111111111111", cardholder = "Ada Lovelace",
        ))
        val order = sys.read(accept(r).ref)
        // the rule read the card at the commit and recorded the cardholder's name
        val charge = sys.read(sys.charges().single())
        assertEquals(order, charge.order)
        assertEquals("Ada Lovelace", charge.cardholder)
        // the model object has no card: `order.cardNumber` does not compile
        val orderFields = Order::class.java.declaredFields.map { it.name }
        assertTrue("customer" in orderFields && "cardNumber" !in orderFields && "cardholder" !in orderFields, orderFields.toString())
    }

    @Test
    fun `a short card number refuses the whole order at the door`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val r = sys.commit(NewOrder(
            customer = ada, placedOn = LocalDate.parse("2026-08-21"),
            cardNumber = "4111", cardholder = "Ada Lovelace",
        ))
        assertIs<Commit.Refused<*, *>>(r)
        assertTrue(sys.orders().isEmpty())
        assertTrue(sys.charges().isEmpty())
    }

    @Test
    fun `a price request is answered with a quote at today's prices and is not kept`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val prod = sys.read(accept(sys.commit(NewProduct("SKU-1", unitPrice = 10))).ref)
        val r = sys.commit(NewPriceRequest(
            customer = ada,
            requestedItems = listOf(
                NewRequestedItem(product = prod, quantity = 3),
                NewRequestedItem(product = prod, quantity = 1),
            ),
        ))
        // a transient act leaves no record: the acceptance carries no reference
        assertEquals(null, accept(r).ref)
        val quote = sys.read(sys.quotes().single())
        assertEquals(ada, quote.customer)
        assertEquals(setOf(3, 1), sys.quoteLines().map { sys.read(it).quantity }.toSet())
        sys.quoteLines().forEach { assertEquals(10, sys.read(it).unitPrice) }
        // the request and its items are gone: there is no `priceRequests()` or `requestedItems()` to read
        assertTrue(OrdersSystem::class.java.methods.none { it.name == "priceRequests" || it.name == "requestedItems" })
    }

    @Test
    fun `an empty price request is refused at the door`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val r = sys.commit(NewPriceRequest(customer = ada))
        assertIs<Commit.Refused<*, *>>(r)
        assertTrue(sys.quotes().isEmpty())
    }

    @Test
    fun `the empty part collection is the absence`() {
        val sys = newSystem()
        val ada = sys.read(accept(sys.commit(NewCustomer("Ada"))).ref)
        val r = sys.commit(NewOrder(customer = ada, placedOn = LocalDate.parse("2026-08-21"), cardNumber = "4111111111111111", cardholder = "Ada Lovelace"))
        val order = sys.read(accept(r).ref)
        assertTrue(sys.read(order).orderLines.isEmpty())
        assertEquals(0, sys.read(order).total)
    }
}

