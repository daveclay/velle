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
import velle.generated.BillingStore
import velle.generated.BillingStoreOverGeneric
import velle.generated.BillingStoreResolver
import velle.generated.BillingSystem
import velle.generated.InvoiceRow
import velle.generated.toInvoiceRow

/**
 * The typed store surface (investigate_runtime.md §10), exercised in its
 * intended layering — the framework scenario: a generic, spec-agnostic backend
 * (SqliteStore, standing in for a springboot-velle) serves everything through
 * BillingStoreOverGeneric's defaults, and the engineer overrides exactly one
 * hot question with hand-tuned SQL. The runtime speaks the generic protocol
 * throughout; BillingStoreResolver recognizes each candidate filter as its
 * named question and routes it, typed parameters and all.
 */
class TypedStoreTest {

    private lateinit var conn: Connection

    @AfterTest
    fun close() { if (::conn.isInitialized) conn.close() }

    /** The engineer's store: everything generic except the reminder sweep. */
    private class TunedStore(backend: SqliteStore, model: Model, private val conn: Connection) :
        BillingStoreOverGeneric(backend, model) {

        val tunedCalls = mutableListOf<Pair<LocalDate, LocalDate>>()

        override fun remindOverdueCandidates(dueBefore: LocalDate, sentOnAfter: LocalDate): List<InvoiceRow> {
            tunedCalls.add(dueBefore to sentOnAfter)
            // hand-written SQL for the hot question — parameters arrive as
            // already-folded dates, no filter tree in sight
            val sql = """
                SELECT i.* FROM "Invoice" i
                WHERE i."due" < ?
                  AND NOT EXISTS (SELECT 1 FROM "Reminder" r WHERE r."invoice" = i.id AND r."sentOn" > ?)
            """.trimIndent()
            return conn.prepareStatement(sql).use { ps ->
                ps.setString(1, dueBefore.toString())
                ps.setString(2, sentOnAfter.toString())
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val fields = buildMap {
                                put("customer", Ref.Persisted("Customer", StoreKey(rs.getLong("customer"))))
                                put("due", LocalDate.parse(rs.getString("due")))
                                put("reference", rs.getString("reference"))
                                put("issuedOn", java.time.Instant.parse(rs.getString("issuedOn")))
                            }
                            add(Row("Invoice", StoreKey(rs.getLong("id")), fields).toInvoiceRow())
                        }
                    }
                }
            }
        }
    }

    private fun accept(r: CommitResult): Long = assertIs<CommitResult.Accepted>(r, "refused: $r").id

    @Test
    fun `generic defaults serve everything, the one override serves its question`() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val billing = BillingSystem() // clock: 2026-01-01T09:00:00Z
        val sqlite = SqliteStore(billing.system.model, conn)
        sqlite.createSchema()

        val store: BillingStore = TunedStore(sqlite, billing.system.model, conn)
        billing.system.connect(BillingStoreResolver(store, billing.system.model), sqlite)

        // the full cascade rides the typed layer's generic defaults
        val cust = billing.customer(accept(billing.commitCustomer("Ada", "ada@x.com")))
        val inv = billing.invoice(accept(billing.commitInvoice(cust, due = LocalDate.of(2026, 2, 1))))
        accept(billing.commitLineItem(inv, "Design", BigDecimal("1200.00"), 1))
        accept(billing.commitPayment(inv, BigDecimal("1200.00")))
        assertEquals("paid", billing.invoice(inv.id).status)
        assertEquals(1, billing.receipts().size)
        assertEquals(1, billing.receiptEmails().size)

        // the weekly sweep routes to the hand-tuned override, typed and folded
        val overdue = billing.invoice(accept(billing.commitInvoice(cust, due = LocalDate.of(2025, 12, 20))))
        accept(billing.commitLineItem(overdue, "Old work", BigDecimal("99.00"), 1))
        billing.tickWeekly()

        assertEquals(1, billing.reminders().size)
        val tuned = (store as TunedStore).tunedCalls
        assertEquals(1, tuned.size, "the adapter should recognize the sweep as remindOverdueCandidates")
        assertEquals(LocalDate.of(2026, 1, 1), tuned.single().first)          // today
        assertEquals(LocalDate.of(2025, 12, 25), tuned.single().second)       // today - 7 days
        assertTrue(billing.reminders().single().invoice.id == overdue.id)

        // and the guard's evidence suppresses the next sweep through the same override
        billing.tickWeekly()
        assertEquals(1, billing.reminders().size)
        assertEquals(2, tuned.size)
    }
}
