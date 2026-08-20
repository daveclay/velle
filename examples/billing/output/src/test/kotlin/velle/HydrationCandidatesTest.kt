package velle

import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.app.SqliteStore
import velle.generated.BillingSystem

/**
 * The read-set relevance gate and the candidate pre-filter, observed from the
 * resolver's side (investigate_runtime.md §6): commits stop hydrating tables
 * their footprint provably cannot affect, and the scans that remain arrive as
 * fetchCandidates with a compiled filter instead of fetchAll.
 */
class HydrationCandidatesTest {

    private class CountingStore(private val inner: SqliteStore) : StateResolver by inner {
        val fetchAlls = mutableListOf<String>()
        val candidates = mutableListOf<Pair<String, QF>>()

        override fun fetchAll(shape: String): List<Row> {
            fetchAlls.add(shape)
            return inner.fetchAll(shape)
        }

        override fun fetchCandidates(shape: String, filter: QF): List<Row> {
            candidates.add(shape to filter)
            return inner.fetchCandidates(shape, filter)
        }

        fun reset() { fetchAlls.clear(); candidates.clear() }
    }

    private lateinit var conn: Connection

    @AfterTest
    fun close() { if (::conn.isInitialized) conn.close() }

    private fun connect(): Pair<BillingSystem, CountingStore> {
        if (!::conn.isInitialized) conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val billing = BillingSystem() // fixed default clock: 2026-01-01T09:00:00Z
        val store = SqliteStore(billing.system.model, conn)
        store.createSchema()
        val counting = CountingStore(store)
        billing.system.connect(counting, store)
        return billing to counting
    }

    private fun accept(r: CommitResult): Long =
        assertIs<CommitResult.Accepted>(r, "refused: $r").id

    private fun today(): LocalDate = LocalDate.of(2026, 1, 1)

    private fun sqlCount(table: String): Long =
        conn.prepareStatement("""SELECT COUNT(*) FROM "$table"""").use { ps ->
            ps.executeQuery().use { rs -> rs.getLong(1) }
        }

    @Test
    fun `a commit nothing watches scans no tables at all`() {
        val (billing, store) = connect()
        store.reset()
        accept(billing.commitSignUp("Ada", "ada@x.com"))
        // no watcher's condition and no never reads Customer data — the
        // relevance gate leaves every table untouched
        assertEquals(emptyList(), store.fetchAlls)
        assertEquals(emptyList(), store.candidates)
    }

    @Test
    fun `the payment cascade never scans customers, and never checks arrive filtered`() {
        val (billing, store) = connect()
        accept(billing.commitSignUp("Ada", "ada@x.com"))
        val cust = billing.customers().last()
        accept(billing.commitBillCustomer(cust, due = today().plusDays(30)))
        val inv = billing.invoices().last()
        store.reset()

        accept(billing.commitLineItem(inv, "Design", BigDecimal("1200.00"), 1))
        accept(billing.commitSubmitPayment(inv, BigDecimal("1200.00")))

        assertEquals("paid", billing.invoice(inv.id).status)
        assertEquals(1, sqlCount("Receipt"))
        assertEquals(1, sqlCount("ReceiptEmail"))

        // the fold writes customer.largestPayment through a by-id read — the
        // Customer table is never scanned, here or anywhere in this scenario
        assertTrue("Customer" !in store.fetchAlls, "customer scans: ${store.fetchAlls}")
        // the LineItem nevers are self-contained: their scans arrive as
        // fetchCandidates with the compiled quantity/price filters, not fetchAll
        assertTrue("LineItem" !in store.fetchAlls, "lineitem scans: ${store.fetchAlls}")
        assertTrue(store.candidates.any { it.first == "LineItem" && it.second != QF.True })
    }

    @Test
    fun `the weekly tick prefilters invoices and reads guards keyed`() {
        val (billing, store) = connect()
        accept(billing.commitSignUp("Ada", "ada@x.com"))
        val cust = billing.customers().last()
        accept(billing.commitBillCustomer(cust, due = today().minusDays(10)))
        val overdue = billing.invoices().last()
        accept(billing.commitLineItem(overdue, "Old work", BigDecimal("99.00"), 1))
        store.reset()

        billing.tickWeekly()
        assertEquals(1, sqlCount("Reminder"))

        // the sweep's only scan is the compiled candidate query over Invoice;
        // the reminder guard and archive facts hydrate as keyed joins, and the
        // firing's own envelope (a Reminder create) is relevant to no watcher
        assertEquals(emptyList(), store.fetchAlls)
        assertEquals(listOf("Invoice"), store.candidates.map { it.first })
        assertTrue(store.candidates.single().second != QF.True)

        // same tick again: the Reminder evidence suppresses re-nagging
        billing.tickWeekly()
        assertEquals(1, sqlCount("Reminder"))
    }

    @Test
    fun `cross-process guard memory flows through the candidate query`() {
        val (first, _) = connect()
        accept(first.commitSignUp("Ada", "ada@x.com"))
        val cust = first.customers().last()
        accept(first.commitBillCustomer(cust, due = today().minusDays(10)))
        val overdue = first.invoices().last()
        accept(first.commitLineItem(overdue, "Old work", BigDecimal("99.00"), 1))
        first.tickWeekly()
        assertEquals(1, sqlCount("Reminder"))

        // a second process over the same storage: last run's Reminder row is
        // excluded by the NOT EXISTS in the candidate query itself
        val (second, store) = connect()
        store.reset()
        second.tickWeekly()
        assertEquals(1, sqlCount("Reminder"))
        assertEquals(emptyList(), store.fetchAlls)
    }
}
