package velle.generated.membership

import java.math.BigDecimal
import java.time.LocalDate
import velle.generated.MembershipSystem

/**
 * The human-owned scenarios the generated membership specs demand (testgen.md):
 * how to *reach* each interesting state is business judgment the spec doesn't
 * contain, so it lives here — one findable place, named in business language.
 */
class Givens(private val sys: MembershipSystem) : RequiredGivens {

    private fun plan(price: BigDecimal = BigDecimal("20")): MembershipSystem.PlanView {
        sys.commitPlan("Standard", price)
        return sys.plans().last()
    }

    private fun member(
        balance: BigDecimal? = null,
        suspended: Boolean? = null,
    ): MembershipSystem.MemberView {
        sys.commitMember("Ada", "Ada@Example.com", plan(), balance = balance, suspended = suspended)
        return sys.members().last()
    }

    private fun agent(): MembershipSystem.AgentView {
        sys.commitAgent("Grace", "grace@velle.example")
        return sys.agents().last()
    }

    private fun ticket(
        priority: String = "normal",
        due: LocalDate = LocalDate.of(2026, 3, 1),
    ): MembershipSystem.TicketView {
        sys.commitTicket(member(), "billing question", due, priority)
        return sys.tickets().last()
    }

    override fun enterSendWelcome(): Long = member().id

    override fun populateRestoreService(): Long = member(suspended = true).id

    override fun populateScoreEngagement(): Long {
        val m = member()
        sys.commitVisit(m, 30)
        return m.id
    }

    override fun enterCountVisit(): Long {
        sys.commitVisit(member(), 30)
        return sys.visits().last().id
    }

    override fun enterPingAnalytics(): Long {
        sys.commitVisit(member(), 45)
        return sys.visits().last().id
    }

    override fun someMember(): Long = member().id

    override fun enterApplyDeposit(): Long {
        sys.commitDeposit(member(), BigDecimal("25"))
        return sys.deposits().last().id
    }

    override fun populateRenewMembership(): Long = member().id

    override fun enterApplyCharge(): Long {
        member()
        sys.tickMonthly() // the renewal sweep mints the charge; ApplyCharge follows after commit
        return sys.charges().last().id
    }

    override fun enterTrackLowestBalance(): Long = member(balance = BigDecimal("-5")).id

    override fun populateSuspendDelinquents(): Long = member(balance = BigDecimal("-5")).id

    override fun enterOpenDelinquencyEpisode(): Long = member(balance = BigDecimal("-5")).id

    override fun exitCloseDelinquencyEpisode(): Long {
        val m = member(balance = BigDecimal("-5")) // opens the episode at creation
        sys.commitDeposit(m, BigDecimal("10"))     // recovery closes it
        return m.id
    }

    override fun enterOpenAccountReview(): Long {
        // three delinquency episodes: born delinquent, then two renewal charges
        // each driving the recovered balance negative again
        val m = member(balance = BigDecimal("-5"))
        repeat(2) {
            sys.commitDeposit(m, BigDecimal("20")) // recover: episode closes
            sys.advanceDays(31)                    // reopen the 30-day renewal window
            sys.tickMonthly()                      // charge 20 drives the balance negative
        }
        return m.id
    }

    override fun exitNoticeReopen(): Long {
        val t = ticket()
        sys.commitCloseTicket(t, agent())
        sys.commitReopenTicket(t)
        return t.id
    }

    override fun enterApplyAssignment(): Long {
        val t = ticket()
        sys.commitAssignTicket(t, agent())
        return sys.assignTickets().last().id
    }

    override fun enterRecordAssignmentRefusal(): Long {
        val t = ticket()
        sys.commitCloseTicket(t, agent())
        sys.commitAssignTicket(t, agent())
        return sys.assignTickets().last().id
    }

    override fun populateEscalateUrgent(): Long = ticket(priority = "high").id
}
