package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleGraphGenTest {

    private val doc = DiagramGen.generate(File("../examples/billing/billing.velle").readText(), "Billing")
    private val graph = doc.substringAfter("## Rule graph").substringBefore("## Sequence diagrams")

    @Test
    fun `boundary nevers become accept-refuse diamonds`() {
        assertTrue("commitPayment --> qPayment{\"amount <= 0 ?\"}" in graph)
        assertTrue("qPayment -->|\"violated — refused, nothing begins\"| refPayment([\"refusal\"])" in graph)
        assertTrue("qPayment -->|\"passes\"| envPayment[\"the Payment envelope\"]" in graph)
        // LineItem's two boundary nevers fold into one question
        assertTrue("qLineItem{\"quantity <= 0 or price < 0 ?\"}" in graph)
    }

    @Test
    fun `guarded fan-out carries conditions without claiming exclusivity`() {
        assertTrue("envPayment --> TrackLargestPayment" in graph, "unconditional firing has no guard label")
        assertTrue("envPayment -->|\"when PaidInvoice\"| SendReceipt" in graph)
    }

    @Test
    fun `transient complement partitions render as a total decision diamond`() {
        assertTrue("commitChangeDueDate --> qChangeDueDate{\"invoice is IssuedInvoice ?\"}" in graph)
        assertTrue("qChangeDueDate -->|\"yes — exactly one branch, the guards are complements\"| RecordDueChangeRefusal" in graph)
        assertTrue("qChangeDueDate -->|\"no\"| ApplyDueChange" in graph)
    }

    @Test
    fun `direction proofs prune impossible edges`() {
        // a payment only lowers balance: it can quiet RemindOverdue, never arm it
        assertFalse("envPayment -.->" in graph, "no Payment edge crosses into the tick sweep")
        // an ArchiveRequest moves toward membership: it cannot cause the leaving rule
        assertFalse("commitArchiveRequest -->" in graph)
        assertFalse("commitArchiveRequest -.->" in graph)
        assertTrue("commitUnarchiveRequest -->|\"when leaving ArchivedInvoice\"| NoteUnarchival" in graph)
        // a new Invoice is provably in no refinement: its act is inert
        assertFalse("commitInvoice --" in graph)
    }

    @Test
    fun `boundary-crossing edges are dotted and labeled`() {
        assertTrue("SendReceipt -.->|\"inserts Receipt — after commit, healed every Hourly\"| EmailReceipt" in graph)
        assertTrue("envLineItem -.->|\"swept every Weekly — when ActionableOverdue …\"| RemindOverdue" in graph)
        assertTrue("ApplyDueChange -.->|\"writes Invoice.due — swept every Weekly — when ActionableOverdue …\"| RemindOverdue" in graph)
    }

    @Test
    fun `guard-disarm feedback renders as annotated self-loops`() {
        assertTrue("EmailReceipt -.->|\"inserts ReceiptEmail — disarms its own guard\"| EmailReceipt" in graph)
        assertTrue("RemindOverdue -.->|\"inserts Reminder — disarms its own guard\"| RemindOverdue" in graph)
    }

    @Test
    fun `long conditions truncate to a legend`() {
        assertTrue("- **RemindOverdue** fires when ActionableOverdue where not (exists (Reminder" in graph)
    }
}
