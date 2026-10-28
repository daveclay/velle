package velle.api.spring

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.config.TriggerTask
import org.springframework.scheduling.support.CronTrigger
import org.springframework.scheduling.support.SimpleTriggerContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import velle.Model
import velle.Parser
import velle.VelleSystem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A system without authorization over a DataSource: the starter picks the SQLite dialect
 * from the connection, creates the schema, the ticks endpoint is on, and the two schedules,
 * one written as the cron alone and one with its zone, are tasks on Spring's scheduler.
 */
@SpringBootTest(classes = [BillingOverSqliteTest.App::class], properties = [
    "spring.datasource.url=jdbc:sqlite:file:velle_starter_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
    "velle.api.ticks=true",
    "velle.api.path=/billing",
    "velle.schedules.Hourly=0 0 * * * *",
    "velle.schedules.Weekly.cron=0 0 6 * * MON",
    "velle.schedules.Weekly.zone=America/New_York",
])
@AutoConfigureMockMvc
class BillingOverSqliteTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class App {
        @Bean
        fun billing(): VelleSystem = VelleSystem(Model(Parser.parse(File("../../examples/billing/billing.velle").readText())))
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var scheduled: ScheduledTaskHolder

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    @Test
    fun `commits land in the database and read back through the api path configured`() {
        mvc.post("/billing/acts/SignUp") { contentType = MediaType.APPLICATION_JSON; content = """{"name": "Ada", "email": "ada@example.com"}""" }.andExpect { status { isOk() } }
        val customers = parse(mvc.get("/billing/shapes/Customer?orderBy=name").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        val ada = (customers["items"] as List<*>).single() as Map<*, *>
        assertEquals(1, ada["key"]) // SQLite's row id: the store's key on the wire
        mvc.post("/billing/acts/BillCustomer") { contentType = MediaType.APPLICATION_JSON; content = """{"customer": 1, "due": "2099-04-01"}""" }.andExpect { status { isOk() } }
        val li = parse(mvc.post("/billing/acts/LineItem") { contentType = MediaType.APPLICATION_JSON; content = """{"invoice": 1, "description": "Design", "price": "1200.00", "quantity": 1}""" }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(mapOf("shape" to "LineItem", "key" to 1), li["ref"])
        val invoice = parse(mvc.get("/billing/shapes/Invoice/1").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals("1200.00", invoice["total"])
        assertEquals("open", invoice["status"]) // the application's clock is the wall clock: the due date is far off, so the invoice is open

        val tick = parse(mvc.post("/billing/ticks/Weekly").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals("Weekly", tick["schedule"])
        assertTrue((tick["rejections"] as List<*>).isEmpty())
        mvc.get("/velle").andExpect { status { isOk() } }.andReturn().response.contentAsString.let { assertTrue("const API = \"/billing\"" in it) }
    }

    @Test
    fun `each schedule is a cron task on spring's scheduler, read in its zone`() {
        val byCron = scheduled.scheduledTasks.map { it.task as TriggerTask }.associateBy { (it.trigger as CronTrigger).expression }
        assertEquals(setOf("0 0 * * * *", "0 0 6 * * MON"), byCron.keys)

        // Weekly is read in New York: from a Wednesday, the next run is Monday 06:00 there, not 06:00 UTC
        val wednesday = Instant.parse("2026-10-28T00:00:00Z")
        val next = byCron.getValue("0 0 6 * * MON").trigger.nextExecution(SimpleTriggerContext(wednesday, wednesday, wednesday))
        assertEquals(ZonedDateTime.of(2026, 11, 2, 6, 0, 0, 0, ZoneId.of("America/New_York")).toInstant(), next)

        // the task's body is api.tick: it runs against the same system the endpoint ticks
        byCron.getValue("0 0 6 * * MON").runnable.run()
    }
}
