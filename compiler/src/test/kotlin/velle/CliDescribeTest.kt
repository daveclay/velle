package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `velle describe` over `examples/billing/billing.velle`: the whole spec, the part about one
 * shape or act (`Invoice`), a refinement's name (`OverdueInvoice`), and a name the spec does not have.
 */
class CliDescribeTest {

    private val billing = "../examples/billing/billing.velle"

    private val sendReceiptSentence = "- SendReceipt: when it becomes true of an Invoice that it is a PaidInvoice, it creates a Receipt with invoice set to it, sentOn set to now."

    private fun describe(vararg arguments: String): CommandOutcome = Cli.run(listOf("describe", billing) + arguments)

    private fun pointer(line: Int, declaration: String) = "  In the spec: $billing, line $line, `$declaration`"

    @Test
    fun `the whole spec is said under a heading per section, each sentence pointing at its line`() {
        val outcome = describe()
        val text = outcome.text

        assertEquals(CommandOutcome.DONE, outcome.exitCode, text)
        assertTrue(text.startsWith("# What $billing says\n"), text)
        assertEquals(
            listOf(
                "## Words and types the spec uses",
                "## What the system keeps",
                "## What someone can ask for",
                "## What the rules do",
                "## What is never allowed",
            ),
            text.lines().filter { line -> line.startsWith("## ") },
        )
        assertTrue("- Status is one of the words \"paid\", \"overdue\", \"open\".\n" in text, text)
        assertTrue(
            "- Payment is a record the system keeps.\n" + pointer(100, "shape Payment") + "\n" +
                "  - Its invoice is one Invoice.\n" +
                "  - Its amount is a Money.\n" +
                "  - Its receivedOn is the time it was created.\n" in text,
            text,
        )
        assertTrue("- SignUp is a request someone makes, not kept.\n" + pointer(21, "transient act SignUp") in text, text)
        assertTrue(
            "- RecordPayment: when a SubmitPayment arrives, it creates a Payment with invoice set to its invoice, amount set to its amount.\n" +
                pointer(106, "rule RecordPayment when SubmitPayment") in text,
            text,
        )
        assertTrue("- A SubmitPayment is refused when its amount is at most 0.\n" + pointer(98, "never (SubmitPayment where amount <= 0)") in text, text)
    }

    @Test
    fun `every declaration of the billing spec has words`() {
        val text = describe().text

        assertTrue("There are no words" !in text, text)
        assertTrue("```velle" !in text, text)
    }

    @Test
    fun `a refinement of an act is said under that act`() {
        val requests = describe().text.substringAfter("## What someone can ask for").substringBefore("## What the rules do")
        val changeDueDate = requests.substringAfter("### ChangeDueDate").substringBefore("### ")

        assertTrue("- A ChangeDueDate is an ApplicableDueChange when its invoice is not an IssuedInvoice." in changeDueDate, requests)
        assertTrue("- A ChangeDueDate is a RefusedDueChange when its invoice is an IssuedInvoice." in changeDueDate, requests)
    }

