package velle

import java.math.BigDecimal
import velle.generated.*
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
open class MembershipApiTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): MembershipSystem = MembershipSystem()

    @Test
    fun `the ledger derives the current email`() {
        val sys = newSystem()
        assertIs<Commit.Accepted<*>>(sys.commit(NewPlan("Standard", BigDecimal("20"))))
        assertIs<Commit.Accepted<*>>(sys.commit(NewSignUp("Ada", "Ada@Example.com", sys.plans().single())))
        val ada = sys.members().single()

        assertEquals("ada@example.com", sys.read(ada).email, "no changes: lowercased signup email")
        assertEquals(1, sys.welcomeNotes().size, "the compact-for creation fired on entry")

        sys.commit(NewChangeEmail(ada, "New@Example.com"))
        assertEquals("new@example.com", sys.read(ada).email, "the latest ledger entry wins, lowercased")
    }

    @Test
    fun `optional narrowing and composed attention traits`() {
        val sys = newSystem()
        sys.commit(NewPlan("Standard", BigDecimal("20")))
        sys.commit(NewSignUp("Ada", "ada@example.com", sys.plans().single()))
        val ada = sys.members().single()
        sys.commit(NewAgent("Grace", "grace@velle.example"))
        val grace = sys.agents().single()

        sys.commit(NewRaiseTicket(ada, "playback stutters", LocalDate.of(2026, 2, 1), "high"))
        val ticket = sys.tickets().single()

        assertNull(sys.read(ticket).assigneeEmail, "?. short-circuits on the absent assignee")
        assertEquals("support@velle.example", sys.read(ticket).contact, "the is-some else branch")
        assertTrue(sys.system.isMember(sys.system.idOf(ticket), "UrgentQueue"), "high + unassigned composes in")

        sys.commit(NewAssignTicket(ticket, grace))
        assertEquals("grace@velle.example", sys.read(ticket).assigneeEmail)
        assertEquals("grace@velle.example", sys.read(ticket).contact)
        assertTrue(!sys.system.isMember(sys.system.idOf(ticket), "UrgentQueue"), "assigned drops out of the queue")

        // assigning a closed ticket is pure validation: the transient act is
        // refused at the door (`never`), the caller gets the message, and
        // nothing is kept — no act, no refusal record, no change
        sys.commit(NewCloseTicket(ticket, grace))
        val refused = sys.commit(NewAssignTicket(ticket, grace))
        assertIs<Commit.Refused<*>>(refused)
        assertEquals("grace@velle.example", sys.read(ticket).assigneeEmail, "the settled world is untouched")
    }

    @Test
    fun `cross-collection classifications join and reach back`() {
        val sys = newSystem()
        sys.commit(NewPlan("Standard", BigDecimal("20")))
        sys.commit(NewSignUp("Ada", "ada@example.com", sys.plans().single()))
        val ada = sys.members().single()

        sys.tickMonthly() // renewal mints the charge with its generated reference
        val reference = sys.read(sys.charges().single()).reference
        sys.commit(NewRaiseTicket(ada, reference, LocalDate.of(2026, 3, 1), "low"))
        assertTrue(
            sys.system.isMember(sys.system.idOf(ada), "MemberWithDisputedCharge"),
            "sibling join: a ticket subject quoting a charge reference",
        )

        // an already-overdue urgent ticket gets escalated after its due date
        sys.commit(NewRaiseTicket(ada, "old issue", LocalDate.of(2025, 12, 1), "normal"))
        sys.tickDaily()
        assertTrue(
            sys.system.isMember(sys.system.idOf(ada), "LateEscalatedMember"),
            "as reach-back: an escalation raised after the ticket's own due date",
        )
    }

    @Test
    fun `closing captures the closer and the reopen notice reads it on the way out`() {
        val sys = newSystem()
        sys.commit(NewPlan("Standard", BigDecimal("20")))
        sys.commit(NewSignUp("Ada", "ada@example.com", sys.plans().single()))
        val ada = sys.members().single()
        sys.commit(NewAgent("Grace", "grace@velle.example"))
        val grace = sys.agents().single()
        sys.commit(NewRaiseTicket(ada, "billing question", LocalDate.of(2026, 3, 1), "normal"))
        val ticket = sys.tickets().single()

        sys.commit(NewCloseTicket(ticket, grace))
        assertTrue(sys.system.isMember(sys.system.idOf(ticket), "ClosedTicket"))
        assertEquals(grace.key, sys.closedTickets().single().closedBy.key, "the capture holds the closer")

        sys.commit(NewReopenTicket(ticket))
        assertTrue(!sys.system.isMember(sys.system.idOf(ticket), "ClosedTicket"))
        val notice = sys.reopenNotices().single()
        assertEquals(grace.key, sys.read(notice).reassignTo.key, "the exit rule was the capture's last reader")
    }

    @Test
    fun `deposits and charges fold the balance from disjoint writers`() {
        val sys = newSystem()
        sys.commit(NewPlan("Standard", BigDecimal("20")))
        sys.commit(NewSignUp("Ada", "ada@example.com", sys.plans().single()))
        val ada = sys.members().single()

        sys.commit(NewMakeDeposit(ada, BigDecimal("30")))
        assertEquals(0, sys.read(ada).balance.compareTo(BigDecimal("30")), "the deposit folded in after commit")
        assertTrue(sys.read(sys.deposits().single()).applied, "the flag witness disarmed the guard")

        sys.tickMonthly()
        assertEquals(0, sys.read(ada).balance.compareTo(BigDecimal("10")), "the renewal charge folded out")
    }
}

