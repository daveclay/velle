package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateFlowGenTest {

    private val doc = DiagramGen.generate(File("../examples/billing/billing.velle").readText(), "Billing")
    private val flow = doc.substringAfter("## State flow").substringBefore("## Sequence diagrams")
    private val invoice = flow.substringAfter("### Invoice").substringBefore("\n### ")
    private val receipt = flow.substringAfter("### Receipt").substringBefore("\n### ")
    private val change = flow.substringAfter("### ChangeDueDate")

    @Test
    fun `disjoint payment memberships share one axis with directed and hedged edges`() {
        assertTrue("**PaidInvoice / OverdueInvoice**" in invoice, "complementary balance comparisons should group")
        // never (Payment where amount <= 0) pins the fold's sign: a Payment only moves toward Paid
        assertTrue("otherwise --> PaidInvoice : commitPayment" in invoice)
        assertTrue("OverdueInvoice --> otherwise : commitPayment" in invoice)
        assertFalse("PaidInvoice --> otherwise : commitPayment" in invoice, "a Payment can never un-pay")
        // a LineItem moves total and balance in opposite senses — no proof, hedge both ways
        assertTrue("otherwise --> PaidInvoice : commitLineItem — may flip" in invoice)
        assertTrue("PaidInvoice --> otherwise : commitLineItem — may flip" in invoice)
        // `due < today` can only be satisfied by the clock advancing
        assertTrue("otherwise --> OverdueInvoice : time passes" in invoice)
        // a rule's write with no provable direction hedges too
        assertTrue("ApplyDueChange writes Invoice.due — may flip" in invoice)
    }

    @Test
    fun `a monotone exists renders as a one-way axis`() {
        assertTrue("**IssuedInvoice** — one-way" in invoice)
        assertTrue("pre --> IssuedInvoice : commitIssuance" in invoice)
        assertFalse("IssuedInvoice -->" in invoice, "nothing can leave IssuedInvoice")
        assertTrue("due frozen" in invoice)
    }

    @Test
    fun `the archival chain is once-through with a terminal state`() {
        assertTrue("**ArchivedInvoice** — once through" in invoice)
        assertTrue("pre --> ArchivedInvoice : commitArchiveRequest" in invoice)
        assertTrue("ArchivedInvoice --> post : commitUnarchiveRequest" in invoice)
        assertTrue("terminal" in invoice)
        assertTrue("captured archivedOn" in invoice)
        assertTrue("on exit — NoteUnarchival" in invoice)
    }

    @Test
    fun `a negated exists is born a member and leaves one way`() {
        assertTrue("**UnemailedReceipt** — born a member" in receipt)
        assertTrue("[*] --> UnemailedReceipt" in receipt)
        assertTrue("UnemailedReceipt --> post : EmailReceipt inserts ReceiptEmail" in receipt)
        assertTrue("healed at every Hourly tick" in receipt)
    }

    @Test
    fun `a transient act renders as a decided-once total choice`() {
        assertTrue("state decide <<choice>>" in change)
        assertTrue("decide --> ApplicableDueChange" in change)
        assertTrue("decide --> RefusedDueChange" in change)
        assertTrue("ApplicableDueChange --> [*] : ApplyDueChange" in change)
        assertTrue("RefusedDueChange --> [*] : RecordDueChangeRefusal" in change)
        assertTrue("exactly one branch" in change, "complement guards should be reported as total")
        assertFalse("no partition applies" in change)
    }

    @Test
    fun `composed refinements and their rules are views, not states`() {
        assertTrue("**ActionableOverdue** = OverdueInvoice and not ArchivedInvoice" in invoice)
        assertTrue("**RemindOverdue** — runs at every Weekly tick" in invoice)
        assertFalse("--> ActionableOverdue" in invoice, "a composition is not a state")
    }
}