    @Test
    fun `describing Invoice says each field, each refinement with its body, and the rules over it`() {
        val outcome = describe("Invoice")
        val text = outcome.text

        assertEquals(CommandOutcome.DONE, outcome.exitCode, text)
        assertTrue(text.startsWith("# What $billing says about Invoice\n"), text)
        assertEquals(
            listOf(
                "## What the system keeps", "### Invoice",
                "## What the rules do", "### Invoice",
                "## What rules over other records do to Invoice", "### BillCustomer", "### ChangeDueDate",
                "## What the refinements of other shapes it mentions mean", "### ChangeDueDate",
            ),
            text.lines().filter { line -> line.startsWith("#") && !line.startsWith("# ") },
        )
        assertTrue(
            "- Invoice is a record the system keeps.\n" + pointer(61, "shape Invoice") + "\n" +
                "  - Its customer is one Customer.\n" +
                "  - Its due is a Date.\n" +
                "  - Its reference is a text, starting as a new random identifier.\n" +
                "  - Its issuedOn is the time it was created.\n" +
                "  - Its total is worked out as the sum of amount over its lineItems.\n" +
                "  - Its balance is worked out as its total minus the sum of amount over its payments.\n" +
                "  - Its status is worked out as \"paid\" when its balance is at most 0, otherwise \"overdue\" when its due is before today, otherwise \"open\".\n" in text,
            text,
        )
        assertTrue(
            "- An Invoice is an IssuedInvoice when there is an Issuance for it.\n" +
                pointer(179, "shape IssuedInvoice = Invoice where exists Issuance for this") + "\n" +
                "  - While it is an IssuedInvoice, its due cannot change.\n" in text,
            text,
        )
        assertTrue(
            "- An Invoice is an ArchivedInvoice when there is an ArchiveRequest for it and there is no UnarchiveRequest for it.\n" +
                pointer(220, "shape ArchivedInvoice = Invoice where") + "\n" +
                "  - When it becomes an ArchivedInvoice, it records its archivedOn as today at that moment.\n" in text,
            text,
        )
        assertTrue(
            "- An Invoice is an ActionableOverdue when it is an OverdueInvoice and it is not an ArchivedInvoice." in text,
            "a refinement of a refinement of Invoice is Invoice's: $text",
        )
        assertTrue(
            "- RemindOverdue: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue " +
                "and there is no Reminder whose invoice is it and whose sentOn is later than 7 days ago, " +
                "it creates a Reminder with invoice set to it, sentOn set to today.\n" +
                pointer(158, "rule RemindOverdue") in text,
            text,
        )
        assertTrue("Payment is a record" !in text, "another shape is not described: $text")
    }

    @Test
    fun `describing Invoice includes the rules over other records that create or change one`() {
        val fromElsewhere = describe("Invoice").text.substringAfter("## What rules over other records do to Invoice")

        assertTrue(
            "### BillCustomer\n\n- OpenInvoice: when a BillCustomer arrives, it creates an Invoice with customer set to its customer, due set to its due.\n" +
                pointer(71, "rule OpenInvoice when BillCustomer") in fromElsewhere,
            fromElsewhere,
        )
        assertTrue(
            "### ChangeDueDate\n\n- ApplyDueChange: when it becomes true of a ChangeDueDate that it is an ApplicableDueChange, it sets its invoice's due to its newDue." in fromElsewhere,
            fromElsewhere,
        )
        assertTrue("RecordPayment" !in fromElsewhere, "creating a Payment for an Invoice changes no Invoice: $fromElsewhere")
    }

    @Test
    fun `describing Customer includes a rule that changes a Customer two steps away`() {
        val fromElsewhere = describe("Customer").text.substringAfter("## What rules over other records do to Customer")

        assertTrue("- TrackLargestPayment: when a Payment is created, it sets its invoice's customer's largestPayment" in fromElsewhere, fromElsewhere)
        assertTrue("- ApplyEmailCorrection: when a CorrectEmail arrives, it sets its customer's email to its corrected." in fromElsewhere, fromElsewhere)
        assertTrue("- AdmitCustomer: when a SignUp arrives, it creates a Customer" in fromElsewhere, fromElsewhere)
    }

    @Test
    fun `describing Reminder closes with what the refinements of Invoice it mentions mean`() {
        val text = describe("Reminder").text
        val closing = text.substringAfter("## What the refinements of other shapes it mentions mean\n")

        assertTrue("- RemindOverdue: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue" in text, text)
        assertTrue(
            closing.startsWith(
                "\n### Invoice\n\n" +
                    "- An Invoice is an OverdueInvoice when its balance is above 0 and its due is before today.\n" +
                    pointer(148, "shape OverdueInvoice = Invoice where balance > 0 and due < today") + "\n" +
                    "- An Invoice is an ActionableOverdue when it is an OverdueInvoice and it is not an ArchivedInvoice.\n" +
                    pointer(151, "shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice") + "\n" +
                    "- An Invoice is an ArchivedInvoice when there is an ArchiveRequest for it and there is no UnarchiveRequest for it.\n",
            ),
            text,
        )
        assertTrue("PaidInvoice" !in closing, "a refinement the description does not mention is left out: $text")
    }

