package velle

import java.math.BigDecimal
import velle.generated.*
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.generated.BillingStoreOverGeneric
import velle.generated.BillingStoreResolver
import velle.generated.BillingSystem
import velle.generated.Invoice
import velle.sqlite.SqliteStore

/**
 * The query API (README §22, the composed query; OQ51 R1–R11): the caller
 * composes a query from the spec's declared vocabulary — filter in the spec's
 * predicate language, a required order, a page — and gets the shape's data
 * classes back. Run in memory and over SQLite through both store layers.
 */
open class QueryApiTest {

    open fun newSystem(): BillingSystem = BillingSystem()

    private fun BillingSystem.customer(name: String, email: String = "${name.lowercase()}@example.com"): Customer {
        commit(NewSignUp(name, email))
        return customers().last()
    }

    private fun BillingSystem.invoice(
        customer: Customer,
        due: LocalDate,
        amount: BigDecimal? = null,
    ): Invoice {
        commit(NewBillCustomer(customer, due))
        val inv = invoices().last()
        amount?.let { commit(NewLineItem(inv, "widgets", it, 1)) }
        return inv
    }

    private val d1 = LocalDate.of(2026, 2, 1)
    private val d2 = LocalDate.of(2026, 3, 1)
    private val d3 = LocalDate.of(2026, 4, 1)

    @Test
    fun `a filter over a derived property, ordered, returns data classes`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        sys.invoice(ada, d3, BigDecimal("30"))
        sys.invoice(ada, d1, BigDecimal("10"))
        sys.invoice(ada, d2) // no balance: excluded
        val paid = sys.invoice(ada, d1, BigDecimal("50"))
        sys.commit(NewSubmitPayment(paid, BigDecimal("50"))) // paid off: excluded

        val unpaid: List<Invoice> = sys.read.invoices
            .where { balance gt 0 and (due lte d3) }
            .orderBy { due.ascending }
            .list()

