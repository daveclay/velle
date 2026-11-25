package velle.api.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations
import velle.ActDescription
import velle.api.VelleApi

/**
 * One tool per act the spec exposes, named for the act. Its input schema is the act's fields
 * and parts ([InputSchema]); its description is written from the model ([ActDescription]); a
 * call commits the act for the actor the transport resolved, or, with [DRY_RUN] set, answers
 * what a commit would answer now and keeps nothing ([VelleApi.check]).
 */
internal class ActTools(private val api: VelleApi, private val answers: Answers) {
    private val schema = InputSchema(api.model)
    private val descriptions = ActDescription(api.model)

    @Suppress("UNCHECKED_CAST")
    private val described: Map<String, Map<String, Any?>> =
        (api.description["acts"] as List<Map<String, Any?>>).associateBy { act -> act["name"] as String }

    val tools: List<SyncToolSpecification> get() = api.acts.map { act -> tool(act) }

    private fun tool(act: String): SyncToolSpecification {
        val inputSchema = schema.of(described.getValue(act), extra = mapOf(DRY_RUN to dryRunSchema))
        val tool = Tool.builder(act, inputSchema)
            .title(act)
            .description(descriptions.of(act) + "\n" + DRY_RUN_SENTENCE)
            .annotations(ToolAnnotations.builder().title(act).readOnlyHint(false).build())
            .build()
        return SyncToolSpecification(tool) { context, request -> call(act, context, request) }
    }

    private fun call(act: String, context: McpTransportContext, request: CallToolRequest): CallToolResult {
        val arguments = request.arguments().orEmpty()
        val body = arguments - DRY_RUN
        val actor = VelleMcp.actorOf(context)
        return if (arguments[DRY_RUN] == true) answers.of { api.check(act, body, actor) }
        else answers.of { api.commit(act, body, actor) }
    }

    companion object {
        /** The input that turns a call into a dry run. `@` keeps it apart from every field a spec can name, as the wire's `@shape` and `@new` are. */
        const val DRY_RUN = "@dryRun"

        private const val DRY_RUN_SENTENCE =
            "With \"$DRY_RUN\": true nothing is committed: the answer is what a commit would answer now, for what runs inside the commit. " +
                "It is no promise, since another commit can land first."

        private val dryRunSchema = mapOf<String, Any?>(
            "type" to "boolean",
            "description" to "Check whether the act would be accepted, without committing it.",
        )
    }
}
