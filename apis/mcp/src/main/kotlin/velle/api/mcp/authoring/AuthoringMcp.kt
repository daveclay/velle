package velle.api.mcp.authoring

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import tools.jackson.databind.json.JsonMapper
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * The authoring MCP server, for an assistant writing a spec with someone: the `velle` command's
 * `validate`, `compare`, `describe`, and `try` as tools over a spec given as text ([AuthoringTools]),
 * and the velle-design skill as resources and a prompt ([SkillFiles]). It runs no system of its own,
 * unlike [velle.api.mcp.VelleMcp], which serves a running one.
 */
object AuthoringMcp {

    val json: McpJsonMapper = JacksonMcpJsonMapper(JsonMapper.builder().build())

    /** The server over standard input and output, which carry the protocol's messages and nothing else. */
    fun server(input: InputStream, output: OutputStream): McpSyncServer =
        McpServer.sync(StdioServerTransportProvider(json, input, output))
            .serverInfo("velle-authoring", "1")
            .jsonMapper(json)
            .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).prompts(false).build())
            .instructions(INSTRUCTIONS)
            .tools(AuthoringTools.tools)
            .resources(SkillFiles.resources)
            .prompts(SkillFiles.prompt)
            .build()

    private const val INSTRUCTIONS =
        "This server helps write a system's business rules in Velle with the people who own them. " +
            "The prompt velle-design and the resource velle-design://SKILL.md say how to run the session; the other resources are its references. " +
            "The tools validate, compare, describe, and try each take the spec's text and answer what the velle command prints."
}

/**
 * Runs the server on this process's standard input and output until the input closes. Anything else
 * that would print to standard output goes to standard error, so only protocol messages reach the host.
 */
fun main() {
    val protocolOutput = System.out
    System.setOut(PrintStream(System.err, true, Charsets.UTF_8))
    val inputClosed = CountDownLatch(1)
    val server = AuthoringMcp.server(EndOfInputSignal(System.`in`, inputClosed), protocolOutput)
    inputClosed.await()
    server.closeGracefully()
    exitProcess(0)
}

/** [input], counting [closed] down when it reaches its end, the host's sign that the session is over. */
private class EndOfInputSignal(input: InputStream, private val closed: CountDownLatch) : FilterInputStream(input) {

    override fun read(): Int = super.read().also { byte -> if (byte < 0) closed.countDown() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { count -> if (count < 0) closed.countDown() }
}
