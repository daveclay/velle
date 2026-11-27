package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * V10 (Reference §21) over a `never` that reads a field a rule assigns. Over a transient act
 * the `never` is a refusal, and the message calls it one; over a kept shape it says the
 * compiler cannot yet prove the guarantee. The advice the refusal's message gives is tried
 * here at run time.
 */
class RefusalReadsAssignedFieldTest {

    private fun v10Messages(source: String): List<String> =
        Validator.validate(source).filter { diagnostic -> diagnostic.code == "V10" }.map { diagnostic -> diagnostic.message }

    private fun catalogued(name: String): String = File("src/test/resources/diagnostics/$name").readText()

    @Test
    fun `a refusal that reads an assigned field is called a refusal`() {
        val source = catalogued("V10-refusal-reads-assigned-field.velle")
        assertEquals(
            listOf(
                "a 'never' over the request 'Borrow' reads 'Loan.returnedOn', which rule 'TakeBackTool' assigns, " +
                    "and the compiler cannot yet check a refusal that reads a field a rule assigns. Read a field " +
                    "no rule assigns, or derive 'returnedOn' from the records the rule writes; a refusal that reads " +
                    "records the request's own rules create is checked after those rules run, so it sees them " +
                    "(Reference §21)",
            ),
            v10Messages(source),
        )
    }

    @Test
    fun `a never over a kept shape that reads an assigned field is still called a guarantee`() {
        val source = catalogued("V10-never-reads-assigned-field.velle")
        assertEquals(
            listOf(
                "a 'never' reads 'Account.suspended', which rule 'ApplySuspend' assigns, so it is a guarantee " +
                    "that the rules keep it true, and the compiler cannot yet prove one over every assignment; " +
                    "read a field no rule assigns, or derive 'suspended' from the records the rule writes " +
                    "(Reference §21)",
            ),
            v10Messages(source),
        )
    }

    /** The tool lending of the catalogued spec, with the return recorded as a record of its own. */
    private fun lendingWithReturns(prohibition: String) = """
        act Tool { name: text }
        transient act Borrow { tool: one Tool }
        shape Loan { tool: one Tool }
        shape Return { loan: one Loan  returnedOn: Date }
        rule LendTool when Borrow { Loan from { tool: tool } }
        transient act ReturnTool { loan: one Loan }
        rule TakeBackTool when ReturnTool { Return from { loan: loan, returnedOn: today } }
        $prohibition
    """.trimIndent()

    private fun borrowTwice(source: String): Pair<CommitResult, CommitResult> {
        assertEquals(emptyList(), Validator.validate(source))
        val system = VelleSystem(Model(Parser.parse(source)))
        return system.scope {
            val toolId = (system.commit("Tool", mapOf("name" to "drill")) as CommitResult.Accepted).id
            val firstBorrow = system.commit("Borrow", mapOf("tool" to toolId))
            val secondBorrow = system.commit("Borrow", mapOf("tool" to toolId))
            firstBorrow to secondBorrow
        }
    }

    @Test
    fun `a refusal derived from the returns and counting the request's own loan lends a tool once`() {
        val source = lendingWithReturns("never (Borrow where count(tool.loans where not exists (returns)) > 1)")
        val (firstBorrow, secondBorrow) = borrowTwice(source)
        assertIs<CommitResult.Accepted>(firstBorrow)
        assertIs<CommitResult.Refused>(secondBorrow)
    }

    @Test
    fun `a refusal derived from the returns sees the loan its own request creates`() {
        // the refusal reads loans, which rule LendTool creates, so it is checked after LendTool has run
        val source = lendingWithReturns("never (Borrow where exists (tool.loans where not exists (returns)))")
        val (firstBorrow, _) = borrowTwice(source)
        assertIs<CommitResult.Refused>(firstBorrow)
    }
}
