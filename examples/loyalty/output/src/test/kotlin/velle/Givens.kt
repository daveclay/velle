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
        tier: String? = null,
    ): LoyaltySystem.MemberView {
        sys.commitMember("Member ${++n}", referrer = referrer, tier = tier)
        return sys.members().last()
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
        // already gold BEFORE the purchase, so this purchase is the entrant —
        // a qualifying purchase would enter at its own promotion's commit and
        // carry a note of its own before the given returns
        sys.commitPurchase(member(tier = "gold"), BigDecimal("20"))
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
