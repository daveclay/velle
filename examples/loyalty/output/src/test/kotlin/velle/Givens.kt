package velle.generated.loyalty

import velle.generated.LoyaltySystem
import java.math.BigDecimal

/**
 * The human-owned scenarios the generated loyalty specs demand (testgen.md).
 */
class Givens(private val sys: LoyaltySystem) : RequiredGivens {

    private var n = 0

    private fun member(
        referrer: LoyaltySystem.MemberView? = null,
    ): LoyaltySystem.MemberView {
        sys.commitJoin("Member ${++n}", referrer = referrer)
        return sys.members().last()
    }

    override fun join() {
        sys.commitJoin("Member ${++n}")
    }

    override fun qualifiedPurchase(): LoyaltySystem.PurchaseView {
        sys.commitPurchase(member(), BigDecimal("150"))
        return sys.purchases().last()
    }

    override fun purchaseForPromoteReferrer(): LoyaltySystem.PurchaseView {
        val recruiter = member()
        sys.commitPurchase(member(referrer = recruiter), BigDecimal("150"))
        return sys.purchases().last()
    }

    override fun purchaseForThankVip(): LoyaltySystem.PurchaseView {
        // tier is system-maintained, so gold is earned, never given: the
        // qualifying purchase is itself the subject — it enters VipPurchase at
        // its own promotion's commit, and ThankVip thanks it there
        sys.commitPurchase(member(), BigDecimal("150"))
        return sys.purchases().last()
    }

    override fun enrollment(): LoyaltySystem.EnrollmentView {
        sys.commitEnrollment(member())
        return sys.enrollments().last()
    }

    override fun memberForIssueKeycard(): LoyaltySystem.MemberView {
        val m = member()
        sys.commitEnrollment(m)
        return m
    }

    override fun someMember(): LoyaltySystem.MemberView = member()
}
