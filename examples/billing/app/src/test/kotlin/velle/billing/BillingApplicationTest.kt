package velle.billing

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole stack: the billing application booted against a PostgreSQL in a
 * container, driven over REST and GraphQL, with the application's own typed
 * endpoint beside the generic ones. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["spring.docker.compose.enabled=false", "velle.api.ticks=true"])
@AutoConfigureMockMvc
class BillingApplicationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @BeforeAll
        @JvmStatic
        fun docker() = Assumptions.assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false), "needs Docker")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.at(vararg path: String): Any? = path.fold(this as Any?) { acc, k -> (acc as Map<String, Any?>)[k] }

    private fun post(path: String, body: String) =
        parse(mvc.post(path) { contentType = MediaType.APPLICATION_JSON; content = body }.andReturn().response.contentAsString)

    @Test
    fun `a customer is billed, pays, and the books follow, over PostgreSQL`() {
        assertEquals(true, post("/api/acts/SignUp", """{"name": "Ada Lovelace", "email": "ada@example.com"}""")["accepted"])
        val ada = (parse(mvc.get("/api/shapes/Customer?orderBy=name").andReturn().response.contentAsString)["items"] as List<*>).single() as Map<*, *>
        assertEquals(true, post("/api/acts/BillCustomer", """{"customer": ${ada["key"]}, "due": "2026-01-15"}""")["accepted"])
        val invoice = (parse(mvc.get("/api/shapes/Invoice?orderBy=due").andReturn().response.contentAsString)["items"] as List<*>).single() as Map<*, *>
        val key = invoice["key"]
        assertEquals(true, post("/api/acts/LineItem", """{"invoice": $key, "description": "Design work", "price": "1200.00", "quantity": 1}""")["accepted"])
        assertEquals(true, post("/api/acts/LineItem", """{"invoice": $key, "description": "Hosting", "price": 50, "quantity": 2}""")["accepted"])
        assertEquals(true, post("/api/acts/Issuance", """{"invoice": $key}""")["accepted"])

        // the due date is frozen once issued: the change is refused as data, and the refusal is a record
        assertEquals(true, post("/api/acts/ChangeDueDate", """{"invoice": $key, "newDue": "2026-02-01"}""")["accepted"])
        val refusals = parse(mvc.get("/api/shapes/DueChangeRefusal?orderBy=refusedOn").andReturn().response.contentAsString)
        assertEquals("invoice is issued", ((refusals["items"] as List<*>).single() as Map<*, *>)["reason"])

        // the application's own typed endpoint sees the overdue invoice (due in the past, balance owed)
        val overdue = json.readValue(mvc.get("/overdue").andExpect { status { isOk() } }.andReturn().response.contentAsString, List::class.java)
        assertEquals(1, overdue.size)
        assertEquals("1300.00", (overdue.single() as Map<*, *>)["balance"])
        assertEquals("Ada Lovelace", (overdue.single() as Map<*, *>)["customer"])

        // the weekly tick reminds; a second tick within the week does not nag again
        mvc.post("/api/ticks/Weekly").andExpect { status { isOk() } }
        mvc.post("/api/ticks/Weekly").andExpect { status { isOk() } }
        assertEquals(1, parse(mvc.get("/api/shapes/Reminder/count").andReturn().response.contentAsString)["count"])

        // a zero payment is refused at the door, 422 with the never that refused it
        val zero = mvc.post("/api/acts/SubmitPayment") { contentType = MediaType.APPLICATION_JSON; content = """{"invoice": $key, "amount": 0}""" }
            .andExpect { status { isUnprocessableContent() } }.andReturn().response.contentAsString
        assertTrue("amount" in parse(zero).at("rejection", "message") as String)

        // paid in full through GraphQL: the receipt follows, the email is queued after commit
        val gql = parse(mvc.post("/graphql") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("query" to """mutation { submitPayment(input: {invoice: "$key", amount: "1300.00"}) { accepted } }"""))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(true, gql.at("data", "submitPayment", "accepted"), gql.toString())
        val after = parse(mvc.post("/graphql") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("query" to """{ invoice(key: "$key") { status balance isPaidInvoice receipts { receiptEmails { queuedOn } } } customers(orderBy: ["name"]) { items { largestPayment } } }"""))
        }.andReturn().response.contentAsString)
        assertEquals("paid", after.at("data", "invoice", "status"))
        assertEquals("0.00", after.at("data", "invoice", "balance"))
        assertEquals(1, ((after.at("data", "invoice", "receipts") as List<*>).single() as Map<*, *>).let { (it["receiptEmails"] as List<*>).size })
        assertEquals("1300.00", ((after.at("data", "customers", "items") as List<*>).single() as Map<*, *>)["largestPayment"])

        // the console is up
        mvc.get("/velle").andExpect { status { isOk() } }
    }
}
