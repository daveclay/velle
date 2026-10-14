package velle.app

import velle.Commit
import velle.generated.*
import velle.generated.BillingSystem
import velle.sqlite.SqliteStore
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The billing app: an engineer's
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

        println("=== Velle billing over SQLite (billing.db) ===")
        println()
        println("-- rows already in the DB from previous runs:")
        for (shape in listOf("Customer", "Invoice", "LineItem", "Payment", "Receipt", "ReceiptEmail", "Reminder"))
            println("   $shape: ${count(conn, shape)}")
        println()

        // ── the scenario: ordinary application code calling generated commits ──
        // SignUp/BillCustomer/SubmitPayment are transient acts: the durable
        // record is what their rules materialize, so the app reads it back
        // through the typed accessors (identity is the store's,
        // Hydration.kt)
        accept(billing.commit(NewSignUp("Ada Lovelace", "ada@example.com")))
        val cust = billing.customers().last()
        accept(billing.commit(NewBillCustomer(cust, due = today().plusDays(30))))
        val inv = billing.invoices().last()
        accept(billing.commit(NewLineItem(inv, "Design work", BigDecimal("1200.00"), 1)))
        accept(billing.commit(NewLineItem(inv, "Hosting", BigDecimal("50.00"), 2)))
        accept(billing.commit(NewIssuance(inv)))
        println("committed customer #${cust.key.value}, invoice #${inv.key.value} with two line items, issued")

        // typed reads: derived properties computed by demand-hydrating rows from SQLite
        billing.read(inv).let { println("   invoice.total=${it.total}  balance=${it.balance}  status=${it.status}") }

        // a `never` refusal: rejected at the boundary, nothing reaches the DB
        val refused = billing.commit(NewSubmitPayment(inv, BigDecimal.ZERO))
        println("zero payment -> $refused")

        // payment in full: one envelope = Payment + largestPayment assign + Receipt,
        // landed as one SQLite transaction; EmailReceipt is `after commit` — its
        // ReceiptEmail is a second envelope, a second SQLite transaction
        accept(billing.commit(NewSubmitPayment(inv, BigDecimal("1300.00"))))
        println("paid in full -> status=${billing.read(inv).status}")
        println()

        // an already-overdue invoice, then the weekly tick sweeps it — the tick's
        // candidate query brings back overdue invoices from SQLite
        accept(billing.commit(NewBillCustomer(cust, due = today().minusDays(10))))
        val overdue = billing.invoices().last()
        accept(billing.commit(NewLineItem(overdue, "Old work", BigDecimal("99.00"), 1)))
        billing.tickWeekly()
        println("committed overdue invoice #${overdue.key.value}, ran the weekly tick")
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

private fun accept(r: Commit<*>): Commit.Accepted<*> = when (r) {
    is Commit.Accepted<*> -> r
    is Commit.Refused<*> -> error("refused: ${r.reason}")
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
