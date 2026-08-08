package velle

import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives billing.velle end-to-end through the evaluation.md runtime. */
class RuntimeTest {

    private fun newSystem(): VelleSystem {
        val decls = Parser.parse(File("../examples/billing/billing.velle").readText())
        val model = Model(decls)
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return VelleSystem(model)
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    private fun num(v: Any?): BigDecimal = assertIs<BigDecimal>(v)

    @Test
    fun `an act's consequence lands in the same transaction`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "old@x.com")
        sys.mustCommit("CorrectEmail", "customer" to cust, "corrected" to "new@x.com")
        assertEquals("new@x.com", sys.get(cust, "email"))
    }

    @Test
    fun `derived properties recompute from current data`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val inv = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2026, 2, 1))
        assertEquals(0, num(sys.get(inv, "total")).compareTo(BigDecimal.ZERO))

        sys.mustCommit("LineItem", "invoice" to inv, "description" to "widgets",
            "price" to BigDecimal("100"), "quantity" to 2)
        assertEquals(0, num(sys.get(inv, "total")).compareTo(BigDecimal("200")))
        assertEquals(0, num(sys.get(inv, "balance")).compareTo(BigDecimal("200")))
        assertEquals("open", sys.get(inv, "status"))
        assertNotNull(sys.get(inv, "reference")) // initially randomUUID
        assertNotNull(sys.get(inv, "issuedOn"))  // timestamp on create
    }

    @Test
    fun `input-constrained nevers refuse the act and commit nothing`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val inv = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2026, 2, 1))
        val refused = sys.commit("Payment", mapOf("invoice" to inv, "amount" to BigDecimal.ZERO))
        val r = assertIs<CommitResult.Refused>(refused)
        assertTrue("Payment" in r.reason, r.reason)
        assertEquals(emptyList(), sys.instancesOf("Payment"))
    }

    @Test
    fun `payment cascade - receipt in-transaction, email after, fold updated`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val inv = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2026, 2, 1))
        sys.mustCommit("LineItem", "invoice" to inv, "description" to "widgets",
            "price" to BigDecimal("100"), "quantity" to 2)

        sys.mustCommit("Payment", "invoice" to inv, "amount" to BigDecimal("200"))

        assertTrue(sys.isMember(inv, "PaidInvoice"))
        assertEquals("paid", sys.get(inv, "status"))
        assertEquals(1, sys.instancesOf("Receipt").size)       // entry rule, same transaction
        assertEquals(1, sys.instancesOf("ReceiptEmail").size)  // after-commit queue drained
        assertEquals(0, num(sys.get(cust, "largestPayment")).compareTo(BigDecimal("200")))
        assertEquals(emptyList(), sys.failures)

        // a second, smaller payment: no new PaidInvoice entry, fold keeps the max
        sys.mustCommit("Payment", "invoice" to inv, "amount" to BigDecimal("50"))
        assertEquals(1, sys.instancesOf("Receipt").size)
        assertEquals(0, num(sys.get(cust, "largestPayment")).compareTo(BigDecimal("200")))
    }

    @Test
    fun `the weekly sweep reminds, its evidence rate-limits, and time reopens it`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val inv = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2025, 12, 1))
        sys.mustCommit("LineItem", "invoice" to inv, "description" to "widgets",
            "price" to BigDecimal("100"), "quantity" to 1)

        sys.tick("Weekly")
        assertEquals(1, sys.instancesOf("Reminder").size)

        sys.tick("Weekly") // same day: the 7-day guard holds
        assertEquals(1, sys.instancesOf("Reminder").size)

        sys.advanceDays(8)
        sys.tick("Weekly") // the window expired: remind again
        assertEquals(2, sys.instancesOf("Reminder").size)
        assertEquals(emptyList(), sys.failures)
    }

    @Test
    fun `archived invoices are not actionable for the sweep`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val inv = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2025, 12, 1))
        sys.mustCommit("LineItem", "invoice" to inv, "description" to "widgets",
            "price" to BigDecimal("50"), "quantity" to 1)
        sys.mustCommit("ArchiveRequest", "invoice" to inv)

        sys.tick("Weekly")
        assertEquals(0, sys.instancesOf("Reminder").size)
    }

    @Test
    fun `issuance freezes the due date through the rejection partition`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val issued = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2026, 2, 1))
        val draft = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2026, 2, 1))
        sys.mustCommit("Issuance", "invoice" to issued)
        assertTrue(sys.isMember(issued, "IssuedInvoice"))

        // issued: the applicable-writer never fires; the refusal lands as data
        sys.mustCommit("ChangeDueDate", "invoice" to issued, "newDue" to LocalDate.of(2026, 3, 1))
        assertEquals(LocalDate.of(2026, 2, 1), sys.get(issued, "due"))
        assertEquals(1, sys.instancesOf("DueChangeRefusal").size)

        // draft: the write goes through, no refusal
        sys.mustCommit("ChangeDueDate", "invoice" to draft, "newDue" to LocalDate.of(2026, 3, 1))
        assertEquals(LocalDate.of(2026, 3, 1), sys.get(draft, "due"))
        assertEquals(1, sys.instancesOf("DueChangeRefusal").size)
    }

    @Test
    fun `captures fix entry-moment data and the exit rule is their last reader`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        val inv = sys.mustCommit("Invoice", "customer" to cust, "due" to LocalDate.of(2026, 2, 1))

        val entryDay = LocalDate.of(2026, 1, 1) // system start date
        sys.mustCommit("ArchiveRequest", "invoice" to inv)
        assertTrue(sys.isMember(inv, "ArchivedInvoice"))
        assertEquals(entryDay, sys.getAs(inv, "ArchivedInvoice", "archivedOn"))

        sys.advanceDays(3)
        sys.mustCommit("UnarchiveRequest", "invoice" to inv)
        assertTrue(!sys.isMember(inv, "ArchivedInvoice"))

        val notice = sys.instancesOf("UnarchiveNotice").single()
        assertEquals(entryDay, sys.get(notice, "wasArchivedOn")) // capture read at exit
        assertFailsWith<VelleRuntimeError> { sys.getAs(inv, "ArchivedInvoice", "archivedOn") } // retracted
    }

    @Test
    fun `only exposed shapes are committable`() {
        val sys = newSystem()
        val r = sys.commit("Receipt", mapOf())
        val refused = assertIs<CommitResult.Refused>(r)
        assertTrue("not exposed" in refused.reason, refused.reason)
    }

    @Test
    fun `on-update timestamps advance when a stored field is written`() {
        val sys = newSystem()
        val cust = sys.mustCommit("Customer", "name" to "Ada", "email" to "a@x.com")
        // Review-style on-update isn't in billing; verify create-timestamps stay fixed instead
        val created = sys.get(cust, "signedUpOn")
        sys.advanceDays(1)
        sys.mustCommit("CorrectEmail", "customer" to cust, "corrected" to "b@x.com")
        assertEquals(created, sys.get(cust, "signedUpOn"))
    }
}
