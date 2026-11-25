package velle.api.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations
import velle.api.VelleApi

/**
 * The reads, three tools shared by every shape the caller may read: [READ] one record by its
 * key, [QUERY] the records that satisfy a predicate written in the spec's own language, a page
 * at a time, and [COUNT] how many there are. Each reads as the viewer the transport resolved.
 */
internal class ReadTools(private val api: VelleApi, private val answers: Answers) {

    val tools: List<SyncToolSpecification> get() = listOf(read(), query(), count())

    private val shapeSchema get() = mapOf<String, Any?>(
        "type" to "string",
        "enum" to api.readable,
        "description" to "The shape or refinement to read. The resource ${Resources.MODEL} lists each one's members.",
    )

    private val fieldsSchema = mapOf<String, Any?>(
        "type" to "array",
        "items" to mapOf("type" to "string"),
        "description" to "The members to return. Left off, every member.",
    )

    private fun read(): SyncToolSpecification {
        val properties = mapOf(
            "shape" to shapeSchema,
            "key" to mapOf("type" to listOf("integer", "string"), "description" to "The record's key."),
            "fields" to fieldsSchema,
        )
        val tool = readOnly(READ, "Read one record by its key.", properties, required = listOf("shape", "key"))
        return SyncToolSpecification(tool) { context, request ->
            val arguments = request.arguments().orEmpty()
            answers.of { api.read(arguments["shape"] as String, arguments["key"]!!, VelleMcp.actorOf(context), selected(arguments)) }
        }
    }

    private fun query(): SyncToolSpecification {
        val properties = mapOf(
            "shape" to shapeSchema,
            "where" to mapOf("type" to "string", "description" to WHERE),
            "orderBy" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Orderable members to sort by, a leading - for descending, such as -due. Left off, the records come in the order of their keys.",
            ),
            "size" to mapOf("type" to "integer", "description" to "How many records a page holds. Left off, all of them."),
            "after" to mapOf("type" to "string", "description" to "The next value of the previous page, to read the page after it."),
            "fields" to fieldsSchema,
        )
        val tool = readOnly(QUERY, "List the records of a shape that satisfy a predicate, a page at a time.", properties, required = listOf("shape"))
        return SyncToolSpecification(tool) { context, request -> queried(context, request.arguments().orEmpty()) }
    }

    private fun queried(context: McpTransportContext, arguments: Map<String, Any?>): CallToolResult {
        @Suppress("UNCHECKED_CAST")
        val orderBy = arguments["orderBy"] as List<String>? ?: emptyList()
        val size = (arguments["size"] as Number?)?.toInt()
        return answers.of {
            api.query(arguments["shape"] as String, arguments["where"] as String?, orderBy, size, arguments["after"] as String?, VelleMcp.actorOf(context), selected(arguments))
        }
    }

    private fun count(): SyncToolSpecification {
        val properties = mapOf("shape" to shapeSchema, "where" to mapOf("type" to "string", "description" to WHERE))
        val tool = readOnly(COUNT, "Count the records of a shape that satisfy a predicate.", properties, required = listOf("shape"))
        return SyncToolSpecification(tool) { context, request ->
            val arguments = request.arguments().orEmpty()
            answers.ofCount { api.count(arguments["shape"] as String, arguments["where"] as String?, VelleMcp.actorOf(context)) }
        }
    }

    private fun readOnly(name: String, description: String, properties: Map<String, Any?>, required: List<String>): Tool {
        val inputSchema = mapOf<String, Any?>("type" to "object", "properties" to properties, "required" to required)
        return Tool.builder(name, inputSchema)
            .title(name)
            .description(description)
            .annotations(ToolAnnotations.builder().title(name).readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build())
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun selected(arguments: Map<String, Any?>): Set<String>? = (arguments["fields"] as List<String>?)?.toSet()

    companion object {
        const val READ = "read"
        const val QUERY = "query"
        const val COUNT = "count"

        private const val WHERE =
            "A predicate in the spec's own language over the shape's members, such as `balance > 0 and due < today`. " +
                "Comparisons are ==, !=, <, <=, >, >=, `between a and b`, and `in [\"open\", \"pending\"]`; combine them with and, or, not. " +
                "Text is in double quotes and also takes contains, starts with, ends with. " +
                "`field is none` and `field is some` test an optional value, `items is empty` a collection, `invoice is PaidInvoice` membership of a refinement. " +
                "A relationship is followed with a dot, `customer.name`. Dates read `today`, `now`, and `today - 7 days`. " +
                "Left off, every record."
    }
}
