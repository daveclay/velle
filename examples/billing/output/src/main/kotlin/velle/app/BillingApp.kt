package velle.app

import velle.CommitResult
import velle.generated.BillingSystem
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The hydration spike app (working-docs/investigate_runtime.md): an engineer's
 * application using the transpiled billing system *from* their own code, with
 * state living in a local SQLite database. Commits go through the generated
 * functions; the runtime demand-hydrates what each commit's rule chain needs
 * through the SqliteStore resolver, and lands each transaction's mutation set
 * through its commit callback. Reads at the end are the engineer's own SQL —
 * the DB is theirs.
 *
 * Run twice: rows from the previous run are still there, hydrated on demand.
 */
fun main() {
    DriverManager.getConnection("jdbc:sqlite:billing.db").use { conn ->
        val billing = BillingSystem(startTime = Instant.now())
        val store = SqliteStore(billing.system.model, conn)
        store.createSchema()
        billing.system.connect(store, store)

        println("=== Velle hydration spike — billing over SQLite (billing.db) ===")
        println()
        println("-- rows already in the DB from previous runs:")
        for (shape in listOf("Customer", "Invoice", "LineItem", "Payment", "Receipt", "ReceiptEmail", "Reminder"))
            println("   $shape: ${count(conn, shape)}")
        println()

        // ── the scenario: ordinary application code calling generated commits ──
        val cust = billing.customer(accept(billing.commitCustomer("Ada Lovelace", "ada@example.com")))
        val inv = billing.invoice(accept(billing.commitInvoice(cust, due = today().plusDays(30))))
        accept(billing.commitLineItem(inv, "Design work", BigDecimal("1200.00"), 1))
        accept(billing.commitLineItem(inv, "Hosting", BigDecimal("50.00"), 2))
        accept(billing.commitIssuance(inv))
        println("committed customer #${cust.id}, invoice #${inv.id} with two line items, issued")

        // typed reads: derived properties computed by demand-hydrating rows from SQLite
        println("   invoice.total=${inv.total}  balance=${inv.balance}  status=${inv.status}")

        // a `never` refusal: rejected at the boundary, nothing reaches the DB
        val refused = billing.commitPayment(inv, BigDecimal.ZERO)
        println("zero payment -> $refused")

        // payment in full: one envelope = Payment + largestPayment assign + Receipt,
        // landed as one SQLite transaction; EmailReceipt is `after commit` — its
        // ReceiptEmail is a second envelope, a second SQLite transaction
        accept(billing.commitPayment(inv, BigDecimal("1300.00")))
        println("paid in full -> status=${inv.status}")
        println()

        // an already-overdue invoice, then the weekly tick sweeps it — the tick's
        // scan read hydrates every invoice from SQLite to find current members
        val overdue = billing.invoice(accept(billing.commitInvoice(cust, due = today().minusDays(10))))
        accept(billing.commitLineItem(overdue, "Old work", BigDecimal("99.00"), 1))
        billing.tickWeekly()
        println("committed overdue invoice #${overdue.id}, ran the weekly tick")
        println()

        // ── the engineer's own SQL against their own DB ──
        println("-- customers (largestPayment maintained by the TrackLargestPayment fold):")
        query(conn, """SELECT id, name, email, largestPayment FROM Customer""") {
            println("   #${it.getLong(1)} ${it.getString(2)} <${it.getString(3)}> largestPayment=${it.getString(4)}")
        }
        println("-- receipts (SendReceipt fired when the invoice became PaidInvoice):")
        query(
            conn,
            """SELECT r.id, r.invoice, c.name, r.sentOn FROM Receipt r
               JOIN Invoice i ON i.id = r.invoice JOIN Customer c ON c.id = i.customer"""
        ) {
            println("   #${it.getLong(1)} for invoice #${it.getLong(2)} (${it.getString(3)}) sent ${it.getString(4)}")
        }
        println("-- receipt emails (EmailReceipt, after commit — its own transaction):")
        query(conn, """SELECT id, receipt, queuedOn FROM ReceiptEmail""") {
            println("   #${it.getLong(1)} for receipt #${it.getLong(2)} queued ${it.getString(3)}")
        }
        println("-- reminders (RemindOverdue at the weekly tick):")
        query(conn, """SELECT id, invoice, sentOn FROM Reminder""") {
            println("   #${it.getLong(1)} for invoice #${it.getLong(2)} sent ${it.getString(3)}")
        }
    }
}

private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC) // the system's declared zone (UTC)

private fun accept(r: CommitResult): Long = when (r) {
    is CommitResult.Accepted -> r.id
    is CommitResult.Refused -> error("refused: ${r.reason}")
}

private fun count(conn: Connection, table: String): Long =
    conn.prepareStatement("""SELECT COUNT(*) FROM "$table"""").use { ps ->
        ps.executeQuery().use { rs -> rs.getLong(1) }
    }

private fun query(conn: Connection, sql: String, each: (java.sql.ResultSet) -> Unit) {
    conn.prepareStatement(sql).use { ps ->
        ps.executeQuery().use { rs -> while (rs.next()) each(rs) }
    }
}