        assertEquals(listOf(d1, d3), unpaid.map { it.due })
        assertEquals(listOf(BigDecimal("10"), BigDecimal("30")).map { it.stripTrailingZeros() }, unpaid.map { it.balance.stripTrailingZeros() })
        assertEquals("Ada", sys.read(unpaid.first().customer).name) // a reference is a handle; reading it is a fetch
    }

    @Test
    fun `identity - this customer's invoices, by reference equality and by a nested path`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        val bob = sys.customer("Bob")
        sys.invoice(ada, d1); sys.invoice(bob, d1); sys.invoice(ada, d2)

        val byRef = sys.read.invoices.where { customer eq ada }.orderBy { due.ascending }.list()
        assertEquals(listOf(d1, d2), byRef.map { it.due })
        assertTrue(byRef.all { it.customer == ada })

        val byName = sys.read.invoices.where { customer.name eq "Bob" }.orderBy { due.ascending }.list()
        assertEquals(1, byName.size)
        assertEquals(bob, byName.single().customer)
    }

    @Test
    fun `a refinement is a root and a membership test`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        sys.invoice(ada, d1, BigDecimal("10"))   // overdue once the clock passes d1
        sys.invoice(ada, d3, BigDecimal("10"))   // not yet due
        sys.setTime(d2.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))

        val overdue = sys.read.overdueInvoices.orderBy { due.ascending }.list()
        assertEquals(listOf(d1), overdue.map { it.due })
        assertEquals("overdue", overdue.single().status)

        val viaMembership = sys.read.invoices.where { isOverdueInvoice() }.orderBy { due.ascending }.list()
        assertEquals(overdue.map { it.key }, viaMembership.map { it.key })

        val notOverdue = sys.read.invoices.where { not(isOverdueInvoice()) }.orderBy { due.ascending }.list()
        assertEquals(listOf(d3), notOverdue.map { it.due })
    }

    @Test
    fun `the text operators - contains, starts with, ends with, like, and lowercase`() {
        val sys = newSystem()
        listOf("Ada Lovelace", "Alan Turing", "Grace Hopper", "ada byron").forEach { sys.customer(it, "${it.replace(" ", ".")}@example.com") }
        fun names(q: TypedQuery<*, velle.generated.Customer>) = q.list().map { it.name }

        assertEquals(listOf("Ada Lovelace"), names(sys.read.customers.where { name contains "Love" }.orderBy { name.ascending }))
        assertEquals(listOf("Ada Lovelace", "Alan Turing"), names(sys.read.customers.where { name startsWith "A" }.orderBy { name.ascending }))
        assertEquals(listOf("Grace Hopper"), names(sys.read.customers.where { name endsWith "Hopper" }.orderBy { name.ascending }))
        assertEquals(listOf("Alan Turing"), names(sys.read.customers.where { name like "A%n_" }.orderBy { name.ascending }))
        // exact and case-sensitive: "ada byron" does not start with "A"; folding is the author's visible `lowercase`
        assertEquals(listOf("Ada Lovelace", "ada byron"), names(sys.read.customers.where { name.lowercase startsWith "ada" }.orderBy { name.ascending }))
        // `like` escapes: an underscore in the pattern is any one character; escaped, it is literal
        assertEquals(listOf("Alan Turing"), names(sys.read.customers.where { email like "Alan\\.Turing@%" }.orderBy { name.ascending }))
        assertEquals(emptyList(), names(sys.read.customers.where { email like "Alan\\_Turing@%" }.orderBy { name.ascending }))
    }

    @Test
    fun `every query names its order, and the order is checked against the spec`() {
        val sys = newSystem()
        sys.customer("Ada")
        val unordered = assertFailsWith<QueryRefused> { sys.read.customers.list() }
        assertTrue(unordered.diagnostics.single().message.contains("names its order"), unordered.message)

        // the checks without running: identity orders nothing (README §5)
        val byId = sys.system.check(Query("Customer", orderBy = listOf(OrderDatum("id"))))
        assertTrue(byId.single().message.contains("identity orders nothing"), byId.toString())

        val unknown = sys.system.check(Query("Customer", orderBy = listOf(OrderDatum("colour"))))
        assertTrue(unknown.single().message.contains("no member 'colour'"), unknown.toString())

        // a filter is checked with a refinement's rules: a text operator on a number
        val badText = sys.system.check(Query("Invoice",
            where = Binary("contains", PathExpr("this", listOf(Seg("total", false))), TextLit("x")),
            orderBy = listOf(OrderDatum("due"))))
        assertTrue(badText.single().message.contains("compares text"), badText.toString())

        // an unknown root, and a transient act (never readable)
        assertTrue(sys.system.check(Query("Nothing", orderBy = listOf(OrderDatum("x")))).isNotEmpty())
        assertTrue(sys.system.check(Query("SignUp", orderBy = listOf(OrderDatum("name")))).single().message.contains("transient"))
    }

    @Test
    fun `pages are stable under ties - the hidden store-key tie-break`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        repeat(5) { sys.invoice(ada, d1) } // five invoices, one due date: the order alone cannot tell them apart

        val seen = mutableListOf<StoreKey>()
        var cursor: Cursor? = null
        var pages = 0
        do {
            val page = sys.read.invoices.orderBy { due.ascending }.page(2, cursor).fetch()
            assertTrue(page.items.size <= 2)
            seen.addAll(page.items.map { it.key })
            cursor = page.next
            pages++
        } while (cursor != null)
        assertEquals(3, pages)
        assertEquals(5, seen.size)
        assertEquals(seen.toSet().size, seen.size, "a page repeated a row: $seen")
        assertEquals(sys.invoices().map { it.key }.toSet(), seen.toSet())

        // a page that holds everything has no next cursor
        assertNull(sys.read.invoices.orderBy { due.ascending }.page(10).fetch().next)
    }

    @Test
    fun `descending order, a secondary datum, and absent values last`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        sys.invoice(ada, d2, BigDecimal("5"))
        sys.invoice(ada, d1, BigDecimal("20"))
        sys.invoice(ada, d1, BigDecimal("10"))

        val rows = sys.read.invoices.orderBy { due.descending }.thenBy { total.ascending }.list()
        assertEquals(listOf(d2, d1, d1), rows.map { it.due })
        assertEquals(listOf("5", "10", "20"), rows.map { it.total.stripTrailingZeros().toPlainString() })
    }

    @Test
    fun `count and sum are composed aggregates - no spec declaration`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        sys.invoice(ada, d1, BigDecimal("10"))
        sys.invoice(ada, d2, BigDecimal("30"))
        sys.invoice(ada, d3)

        assertEquals(3, sys.read.invoices.count())
        assertEquals(2, sys.read.invoices.where { balance gt 0 }.count())
        assertEquals(0, BigDecimal("40").compareTo(sys.read.invoices.sum { total }))
        assertEquals(0, BigDecimal("30").compareTo(sys.read.invoices.where { due gte d2 }.sum { total }))
    }

    @Test
    fun `a registered query is checked by name and runs by name`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        sys.invoice(ada, d1, BigDecimal("10"))
        sys.invoice(ada, d2)

        val unpaidFor = sys.read.register("unpaidInvoices") {
            invoices.where { balance gt 0 }.orderBy { due.ascending }
        }
        assertEquals(listOf("unpaidInvoices"), sys.read.registry.names)
        assertTrue(sys.read.registry.validate().isEmpty())
        assertEquals(1, unpaidFor.count())
        assertEquals(1, sys.read.registry["unpaidInvoices"].count())
    }

    @Test
    fun `fetch by identity - a handle points, the data class carries the data and the membership flags`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        val inv = sys.invoice(ada, d1, BigDecimal("10"))

        // the handle has no getters; the data class is one read at the moment of the fetch
        val before = sys.read(inv)
        assertEquals(inv, before)                       // the same identity: a model object is a reference
        assertEquals(0, BigDecimal("10").compareTo(before.balance))
        assertEquals(false, before.isPaidInvoice)

        sys.commit(NewSubmitPayment(inv, BigDecimal("10")))
        assertEquals(0, BigDecimal("10").compareTo(before.balance), "a data class does not change after the fact")
        val after = sys.read(inv)
        assertEquals(0, BigDecimal.ZERO.compareTo(after.balance))
        assertEquals(true, after.isPaidInvoice)
        assertEquals("paid", after.status)

        // a refinement root fetches the member, or null
        assertEquals(after.key, sys.read.paidInvoice(inv)?.key)
        assertNull(sys.read.overdueInvoice(inv))

        // by store key: only meaningful over a store; in memory the handle is the identity
        val key = (sys.commit(NewBillCustomer(ada, d2)) as Commit.Accepted<*>).storeKey
        if (key != null) assertEquals(d2, sys.read.invoice(StoreKey(key)).due)
    }

    @Test
    fun `a query reads settled state - a commit is visible to the next query`() {
        val sys = newSystem()
        val ada = sys.customer("Ada")
        assertEquals(0, sys.read.invoices.count())
        sys.invoice(ada, d1)
        assertEquals(1, sys.read.invoices.count())
        val inv = sys.read.invoices.orderBy { due.ascending }.list().single()
        assertEquals(ada, inv.customer)
        assertEquals(BigDecimal.ZERO.compareTo(inv.balance), 0)
    }
}

class QueryApiOverSqliteTest : QueryApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BillingSystem = BillingSystem().also { SqliteStore.attach(it.system, conn) }
}

class QueryApiOverTypedStoreTest : QueryApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BillingSystem = BillingSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = BillingStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(BillingStoreResolver(store, sys.system.model), backend)
    }
}

