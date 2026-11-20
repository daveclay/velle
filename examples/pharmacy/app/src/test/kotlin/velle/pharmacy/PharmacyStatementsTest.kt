package velle.pharmacy

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many SQL statements a read of the seeded pharmacy sends, counted at the DataSource. On a
 * database across a network each statement is a round trip, so the count is what a page waits for.
 */
@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false",
    "spring.datasource.url=jdbc:sqlite:file:pharmacy_statements_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
    "pharmacy.seed.days=36",
])
@AutoConfigureMockMvc
@Import(PharmacyStatementsTest.CountedDataSource::class)
class PharmacyStatementsTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    companion object {
        /** The text of every statement prepared since the last `clear()`. */
        val statementsSent = ConcurrentLinkedQueue<String>()
    }

    /** Wraps the application's DataSource so every statement a connection prepares is noted. */
    @TestConfiguration
    class CountedDataSource {
        @Bean
        fun countedDataSources() = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is DataSource) proxyOf(DataSource::class.java, bean) { result -> if (result is Connection) counted(result) else result } else bean
        }

        private fun counted(connection: Connection): Connection =
            proxyOf(Connection::class.java, connection, onCall = { method, arguments ->
                if (method == "prepareStatement") statementsSent.add(arguments[0] as String)
            })

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> proxyOf(type: Class<T>, target: T, onCall: (String, Array<Any?>) -> Unit = { _, _ -> }, wrap: (Any?) -> Any? = { it }): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                val given = arguments ?: emptyArray()
                onCall(method.name, given)
                try { wrap(method.invoke(target, *given)) } catch (thrown: InvocationTargetException) { throw thrown.targetException }
            } as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    /** The statements sent while [read] runs. */
    private fun statementsOf(read: () -> Unit): List<String> {
        statementsSent.clear()
        read()
        return statementsSent.toList()
    }

    private fun graphql(query: String): Map<String, Any?> = parse(mvc.post("/graphql") {
        header("X-Velle-Actor", "Staff:1")
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("query" to query))
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `the console's table of interactions reads no drug's own members to show its key`() {
        // the query `pageDocument` in console/src/graphql/documents.ts builds for the Interaction shape
        val table = "{ page: interactions(first: 25) { items { shape key drugA { shape key } drugB { shape key } severity } next } }"
        lateinit var answer: Map<String, Any?>
        val statements = statementsOf { answer = graphql(table) }
        assertEquals(null, answer["errors"], answer.toString())
        val items = ((answer["data"] as Map<*, *>)["page"] as Map<*, *>)["items"] as List<*>
        assertEquals(Roster.interactions.size, items.size)
        // a drug's stock, price, and prescriptions are members nobody asked for
        val unasked = statements.filter { statement -> listOf("StockLot", "PriceChange", "Prescription", "Fill", "PurchaseOrder").any { table -> "\"$table\"" in statement } }
        assertEquals(emptyList(), unasked.distinct())
        // one request is one snapshot: the list, the actor, and each distinct drug's row and ingredients
        // once. Following a reference to a drug the list already read sends nothing.
        val drugsNamed = items.flatMap { item -> listOf((item as Map<*, *>)["drugA"], item["drugB"]) }.distinct().size
        assertTrue(statements.size <= 2 + drugsNamed * 2, "${statements.size} statements for ${items.size} interactions naming $drugsNamed drugs: ${statements.groupingBy { statement -> statement }.eachCount()}")
    }

    /**
     * What a whole record costs today, pinned so a change to it is seen: every member of every
     * drug is evaluated one record at a time, and each related record read on the way is a
     * statement of its own, so the same few statements are sent over and over.
     */
    @Test
    fun `a list of whole drugs sends a statement for every related record it reads`() {
        lateinit var body: String
        val statements = statementsOf {
            body = mvc.get("/api/shapes/Drug") { header("X-Velle-Actor", "Staff:1") }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        }
        val drugs = (parse(body)["items"] as List<*>).size
        assertEquals(Roster.drugs.size, drugs)
        assertTrue(statements.size > drugs * 5, "${statements.size} statements for $drugs drugs")
        assertTrue(statements.distinct().size <= 20, "the statements differ only in the key they ask for: ${statements.distinct()}")
    }
}
