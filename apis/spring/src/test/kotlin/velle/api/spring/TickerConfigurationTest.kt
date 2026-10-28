package velle.api.spring

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import org.springframework.scheduling.TaskScheduler
import velle.Model
import velle.Parser
import velle.VelleSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The ticker's conditions: no schedules, no scheduler; `velle.ticker.enabled: false` skips it and nothing else; a schedule the spec lacks or a cron that does not parse stops startup, naming the schedule. */
class TickerConfigurationTest {

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(VelleAutoConfiguration::class.java, TaskSchedulingAutoConfiguration::class.java))
        .withBean(VelleSystem::class.java, { VelleSystem(Model(Parser.parse(File("../../examples/billing/billing.velle").readText()))) })
        .withPropertyValues("velle.store.enabled=false", "velle.console.enabled=false")

    private fun Throwable.messages(): String = generateSequence(this) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `without schedules nothing is scheduled and no scheduler is made`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(TaskScheduler::class.java).isEmpty())
            assertTrue(ctx.getBeanNamesForType(VelleAutoConfiguration.Ticker::class.java).isEmpty())
        }
    }

    @Test
    fun `with a schedule spring's scheduler is made and the schedule is bound in both spellings`() {
        runner.withPropertyValues("velle.schedules.Hourly=@hourly", "velle.schedules.Weekly.cron=0 0 6 * * MON", "velle.schedules.Weekly.zone=Europe/London").run { ctx ->
            assertEquals(1, ctx.getBeansOfType(TaskScheduler::class.java).size)
            val schedules = ctx.getBean(VelleProperties::class.java).schedules
            assertEquals("@hourly", schedules.getValue("Hourly").cron)
            assertEquals(null, schedules.getValue("Hourly").zone)
            assertEquals("0 0 6 * * MON", schedules.getValue("Weekly").cron)
            assertEquals("Europe/London", schedules.getValue("Weekly").zone.toString())
        }
    }

    @Test
    fun `velle ticker enabled false skips the ticker on this instance and nothing else`() {
        runner.withPropertyValues("velle.ticker.enabled=false", "velle.schedules.Weekly=0 0 6 * * MON").run { ctx ->
            assertTrue(ctx.getBeansOfType(TaskScheduler::class.java).isEmpty())
            assertTrue(ctx.getBeanNamesForType(VelleAutoConfiguration.Ticker::class.java).isEmpty())
            val tick = ctx.getBean(velle.api.VelleApi::class.java).tick("Weekly") // a tick by hand still runs
            assertEquals("Weekly", tick["schedule"])
        }
    }

    @Test
    fun `with no listener of the application's the log hears the ticks`() {
        runner.run { ctx ->
            val listeners = ctx.getBean(VelleSystem::class.java).tickListeners
            assertEquals(listOf(LoggingTickListener::class), listeners.map { it::class })
        }
    }

    @Test
    fun `the application's listener replaces the log, and a meter registry adds the counts`() {
        val heard = mutableListOf<String>()
        val mine = object : velle.TickListener { override fun ticked(report: velle.TickReport, rejections: List<velle.Rejection>) { heard += report.schedule } }
        runner.withBean("mine", velle.TickListener::class.java, { mine })
            .withBean(io.micrometer.core.instrument.simple.SimpleMeterRegistry::class.java)
            .withPropertyValues("velle.schedules.Weekly=0 0 6 * * MON").run { ctx ->
                val system = ctx.getBean(VelleSystem::class.java)
                assertEquals(listOf(mine::class, MetricsTickListener::class), system.tickListeners.map { it::class })
                ctx.getBean(velle.api.VelleApi::class.java).tick("Weekly")
                assertEquals(listOf("Weekly"), heard)
                val registry = ctx.getBean(io.micrometer.core.instrument.MeterRegistry::class.java)
                assertEquals(1.0, registry.counter("velle.ticks", "schedule", "Weekly", "outcome", "clean").count())
            }
    }

    @Test
    fun `a schedule the spec does not have stops startup`() {
        runner.withPropertyValues("velle.schedules.Monthly=0 0 0 1 * *").run { ctx ->
            val failure = assertNotNull(ctx.startupFailure)
            assertTrue("[Monthly]" in failure.messages(), failure.messages())
        }
    }

    @Test
    fun `a cron that does not parse stops startup, naming the schedule`() {
        runner.withPropertyValues("velle.schedules.Weekly.cron=every monday").run { ctx ->
            val failure = assertNotNull(ctx.startupFailure)
            assertTrue("velle.schedules.Weekly" in failure.messages(), failure.messages())
        }
    }

    @Test
    fun `with a schedule a shutdown waits for a sweep in flight, unless the application says otherwise`() {
        fun env(vararg props: Pair<String, String>): MockEnvironment = MockEnvironment().also { e ->
            props.forEach { (k, v) -> e.setProperty(k, v) }
            TickerShutdownDefaults().postProcessEnvironment(e, SpringApplication())
        }
        assertEquals(null, env().getProperty("spring.task.scheduling.shutdown.await-termination"), "no schedules: Boot's own default stands")
        val defaulted = env("velle.schedules.Hourly" to "@hourly")
        assertEquals("true", defaulted.getProperty("spring.task.scheduling.shutdown.await-termination"))
        assertEquals("30s", defaulted.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
        val own = env("velle.schedules.Hourly" to "@hourly", "spring.task.scheduling.shutdown.await-termination" to "false")
        assertEquals("false", own.getProperty("spring.task.scheduling.shutdown.await-termination"), "the application's setting sits above the default")
        assertEquals("30s", own.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
    }
}
