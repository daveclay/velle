package velle.generated.membership

import java.math.BigDecimal
import velle.generated.*
import velle.generated.Member
import java.time.LocalDate
import velle.generated.MembershipSystem

/**
 * The human-owned scenarios the generated membership specs demand (testgen.md):
 * how to *reach* each interesting state is business judgment the spec doesn't
 * contain, so it lives here — one findable place, named in business language.
 *
 * Members carry no committer-suppliable bookkeeping (V21): a delinquent member
 * is *made* delinquent the way the business does it — a renewal charge the
 * balance can't cover — never handed a negative balance at the door.
 */
class Givens(private val sys: MembershipSystem) : RequiredGivens {

    private fun plan(price: BigDecimal = BigDecimal("20")): Plan {
        sys.commitPlan("Standard", price)
        return sys.plans().last()
    }

    private fun newMember(): Member {
        sys.commitSignUp("Ada", "Ada@Example.com", plan())
        return sys.members().last()
    }

    /** Sign up, then let the monthly renewal charge drive the balance to -20. */
    private fun delinquentMember(): Member {
        val m = newMember()
        sys.tickMonthly()
        return m
    }

    private fun agent(): Agent {
        sys.commitAgent("Grace", "grace@velle.example")
        return sys.agents().last()
    }

    private fun ticket(
        priority: String = "normal",
        due: LocalDate = LocalDate.of(2026, 3, 1),
    ): Ticket {
        sys.commitRaiseTicket(newMember(), "billing question", due, priority)
        return sys.tickets().last()
    }

    override fun signUp() {
        sys.commitSignUp("Grace", "Grace@Example.com", plan())
    }

    override fun changeEmail() {
        sys.commitChangeEmail(newMember(), "New@Example.com")
    }

    override fun makeDeposit() {
        sys.commitMakeDeposit(newMember(), BigDecimal("25"))
    }

    override fun raiseTicket() {
        sys.commitRaiseTicket(newMember(), "billing question", LocalDate.of(2026, 3, 1), "normal")
    }

    override fun closeTicket() {
        sys.commitCloseTicket(ticket(), agent())
    }

    override fun member(): Member = newMember()

    override fun someMember(): Member = newMember()

    override fun memberForRestoreService(): Member {
        // suspended with the balance recovered: go delinquent, get suspended by
        // the nightly sweep, then a covering deposit — the restoring tick is
        // the spec's to run
        val m = delinquentMember()
        sys.tickNightly()
        sys.commitMakeDeposit(m, BigDecimal("25"))
        return m
    }

    override fun visit(): Visit {
        sys.commitVisit(newMember(), 30)
        return sys.visits().last()
    }

    override fun unappliedDeposit(): Deposit {
        sys.commitMakeDeposit(newMember(), BigDecimal("25"))
        return sys.deposits().last()
    }

    override fun memberForRenewMembership(): Member = newMember()

    override fun unappliedCharge(): Charge {
        newMember()
        sys.tickMonthly() // the renewal sweep mints the charge; ApplyCharge follows after commit
        return sys.charges().last()
    }

    override fun delinquent(): Member = delinquentMember()

    override fun memberForSuspendDelinquents(): Member = delinquentMember()

    override fun memberForOpenDelinquencyEpisode(): Member = delinquentMember()

    override fun exitDelinquent(member: Member) {
        sys.commitMakeDeposit(member, BigDecimal("25")) // a covering deposit: the recovery commit
    }

    override fun memberForOpenAccountReview(): Member {
        // three delinquency episodes, each a renewal charge the balance can't
        // cover, each recovered by a covering deposit before the next
        val m = delinquentMember()                     // episode 1: balance -20
        repeat(2) {
            sys.commitMakeDeposit(m, BigDecimal("25")) // recover: episode closes (+5)
            sys.advanceDays(31)                        // reopen the 30-day renewal window
            sys.tickMonthly()                          // charge 20 drives the balance negative
        }
        return m
    }

    override fun closedTicket(): Ticket {
        val t = ticket()
        sys.commitCloseTicket(t, agent())
        return t
    }

    override fun applicableAssignment() {
        sys.commitAssignTicket(ticket(), agent())
    }

    override fun ticketForEscalateUrgent(): Ticket = ticket(priority = "high")
}
