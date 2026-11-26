package velle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The skill's launcher, `skills/velle-design/scripts/velle`, run as a host runs it, over the jar
 * `./gradlew :compiler:skillJar` builds beside it. Each subcommand reads
 * `examples/billing/billing.velle`, whose amounts are `Money`, so a run that validates proves the
 * Kotlin standard library and `extensions/finance` are inside the jar, and `try` proves the API
 * layer (`apis/json`) and its JSON reader are.
 */
class SkillJarTest {

    private val launcher = File("../skills/velle-design/scripts/velle").absoluteFile
    private val billing = File("../examples/billing/billing.velle").absolutePath
    private val changes = File("../examples/billing/changes").absolutePath

    /** What one run of a script printed, and the code it exited with. */
    private data class Run(val output: String, val exitCode: Int)

    /** Runs [script] with the JVM running this test first on the path, or with [path] as the whole path. */
    private fun run(script: File, arguments: List<String>, path: String? = null): Run {
        val process = ProcessBuilder(listOf(script.path) + arguments).redirectErrorStream(true)
        val javaDirectory = File(System.getProperty("java.home"), "bin").path
        process.environment()["PATH"] = path ?: (javaDirectory + File.pathSeparator + System.getenv("PATH"))
        val started = process.start()
        val output = started.inputStream.bufferedReader().readText()
        return Run(output, started.waitFor())
    }

    @Test
    fun `validate runs from the jar and finds Money`() {
        val run = run(launcher, listOf("validate", billing))

        assertEquals(Run("`$billing` validates.\n", 0), run)
    }

    @Test
    fun `compare runs from the jar`() {
        val before = "$changes/05-zero-total-receipts.velle"
        val after = "$changes/06-payments-positive.velle"

        val run = run(launcher, listOf("compare", before, after))

        assertEquals(0, run.exitCode, run.output)
        assertTrue("- A Payment is now forbidden when its amount is at most 0.\n  In the spec: $after, line 69" in run.output, run.output)
    }

    @Test
    fun `describe runs from the jar`() {
        val run = run(launcher, listOf("describe", billing, "Invoice"))

        assertEquals(0, run.exitCode, run.output)
        assertTrue(
            "- An Invoice is an OverdueInvoice when its balance is above 0 and its due is before today.\n" +
                "  In the spec: $billing, line 148, `shape OverdueInvoice = Invoice where balance > 0 and due < today`" in run.output,
            run.output,
        )
    }

    @Test
    fun `try runs a scenario from the jar, through the API layer it carries`() {
        val scenario = Files.createTempFile("scenario", ".json").toFile()
        scenario.writeText(
            """[
                { "commit": "SignUp", "with": { "name": "Alice", "email": "alice@example.org" } },
                { "find": "Customer", "where": "name == \"Alice\"", "as": "alice" },
                { "commit": "BillCustomer", "with": { "customer": "alice", "due": "2026-01-15" } },
                { "find": "Invoice", "where": "customer.name == \"Alice\"", "as": "invoice" },
                { "commit": "LineItem", "with": { "invoice": "invoice", "description": "Drill", "price": "80.00", "quantity": 0 } },
                { "pass": "21 days" },
                { "tick": "Weekly" }
            ]""",
        )

        val run = run(launcher, listOf("try", billing, scenario.path))

        assertEquals(0, run.exitCode, run.output)
        assertTrue("- Customer 1: name \"Alice\", email \"alice@example.org\", signedUpOn 2026-01-01T09:00:00Z, largestPayment 0.00\n" in run.output, run.output)
        assertTrue("- A LineItem is refused when its quantity is at most 0.\n" in run.output, run.output)
        assertTrue("## Step 7: the Weekly tick\n\nIt ran at 2026-01-22T09:00:00Z.\n" in run.output, run.output)
        assertTrue("Step 5 was refused." in run.output, run.output)
    }

    @Test
    fun `the exit codes of the command come through the launcher`() {
        assertEquals(1, run(launcher, listOf("describe", billing, "Invoce")).exitCode)
        assertEquals(2, run(launcher, emptyList()).exitCode)
    }

    @Test
    fun `a java older than 21 is said in one sentence and exits 3`() {
        val fakeJavaDirectory = Files.createTempDirectory("old-java").toFile()
        val fakeJava = File(fakeJavaDirectory, "java")
        fakeJava.writeText("#!/bin/sh\necho 'openjdk version \"17.0.2\" 2022-01-18' >&2\n")
        fakeJava.setExecutable(true)

        val run = run(launcher, listOf("validate", billing), path = fakeJavaDirectory.path + File.pathSeparator + "/usr/bin:/bin")

        assertEquals(Run("The Velle compiler needs Java 21 or later, and this machine's java command is version 17.0.2.\n", 3), run)
    }

    @Test
    fun `a missing jar is said with how to build it and exits 3`() {
        val skillWithoutJar = Files.createTempDirectory("skill-without-jar").toFile()
        val copiedLauncher = File(skillWithoutJar, "velle")
        launcher.copyTo(copiedLauncher)
        copiedLauncher.setExecutable(true)

        val run = run(copiedLauncher, listOf("validate", billing))

        assertEquals(3, run.exitCode, run.output)
        assertTrue("./gradlew :compiler:skillJar" in run.output, run.output)
    }

    @Test
    fun `the skill's best practices are the wiki's, copied when the jar is built`() {
        val wikiPage = File("../wiki/Guides/Best-practices.md")
        val skillCopy = File("../skills/velle-design/references/Best-practices.md")

        assertTrue(
            skillCopy.exists() && wikiPage.readBytes().contentEquals(skillCopy.readBytes()),
            "skills/velle-design/references/Best-practices.md differs from wiki/Guides/Best-practices.md. " +
                "Never edit the copy by hand; run ./gradlew :compiler:copyBestPractices and commit the copy.",
        )
    }
}
