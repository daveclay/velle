package velle.api.mcp.authoring

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations
import velle.CommandOutcome

/**
 * The tools `validate`, `compare`, `describe`, and `try`, each taking the spec as text and answering
 * what the `velle` command prints for it. A draft that does not validate, or a scenario a step of
 * which is refused, is a successful answer: it is what the spec does. A call missing an argument, or
 * with an argument that is not text, is answered as an error.
 */
internal object AuthoringTools {

    /** One input of a tool: its name, what it holds, and whether a call must give it. */
    private class Input(val name: String, val description: String, val required: Boolean)

    private val draftName = Input("fileName", "The name the answer calls the spec by, such as \"draft.velle\". Leave it out for \"draft.velle\".", required = false)

    val tools: List<SyncToolSpecification> = listOf(
        tool(
            "validate",
            "Check a Velle spec. The answer says it validates, or lists every error and advisory the compiler finds, as `velle validate` prints them.",
            listOf(Input("spec", "The spec's Velle text.", required = true), draftName),
        ) { arguments -> AuthoringCommands.validate(arguments.text("fileName", DRAFT), arguments.text("spec")) },
        tool(
            "compare",
            "Say what changes from one version of a spec to another, in the compiler's words, as `velle compare` prints it: what each edit says, and what the compiler works out beyond it.",
            listOf(
                Input("before", "The earlier version's Velle text.", required = true),
                Input("after", "The later version's Velle text.", required = true),
                Input("beforeName", "The name the answer calls the earlier version by. Leave it out for \"before.velle\".", required = false),
                Input("afterName", "The name the answer calls the later version by. Leave it out for \"after.velle\".", required = false),
            ),
        ) { arguments ->
            AuthoringCommands.compare(arguments.text("beforeName", "before.velle"), arguments.text("before"), arguments.text("afterName", "after.velle"), arguments.text("after"))
        },
        tool(
            "describe",
            "Say in words what a spec means, as `velle describe` prints it: the whole spec, or one shape, act, refinement, or rule by its name.",
            listOf(
                Input("spec", "The spec's Velle text.", required = true),
                Input("name", "A shape, act, refinement, or rule to describe alone. Leave it out to describe the whole spec.", required = false),
                draftName,
            ),
        ) { arguments -> AuthoringCommands.describe(arguments.text("fileName", DRAFT), arguments.text("spec"), arguments.optionalText("name")) },
        tool(
            "try",
            "Run a scenario on a spec, as `velle try` does: its steps commit acts, find and show records, pass time, and run schedules, on a system that starts empty, and the answer says what each step did. The scenario is a JSON list of steps; the resource velle-design://references/scenarios.md says how to write one.",
            listOf(
                Input("spec", "The spec's Velle text.", required = true),
                Input("scenario", "The scenario's JSON text, a list of steps.", required = true),
                draftName,
                Input("scenarioName", "The name the answer calls the scenario by. Leave it out for \"scenario.json\".", required = false),
            ),
        ) { arguments ->
            AuthoringCommands.tryScenario(arguments.text("fileName", DRAFT), arguments.text("spec"), arguments.text("scenarioName", "scenario.json"), arguments.text("scenario"))
        },
    )

    private fun tool(name: String, description: String, inputs: List<Input>, command: (Arguments) -> CommandOutcome): SyncToolSpecification {
        val properties = inputs.associate { input -> input.name to mapOf("type" to "string", "description" to input.description) }
        val schema = mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to inputs.filter { input -> input.required }.map { input -> input.name },
            "additionalProperties" to false,
        )
        val tool = Tool.builder(name, schema)
            .title(name)
            .description(description)
            .annotations(ToolAnnotations.builder().title(name).readOnlyHint(true).build())
            .build()
        return SyncToolSpecification(tool) { _, request -> answer(request, inputs, command) }
    }

    private fun answer(request: CallToolRequest, inputs: List<Input>, command: (Arguments) -> CommandOutcome): CallToolResult {
        val given = request.arguments().orEmpty()
        val unknown = given.keys - inputs.map { input -> input.name }.toSet()
        if (unknown.isNotEmpty()) return malformed("`${request.name()}` has no input ${unknown.joinToString(" or ") { name -> "`$name`" }}.")
        return try {
            text(command(Arguments(request.name(), given)).text, isError = false)
        } catch (problem: MalformedCall) {
            malformed(problem.message!!)
        }
    }

    private fun malformed(sentence: String) = text(sentence, isError = true)

    private fun text(text: String, isError: Boolean): CallToolResult =
        CallToolResult.builder().content(listOf(TextContent(text))).isError(isError).build()

    private const val DRAFT = "draft.velle"

    /** A call's arguments, each read as text. */
    private class Arguments(private val tool: String, private val given: Map<String, Any?>) {

        fun text(name: String): String = optionalText(name) ?: throw MalformedCall("`$tool` needs `$name`.")

        fun text(name: String, otherwise: String): String = optionalText(name) ?: otherwise

        fun optionalText(name: String): String? {
            val value = given[name] ?: return null
            return value as? String ?: throw MalformedCall("`$name` is text.")
        }
    }

    private class MalformedCall(sentence: String) : RuntimeException(sentence)
}
