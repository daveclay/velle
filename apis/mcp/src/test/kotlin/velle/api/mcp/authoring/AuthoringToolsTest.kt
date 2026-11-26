package velle.api.mcp.authoring

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import velle.Cli
import velle.CommandOutcome
import velle.api.scenario.TryCommand
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The authoring server's tools, resources, and prompt, called as the SDK calls them: each tool
 * answers exactly what the `velle` command prints, over `examples/billing/billing.velle`, whose
 * amounts are `Money`.
 */
class AuthoringToolsTest {

    private val billingPath = "../../examples/billing/billing.velle"
    private val billing = File(billingPath).readText()
    private val changes = "../../examples/billing/changes"

    private fun call(tool: String, arguments: Map<String, Any?>): CallToolResult {
        val specification = AuthoringTools.tools.single { candidate -> candidate.tool().name() == tool }
        return specification.callHandler().apply(null, CallToolRequest(tool, arguments))
    }

    private fun CallToolResult.text(): String = (content().single() as TextContent).text()

    private fun assertAnswers(outcome: CommandOutcome, result: CallToolResult) {
        assertEquals(outcome.text, result.text())
        assertFalse(result.isError, result.text())
    }

    @Test
    fun `validate answers what the command prints`() {
        assertAnswers(Cli.run(listOf("validate", billingPath)), call("validate", mapOf("spec" to billing, "fileName" to billingPath)))
        assertEquals("`draft.velle` validates.\n", call("validate", mapOf("spec" to billing)).text())
    }

    @Test
    fun `a draft that does not validate is a successful answer, the refusal the command prints`() {
        val refused = "../../compiler/src/test/resources/diagnostics/V2-guard-never-made-false.velle"
        val outcome = Cli.run(listOf("validate", refused))

        assertEquals(CommandOutcome.REFUSED, outcome.exitCode)
        assertAnswers(outcome, call("validate", mapOf("spec" to File(refused).readText(), "fileName" to refused)))
    }

    @Test
    fun `compare answers what the command prints`() {
        val before = "$changes/05-zero-total-receipts.velle"
        val after = "$changes/06-payments-positive.velle"

        assertAnswers(
            Cli.run(listOf("compare", before, after)),
            call("compare", mapOf("before" to File(before).readText(), "after" to File(after).readText(), "beforeName" to before, "afterName" to after)),
        )
    }

    @Test
    fun `describe answers what the command prints, for the whole spec and for one name`() {
        assertAnswers(Cli.run(listOf("describe", billingPath)), call("describe", mapOf("spec" to billing, "fileName" to billingPath)))
        assertAnswers(Cli.run(listOf("describe", billingPath, "Invoice")), call("describe", mapOf("spec" to billing, "fileName" to billingPath, "name" to "Invoice")))
    }

    @Test
    fun `try answers what the command prints, a refused step included`() {
        val scenario = """[
            { "commit": "SignUp", "with": { "name": "Alice", "email": "alice@example.org" } },
            { "find": "Customer", "where": "name == \"Alice\"", "as": "alice" },
            { "commit": "BillCustomer", "with": { "customer": "alice", "due": "2026-01-15" } },
            { "find": "Invoice", "where": "customer.name == \"Alice\"", "as": "invoice" },
            { "commit": "LineItem", "with": { "invoice": "invoice", "description": "Drill", "price": "80.00", "quantity": 0 } }
        ]"""
        val outcome = TryCommand.runText("draft.velle", billing, "scenario.json", scenario)

        assertTrue("Refused." in outcome.text, outcome.text)
        assertAnswers(outcome, call("try", mapOf("spec" to billing, "scenario" to scenario)))
    }

    @Test
    fun `a scenario that stops is a successful answer, what the command prints`() {
        val outcome = TryCommand.runText("draft.velle", billing, "scenario.json", "[ { \"tick\": \"Nightly\" } ]")

        assertEquals(CommandOutcome.REFUSED, outcome.exitCode)
        assertAnswers(outcome, call("try", mapOf("spec" to billing, "scenario" to "[ { \"tick\": \"Nightly\" } ]")))
    }

    @Test
    fun `a call missing an argument, with one that is not text, or with one the tool has not is an error`() {
        val missing = call("compare", mapOf("before" to billing))
        assertTrue(missing.isError)
        assertEquals("`compare` needs `after`.", missing.text())

        val notText = call("validate", mapOf("spec" to 42))
        assertTrue(notText.isError)
        assertEquals("`spec` is text.", notText.text())

        val unknown = call("describe", mapOf("spec" to billing, "shape" to "Invoice"))
        assertTrue(unknown.isError)
        assertEquals("`describe` has no input `shape`.", unknown.text())
    }

    @Test
    fun `every tool's schema names its inputs, and the ones a call must give`() {
        val schemas = AuthoringTools.tools.associate { specification -> specification.tool().name() to specification.tool().inputSchema() }

        assertEquals(listOf("validate", "compare", "describe", "try"), schemas.keys.toList())
        val trySchema = schemas.getValue("try") as Map<*, *>
        assertEquals(listOf("spec", "scenario"), trySchema["required"])
        assertEquals(setOf("spec", "scenario", "fileName", "scenarioName"), (trySchema["properties"] as Map<*, *>).keys)
    }

    @Test
    fun `SKILL md and every reference are resources, read as the skill folder holds them`() {
        val skillFolder = File("../../skills/velle-design")
        val expected = listOf("SKILL.md") + File(skillFolder, "references").listFiles()!!.map { file -> "references/${file.name}" }.sorted()

        assertEquals(expected.map { path -> "velle-design://$path" }, SkillFiles.resources.map { resource -> resource.resource().uri() })
        SkillFiles.resources.forEach { specification ->
            val uri = specification.resource().uri()
            val contents = specification.readHandler().apply(null, null).contents().single() as TextResourceContents
            assertEquals(File(skillFolder, uri.removePrefix("velle-design://")).readText(), contents.text(), uri)
        }
    }

    @Test
    fun `the prompt hands the assistant SKILL md`() {
        val prompt = SkillFiles.prompt
        val message = prompt.promptHandler().apply(null, null).messages().single()

        assertEquals("velle-design", prompt.prompt().name())
        assertEquals(File("../../skills/velle-design/SKILL.md").readText(), (message.content() as TextContent).text())
    }
}
