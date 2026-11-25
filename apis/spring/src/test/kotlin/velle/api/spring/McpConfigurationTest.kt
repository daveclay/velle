package velle.api.spring

import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import velle.Model
import velle.Parser
import velle.VelleSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * When the starter serves the MCP server: only with `velle.mcp.path` set, and for a spec that
 * declares no authorization, such as `examples/billing/billing.velle`, only with
 * `velle.mcp.unauthenticated: true`; without it the application does not start.
 */
class McpConfigurationTest {

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(VelleAutoConfiguration::class.java))
        .withBean(VelleSystem::class.java, { VelleSystem(Model(Parser.parse(File("../../examples/billing/billing.velle").readText()))) })
        .withPropertyValues("velle.store.enabled=false", "velle.console.enabled=false")

    private fun Throwable.messages(): String = generateSequence(this) { cause -> cause.cause }.mapNotNull { cause -> cause.message }.joinToString(" | ")

    @Test
    fun `without a path there is no MCP server`() {
        runner.run { context -> assertTrue(context.getBeansOfType(HttpServletStatelessServerTransport::class.java).isEmpty()) }
    }

    @Test
    fun `a spec without authorization stops the application, naming the setting that would serve it`() {
        runner.withPropertyValues("velle.mcp.path=/mcp").run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue("declares no authorization" in failure.messages() && "velle.mcp.unauthenticated" in failure.messages(), failure.messages())
        }
    }

    @Test
    fun `with velle mcp unauthenticated the same spec is served`() {
        runner.withPropertyValues("velle.mcp.path=/mcp", "velle.mcp.unauthenticated=true").run { context ->
            assertEquals(null, context.startupFailure)
            assertEquals(1, context.getBeansOfType(HttpServletStatelessServerTransport::class.java).size)
        }
    }
}
