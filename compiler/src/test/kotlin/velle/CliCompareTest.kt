package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `velle compare` over the billing spec's history of changes, `examples/billing/changes/`. */
class CliCompareTest {

    private val changes = "../examples/billing/changes"

    @Test
    fun `comparing two versions prints the report the history holds for them`() {
        val before = "$changes/05-zero-total-receipts.velle"
        val after = "$changes/06-payments-positive.velle"

        val outcome = Cli.run(listOf("compare", before, after))

        assertEquals(CommandOutcome.DONE, outcome.exitCode, outcome.text)
        assertTrue("# What changed from $before to $after" in outcome.text, outcome.text)
        assertTrue("- A Payment is now forbidden when its amount is at most 0." in outcome.text, outcome.text)
        assertTrue("  In the spec: $after, line 69, `never (Payment where amount <= 0)`" in outcome.text, outcome.text)
        val checkedIn = File("$changes/06-payments-positive.md").readText()
            .replace("05-zero-total-receipts.velle", before)
            .replace("06-payments-positive.velle", after)
        assertEquals(checkedIn, outcome.text, "the report is the one examples/billing/changes/06-payments-positive.md holds")
    }

    @Test
    fun `a version that does not validate is named with its diagnostics and exits 1`() {
        val refused = "src/test/resources/diagnostics/V2-guard-never-made-false.velle"

        val outcome = Cli.run(listOf("compare", "$changes/00-billing.velle", refused))

        assertEquals(CommandOutcome.REFUSED, outcome.exitCode, outcome.text)
        assertTrue(outcome.text.startsWith("`$refused` does not validate."), outcome.text)
        assertTrue("- [V2] " in outcome.text, outcome.text)
        assertTrue("00-billing.velle" !in outcome.text, "only the file that does not validate is named: ${outcome.text}")
    }
}
