package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A record stored before a `never` over its shape or kept act was added can already break it.
 * The prohibition is read over every record of its shape, so the next commit it watches, here the
 * next record of that shape or act, is refused and names the stored record as the one in the
 * forbidden state. A kept act is caught exactly as a shape is. This is what `velle compare` means
 * by "A Visit stored before this change is now forbidden when its minutes is above 600. Its next
 * change could be refused." A seeded record stands for one stored under the earlier spec: it lands
 * with no prohibition checked. No rule may assign a field a `never` reads (V10), so the stored
 * record itself is not changed through that field.
 */
class StoredBeforeConstraintRuntimeTest {

    private val spec = """
        act Member { name: text }

        act Visit {
            member: one Member
            minutes: integer
        }

        transient act MakeDeposit {
            member: one Member
            amount: decimal
        }

        shape Deposit {
            member: one Member
            amount: decimal
        }

        rule RecordDeposit when MakeDeposit {
            Deposit from { member: member, amount: amount }
        }

        never (Visit where minutes > 600)

        never (Deposit where amount > 5000)
    """.trimIndent()

    private fun system(): VelleSystem {
        assertEquals(emptyList(), Validator.validate(spec), "the spec validates")
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun accepted(result: CommitResult): Long {
        assertIs<CommitResult.Accepted>(result, "commit refused: $result")
        return result.id
    }

    /** The value of [field] on each record the refusal names as in the forbidden state. */
    private fun violatingValues(result: CommitResult, field: String): List<String> {
        val refusal = assertIs<CommitResult.Refused>(result, "a stored record still breaks the constraint: $result")
        val rejection = refusal.rejection ?: error("the refusal carries no rejection: ${refusal.reason}")
        return rejection.violating.map { snapshot -> snapshot.fields[field].toString() }
    }

    @Test
    fun `a kept act stored before its constraint and above the limit refuses the next one committed`() {
        val sys = system()
        sys.scope {
            val member = accepted(sys.commit("Member", mapOf("name" to "Ada")))
            sys.seed("Visit", mapOf("member" to member, "minutes" to 700L))

            val nextVisit = sys.commit("Visit", mapOf("member" to member, "minutes" to 30L))

            assertEquals(listOf("700"), violatingValues(nextVisit, "minutes"), "the stored Visit is the one named")
            assertEquals(1, sys.instancesOf("Visit").size, "the refused Visit was not stored")
        }
    }

    @Test
    fun `a shape stored before its constraint and above the limit refuses the next one made`() {
        val sys = system()
        sys.scope {
            val member = accepted(sys.commit("Member", mapOf("name" to "Ada")))
            sys.seed("Deposit", mapOf("member" to member, "amount" to BigDecimal("6000")))

            val nextDeposit = sys.commit("MakeDeposit", mapOf("member" to member, "amount" to BigDecimal("30")))

            assertEquals(listOf("6000"), violatingValues(nextDeposit, "amount"), "the stored Deposit is the one named")
            assertEquals(1, sys.instancesOf("Deposit").size, "the refused Deposit was not stored")
        }
    }
}
