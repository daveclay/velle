package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `velle validate`: whether a draft validates, and if not, why, with no file written. */
class CliValidateTest {

    private val billing = "../examples/billing/billing.velle"
    private val refusedSpec = "src/test/resources/diagnostics/V2-guard-never-made-false.velle"

    @Test
    fun `the billing spec validates, with Money found on the classpath`() {
        val outcome = Cli.run(listOf("validate", billing))

        assertEquals(CommandOutcome("`$billing` validates.\n", CommandOutcome.DONE), outcome)
    }

    @Test
    fun `a spec that validates with advisories lists them under their own heading and exits 0`() {
        val membership = "../examples/membership/membership.velle"

        val outcome = Cli.run(listOf("validate", membership))

        assertEquals(CommandOutcome.DONE, outcome.exitCode, outcome.text)
        assertTrue(
            outcome.text.startsWith(
                "`$membership` validates.\n\n## What the compiler advises\n\n" +
                    "Each is something the spec says that its author may not have meant. None stops it validating.\n\n- [A5] ",
            ),
            outcome.text,
        )
        val advisories = Validator.advisories(File(membership).readText())
        assertEquals(advisories.size, outcome.text.lines().count { line -> line.startsWith("- [A") }, outcome.text)
        assertTrue("\n  - correlate the read" in outcome.text, "an advisory's later lines stay inside its list item: ${outcome.text}")
    }

    @Test
    fun `a refused spec is reported with its diagnostics as a Markdown list and exits 1`() {
        val outcome = Cli.run(listOf("validate", refusedSpec))

        assertEquals(CommandOutcome.REFUSED, outcome.exitCode, outcome.text)
        assertTrue(
            outcome.text.startsWith("`$refusedSpec` does not validate. The compiler refuses it for these reasons:\n\n- [V2] "),
            outcome.text,
        )
        assertTrue("Apply" in outcome.text, "the message names the rule the author wrote: ${outcome.text}")
    }

    @Test
    fun `a spec the parser cannot read is reported as a refusal, not a stack trace`() {
        val unreadable = kotlin.io.path.createTempFile(suffix = ".velle").toFile()
        unreadable.writeText("shape Invoice {\n    due Date\n}\n")

        val outcome = Cli.run(listOf("validate", unreadable.path))

        assertEquals(CommandOutcome.REFUSED, outcome.exitCode, outcome.text)
        assertTrue("- The compiler cannot read it: " in outcome.text, outcome.text)
        assertTrue("(at 2:" in outcome.text, "the reason names the line: ${outcome.text}")
    }

    @Test
    fun `a missing file is said plainly and exits 1`() {
        val outcome = Cli.run(listOf("validate", "no-such-spec.velle"))

        assertEquals(CommandOutcome("There is no file at `no-such-spec.velle`.\n", CommandOutcome.REFUSED), outcome)
    }
}
