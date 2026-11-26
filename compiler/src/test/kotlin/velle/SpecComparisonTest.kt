package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comparison of two specs (`velle.CompareKt`) over seven edits to
 * `examples/billing/billing.velle`. Each test makes one edit, checks that the edited spec
 * still validates, and reads the report a reviewer would read.
 */
class SpecComparisonTest {

    private val billing = File("../examples/billing/billing.velle").readText()

    private fun edited(original: String, replacement: String): String {
        assertTrue(original in billing, "billing.velle no longer holds: $original")
        val editedSpec = billing.replace(original, replacement)
        assertEquals(emptyList(), Validator.validate(editedSpec), "the edited spec still validates")
        return editedSpec
    }

    private fun reportFor(editedSpec: String): String = SpecComparison.report(
        SpecVersion("billing.velle (before)", billing),
        SpecVersion("billing.velle", editedSpec),
    )

    private fun lineOf(text: String, spec: String = billing): Int =
        spec.lines().indexOfFirst { line -> text in line } + 1

    @Test
    fun `case A, moving the customer section to the end of the file is no change`() {
        val customerSection = "-- ── Customers" + billing.substringAfter("-- ── Customers").substringBefore("-- ── Invoices")
        val reorderedSpec = billing.replace(customerSection, "") + "\n" + customerSection

        val report = reportFor(reorderedSpec)

        assertTrue("No change." in report, report)
        assertTrue("## What was edited" !in report, report)
    }

    @Test
    fun `case B, removing the refusal of a negative price says a LineItem with a negative price is no longer refused`() {
        val report = reportFor(edited("never (LineItem where price < 0)\n", ""))

        assertTrue("- A LineItem is no longer refused when its price is below 0." in report, report)
        assertTrue(
            "In the spec: billing.velle (before), line ${lineOf("never (LineItem where price < 0)")}, `never (LineItem where price < 0)`" in report,
            report,
        )
        val consequences = report.substringAfter("## What that changes")
        assertTrue("- A new LineItem can now make an Invoice stop being an OverdueInvoice." in consequences, report)
        assertTrue(
            "- The compiler no longer writes 2 of the example tests it writes from the spec. They checked " +
                "that a LineItem whose price is below 0.00 is refused, and that a LineItem whose price is 0.00 is accepted." in consequences,
            report,
        )
        assertTrue("no longer checked when it arrives" !in report, "a removed refusal is an edit, not a move out of the door: $report")
    }

    @Test
    fun `case C, changing ActionableOverdue says what it now is and what it was`() {
        val editedSpec = edited(
            "shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice",
            "shape ActionableOverdue = OverdueInvoice and not IssuedInvoice",
        )
        val report = reportFor(editedSpec)

        assertTrue(
            "- An Invoice is now an ActionableOverdue when it is an OverdueInvoice and it is not an IssuedInvoice. " +
                "Before: when it is an OverdueInvoice and it is not an ArchivedInvoice." in report,
            report,
        )
        assertTrue(
            "In the spec: billing.velle, line ${lineOf("shape ActionableOverdue", editedSpec)}, `shape ActionableOverdue = OverdueInvoice and not IssuedInvoice`" in report,
            report,
        )
        assertTrue(
            "- A new UnarchiveRequest can no longer cause RemindOverdue to run, later rather than in the same commit." in report.substringAfter("## What that changes"),
            report,
        )
        val records = report.substringAfter("## What happens to a record like this")
        assertTrue(
            "- An Invoice becomes an ActionableOverdue by this change alone when it is an OverdueInvoice, it is not an IssuedInvoice, and it is an ArchivedInvoice." in records,
            report,
        )
        assertTrue("- At the next Weekly tick, RemindOverdue runs for it if it is an ActionableOverdue and there is no Reminder" in records, report)
        assertTrue(
            "- An Invoice stops being an ActionableOverdue by this change alone when it is an OverdueInvoice, it is not an ArchivedInvoice, and it is an IssuedInvoice." in records,
            report,
        )
        assertTrue("- At the next Weekly tick, RemindOverdue no longer runs for it." in records, report)
        assertTrue("example test" !in report, "no generated case tells the two specs apart: $report")
    }

    @Test
    fun `case D, moving the overdue boundary by one day is reported in words`() {
        val report = reportFor(edited("balance > 0 and due < today", "balance > 0 and due <= today"))

        assertTrue(
            "- An Invoice is now an OverdueInvoice when its balance is above 0 and its due is on or before today. " +
                "Before: when its balance is above 0 and its due is before today." in report,
            report,
        )
        val records = report.substringAfter("## What happens to a record like this")
        assertTrue(
            "- An Invoice becomes an OverdueInvoice by this change alone when its balance is above 0, its due is on or before today, and its due is not before today." in records,
            report,
        )
        assertTrue("stops being an OverdueInvoice" !in records, "a weaker condition loses no member: $report")
        assertTrue("- At the next Weekly tick, RemindOverdue could run for it, since ActionableOverdue is made from OverdueInvoice." in records, report)
        assertTrue("example test" !in report, "no generated case tells the two specs apart: $report")
    }

