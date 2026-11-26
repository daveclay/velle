package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `velle` command given nothing it can run: the usage, and exit code 2. */
class CliUsageTest {

    private val usageOutcome = CommandOutcome(Cli.usage, CommandOutcome.USAGE)

    @Test
    fun `no arguments print the usage`() {
        assertEquals(usageOutcome, Cli.run(emptyList()))
    }

    @Test
    fun `an unknown subcommand prints the usage`() {
        assertEquals(usageOutcome, Cli.run(listOf("run", "billing.velle", "scenario.json")))
    }

    @Test
    fun `the usage names every subcommand, try among them`() {
        for (subcommand in listOf("validate", "compare", "describe", "try")) assertTrue("    velle $subcommand <" in Cli.usage, Cli.usage)
        assertTrue("    velle try <draft.velle> <scenario.json>\n" in Cli.usage, Cli.usage)
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
