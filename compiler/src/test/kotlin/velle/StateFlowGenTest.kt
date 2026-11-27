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
        // a LineItem with a positive quantity and no negative price only raises the balance: one way into Overdue
        assertTrue("otherwise --> OverdueInvoice : commitLineItem" in invoice)
        assertFalse("OverdueInvoice --> otherwise : commitLineItem" in invoice, "a LineItem can never clear an overdue balance")
        // a LineItem moves total and balance in opposite senses — no proof, hedge both ways
        assertTrue("otherwise --> PaidInvoice : commitLineItem — may flip" in invoice)
        assertTrue("PaidInvoice --> otherwise : commitLineItem — may flip" in invoice)
        // `due < today` can only be satisfied by the clock advancing
        assertTrue("otherwise --> OverdueInvoice : time passes" in invoice)
        // a rule's write with no provable direction hedges too
        assertTrue("ApplyDueChange writes Invoice.due — may flip" in invoice)
    }

    /** OQ158: a payment only moves an invoice toward paid. That was proven while
     *  `never (Payment where amount <= 0)` stood on the record. The refusal now stands on the
     *  transient SubmitPayment and the proof does not follow the amount through rule
     *  RecordPayment, so every payment edge is hedged both ways. This pins what is drawn today. */
    @Test
    fun `a payment is drawn as able to un-pay an invoice`() {
        assertTrue("otherwise --> PaidInvoice : RecordPayment inserts Payment — may flip" in invoice)
        assertTrue("PaidInvoice --> otherwise : RecordPayment inserts Payment — may flip" in invoice)
        assertTrue("otherwise --> OverdueInvoice : RecordPayment inserts Payment — may flip" in invoice)
        assertTrue("OverdueInvoice --> otherwise : RecordPayment inserts Payment — may flip" in invoice)
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

    private fun movingLoans(lateLoanTest: String) = """
        act Member { name: text }

        act Loan {
            member: one Member
            dueOn: Date
        }

        transient act Transfer {
            loan: one Loan
            receiver: one Member
        }

        rule MoveLoan when Transfer {
            loan.member = receiver
        }

        shape MemberWithLateLoan = Member where $lateLoanTest
    """.trimIndent()

    @Test
    fun `a rule moving a loan to another member draws the member moving both ways`() {
        val member = memberStateFlow(movingLoans("exists (loans where dueOn < today)"))

        assertTrue("out --> MemberWithLateLoan : MoveLoan writes Loan.member — may flip" in member, member)
        assertTrue("MemberWithLateLoan --> out : MoveLoan writes Loan.member — may flip" in member, member)
    }

    @Test
    fun `a rule moving a loan to another member draws the member moving both ways when the loans are read with for`() {
        val member = memberStateFlow(movingLoans("exists (Loan for this where dueOn < today)"))

        assertTrue("out --> MemberWithLateLoan : MoveLoan writes Loan.member — may flip" in member, member)
        assertTrue("MemberWithLateLoan --> out : MoveLoan writes Loan.member — may flip" in member, member)
    }

    @Test
    fun `time passing draws the member into one with a late loan and never out, read as the loans naming the member`() {
        val member = memberStateFlow(movingLoans("exists (Loan where member == this and dueOn < today)"))

        assertTrue("out --> MemberWithLateLoan : time passes\n" in member, member)
        assertFalse("MemberWithLateLoan --> out : time passes" in member, member)
    }

    @Test
    fun `time passing draws the member into one with a late loan and never out, read as the loans for the member`() {
        val member = memberStateFlow(movingLoans("exists (Loan for this where dueOn < today)"))

        assertTrue("out --> MemberWithLateLoan : time passes\n" in member, member)
        assertFalse("MemberWithLateLoan --> out : time passes" in member, member)
    }

    @Test
    fun `a rule moving a loan whose member is declared on a taken shape draws the member moving both ways`() {
        val member = memberStateFlow(
            """
            act Member { name: text }

            shape Borrowing {
                member: one Member
                dueOn: Date
            }

            act Loan is Borrowing { title: text }

            act Rental is Borrowing { fee: integer }

            transient act Transfer {
                loan: one Loan
                receiver: one Member
            }

            rule MoveLoan when Transfer {
                loan.member = receiver
            }

            shape MemberWithLateBorrowing = Member where exists (borrowings where dueOn < today)
            """.trimIndent()
        )

        assertTrue("out --> MemberWithLateBorrowing : MoveLoan writes Loan.member — may flip" in member, member)
        assertTrue("MemberWithLateBorrowing --> out : MoveLoan writes Loan.member — may flip" in member, member)
        assertTrue("out --> MemberWithLateBorrowing : commitLoan" in member, member)
        assertTrue("out --> MemberWithLateBorrowing : commitRental" in member, member)
        assertFalse("MemberWithLateBorrowing --> out : time passes" in member, member)
    }

    private fun memberStateFlow(spec: String): String =
        DiagramGen.generate(spec, "Library").substringAfter("## State flow").substringAfter("### Member").substringBefore("\n### ")
}
