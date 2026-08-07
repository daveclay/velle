package velle

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.generated.MembershipSystem

/**
 * Hand-written scenarios through the membership typed surface, exercising the
 * evaluator paths the generated specs don't reach: the email ledger with
 * latest/lowercase, `?.` and `is some` narrowing, or/and composition, the
 * sibling join and `as` reach-back refinements, and captures read at exit.
 */
class MembershipApiTest {

    @Test
    fun `the ledger derives the current email`() {
        val sys = MembershipSystem()
        assertIs<CommitResult.Accepted>(sys.commitPlan("Standard", BigDecimal("20")))
        assertIs<CommitResult.Accepted>(sys.commitMember("Ada", "Ada@Example.com", sys.plans().single()))
        val ada = sys.members().single()

        assertEquals("ada@example.com", ada.email, "no changes: lowercased signup email")
        assertEquals(1, sys.welcomeNotes().size, "the compact-for creation fired on entry")

        sys.commitEmailChange(ada, "New@Example.com")
        assertEquals("new@example.com", ada.email, "the latest ledger entry wins, lowercased")
    }

    @Test
    fun `optional narrowing and composed attention traits`() {
        val sys = MembershipSystem()
        sys.commitPlan("Standard", BigDecimal("20"))
        sys.commitMember("Ada", "ada@example.com", sys.plans().single())
        val ada = sys.members().single()
        sys.commitAgent("Grace", "grace@velle.example")
        val grace = sys.agents().single()

        sys.commitTicket(ada, "playback stutters", LocalDate.of(2026, 2, 1), "high")
        val ticket = sys.tickets().single()

        assertNull(ticket.assigneeEmail, "?. short-circuits on the absent assignee")
        assertEquals("support@velle.example", ticket.contact, "the is-some else branch")
        assertTrue(sys.system.isMember(ticket.id, "UrgentQueue"), "high + unassigned composes in")

        sys.commitAssignTicket(ticket, grace)
        assertEquals("grace@velle.example", ticket.assigneeEmail)
        assertEquals("grace@velle.example", ticket.contact)
        assertTrue(!sys.system.isMember(ticket.id, "UrgentQueue"), "assigned drops out of the queue")
    }

    @Test
    fun `cross-collection classifications join and reach back`() {
        val sys = MembershipSystem()
        sys.commitPlan("Standard", BigDecimal("20"))
        sys.commitMember("Ada", "ada@example.com", sys.plans().single())
        val ada = sys.members().single()

        sys.tickMonthly() // renewal mints the charge with its generated reference
        val reference = sys.charges().single().reference
        sys.commitTicket(ada, reference, LocalDate.of(2026, 3, 1), "low")
        assertTrue(
            sys.system.isMember(ada.id, "MemberWithDisputedCharge"),
            "sibling join: a ticket subject quoting a charge reference",
        )

        // an already-overdue urgent ticket gets escalated after its due date
        sys.commitTicket(ada, "old issue", LocalDate.of(2025, 12, 1), "normal")
        sys.tickDaily()
        assertTrue(
            sys.system.isMember(ada.id, "LateEscalatedMember"),
            "as reach-back: an escalation raised after the ticket's own due date",
        )
    }

    @Test
    fun `closing captures the closer and the reopen notice reads it on the way out`() {
        val sys = MembershipSystem()
        sys.commitPlan("Standard", BigDecimal("20"))
        sys.commitMember("Ada", "ada@example.com", sys.plans().single())
        val ada = sys.members().single()
        sys.commitAgent("Grace", "grace@velle.example")
        val grace = sys.agents().single()
        sys.commitTicket(ada, "billing question", LocalDate.of(2026, 3, 1), "normal")
        val ticket = sys.tickets().single()

        sys.commitCloseTicket(ticket, grace)
        assertTrue(sys.system.isMember(ticket.id, "ClosedTicket"))
        assertEquals(grace.id, sys.closedTickets().single().closedBy.id, "the capture holds the closer")

        sys.commitReopenTicket(ticket)
        assertTrue(!sys.system.isMember(ticket.id, "ClosedTicket"))
        val notice = sys.reopenNotices().single()
        assertEquals(grace.id, notice.reassignTo.id, "the exit rule was the capture's last reader")
    }

    @Test
    fun `deposits and charges fold the balance from disjoint writers`() {
        val sys = MembershipSystem()
        sys.commitPlan("Standard", BigDecimal("20"))
        sys.commitMember("Ada", "ada@example.com", sys.plans().single())
        val ada = sys.members().single()

        sys.commitDeposit(ada, BigDecimal("30"))
        assertEquals(0, ada.balance.compareTo(BigDecimal("30")), "the deposit folded in after commit")
        assertTrue(sys.deposits().single().applied, "the flag witness disarmed the guard")

        sys.tickMonthly()
        assertEquals(0, ada.balance.compareTo(BigDecimal("10")), "the renewal charge folded out")
    }
}
