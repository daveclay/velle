package velle.api.spring

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import velle.VelleSystem
import java.net.http.HttpRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The starter's MCP server over the vendors spec, reached over HTTP by the MCP SDK's own
 * client, as an AI chat client reaches it: the tools listed, an act committed as the actor
 * the application's resolver found for the request, refused without one, and checked by a
 * dry run that keeps nothing.
 */
@SpringBootTest(classes = [VendorsApp::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "velle.mcp.path=/mcp",
])
class McpOverHttpTest {

    @LocalServerPort var port: Int = 0
    @Autowired lateinit var system: VelleSystem

    private val clients = mutableListOf<McpSyncClient>()

    @AfterTest
    fun closeClients() = clients.forEach { client -> client.close() }

    /** A client of the server; with [actor], every request carries the header the vendors resolver reads. */
    private fun client(actor: String? = null): McpSyncClient {
        val request = HttpRequest.newBuilder().also { builder -> actor?.let { header -> builder.header("X-Velle-Actor", header) } }
        val transport = HttpClientStreamableHttpTransport.builder("http://localhost:$port").endpoint("/mcp").requestBuilder(request).build()
        return McpClient.sync(transport).build().also { client -> client.initialize(); clients.add(client) }
    }

    private fun McpSyncClient.call(tool: String, arguments: Map<String, Any?>): CallToolResult = callTool(CallToolRequest(tool, arguments))

    private fun CallToolResult.text(): String = (content().single() as TextContent).text()

    @Suppress("UNCHECKED_CAST")
    private fun CallToolResult.structured(): Map<String, Any?> = structuredContent() as Map<String, Any?>

    @Test
    fun `the tools are the acts and the reads, and the spec is not offered when the system was built from a model alone`() {
        val client = client()
        val names = client.listTools().tools().map { tool -> tool.name() }
        assertTrue("ListProduct" in names && "query" in names, names.toString())
        assertEquals(listOf("velle://model"), client.listResources().resources().map { resource -> resource.uri() })
        val model = client.readResource(ReadResourceRequest("velle://model")).contents().single() as TextResourceContents
        assertTrue("\"authorization\":true" in model.text(), model.text())
    }

    @Test
    fun `an act is refused without an actor and committed as the one the request carries`() {
        val admin = system.seed("Staff", mapOf("name" to "Grace", "email" to "grace@vendors.example", "identityId" to "grace-id", "role" to "admin")).key.value
        val vendor = system.seed("Vendor", mapOf("name" to "Acme")).key.value
        val product = mapOf("vendor" to vendor, "name" to "Widget", "price" to 10, "cost" to 4)

        val anonymous = client().call("ListProduct", product)
        assertEquals(true, anonymous.isError(), anonymous.text())

        val asAdmin = client("Staff:$admin")
        val checked = asAdmin.call("ListProduct", product + ("@dryRun" to true))
        assertEquals(mapOf<String, Any?>("accepted" to true), checked.structured())
        assertEquals(0, asAdmin.call("count", mapOf("shape" to "Product")).structured()["count"])

        val listed = asAdmin.call("ListProduct", product)
        assertEquals(true, listed.structured()["accepted"], listed.text())
        assertEquals(1, asAdmin.call("count", mapOf("shape" to "Product")).structured()["count"])
    }
}
