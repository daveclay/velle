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

    override fun enterSendWelcome(): MembershipSystem.MemberView = member()

    override fun populateRestoreService(): MembershipSystem.MemberView = member(suspended = true)

    override fun populateScoreEngagement(): MembershipSystem.MemberView {
        val m = member()
        sys.commitVisit(m, 30)
        return m
    }

    override fun enterCountVisit(): MembershipSystem.VisitView {
        sys.commitVisit(member(), 30)
        return sys.visits().last()
    }

    override fun enterPingAnalytics(): MembershipSystem.VisitView {
        sys.commitVisit(member(), 45)
        return sys.visits().last()
    }

    override fun someMember(): MembershipSystem.MemberView = member()

    override fun enterApplyDeposit(): MembershipSystem.DepositView {
        sys.commitDeposit(member(), BigDecimal("25"))
        return sys.deposits().last()
    }

    override fun populateRenewMembership(): MembershipSystem.MemberView = member()

    override fun enterApplyCharge(): MembershipSystem.ChargeView {
        member()
        sys.tickMonthly() // the renewal sweep mints the charge; ApplyCharge follows after commit
        return sys.charges().last()
    }

    override fun enterTrackLowestBalance(): MembershipSystem.MemberView = member(balance = BigDecimal("-5"))

    override fun populateSuspendDelinquents(): MembershipSystem.MemberView = member(balance = BigDecimal("-5"))

    override fun enterOpenDelinquencyEpisode(): MembershipSystem.MemberView = member(balance = BigDecimal("-5"))

    override fun exitCloseDelinquencyEpisode(): MembershipSystem.MemberView {
        val m = member(balance = BigDecimal("-5")) // opens the episode at creation
        sys.commitDeposit(m, BigDecimal("10"))     // recovery closes it
        return m
    }

    override fun enterOpenAccountReview(): MembershipSystem.MemberView {
        // three delinquency episodes: born delinquent, then two renewal charges
        // each driving the recovered balance negative again
        val m = member(balance = BigDecimal("-5"))
        repeat(2) {
            sys.commitDeposit(m, BigDecimal("20")) // recover: episode closes
            sys.advanceDays(31)                    // reopen the 30-day renewal window
            sys.tickMonthly()                      // charge 20 drives the balance negative
        }
        return m
    }

    override fun exitNoticeReopen(): MembershipSystem.TicketView {
        val t = ticket()
        sys.commitCloseTicket(t, agent())
        sys.commitReopenTicket(t)
        return t
    }

    override fun enterApplyAssignment(): MembershipSystem.AssignTicketView {
        val t = ticket()
        sys.commitAssignTicket(t, agent())
        return sys.assignTickets().last()
    }

    override fun enterRecordAssignmentRefusal(): MembershipSystem.AssignTicketView {
        val t = ticket()
        sys.commitCloseTicket(t, agent())
        sys.commitAssignTicket(t, agent())
        return sys.assignTickets().last()
    }

    override fun populateEscalateUrgent(): MembershipSystem.TicketView = ticket(priority = "high")
}
