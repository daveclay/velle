package velle.api.mcp

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import reactor.core.publisher.Mono
import velle.Model
import velle.Parser
import velle.Ref
import velle.VelleSystem
import velle.api.VelleApi
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MCP server over the example specs, called as a client calls it: JSON-RPC requests for
 * the tool list, tool calls, and resource reads, sent to the server the SDK builds, which also
 * checks every tool's input schema is a valid JSON Schema.
 */
class VelleMcpTest {

    private val day = Instant.parse("2026-03-10T12:00:00Z")

    private fun example(name: String) = File("../../examples/$name/$name.velle").readText()

    /** The transport a test talks through: it keeps the server's handler and hands it requests. */
    private class DirectTransport : McpStatelessServerTransport {
        lateinit var handler: McpStatelessServerHandler
        override fun setMcpHandler(mcpHandler: McpStatelessServerHandler) { handler = mcpHandler }
        override fun closeGracefully(): Mono<Void> = Mono.empty()
    }

    private inner class Client(spec: String) {
        val api = VelleApi(VelleSystem(Model(Parser.parse(spec)), startTime = day, source = spec), Clock.fixed(day, ZoneOffset.UTC))
        val mcp = VelleMcp(api)
        private val transport = DirectTransport().also { transport -> mcp.server(transport) }
        private var nextId = 1

        @Suppress("UNCHECKED_CAST")
        fun request(method: String, params: Map<String, Any?>, actor: Ref<*>? = null): Map<String, Any?> {
            val context: McpTransportContext = VelleMcp.contextFor(actor)
            val response = transport.handler.handleRequest(context, JSONRPCRequest("2.0", method, nextId++, params)).block()!!
            check(response.error() == null) { response.error().toString() }
            return mcp.json.readValue(mcp.json.writeValueAsString(response.result()), Map::class.java) as Map<String, Any?>
        }

        fun call(tool: String, arguments: Map<String, Any?>, actor: Ref<*>? = null) =
            request("tools/call", mapOf("name" to tool, "arguments" to arguments), actor)

        @Suppress("UNCHECKED_CAST")
        fun tools(): List<Map<String, Any?>> = request("tools/list", emptyMap())["tools"] as List<Map<String, Any?>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.text(): String = (this["content"] as List<Map<String, Any?>>).single()["text"] as String

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.structured(): Map<String, Any?> = this["structuredContent"] as Map<String, Any?>

    // ── the tool list ───────────────────────────────────────────────────────────

    @Test
    fun `every act is a tool of its own, beside the three reads`() {
        val client = Client(example("pharmacy"))
        val names = client.tools().map { tool -> tool["name"] }
        assertEquals(client.api.acts + listOf("read", "query", "count"), names)
    }

    @Test
    fun `an act's tool takes its fields, required unless optional, and says what the act does`() {
        val hire = Client(example("pharmacy")).tools().single { tool -> tool["name"] == "HireStaff" }
        assertEquals(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "email" to mapOf("type" to "string"),
                    "identityId" to mapOf("type" to "string"),
                    "role" to mapOf("type" to "string", "enum" to listOf("pharmacist", "technician", "admin")),
                    "@dryRun" to mapOf("type" to "boolean", "description" to "Check whether the act would be accepted, without committing it."),
                ),
                "required" to listOf("name", "email", "identityId", "role"),
            ),
            hire["inputSchema"],
        )
        assertEquals(
            """
            Commits a HireStaff. It leaves no record of its own. It is committed by an Administrator: the caller must be one.
            Committing it can start rule RecordHire, which creates a Staff.
            Every prohibition in the spec is checked when its work is done; a refusal names the prohibition it broke.
            With "@dryRun": true nothing is committed: the answer is what a commit would answer now, for what runs inside the commit. It is no promise, since another commit can land first.
            """.trimIndent(),
            hire["description"],
        )
        assertEquals(false, (hire["annotations"] as Map<*, *>)["readOnlyHint"])
    }

    @Test
    fun `the reads are marked read-only`() {
        val reads = Client(example("billing")).tools().filter { tool -> tool["name"] in setOf("read", "query", "count") }
        assertEquals(listOf(true, true, true), reads.map { tool -> (tool["annotations"] as Map<*, *>)["readOnlyHint"] })
    }

    // ── calls ───────────────────────────────────────────────────────────────────

    /** Billing with one customer and one invoice; returns the client and the invoice's key. */
    @Suppress("UNCHECKED_CAST")
    private fun billingWithAnInvoice(): Pair<Client, Any?> {
        val client = Client(example("billing"))
        client.call("SignUp", mapOf("name" to "Ada", "email" to "ada@example.com"))
        val customer = (client.call("query", mapOf("shape" to "Customer")).structured()["items"] as List<Map<String, Any?>>).single()
        client.call("BillCustomer", mapOf("customer" to customer["key"], "due" to "2026-04-01"))
        val invoice = (client.call("query", mapOf("shape" to "Invoice")).structured()["items"] as List<Map<String, Any?>>).single()
        return client to invoice["key"]
    }

    @Test
    fun `a refusal is a successful result whose text says which rule refused the act`() {
        val (client, invoice) = billingWithAnInvoice()

        val refused = client.call("SubmitPayment", mapOf("invoice" to invoice, "amount" to 0))

        assertEquals(false, refused["isError"])
        assertTrue(refused.text().startsWith("SubmitPayment refused"), refused.text())
        assertTrue("SubmitPayment where amount <= 0" in refused.text(), refused.text())
        assertEquals(false, refused.structured()["accepted"])
    }

    @Test
    fun `a malformed input is an error result naming the field`() {
        val (client, invoice) = billingWithAnInvoice()

        val malformed = client.call("SubmitPayment", mapOf("invoice" to invoice, "amount" to "ten"))

        assertEquals(true, malformed["isError"])
        assertTrue("amount" in malformed.text(), malformed.text())
    }

    @Test
    fun `a dry run answers as a commit would and keeps nothing`() {
        val (client, invoice) = billingWithAnInvoice()

        val checked = client.call("SubmitPayment", mapOf("invoice" to invoice, "amount" to "40.00", "@dryRun" to true))
        assertEquals(mapOf<String, Any?>("accepted" to true), checked.structured())
        assertEquals(0, client.call("count", mapOf("shape" to "Payment")).structured()["count"])

        client.call("SubmitPayment", mapOf("invoice" to invoice, "amount" to "40.00"))
        assertEquals(1, client.call("count", mapOf("shape" to "Payment")).structured()["count"])
    }

    @Test
    fun `the reads answer with the records in wire form`() {
        val (client, invoice) = billingWithAnInvoice()
        client.call("LineItem", mapOf("invoice" to invoice, "description" to "Design", "price" to "1200.00", "quantity" to 1))

        val read = client.call("read", mapOf("shape" to "Invoice", "key" to invoice, "fields" to listOf("total", "status"))).structured()
        assertEquals(mapOf("shape" to "Invoice", "key" to invoice, "total" to "1200.00", "status" to "open"), read)

        val owing = client.call("query", mapOf("shape" to "Invoice", "where" to "balance > 1000", "orderBy" to listOf("-due"))).structured()
        assertEquals(1, (owing["items"] as List<*>).size)

        val unknown = client.call("query", mapOf("shape" to "Invoice", "where" to "colour == 1"))
        assertEquals(true, unknown["isError"])
    }

    @Test
    fun `an act declared by is refused without an actor`() {
        val client = Client(example("pharmacy"))

        val hire = client.call("HireStaff", mapOf("name" to "Bea", "email" to "bea@example.com", "identityId" to "bea", "role" to "pharmacist"))

        assertEquals(true, hire["isError"])
        assertTrue("Administrator" in hire.text(), hire.text())
    }

    // ── resources ───────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun Client.resource(uri: String): String =
        ((request("resources/read", mapOf("uri" to uri))["contents"] as List<Map<String, Any?>>).single())["text"] as String

    @Test
    fun `the spec's text and the model are resources`() {
        val client = Client(example("billing"))

        assertEquals(example("billing"), client.resource("velle://spec"))
        assertTrue("\"SubmitPayment\"" in client.resource("velle://model"))
    }

    @Test
    fun `a system built without its spec's text offers only the model`() {
        val api = VelleApi(VelleSystem(Model(Parser.parse(example("billing")))), null)
        assertEquals(listOf("velle://model"), VelleMcp(api).resources.map { resource -> resource.resource().uri() })
    }
}
