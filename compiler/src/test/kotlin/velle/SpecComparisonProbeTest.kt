package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OQ170: what the generated diagrams and the generated spec index say today when
 * `examples/billing/billing.velle` is edited in one place. Each test makes one edit and
 * compares the lines generated before it with the lines generated after it. Nothing in the
 * compiler compares two specs; these tests pin what a reviewer is left to read.
 */
class SpecComparisonProbeTest {

    private val billing = File("../examples/billing/billing.velle").readText()
    private val diagramsBefore = DiagramGen.generate(billing, "Billing").lines()
    private val indexBefore = SpecGen.generate(billing, "Billing").index.lines()

    private class GeneratedChange(val removedLines: List<String>, val addedLines: List<String>) {
        val isEmpty get() = removedLines.isEmpty() && addedLines.isEmpty()
    }

    private fun changeBetween(linesBefore: List<String>, linesAfter: List<String>) = GeneratedChange(
        removedLines = (linesBefore - linesAfter.toSet()).map(String::trim),
        addedLines = (linesAfter - linesBefore.toSet()).map(String::trim),
    )

    private fun edited(original: String, replacement: String): String {
        assertTrue(original in billing, "billing.velle no longer holds: $original")
        val editedSpec = billing.replace(original, replacement)
        assertEquals(emptyList(), Validator.validate(editedSpec), "the edited spec still validates")
        return editedSpec
    }

    private fun diagramChange(editedSpec: String) =
        changeBetween(diagramsBefore, DiagramGen.generate(editedSpec, "Billing").lines())

    private fun indexChange(editedSpec: String) =
        changeBetween(indexBefore, SpecGen.generate(editedSpec, "Billing").index.lines())

    @Test
    fun `case A, moving the customer section to the end of the file changes the order of generated lines and none of them`() {
        val customerSection = "-- ── Customers" + billing.substringAfter("-- ── Customers").substringBefore("-- ── Invoices")
        val reorderedSpec = billing.replace(customerSection, "") + "\n" + customerSection
        assertEquals(emptyList(), Validator.validate(reorderedSpec), "the reordered spec still validates")
        val diagramsAfter = DiagramGen.generate(reorderedSpec, "Billing").lines()

        assertTrue(changeBetween(diagramsBefore, diagramsAfter).isEmpty)
        assertTrue(diagramsBefore != diagramsAfter, "the same lines stand in another order, so a text comparison reports a change")
    }

    @Test
    fun `case B, removing the refusal of a negative price lets a line item clear an overdue invoice, shown on Invoice and not on LineItem`() {
        val withoutPriceRefusal = edited("never (LineItem where price < 0)\n", "")
        val diagrams = diagramChange(withoutPriceRefusal)
        val index = indexChange(withoutPriceRefusal)

        assertTrue("otherwise --> OverdueInvoice : commitLineItem" in diagrams.removedLines)
        assertTrue("OverdueInvoice --> otherwise : commitLineItem — may flip" in diagrams.addedLines)
        assertEquals(
            listOf(
                "- never - a LineItem where price below 0.00 is refused",
                "- never - a LineItem with price 0.00 is accepted",
            ),
            index.removedLines,
        )
        assertEquals(emptyList(), index.addedLines)
    }

    @Test
    fun `case C, reminding issued invoices in place of archived ones removes one arrow and says nothing of archived invoices now being reminded`() {
        val remindingArchived = edited(
            "shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice",
            "shape ActionableOverdue = OverdueInvoice and not IssuedInvoice",
        )
        val diagrams = diagramChange(remindingArchived)

        assertTrue(
            "commitUnarchiveRequest -.->|\"swept every Weekly — when ActionableOverdue …\"| RemindOverdue" in diagrams.removedLines
        )
        assertEquals(
            listOf(
                "ActionableOverdue ..> IssuedInvoice",
                "note for ActionableOverdue \"= OverdueInvoice and not IssuedInvoice\"",
                "- **ActionableOverdue** = OverdueInvoice and not IssuedInvoice — a view composed across the axes below, not an axis of its own.",
            ),
            diagrams.addedLines,
        )
        assertTrue(indexChange(remindingArchived).isEmpty, "no generated case tells the two specs apart")
    }

    @Test
    fun `case D, moving the overdue boundary by one day changes one note and no generated case`() {
        val overdueOnTheDueDate = edited("balance > 0 and due < today", "balance > 0 and due <= today")
        val diagrams = diagramChange(overdueOnTheDueDate)

        assertEquals(listOf("note for OverdueInvoice \"= Invoice where (balance > 0) and (due <= today)\""), diagrams.addedLines)
        assertTrue(indexChange(overdueOnTheDueDate).isEmpty, "no generated case tells the two specs apart")
    }

    @Test
    fun `case E, a condition reworded to the same meaning is drawn as a changed condition`() {
        val rewordedPaid = edited(
            "shape PaidInvoice = Invoice where total > 0 and balance <= 0",
            "shape PaidInvoice = Invoice where balance <= 0 and 0 < total",
        )
        val diagrams = diagramChange(rewordedPaid)

        assertEquals(listOf("note for PaidInvoice \"= Invoice where (balance <= 0) and (0 < total)\""), diagrams.addedLines)
        assertTrue(indexChange(rewordedPaid).isEmpty)
    }

    @Test
    fun `case F, renaming Reminder to Nudge changes eleven generated lines and one generated case`() {
        val renamedSpec = edited("Reminder", "Nudge")

        assertEquals(11, diagramChange(renamedSpec).addedLines.size)
        assertEquals(
            listOf("- RemindOverdue - at the Weekly tick, an ActionableOverdue produces a Nudge"),
            indexChange(renamedSpec).addedLines,
        )
    }

    @Test
    fun `case G, a reminder every fourteen days in place of seven repeats one changed sentence eight times`() {
        val fortnightlyReminders = edited("7 days", "14 days")
        val diagrams = diagramChange(fortnightlyReminders)

        assertEquals(8, diagrams.addedLines.size)
        assertTrue(diagrams.addedLines.all { line -> "(today - 14 days)" in line })
        assertTrue(indexChange(fortnightlyReminders).isEmpty, "no generated case tells the two specs apart")
    }
}
