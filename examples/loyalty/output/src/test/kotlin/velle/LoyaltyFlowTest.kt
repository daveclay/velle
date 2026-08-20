package velle.generated.loyalty

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import velle.CommitResult
import velle.generated.LoyaltySystem

/**
 * The proof constructs driven through the generated typed surface — the
 * business-flow companion to the generated per-rule specs. Each test is one
 * of the spec's `velle:` showcases running for real: the predecessor
 * recurrence healing a late arrival, the referral chain bottoming out, the
 * aliasing discharge writing two different members, causality (not a race)
 * delivering the thank-you note, and same-direction siblings certifying in
 * either order.
 */
class LoyaltyFlowTest {

    private val sys = LoyaltySystem()

    private fun member(
        name: String,
        referrer: LoyaltySystem.MemberView? = null,
    ): LoyaltySystem.MemberView {
        assertIs<CommitResult.Accepted>(sys.commitMember(name, referrer = referrer))
        return sys.members().last()
    }

    private fun dues(m: LoyaltySystem.MemberView, month: String, dueBy: LocalDate, paidOn: LocalDate) {
        assertIs<CommitResult.Accepted>(sys.commitDuesPayment(m, month, dueBy, paidOn))
    }

    @Test
    fun `the streak is derived history, and a late-arriving payment heals it`() {
        val ada = member("Ada")
        dues(ada, "January", dueBy = LocalDate.of(2026, 1, 31), paidOn = LocalDate.of(2026, 1, 10))
        dues(ada, "March", dueBy = LocalDate.of(2026, 3, 31), paidOn = LocalDate.of(2026, 3, 5))
        // the record has a gap: January then March
        assertEquals(2, ada.streak)

        // February's payment arrives LAST, but was paid on time — it slots
        // into the chain at its paidOn position, and every later payment's
        // streak re-derives. A stored counter folded in arrival order would
        // have counted it after March and kept the wrong answer.
        dues(ada, "February", dueBy = LocalDate.of(2026, 2, 28), paidOn = LocalDate.of(2026, 2, 8))
        assertEquals(3, ada.streak)

        // each payment knows the streak as of itself — readable history
        val chain = sys.duesPayments().filter { it.member == ada }.sortedBy { it.paidOn }
        assertEquals(listOf(1, 2, 3), chain.map { it.streakAfter })
        assertEquals("February", chain.last().previous?.coveredMonth)
    }

    @Test
    fun `a payment past its due date resets the streak for everything after it`() {
        val grace = member("Grace")
        dues(grace, "January", dueBy = LocalDate.of(2026, 1, 31), paidOn = LocalDate.of(2026, 1, 10))
        dues(grace, "March", dueBy = LocalDate.of(2026, 3, 31), paidOn = LocalDate.of(2026, 3, 20))
        assertEquals(2, grace.streak)

        // February was paid late — and its arrival rewrites March's streak too
        dues(grace, "February", dueBy = LocalDate.of(2026, 2, 28), paidOn = LocalDate.of(2026, 3, 2))
        assertEquals(1, grace.streak)
        val chain = sys.duesPayments().filter { it.member == grace }.sortedBy { it.paidOn }
        assertEquals(listOf(1, 0, 1), chain.map { it.streakAfter })
    }

    @Test
    fun `the referral chain bottoms out at the founder`() {
        val founder = member("Founder")
        val scout = member("Scout", referrer = founder)
        val recruit = member("Recruit", referrer = scout)
        assertNull(founder.foundingReferrer)
        assertEquals(founder, scout.foundingReferrer)
        assertEquals(founder, recruit.foundingReferrer)
    }

    @Test
    fun `a qualified purchase promotes the buyer and the referrer - two different members`() {
        val recruiter = member("Recruiter")
        val buyer = member("Buyer", referrer = recruiter)
        assertIs<CommitResult.Accepted>(sys.commitPurchase(buyer, BigDecimal("150")))
        assertEquals("gold", buyer.tier)
        assertEquals("advocate", recruiter.tier)
    }

    @Test
    fun `the thank-you note arrives by causality, not by racing the promotion`() {
        val buyer = member("Buyer")
        // the qualifying purchase is not a gold member's purchase at its own
        // commit; the promotion's commit is where it becomes one, and the
        // note lands there — exactly once
        assertIs<CommitResult.Accepted>(sys.commitPurchase(buyer, BigDecimal("150")))
        assertEquals(1, sys.thankYouNotes().size)
        // a later purchase by the now-gold member is thanked at its own commit
        assertIs<CommitResult.Accepted>(sys.commitPurchase(buyer, BigDecimal("20")))
        assertEquals(2, sys.thankYouNotes().size)
    }

    @Test
    fun `one enrollment certifies and issues exactly one keycard`() {
        val climber = member("Climber")
        assertIs<CommitResult.Accepted>(sys.commitEnrollment(climber))
        assertEquals(1, sys.safetyWaivers().size)
        assertEquals(1, sys.orientationRecords().size)
        assertEquals(1, sys.keycards().size)
        // a second enrollment re-files the paperwork; certification already
        // held, and the keycard guard keeps it to one
        assertIs<CommitResult.Accepted>(sys.commitEnrollment(climber))
        assertEquals(1, sys.keycards().size)
    }
}
