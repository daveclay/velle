package velle.api.spring

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import velle.FetchTogether
import velle.Model
import velle.Parser
import velle.VelleSystem
import velle.api.VelleApi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The fetch limit set in an application's properties reaches the running system, and a shape the spec lacks stops startup. */
class FetchLimitConfigurationTest {

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(VelleAutoConfiguration::class.java))
        .withBean(VelleSystem::class.java, { VelleSystem(Model(Parser.parse(File("../../examples/billing/billing.velle").readText()))) })
        .withPropertyValues("velle.store.enabled=false", "velle.console.enabled=false")

    @Test
    fun `with no fetch limit set the system keeps the limit of 500`() {
        runner.run { context ->
            context.getBean(VelleApi::class.java)
            assertEquals(FetchTogether(limit = 500), context.getBean(VelleSystem::class.java).fetchTogether)
        }
    }

    @Test
    fun `the fetch limit and the limit for one shape reach the system`() {
        runner.withPropertyValues("velle.store.fetch-limit=40", "velle.store.fetch-limit-by-shape.Invoice=7").run { context ->
            context.getBean(VelleApi::class.java)
            assertEquals(FetchTogether(limit = 40, limitByShape = mapOf("Invoice" to 7)), context.getBean(VelleSystem::class.java).fetchTogether)
        }
    }

    @Test
    fun `a fetch limit for a shape the spec does not have stops startup`() {
        runner.withPropertyValues("velle.store.fetch-limit-by-shape.Invoyce=7").run { context ->
            assertNotNull(context.startupFailure, "the context started with a limit for a shape that does not exist")
        }
    }
}
