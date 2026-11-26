package velle

import kotlin.test.Test
import kotlin.test.assertEquals

/** The `velle` command given nothing it can run: the usage, and exit code 2. */
class CliUsageTest {

    private val usageOutcome = CommandOutcome(Cli.usage, CommandOutcome.USAGE)

    @Test
    fun `no arguments print the usage`() {
        assertEquals(usageOutcome, Cli.run(emptyList()))
    }

    @Test
    fun `an unknown subcommand prints the usage`() {
        assertEquals(usageOutcome, Cli.run(listOf("try", "billing.velle", "scenario.md")))
    }

    @Test
    fun `a wrong number of files prints the usage`() {
        assertEquals(usageOutcome, Cli.run(listOf("validate")))
        assertEquals(usageOutcome, Cli.run(listOf("validate", "a.velle", "b.velle")))
        assertEquals(usageOutcome, Cli.run(listOf("compare", "a.velle")))
        assertEquals(usageOutcome, Cli.run(listOf("describe")))
        assertEquals(usageOutcome, Cli.run(listOf("describe", "a.velle", "Invoice", "Payment")))
    }
}
