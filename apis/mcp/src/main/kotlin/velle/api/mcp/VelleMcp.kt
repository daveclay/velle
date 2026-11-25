package velle.api.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import tools.jackson.databind.json.JsonMapper
import velle.Ref
import velle.api.VelleApi

/**
 * A running system as an MCP (Model Context Protocol) server, for an AI chat client: a tool
 * per act ([ActTools]), the tools that read ([ReadTools]), and the model and the spec as
 * resources ([Resources]). Built from the model over the API layer, so one class serves every
 * spec, and it names no transport: [server] takes any stateless MCP transport, and the one
 * that carries the request puts the actor it resolved into the call's context under [ACTOR].
 */
class VelleMcp(val api: VelleApi, val json: McpJsonMapper = JacksonMcpJsonMapper(JsonMapper.builder().build())) {
    private val answers = Answers(json)

    /** Every tool: one per act, then the reads. An act's name starts with a capital letter, so it never takes a read's name. */
    val tools: List<SyncToolSpecification> = ActTools(api, answers).tools + ReadTools(api, answers).tools

    val resources: List<SyncResourceSpecification> = Resources(api, json).resources

    /** The MCP server over [transport], named [name]. */
    fun server(transport: McpStatelessServerTransport, name: String = "velle", version: String = "1"): McpStatelessSyncServer =
        McpServer.sync(transport)
            .serverInfo(name, version)
            .jsonMapper(json)
            .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).build())
            .instructions(INSTRUCTIONS)
            .tools(tools)
            .resources(resources)
            .build()

    companion object {
        /** The key under which a transport puts the actor it resolved, a [Ref], into the call's context; absent when none. */
        const val ACTOR = "velle.actor"

        /** The actor a transport resolved for the call with [context], or null. */
        fun actorOf(context: McpTransportContext): Ref<*>? = context.get(ACTOR) as Ref<*>?

        /** The context carrying [actor], as a transport builds it for one call. */
        fun contextFor(actor: Ref<*>?): McpTransportContext =
            if (actor == null) McpTransportContext.EMPTY else McpTransportContext.create(mapOf(ACTOR to actor))

        private const val INSTRUCTIONS =
            "This server runs a system whose business rules are written in Velle. Each act is a tool; committing one records something on the books. " +
                "Read, query, and count read the records. The resource ${Resources.SPEC} holds the rules as written and ${Resources.MODEL} the shapes and their members. " +
                "When an act is refused, the answer says which rule refused it and why: tell the person that, in those words. " +
                "Before committing an act on someone's behalf, a call with \"${ActTools.DRY_RUN}\": true says whether it would be accepted."
    }
}
