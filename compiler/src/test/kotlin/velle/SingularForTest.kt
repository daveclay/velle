package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs Reference §20's episodes pattern end-to-end: the exit rule's singular
 * reference `(OpenDelinquencyFlag for this)` — a refinement name in the
 * proof-gated `for` query — resolves through base-instance scan plus
 * membership filter. The form is one record or absent (Reference §10): an entry
 * with no arrival reads `(Arrival for this).at` absent, and a `from` block that
 * stores the absent record in a required field refuses the act naming the rule
 * (Reference §14).
 */
class SingularForTest {

    private val src = """
        act Account {
            balance: decimal
        }

        act BalanceReport {
            account: one Account
            reported: decimal
        }

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
        sys.scope {
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

    private val entryAndArrival = """
        act Entry {
            key: text unique
            arrivedAt: DateTime? = (Arrival for this).at
        }

        act Arrival {
            entry: one Entry unique
            at: DateTime
        }
    """.trimIndent()

    @Test
    fun `an entry with no arrival reads the singular reference absent`() {
        val sys = VelleSystem(Model(Parser.parse(entryAndArrival)))
        assertEquals(emptyList(), Validator.validate(entryAndArrival))
        sys.scope {
            val entry = sys.mustCommit("Entry", "key" to "PLOT-1")
            assertEquals(null, sys.get(entry, "arrivedAt"))
            sys.mustCommit("Arrival", "entry" to entry, "at" to java.time.Instant.parse("2026-11-13T08:00:00Z"))
            assertEquals(java.time.Instant.parse("2026-11-13T08:00:00Z"), sys.get(entry, "arrivedAt"))
        }
    }

    @Test
    fun `a from block storing an absent singular reference in a required field refuses the act naming the rule`() {
        val spec = entryAndArrival + """

            act Audit {
                entry: one Entry
            }

            shape AuditNote {
                arrival: one Arrival
                audit: one Audit
            }

            rule NoteArrival when Audit {
                AuditNote from { arrival: (Arrival for entry), audit: this }
            }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(spec))
        val sys = VelleSystem(Model(Parser.parse(spec)))
        sys.scope {
            val entry = sys.mustCommit("Entry", "key" to "PLOT-1")
            val refused = assertIs<CommitResult.Refused>(sys.commit("Audit", mapOf("entry" to entry)))
            assertEquals("rule 'NoteArrival' did not fire: 'AuditNote.arrival' is required and '(Arrival for entry)' is absent: the selection matched nothing (Reference §14)", refused.reason)
            assertEquals(null, refused.rejection)
            assertEquals(0, sys.instancesOf("AuditNote").size)
            assertEquals(0, sys.instancesOf("Audit").size)
        }
    }
}
