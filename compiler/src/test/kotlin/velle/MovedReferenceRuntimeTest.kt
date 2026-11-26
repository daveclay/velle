package velle

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A rule that re-points a reference changes what the records on the other side hold: moving a
 * loan from one member to another can make the receiver a member with a late loan. A rule over
 * that condition runs for the receiver, whichever of the three spellings reads the member's loans.
 */
class MovedReferenceRuntimeTest {

    private fun library(lateLoanTest: String): VelleSystem {
        val spec = """
            act Member { name: text }

            act Loan {
                member: one Member
                dueOn: Date
            }

            transient act Transfer {
                loan: one Loan
                receiver: one Member
            }

            shape LateNotice { member: one Member }

            rule MoveLoan when Transfer {
                loan.member = receiver
            }

            shape MemberWithLateLoan = Member where $lateLoanTest

            rule NoticeLateMember when MemberWithLateLoan on commit, Nightly {
                LateNotice from { member: this }
            }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(spec), "the spec validates")
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun accepted(result: CommitResult): Long {
        assertIs<CommitResult.Accepted>(result, "commit refused: $result")
        return result.id
    }

    /** The members a late notice names, after a late loan of Ada's moves to Bo. */
    private fun noticedMembersAfterMovingALateLoan(lateLoanTest: String): Map<String, Long> {
        val sys = library(lateLoanTest)
        return sys.scope {
            val ada = accepted(sys.commit("Member", mapOf("name" to "Ada")))
            val bo = accepted(sys.commit("Member", mapOf("name" to "Bo")))
            // the clock starts on 2026-01-01, so a loan due on 2025-12-01 is late when it is made
            val loan = accepted(sys.commit("Loan", mapOf("member" to ada, "dueOn" to LocalDate.of(2025, 12, 1))))

            accepted(sys.commit("Transfer", mapOf("loan" to loan, "receiver" to bo)))

            val noticedMembers = sys.instancesOf("LateNotice").map { notice -> sys.get(notice, "member") as Long }
            mapOf("ada" to ada, "bo" to bo).filterValues { member -> member in noticedMembers }
        }
    }

    @Test
    fun `moving a late loan to a member reads through the member's loans and notices the receiver`() {
        assertEquals(setOf("ada", "bo"), noticedMembersAfterMovingALateLoan("exists (loans where dueOn < today)").keys)
    }

    @Test
    fun `moving a late loan to a member reads as the loans naming the member and notices the receiver`() {
        assertEquals(setOf("ada", "bo"), noticedMembersAfterMovingALateLoan("exists (Loan where member == this and dueOn < today)").keys)
    }

    @Test
    fun `moving a loan to a member reads as the loans for the member and notices the receiver`() {
        assertEquals(setOf("ada", "bo"), noticedMembersAfterMovingALateLoan("exists Loan for this").keys)
    }
}
