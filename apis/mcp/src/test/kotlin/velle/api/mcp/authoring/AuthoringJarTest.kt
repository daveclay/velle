package velle.api.mcp.authoring

import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `apis/mcp/build/libs/velle-authoring-mcp.jar` started as a host starts it, a process spoken to over
 * its standard input and output: every line it writes there is a protocol message, and a call over
 * `examples/billing/billing.velle`, whose amounts are `Money`, proves the finance types are inside.
 */
class AuthoringJarTest {

    private val jar = File("build/libs/velle-authoring-mcp.jar").absoluteFile
    private val json = JsonMapper.builder().build()
    private val billing = File("../../examples/billing/billing.velle").readText()

    @Suppress("UNCHECKED_CAST")
    private fun answer(reader: BufferedReader): Map<String, Any?> = json.readValue(reader.readLine(), Map::class.java) as Map<String, Any?>

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the jar answers initialize, tools list, and a tool call over standard input and output`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-jar", jar.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val writer = process.outputStream.bufferedWriter()
        val reader = process.inputStream.bufferedReader()
        fun send(message: Map<String, Any?>) {
            writer.write(json.writeValueAsString(message))
            writer.newLine()
            writer.flush()
        }

        send(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any?>(), "clientInfo" to mapOf("name" to "test", "version" to "1"))))
        val initialized = answer(reader)
        send(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized"))
        send(mapOf("jsonrpc" to "2.0", "id" to 2, "method" to "tools/list"))
        val listed = answer(reader)
        send(mapOf("jsonrpc" to "2.0", "id" to 3, "method" to "tools/call", "params" to mapOf("name" to "validate", "arguments" to mapOf("spec" to billing))))
        val called = answer(reader)
        writer.close()
        val exited = process.waitFor(30, TimeUnit.SECONDS)

        assertEquals("velle-authoring", ((initialized["result"] as Map<String, Any?>)["serverInfo"] as Map<String, Any?>)["name"])
        val tools = (listed["result"] as Map<String, Any?>)["tools"] as List<Map<String, Any?>>
        assertEquals(listOf("validate", "compare", "describe", "try"), tools.map { tool -> tool["name"] })
        val result = called["result"] as Map<String, Any?>
        assertEquals(false, result["isError"])
        assertEquals("`draft.velle` validates.\n", (result["content"] as List<Map<String, Any?>>).single()["text"])
        assertTrue(exited, "the server ends when its standard input closes")
        assertEquals(0, process.exitValue())
        assertEquals("", reader.readText(), "nothing but protocol messages on standard output")
    }
}
