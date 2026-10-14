package velle.generated.loyalty

import velle.generated.LoyaltySystem
import velle.generated.*
import velle.generated.Member
import java.math.BigDecimal

/**
 * The human-owned scenarios the generated loyalty specs demand (testgen.md).
 */
class Givens(private val sys: LoyaltySystem) : RequiredGivens {

    private var n = 0

    private fun member(
        referrer: Member? = null,
    ): Member {
        sys.commit(NewJoin("Member ${++n}", referrer = referrer))
        return sys.members().last()
    }

    override fun join() {
        sys.commit(NewJoin("Member ${++n}"))
    }

    override fun qualifiedPurchase(): Purchase {
        sys.commit(NewPurchase(member(), BigDecimal("150")))
        return sys.purchases().last()
    }

    override fun purchaseForPromoteReferrer(): Purchase {
        val recruiter = member()
        sys.commit(NewPurchase(member(referrer = recruiter), BigDecimal("150")))
        return sys.purchases().last()
    }

    override fun purchaseForThankVip(): Purchase {
        // tier is system-maintained, so gold is earned, never given: the
        // qualifying purchase is itself the subject — it enters VipPurchase at
        // its own promotion's commit, and ThankVip thanks it there
        sys.commit(NewPurchase(member(), BigDecimal("150")))
        return sys.purchases().last()
    }

    override fun enrollment(): Enrollment {
        sys.commit(NewEnrollment(member()))
        return sys.enrollments().last()
    }

    override fun memberForIssueKeycard(): Member {
        val m = member()
        sys.commit(NewEnrollment(m))
        return m
    }

    override fun someMember(): Member = member()
}
