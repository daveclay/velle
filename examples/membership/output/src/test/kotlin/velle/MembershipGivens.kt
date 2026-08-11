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

    private fun newMember(
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
        sys.commitTicket(newMember(), "billing question", due, priority)
        return sys.tickets().last()
    }

    override fun member(): MembershipSystem.MemberView = newMember()

    override fun someMember(): MembershipSystem.MemberView = newMember()

    override fun memberForRestoreService(): MembershipSystem.MemberView = newMember(suspended = true)

    override fun visit(): MembershipSystem.VisitView {
        sys.commitVisit(newMember(), 30)
        return sys.visits().last()
    }

    override fun unappliedDeposit(): MembershipSystem.DepositView {
        sys.commitDeposit(newMember(), BigDecimal("25"))
        return sys.deposits().last()
    }

    override fun memberForRenewMembership(): MembershipSystem.MemberView = newMember()

    override fun unappliedCharge(): MembershipSystem.ChargeView {
        newMember()
        sys.tickMonthly() // the renewal sweep mints the charge; ApplyCharge follows after commit
        return sys.charges().last()
    }

    override fun delinquent(): MembershipSystem.MemberView = newMember(balance = BigDecimal("-5"))

    override fun memberForSuspendDelinquents(): MembershipSystem.MemberView = newMember(balance = BigDecimal("-5"))

    override fun memberForOpenDelinquencyEpisode(): MembershipSystem.MemberView = newMember(balance = BigDecimal("-5"))

    override fun exitDelinquent(member: MembershipSystem.MemberView) {
        sys.commitDeposit(member, BigDecimal("10")) // a covering deposit: the recovery commit
    }

    override fun memberForOpenAccountReview(): MembershipSystem.MemberView {
        // three delinquency episodes: born delinquent, then two renewal charges
        // each driving the recovered balance negative again
        val m = newMember(balance = BigDecimal("-5"))
        repeat(2) {
            sys.commitDeposit(m, BigDecimal("20")) // recover: episode closes
            sys.advanceDays(31)                    // reopen the 30-day renewal window
            sys.tickMonthly()                      // charge 20 drives the balance negative
        }
        return m
    }

    override fun closedTicket(): MembershipSystem.TicketView {
        val t = ticket()
        sys.commitCloseTicket(t, agent())
        return t
    }

    override fun applicableAssignment() {
        sys.commitAssignTicket(ticket(), agent())
    }

    override fun ticketForEscalateUrgent(): MembershipSystem.TicketView = ticket(priority = "high")
}
