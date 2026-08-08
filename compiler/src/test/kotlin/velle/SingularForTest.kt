package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs README §20's episodes pattern end-to-end: the exit rule's singular
 * reference `(OpenDelinquencyFlag for this)` — a refinement name in the
 * proof-gated `for` query — resolves through base-instance scan plus
 * membership filter.
 */
class SingularForTest {

    private val src = """
        expose shape Account {
            balance: decimal
        } using MockHarness

        expose shape BalanceReport {
            account: one Account
            reported: decimal
        } using MockHarness

        shape DelinquencyFlag {
            account: one Account
            flaggedOn: Date initially today
        }

        shape DelinquencyResolution {
            flag: one DelinquencyFlag
            resolvedOn: Date initially today
        }

        shape Delinquent = Account where balance < 0
        shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

        rule RecordBalance when BalanceReport {
            account.balance = reported
        }

        rule OpenDelinquencyEpisode
            when (Delinquent where not exists OpenDelinquencyFlag for this) {
            DelinquencyFlag from { account: this }
        }

        rule CloseDelinquencyEpisode when leaving Delinquent {
            DelinquencyResolution from { flag: (OpenDelinquencyFlag for this) }
        }
    """.trimIndent()

    private fun newSystem(): VelleSystem {
        val model = Model(Parser.parse(src))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        check(Validator.validate(src).isEmpty()) { Validator.validate(src).toString() }
        return VelleSystem(model)
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    private fun VelleSystem.report(account: Long, amount: String) =
        mustCommit("BalanceReport", "account" to account, "reported" to BigDecimal(amount))

    @Test
    fun `episodes open, close via the singular reference, and reopen`() {
        val sys = newSystem()
        val acct = sys.mustCommit("Account", "balance" to BigDecimal("100"))
        assertEquals(0, sys.instancesOf("DelinquencyFlag").size)

        // going delinquent opens exactly one flag
        sys.report(acct, "-50")
        val flags = sys.instancesOf("DelinquencyFlag")
        assertEquals(1, flags.size)
        assertTrue(sys.isMember(flags.single(), "OpenDelinquencyFlag"))

        // deeper delinquency is the same episode — the guard holds
        sys.report(acct, "-80")
        assertEquals(1, sys.instancesOf("DelinquencyFlag").size)

        // recovery closes the episode: `(OpenDelinquencyFlag for this)`
        // selects the one open flag and the resolution retires it
        sys.report(acct, "50")
        val resolutions = sys.instancesOf("DelinquencyResolution")
        assertEquals(1, resolutions.size)
        assertEquals(flags.single(), sys.get(resolutions.single(), "flag"))
        assertEquals(0, sys.instancesOf("OpenDelinquencyFlag").size)

        // a second delinquency is a new episode: a second flag, first stays resolved
        sys.report(acct, "-30")
        assertEquals(2, sys.instancesOf("DelinquencyFlag").size)
        assertEquals(1, sys.instancesOf("OpenDelinquencyFlag").size)
    }
}
