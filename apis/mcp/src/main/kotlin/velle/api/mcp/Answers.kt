package velle.api.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import velle.api.ApiException

/**
 * The API layer's answers as MCP tool results. A refusal is the system's information, not a
 * fault of the call, so it is a successful result whose text is the refusal's sentence and whose
 * structured content is the answer [velle.api.VelleApi.commit] gave. A malformed input, an
 * unknown record, and a missing actor are results marked as errors, with the message and its
 * details, for the assistant to correct the call.
 */
internal class Answers(private val json: McpJsonMapper) {

    /** The result of [call], whose answer is the API layer's map, or the error it threw. */
    fun of(call: () -> Map<String, Any?>): CallToolResult = try {
        answered(call())
    } catch (failure: ApiException) {
        failed(failure)
    }

    /** The result of [call], whose answer is a plain number, such as a count. */
    fun ofCount(call: () -> Int): CallToolResult = of { mapOf("count" to call()) }

    private fun answered(answer: Map<String, Any?>): CallToolResult {
        val refusal = answer["accepted"] == false
        val text = if (refusal) answer["reason"] as String else json.writeValueAsString(answer)
        return CallToolResult.builder().addTextContent(text).structuredContent(answer).isError(false).build()
    }

    private fun failed(failure: ApiException): CallToolResult {
        val details = failure.details.joinToString("") { detail -> "\n$detail" }
        return CallToolResult.builder().addTextContent("${failure.message}$details").isError(true).build()
    }
}
