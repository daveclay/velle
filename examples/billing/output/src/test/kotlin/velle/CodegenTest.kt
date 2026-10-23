package velle

import java.io.File
import velle.generated.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.generated.BillingSystem

class CodegenGoldenTest {

    private val systems = listOf("../billing.velle" to "Billing")

    @Test
    fun `the checked-in generated surfaces match the generator`() {
        for ((specPath, systemName) in systems) {
            val expected = Codegen.generate(File(specPath).readText(), systemName)
            val actual = File("src/main/kotlin/velle/generated/$systemName.kt").readText()
            assertEquals(expected, actual, "$systemName surface drifted — run: gradle generate")
            val expectedStore = StoreGen.generate(File(specPath).readText(), systemName)
            val actualStore = File("src/main/kotlin/velle/generated/${systemName}Store.kt").readText()
            assertEquals(expectedStore, actualStore, "${systemName}Store surface drifted — run: gradle generate")
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
            val featuresDir = File("features/${systemName.lowercase()}")
            for ((name, content) in specs.featureFiles) {
                assertEquals(content, File(featuresDir, name).readText(), "$name drifted — run: gradle generate")
            }
            assertEquals(specs.featureFiles.keys,
                featuresDir.listFiles()!!.map { it.name }.toSet(), "stale files in $systemName features/")
        }
    }
}

/** The same billing scenarios, driven through the generated typed surface. */
open class GeneratedApiTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): BillingSystem = BillingSystem()

    @Test
    fun `the typed surface drives the payment cascade`() {
        val sys = newSystem()
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewSignUp("Ada", "a@x.com")))
        val ada = sys.customers().single()

        assertIs<Commit.Accepted<*, *>>(sys.commit(NewBillCustomer(ada, LocalDate.of(2026, 2, 1))))
        val invoice = sys.invoices().single()
        assertIs<Commit.Accepted<*, *>>(
            sys.commit(NewLineItem(invoice, "widgets", BigDecimal("100"), 2)))

        assertEquals(0, sys.read(invoice).total.compareTo(BigDecimal("200")))
        assertEquals("open", sys.read(invoice).status)
        assertEquals(2, sys.read(sys.read(invoice).lineItems.single()).quantity)
        assertEquals("Ada", sys.read(sys.read(invoice).customer).name)

        assertIs<Commit.Accepted<*, *>>(sys.commit(NewSubmitPayment(invoice, BigDecimal("200"))))
        with(sys) { assertTrue(sys.read(invoice).isPaidInvoice) }
        assertEquals("paid", sys.read(invoice).status)
        assertEquals(1, sys.receipts().size)
        assertEquals(1, sys.receiptEmails().size)
        assertEquals(0, sys.read(ada).largestPayment.compareTo(BigDecimal("200")))
        assertEquals(invoice, sys.read(sys.receipts().single()).invoice)
    }

    @Test
    fun `refusals surface through the typed commits`() {
        val sys = newSystem()
        sys.commit(NewSignUp("Ada", "a@x.com"))
        sys.commit(NewBillCustomer(sys.customers().single(), LocalDate.of(2026, 2, 1)))
        val input = NewSubmitPayment(sys.invoices().single(), BigDecimal.ZERO)
        val refused = assertIs<Commit.Refused<NewSubmitPayment, *>>(sys.commit(input))
        // the verdict carries what was refused as the engineer's own type, not a map
        assertEquals(input, refused.input)
        assertEquals(0, refused.input.amount.compareTo(BigDecimal.ZERO))
        assertEquals(0, sys.payments().size)
        val accepted = assertIs<Commit.Accepted<NewSubmitPayment, *>>(
            sys.commit(NewSubmitPayment(sys.invoices().single(), BigDecimal("5"))))
        assertEquals(0, accepted.input.amount.compareTo(BigDecimal("5")))
    }

    @Test
    fun `refinement views expose captures`() {
        val sys = newSystem()
        sys.commit(NewSignUp("Ada", "a@x.com"))
        sys.commit(NewBillCustomer(sys.customers().single(), LocalDate.of(2026, 2, 1)))
        val invoice = sys.invoices().single()

        sys.commit(NewArchiveRequest(invoice))
        val archived = sys.archivedInvoices().single()
        assertEquals(LocalDate.of(2026, 1, 1), archived.archivedOn)
        assertEquals(invoice, (sys.read(archived) as? Invoice))

        sys.advanceDays(2)
        sys.commit(NewUnarchiveRequest(invoice))
        assertEquals(LocalDate.of(2026, 1, 1), sys.read(sys.unarchiveNotices().single()).wasArchivedOn)
        assertEquals(0, sys.archivedInvoices().size)
    }

    @Test
    fun `ticks drive the sweep through the typed surface`() {
        val sys = newSystem()
        sys.commit(NewSignUp("Ada", "a@x.com"))
        sys.commit(NewBillCustomer(sys.customers().single(), LocalDate.of(2025, 12, 1)))
        sys.commit(NewLineItem(sys.invoices().single(), "widgets", BigDecimal("50"), 1))

        with(sys) { assertTrue(sys.read(invoices().single()).isOverdueInvoice) }
        sys.tickWeekly()
        assertEquals(1, sys.reminders().size)
        sys.tickWeekly()
        assertEquals(1, sys.reminders().size)
        sys.advanceDays(8)
        sys.tickWeekly()
        assertEquals(2, sys.reminders().size)
    }
}

