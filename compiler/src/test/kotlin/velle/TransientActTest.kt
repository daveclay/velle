package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `expose transient` (README §4, "Transient acts"): the act is an input to the
 * state, not a member of it — V17 isolates it, V18 demands every request get a
 * response, and the runtime removes the instance at its transaction's close.
 */
class TransientActTest {

    private val spec = """
        expose shape Account {
            openingBalance: decimal
            balance: decimal = openingBalance - sum(withdrawals, amount)
        } using MockHarness

        shape Withdrawal {
            account: one Account
            amount: decimal
        }

        shape OverdrawnAccount = Account where balance < 0

        expose transient shape WithdrawRequest {
            account: one Account
            amount: decimal
        } using MockHarness

        never (WithdrawRequest where amount <= 0)

        rule ApplyWithdrawal when (WithdrawRequest where not account is OverdrawnAccount) {
            Withdrawal from { account: account, amount: amount }
        }

        shape WithdrawalRefusal {
            account: one Account
            requestedAmount: decimal
            refusedOn: DateTime
        }

        rule RefuseWithdrawal when (WithdrawRequest where account is OverdrawnAccount) {
            WithdrawalRefusal from { account: account, requestedAmount: amount, refusedOn: now }
        }
    """.trimIndent()

    // ── validation ────────────────────────────────────────────────────────────

    @Test
    fun `a complement-partitioned transient act validates clean`() {
        assertEquals(emptyList(), Validator.validate(spec))
    }

    @Test
    fun `V17 - nothing durable may reference the act`() {
        val src = spec.replace(
            "shape WithdrawalRefusal {\n    account: one Account",
            "shape WithdrawalRefusal {\n    request: one WithdrawRequest\n    account: one Account",
        )
        assertTrue(Validator.validate(src).any { it.code == "V17" }, "got: ${Validator.validate(src)}")
    }

    @Test
    fun `V17 - no expression may read the act`() {
        val src = spec + "\n\nshape PesteredAccount = Account where exists WithdrawRequest for this"
        assertTrue(Validator.validate(src).any { it.code == "V17" }, "got: ${Validator.validate(src)}")
    }

    @Test
    fun `V17 - no tick or after-commit trigger on the act`() {
        val src = spec.replace(
            "rule ApplyWithdrawal when (WithdrawRequest where not account is OverdrawnAccount) {",
            "rule ApplyWithdrawal when (WithdrawRequest where not account is OverdrawnAccount) on commit, Daily {",
        )
        assertTrue(Validator.validate(src).any { it.code == "V17" }, "got: ${Validator.validate(src)}")
    }

    @Test
    fun `V18 - a request no rule answers is an error`() {
        // drop the refusal side: overdrawn accounts' requests are answered by nobody
        val src = spec
            .substringBefore("shape WithdrawalRefusal")
            .trimEnd()
        assertTrue(Validator.validate(src).any { it.code == "V18" }, "got: ${Validator.validate(src)}")
    }

    // ── runtime ───────────────────────────────────────────────────────────────

    private fun newSystem(): VelleSystem {
        val model = Model(Parser.parse(spec))
        check(Validator.validate(spec).isEmpty())
        return VelleSystem(model)
    }

    @Test
    fun `the act's consequences persist - the act does not`() {
        val sys = newSystem()
        val acct = (sys.commit("Account", mapOf("openingBalance" to BigDecimal("100"))) as CommitResult.Accepted).id

        val r = sys.commit("WithdrawRequest", mapOf("account" to acct, "amount" to BigDecimal("30")))
        assertIs<CommitResult.Accepted>(r)
        assertEquals(1, sys.instancesOf("Withdrawal").size)
        assertEquals(0, BigDecimal("70").compareTo(sys.get(acct, "balance") as BigDecimal))
        assertEquals(0, sys.instancesOf("WithdrawRequest").size) // an input, not a member

        // drive the account overdrawn (the 80 is applied — at ITS commit the
        // account was still fine, and its own consequence flipping the state
        // does not re-partition it), then a refused request: the refusal
        // (with copied payload) persists; the request still doesn't
        sys.commit("WithdrawRequest", mapOf("account" to acct, "amount" to BigDecimal("80")))
        assertEquals(2, sys.instancesOf("Withdrawal").size)
        assertEquals(0, sys.instancesOf("WithdrawalRefusal").size)
        sys.commit("WithdrawRequest", mapOf("account" to acct, "amount" to BigDecimal("5")))
        assertEquals(1, sys.instancesOf("WithdrawalRefusal").size)
        val refusal = sys.instancesOf("WithdrawalRefusal").single()
        assertEquals(0, BigDecimal("5").compareTo(sys.get(refusal, "requestedAmount") as BigDecimal))
        assertEquals(0, sys.instancesOf("WithdrawRequest").size)
    }

    @Test
    fun `a refused act commits nothing - never enforcement still sees it`() {
        val sys = newSystem()
        val acct = (sys.commit("Account", mapOf("openingBalance" to BigDecimal("100"))) as CommitResult.Accepted).id
        val refused = sys.commit("WithdrawRequest", mapOf("account" to acct, "amount" to BigDecimal.ZERO))
        assertIs<CommitResult.Refused>(refused)
        assertEquals(0, sys.instancesOf("Withdrawal").size)
    }
}
