package velle.generated.loyalty

import java.math.BigDecimal
import velle.generated.*
import velle.generated.Member
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import velle.Commit
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
open class LoyaltyFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): LoyaltySystem = LoyaltySystem()
    protected val sys: LoyaltySystem by lazy { newSystem() }

    private fun member(
        name: String,
        referrer: Member? = null,
    ): Member {
        assertIs<Commit.Accepted<*>>(sys.commit(NewJoin(name, referrer = referrer)))
        return sys.members().last()
    }

    private fun dues(m: Member, month: String, dueBy: LocalDate, paidOn: LocalDate) {
        assertIs<Commit.Accepted<*>>(sys.commit(NewDuesPayment(m, month, dueBy, paidOn)))
    }

    @Test
    fun `the streak is derived history, and a late-arriving payment heals it`() {
        val ada = member("Ada")
        dues(ada, "January", dueBy = LocalDate.of(2026, 1, 31), paidOn = LocalDate.of(2026, 1, 10))
        dues(ada, "March", dueBy = LocalDate.of(2026, 3, 31), paidOn = LocalDate.of(2026, 3, 5))
        // the record has a gap: January then March
        assertEquals(2, sys.read(ada).streak)

        // February's payment arrives LAST, but was paid on time — it slots
        // into the chain at its paidOn position, and every later payment's
        // streak re-derives. A stored counter folded in arrival order would
        // have counted it after March and kept the wrong answer.
        dues(ada, "February", dueBy = LocalDate.of(2026, 2, 28), paidOn = LocalDate.of(2026, 2, 8))
        assertEquals(3, sys.read(ada).streak)

        // each payment knows the streak as of itself — readable history
        val chain = sys.duesPayments().filter { sys.read(it).member == ada }.sortedBy { sys.read(it).paidOn }
        assertEquals(listOf(1, 2, 3), chain.map { sys.read(it).streakAfter })
        assertEquals("February", sys.read(chain.last()).previous?.let { sys.read(it) }?.coveredMonth)
    }

    @Test
    fun `a payment past its due date resets the streak for everything after it`() {
        val grace = member("Grace")
        dues(grace, "January", dueBy = LocalDate.of(2026, 1, 31), paidOn = LocalDate.of(2026, 1, 10))
        dues(grace, "March", dueBy = LocalDate.of(2026, 3, 31), paidOn = LocalDate.of(2026, 3, 20))
        assertEquals(2, sys.read(grace).streak)

        // February was paid late — and its arrival rewrites March's streak too
        dues(grace, "February", dueBy = LocalDate.of(2026, 2, 28), paidOn = LocalDate.of(2026, 3, 2))
        assertEquals(1, sys.read(grace).streak)
        val chain = sys.duesPayments().filter { sys.read(it).member == grace }.sortedBy { sys.read(it).paidOn }
        assertEquals(listOf(1, 0, 1), chain.map { sys.read(it).streakAfter })
    }

    @Test
    fun `the referral chain bottoms out at the founder`() {
        val founder = member("Founder")
        val scout = member("Scout", referrer = founder)
        val recruit = member("Recruit", referrer = scout)
        assertNull(sys.read(founder).foundingReferrer)
        assertEquals(founder, sys.read(scout).foundingReferrer)
        assertEquals(founder, sys.read(recruit).foundingReferrer)
    }

    @Test
    fun `a qualified purchase promotes the buyer and the referrer - two different members`() {
        val recruiter = member("Recruiter")
        val buyer = member("Buyer", referrer = recruiter)
        assertIs<Commit.Accepted<*>>(sys.commit(NewPurchase(buyer, BigDecimal("150"))))
        assertEquals("gold", sys.read(buyer).tier)
        assertEquals("advocate", sys.read(recruiter).tier)
    }

    @Test
    fun `the thank-you note arrives by causality, not by racing the promotion`() {
        val buyer = member("Buyer")
        // the qualifying purchase is not a gold member's purchase at its own
        // commit; the promotion's commit is where it becomes one, and the
        // note lands there — exactly once
        assertIs<Commit.Accepted<*>>(sys.commit(NewPurchase(buyer, BigDecimal("150"))))
        assertEquals(1, sys.thankYouNotes().size)
        // a later purchase by the now-gold member is thanked at its own commit
        assertIs<Commit.Accepted<*>>(sys.commit(NewPurchase(buyer, BigDecimal("20"))))
        assertEquals(2, sys.thankYouNotes().size)
    }

    @Test
    fun `one enrollment certifies and issues exactly one keycard`() {
        val climber = member("Climber")
        assertIs<Commit.Accepted<*>>(sys.commit(NewEnrollment(climber)))
        assertEquals(1, sys.safetyWaivers().size)
        assertEquals(1, sys.orientationRecords().size)
        assertEquals(1, sys.keycards().size)
        // a second enrollment re-files the paperwork; certification already
        // held, and the keycard guard keeps it to one
        assertIs<Commit.Accepted<*>>(sys.commit(NewEnrollment(climber)))
        assertEquals(1, sys.keycards().size)
    }
}