    @Test
    fun `describing an act gives its refusals`() {
        val text = describe("LineItem").text

        assertTrue("- A LineItem is refused when its quantity is at most 0." in text, text)
        assertTrue("- A LineItem is refused when its price is below 0." in text, text)
        assertTrue("SubmitPayment" !in text, text)
    }

    @Test
    fun `a refinement's name describes its shape, saying so first`() {
        val outcome = describe("OverdueInvoice")

        assertEquals(CommandOutcome.DONE, outcome.exitCode, outcome.text)
        assertTrue(outcome.text.startsWith("# What $billing says about Invoice\n"), outcome.text)
        assertTrue("\nOverdueInvoice is a refinement of Invoice, so this describes Invoice and everything about it.\n" in outcome.text, outcome.text)
        assertEquals(describe("Invoice").text.lines().filter { line -> line.startsWith("- ") }, outcome.text.lines().filter { line -> line.startsWith("- ") })
    }

    @Test
    fun `a name the spec does not have exits 1 and lists the names it could have been, refinements under their shape`() {
        val outcome = describe("Invoce")

        assertEquals(CommandOutcome.REFUSED, outcome.exitCode, outcome.text)
        assertTrue(
            outcome.text.startsWith(
                "`Invoce` is not the name of a shape, an act, a refinement, or a rule in `$billing`. The names it could be,\n" +
                    "each shape or act with its refinements under it:\n\n",
            ),
            outcome.text,
        )
        assertTrue(
            "- Invoice\n  - ActionableOverdue\n  - ArchivedInvoice\n  - IssuedInvoice\n  - OverdueInvoice\n  - PaidInvoice\n" in outcome.text,
            outcome.text,
        )
        assertTrue("- ChangeDueDate\n  - ApplicableDueChange\n  - RefusedDueChange\n" in outcome.text, outcome.text)
        assertTrue("- SubmitPayment\n" in outcome.text, outcome.text)
        assertTrue("\nand the rules:\n\n- AdmitCustomer\n" in outcome.text, outcome.text)
        assertTrue("- SendReceipt\n" in outcome.text, outcome.text)
    }

    @Test
    fun `describing a rule by name gives its sentence, its place in the spec, and the shape it is over`() {
        val outcome = describe("SendReceipt")

        assertEquals(CommandOutcome.DONE, outcome.exitCode, outcome.text)
        assertTrue(outcome.text.startsWith("# What $billing says about SendReceipt\n"), outcome.text)
        assertTrue(
            "\nSendReceipt is a rule over PaidInvoice, a refinement of Invoice. `velle describe $billing Invoice` says everything about Invoice.\n" in outcome.text,
            outcome.text,
        )
        val bullets = outcome.text.lines().filter { line -> line.startsWith("- ") || line.startsWith("  In the spec") }
        assertEquals(listOf(sendReceiptSentence, pointer(124, "rule SendReceipt when PaidInvoice")), bullets, outcome.text)
    }

    @Test
    fun `a rule over a shape itself names that shape`() {
        val outcome = describe("OpenInvoice")

        assertTrue("\nOpenInvoice is a rule over BillCustomer. `velle describe $billing BillCustomer` says everything about BillCustomer.\n" in outcome.text, outcome.text)
    }

    @Test
    fun `a draft that does not validate is reported as validate reports it`() {
        val refused = "src/test/resources/diagnostics/V2-guard-never-made-false.velle"

        assertEquals(Cli.run(listOf("validate", refused)), Cli.run(listOf("describe", refused)))
    }
}
