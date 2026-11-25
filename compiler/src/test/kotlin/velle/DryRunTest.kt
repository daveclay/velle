package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A dry run ([VelleSystem.check]): the act runs through its rules and its prohibitions
 * exactly as a commit does, and the answer is the one a commit would give now, but
 * nothing is written: the working set is undone and the store's unit ends unwritten.
 */
class DryRunTest {

    private val spec = """
        act Account {
            openingBalance: decimal
        }

        transient act SubmitPayment {
            account: one Account
            amount: decimal
        }

        never (SubmitPayment where amount <= 0)

        shape Payment {
            account: one Account
            amount: decimal
        }

        rule RecordPayment when SubmitPayment {
            Payment from { account: account, amount: amount }
        }

        never (Payment where amount > 1000)
    """.trimIndent()

    private fun connectedSystem(): Pair<VelleSystem, MemoryStore> {
        val model = Model(Parser.parse(spec))
        check(Validator.validate(spec).none { diagnostic -> !diagnostic.advisory }) { Validator.validate(spec).toString() }
        val system = VelleSystem(model)
        val store = MemoryStore(model)
        system.connect(store, store, store)
        return system to store
    }

    /** The system over a memory store, with one account committed, and [body] run in the same scope, where the account's handle is live. */
    private fun withAccount(body: (system: VelleSystem, store: MemoryStore, account: Long) -> Unit) {
        val (system, store) = connectedSystem()
        system.scope {
            val account = assertIs<CommitResult.Accepted>(system.commit("Account", mapOf("openingBalance" to BigDecimal("10")))).id
            body(system, store, account)
        }
    }

    @Test
    fun `an act that would be accepted is answered so and nothing reaches the store`() = withAccount { system, store, account ->
        val answer = system.check("SubmitPayment", mapOf("account" to account, "amount" to BigDecimal("40")))

        assertIs<CheckResult.WouldBeAccepted>(answer, answer.toString())
        assertEquals(0, store.fetchAll("Payment").size, "the rule ran, its payment was not kept")
        assertEquals(0, system.instancesOf("Payment").size, "nothing is left in the working set either")
    }

    @Test
    fun `an act refused at the door is answered with the refusal a commit would give`() = withAccount { system, store, account ->
        val fields = mapOf("account" to account, "amount" to BigDecimal("0"))

        val answer = assertIs<CheckResult.WouldBeRefused>(system.check("SubmitPayment", fields))
        val committed = assertIs<CommitResult.Refused>(system.commit("SubmitPayment", fields))

        assertEquals("SubmitPayment", answer.rejection?.targetShape, answer.reason)
        assertEquals(committed.reason, answer.reason)
        assertEquals(0, store.fetchAll("Payment").size)
    }

    @Test
    fun `a prohibition over what the rules would record is checked too`() = withAccount { system, store, account ->
        val answer = assertIs<CheckResult.WouldBeRefused>(system.check("SubmitPayment", mapOf("account" to account, "amount" to BigDecimal("5000"))))

        assertEquals("Payment", answer.rejection?.targetShape, answer.reason)
        assertEquals(0, store.fetchAll("Payment").size)
    }

    @Test
    fun `a malformed input is answered as a refusal without a rejection, as a commit is`() = withAccount { system, _, _ ->
        val answer = assertIs<CheckResult.WouldBeRefused>(system.check("SubmitPayment", mapOf("amount" to BigDecimal("5"))))

        assertEquals(null, answer.rejection)
    }

    @Test
    fun `a durable act that would be accepted is not kept`() = withAccount { system, store, _ ->
        assertIs<CheckResult.WouldBeAccepted>(system.check("Account", mapOf("openingBalance" to BigDecimal("20"))))

        assertEquals(1, store.fetchAll("Account").size, "only the account committed for real")
    }

    @Test
    fun `a commit after a dry run is accepted and kept`() = withAccount { system, store, account ->
        val fields = mapOf("account" to account, "amount" to BigDecimal("40"))
        system.check("SubmitPayment", fields)

        assertIs<CommitResult.Accepted>(system.commit("SubmitPayment", fields))
        assertEquals(1, store.fetchAll("Payment").size)
    }
}
