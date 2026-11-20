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
        // LineItem's two boundary nevers fold into one question
        assertTrue("commitLineItem --> qLineItem{\"quantity <= 0 or price < 0 ?\"}" in graph)
        assertTrue("qLineItem -->|\"violated — refused, nothing begins\"| refLineItem([\"refusal\"])" in graph)
        assertTrue("qLineItem -->|\"passes\"| envLineItem[\"the LineItem envelope\"]" in graph)
    }

    /** OQ157: the graph draws a transient act only through its partitions, so SubmitPayment,
     *  which has a refusal and a rule of its own and no partition, is drawn doing nothing.
     *  This pins what is drawn today; the fix turns each assertion around. */
    @Test
    fun `a transient act with no partition is drawn with no refusal and no rule`() {
        assertFalse("qSubmitPayment" in graph, "never (SubmitPayment where amount <= 0) has no diamond")
        assertFalse("commitSubmitPayment --" in graph, "rule RecordPayment when SubmitPayment has no edge")
        assertFalse("commitSignUp --" in graph, "rule AdmitCustomer when SignUp has no edge")
        assertFalse("commitBillCustomer --" in graph, "rule OpenInvoice when BillCustomer has no edge")
    }

    @Test
    fun `guarded fan-out carries conditions without claiming exclusivity`() {
        assertTrue("RecordPayment -->|\"inserts Payment\"| TrackLargestPayment" in graph, "unconditional firing has no guard label")
        assertTrue("RecordPayment -->|\"inserts Payment — when PaidInvoice\"| SendReceipt" in graph)
    }

    @Test
    fun `transient complement partitions render as a total decision diamond`() {
        assertTrue("commitChangeDueDate --> qChangeDueDate{\"invoice is IssuedInvoice ?\"}" in graph)
        assertTrue("qChangeDueDate -->|\"yes — exactly one branch, the guards are complements\"| RecordDueChangeRefusal" in graph)
        assertTrue("qChangeDueDate -->|\"no\"| ApplyDueChange" in graph)
    }

    @Test
    fun `direction proofs prune impossible edges`() {
        // an ArchiveRequest moves toward membership: it cannot cause the leaving rule
        assertFalse("commitArchiveRequest -->" in graph)
        assertFalse("commitArchiveRequest -.->" in graph)
        assertTrue("commitUnarchiveRequest -->|\"when leaving ArchivedInvoice\"| NoteUnarchival" in graph)
        // a new Invoice is provably in no refinement: the rule that opens one wakes nothing
        assertFalse("OpenInvoice --" in graph)
    }

    /** OQ158: a payment only lowers a balance, so it can quiet RemindOverdue and never arm it.
     *  That was proven while `never (Payment where amount <= 0)` stood on the record. The refusal
     *  now stands on the transient SubmitPayment and the proof does not follow the amount through
     *  rule RecordPayment, so the arming edge is drawn. This pins what is drawn today. */
    @Test
    fun `a payment is drawn arming the overdue sweep`() {
        assertTrue("RecordPayment -.->|\"inserts Payment — swept every Weekly — when ActionableOverdue …\"| RemindOverdue" in graph)
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
