package velle

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import velle.app.SqliteStore
import velle.generated.BillingSystem

/**
 * Capture persistence over engineer-owned storage (investigate_runtime.md §7):
 * per-membership memory survives the process, the exit rule reads it as last
 * reader wherever the exit happens, and the retraction deletes the row. This
 * is exactly the §5 failure — archive in one run, unarchive in the next, the
 * capture read used to fail — now the store hosts the memory.
 */
class CapturePersistenceTest {

    private lateinit var conn: Connection

    @AfterTest
    fun close() { if (::conn.isInitialized) conn.close() }

    private fun system(startTime: Instant): BillingSystem {
        if (!::conn.isInitialized) conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val billing = BillingSystem(startTime = startTime)
        val store = SqliteStore(billing.system.model, conn)
        store.createSchema()
        billing.system.connect(store, store)
        return billing
    }

    private fun accept(r: CommitResult): Long =
        assertIs<CommitResult.Accepted>(r, "refused: $r").id

    private fun scalar(sql: String, bind: Long? = null): Any? =
        conn.prepareStatement(sql).use { ps ->
            bind?.let { ps.setLong(1, it) }
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else rs.getObject(1).let { if (it is Number) it.toLong() else it }
            }
        }

    @Test
    fun `a capture written in one process is the exit rule's last read in another`() {
        // process one, Jan 1: archive the invoice — ArchivedInvoice entered,
        // `captured archivedOn: Date = today` fixed at the entry moment
        val first = system(Instant.parse("2026-01-01T09:00:00Z"))
        accept(first.commitSignUp("Ada", "ada@x.com"))
        val cust = first.customers().last()
        accept(first.commitBillCustomer(cust, due = java.time.LocalDate.of(2026, 2, 1)))
        val inv = first.invoices().last()
        val invKey = assertIs<Long>(scalar("""SELECT id FROM "Invoice""""), "store key expected for the persisted invoice")
        accept(first.commitArchiveRequest(inv))

        assertEquals("2026-01-01", scalar("""SELECT archivedOn FROM "capture_ArchivedInvoice" WHERE id = ?""", invKey))

        // process two, five days later: unarchive. Handles are session-local —
        // the second process enters from its own storage side, by store key.
        // The exit rule hydrates the capture — the notice carries the *entry*
        // date, not the second process's today
        val second = system(Instant.parse("2026-01-06T09:00:00Z"))
        val invHandle = second.system.handleFor("Invoice", invKey)
        accept(second.commitUnarchiveRequest(second.invoice(invHandle)))

        assertEquals(1L, scalar("""SELECT COUNT(*) FROM "UnarchiveNotice""""))
        assertEquals("2026-01-01", scalar("""SELECT wasArchivedOn FROM "UnarchiveNotice" WHERE invoice = ?""", invKey))
        // retraction landed in the same envelope: the membership's memory is gone
        assertEquals(0L, scalar("""SELECT COUNT(*) FROM "capture_ArchivedInvoice""""))
    }
}