    @Test
    fun `case E, a condition reworded to the same meaning is no change`() {
        val report = reportFor(
            edited(
                "shape PaidInvoice = Invoice where total > 0 and balance <= 0",
                "shape PaidInvoice = Invoice where balance <= 0 and 0 < total",
            ),
        )

        assertTrue("No change." in report, report)
    }

    @Test
    fun `a line item's amount reworded from price times quantity to quantity times price rests on the claim of velle finance`() {
        val report = reportFor(edited("amount: Money = price * quantity", "amount: Money = quantity * price"))

        assertTrue(
            "- LineItem is written differently and means the same, if `velle.finance` is right that the sides may be swapped for `decimal * Money`." in report,
            report,
        )
    }

    @Test
    fun `a rewritten PaidInvoice says SendReceipt could run for an Invoice that became paid by the change alone`() {
        val report = reportFor(edited("shape PaidInvoice = Invoice where total > 0 and balance <= 0", "shape PaidInvoice = Invoice where balance <= 0"))
        val records = report.substringAfter("## What happens to a record like this")

        assertTrue(
            "- An Invoice becomes a PaidInvoice by this change alone when its balance is at most 0 and its total is not above 0." in records,
            report,
        )
        assertTrue(
            "- SendReceipt could then run for it, though nothing happened to the Invoice itself: " +
                "the change to the spec is what made it a PaidInvoice." in records,
            report,
        )
    }

    @Test
    fun `a rewritten PaidInvoice says a LineItem arriving can no longer cause SendReceipt, in the spec's names`() {
        val report = reportFor(edited("shape PaidInvoice = Invoice where total > 0 and balance <= 0", "shape PaidInvoice = Invoice where balance <= 0"))
        val consequences = report.substringAfter("## What that changes").substringBefore("## What happens to a record like this")

        assertTrue("### LineItem\n\n- A new LineItem can no longer cause SendReceipt to run.\n" in consequences, report)
        assertTrue("- A new LineItem can no longer make an Invoice become a PaidInvoice." in consequences, report)
        assertEquals(emptyList(), graphNames(report), "the report names nothing the spec does not: $report")
    }

    @Test
    fun `a rule condition with no words shows the rule as Velle and never leaves the condition out`() {
        val report = reportFor(edited("rule SendReceipt when PaidInvoice {", "rule SendReceipt when (PaidInvoice where reference matches \"^INV-\") {"))

        assertTrue("- SendReceipt changed, and this report has no words for it yet. The Velle:" in report, report)
        assertTrue("rule SendReceipt when (PaidInvoice where reference matches \"^INV-\") {" in report, report)
        assertTrue("when an Invoice becomes a PaidInvoice, it creates" !in report, "the condition is never dropped from the sentence: $report")
    }

    @Test
    fun `no report in the billing history names a node of the rule graph`() {
        val changes = File("../examples/billing/changes")
        val versions = changes.listFiles { file -> file.extension == "velle" }!!.sortedBy { file -> file.name }
        val pairs = versions.zipWithNext() + (versions.first() to versions.last())
        for ((before, after) in pairs) {
            val report = SpecComparison.report(SpecVersion(before.name, before.readText()), SpecVersion(after.name, after.readText()))
            assertEquals(emptyList(), graphNames(report), "${before.name} to ${after.name}: $report")
        }
    }

    /** Names the rule graph gives the arrival of an act and its checks, `envLineItem`, `commitLineItem`, `qLineItem`. */
    private fun graphNames(report: String): List<String> =
        Regex("\\b(env|commit|q|ref|end)[A-Z][A-Za-z0-9]*\\b").findAll(report).map { match -> match.value }.toList()

    @Test
    fun `a constraint added over a kept shape says a record stored before it could be refused at its next change`() {
        val report = reportFor(edited("never (SubmitPayment where amount <= 0)\n", "never (SubmitPayment where amount <= 0)\nnever (Payment where amount <= 0)\n"))

        assertTrue("- A Payment is now forbidden when its amount is at most 0." in report, report)
        assertTrue(
            "- A Payment stored before this change is now forbidden when its amount is at most 0. Its next change could be refused." in report,
            report,
        )
        assertTrue("example test" !in report, "a case the generator skips, such as a never over a kept shape, is not a generated case: $report")
    }

    @Test
    fun `case F, renaming Reminder to Nudge is one rename, with the warning about stored records`() {
        val report = reportFor(edited("Reminder", "Nudge"))

        assertTrue("- Reminder is now called Nudge. Nothing else about it changed." in report, report)
        assertTrue("Records already stored as Reminder are not carried over by this change." in report, report)
        assertEquals(1, report.lines().count { line -> line.startsWith("- ") }, report)
    }

    @Test
    fun `case G, a reminder every fourteen days in place of seven is one changed rule`() {
        val report = reportFor(edited("7 days", "14 days"))

        assertEquals(1, report.lines().count { line -> line.startsWith("- ") }, report)
        assertTrue("- RemindOverdue changed." in report, report)
        assertTrue("whose sentOn is later than 14 days ago" in report, report)
        assertTrue("whose sentOn is later than 7 days ago" in report, report)
        assertTrue("example test" !in report, "no generated case tells the two specs apart: $report")
    }
}
