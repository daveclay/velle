package velle.api.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult
import io.modelcontextprotocol.spec.McpSchema.Resource
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import velle.api.VelleApi

/**
 * The documents a client may attach to the conversation: [MODEL], the model as the API layer
 * describes it, every act and readable shape with its members; and [SPEC], the spec's own text,
 * comments included, when the system was built from one.
 */
internal class Resources(private val api: VelleApi, private val json: McpJsonMapper) {

    val resources: List<SyncResourceSpecification> get() = listOfNotNull(model(), spec())

    private fun model(): SyncResourceSpecification {
        val resource = Resource.builder(MODEL, "model")
            .title("The model")
            .description("Every act with its fields, every shape and refinement a caller may read with its members, the schedules, and whether the spec declares who may see what.")
            .mimeType("application/json")
            .build()
        return SyncResourceSpecification(resource) { _, _ -> text(MODEL, "application/json", json.writeValueAsString(api.description)) }
    }

    private fun spec(): SyncResourceSpecification? {
        val source = api.system.source ?: return null
        val resource = Resource.builder(SPEC, "spec")
            .title("The spec")
            .description("The spec this system runs, as its authors wrote it: the rules, and the comments that tell the business's story.")
            .mimeType("text/plain")
            .build()
        return SyncResourceSpecification(resource) { _, _ -> text(SPEC, "text/plain", source) }
    }

    private fun text(uri: String, mimeType: String, text: String) =
        ReadResourceResult(listOf(TextResourceContents(uri, mimeType, text)))

    companion object {
        const val MODEL = "velle://model"
        const val SPEC = "velle://spec"
    }
}
