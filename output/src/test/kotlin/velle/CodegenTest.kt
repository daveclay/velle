package velle

import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.generated.BillingSystem

class CodegenGoldenTest {

    private val systems = listOf("../billing.velle" to "Billing", "../membership.velle" to "Membership")

    @Test
    fun `the checked-in generated surfaces match the generator`() {
        for ((specPath, systemName) in systems) {
            val expected = Codegen.generate(File(specPath).readText(), systemName)
            val actual = File("src/main/kotlin/velle/generated/$systemName.kt").readText()
            assertEquals(expected, actual, "$systemName surface drifted — run: gradle generate")
        }
    }

    @Test
    fun `the checked-in generated specs match the generator`() {
        for ((specPath, systemName) in systems) {
            val specs = SpecGen.generate(File(specPath).readText(), systemName)
            val systemDir = File("src/test/kotlin/velle/generated/${systemName.lowercase()}")
            val specsDir = File(systemDir, "specs")
            for ((name, content) in specs.specFiles) {
                assertEquals(content, File(specsDir, name).readText(), "$name drifted — run: gradle generate")
            }
            assertEquals(specs.support, File(specsDir, "SpecSupport.kt").readText())
            assertEquals(specs.requiredGivens, File(systemDir, "RequiredGivens.kt").readText())
            assertEquals(specs.index, File("SPEC_INDEX-$systemName.md").readText())
            assertEquals(specs.specFiles.keys + "SpecSupport.kt",
                specsDir.listFiles()!!.map { it.name }.toSet(), "stale files in $systemName specs/")
        }
    }
}

/** The same billing scenarios, driven through the generated typed surface. */
class GeneratedApiTest {

    @Test
    fun `the typed surface drives the payment cascade`() {
        val sys = BillingSystem()
        assertIs<CommitResult.Accepted>(sys.commitCustomer("Ada", "a@x.com"))
        val ada = sys.customers().single()

        assertIs<CommitResult.Accepted>(sys.commitInvoice(ada, LocalDate.of(2026, 2, 1)))
        val invoice = sys.invoices().single()
        assertIs<CommitResult.Accepted>(
            sys.commitLineItem(invoice, "widgets", BigDecimal("100"), 2))

        assertEquals(0, invoice.total.compareTo(BigDecimal("200")))
        assertEquals("open", invoice.status)
        assertEquals(2, invoice.lineItems.single().quantity)
        assertEquals("Ada", invoice.customer.name)

        assertIs<CommitResult.Accepted>(sys.commitPayment(invoice, BigDecimal("200")))
        with(sys) { assertTrue(invoice.isPaidInvoice()) }
        assertEquals("paid", invoice.status)
        assertEquals(1, sys.receipts().size)
        assertEquals(1, sys.receiptEmails().size)
        assertEquals(0, ada.largestPayment.compareTo(BigDecimal("200")))
        assertEquals(invoice, sys.receipts().single().invoice)
    }

    @Test
    fun `refusals surface through the typed commits`() {
        val sys = BillingSystem()
        sys.commitCustomer("Ada", "a@x.com")
        sys.commitInvoice(sys.customers().single(), LocalDate.of(2026, 2, 1))
        val refused = sys.commitPayment(sys.invoices().single(), BigDecimal.ZERO)
        assertIs<CommitResult.Refused>(refused)
        assertEquals(0, sys.payments().size)
    }

    @Test
    fun `refinement views expose captures`() {
        val sys = BillingSystem()
        sys.commitCustomer("Ada", "a@x.com")
        sys.commitInvoice(sys.customers().single(), LocalDate.of(2026, 2, 1))
        val invoice = sys.invoices().single()

        sys.commitArchiveRequest(invoice)
        val archived = sys.archivedInvoices().single()
        assertEquals(LocalDate.of(2026, 1, 1), archived.archivedOn)
        assertEquals(invoice, archived.asInvoice())

        sys.advanceDays(2)
        sys.commitUnarchiveRequest(invoice)
        assertEquals(LocalDate.of(2026, 1, 1), sys.unarchiveNotices().single().wasArchivedOn)
        assertEquals(0, sys.archivedInvoices().size)
    }

    @Test
    fun `ticks drive the sweep through the typed surface`() {
        val sys = BillingSystem()
        sys.commitCustomer("Ada", "a@x.com")
        sys.commitInvoice(sys.customers().single(), LocalDate.of(2025, 12, 1))
        sys.commitLineItem(sys.invoices().single(), "widgets", BigDecimal("50"), 1)

        with(sys) { assertTrue(invoices().single().isOverdueInvoice()) }
        sys.tickWeekly()
        assertEquals(1, sys.reminders().size)
        sys.tickWeekly()
        assertEquals(1, sys.reminders().size)
        sys.advanceDays(8)
        sys.tickWeekly()
        assertEquals(2, sys.reminders().size)
    }
}
