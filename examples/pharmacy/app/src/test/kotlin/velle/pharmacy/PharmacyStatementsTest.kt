package velle.pharmacy

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
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
    @Autowired lateinit var pharmacy: velle.generated.PharmacySystem

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

    /** The first administrator, signed in: a token whose `sub` is her identityId, through Spring Security's test support. */
    private val margaret = jwt().jwt { token -> token.subject(Roster.founder.identityId) }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    /** The statements sent while [read] runs. */
    private fun statementsOf(read: () -> Unit): List<String> {
        statementsSent.clear()
        read()
        return statementsSent.toList()
    }

    private fun graphql(query: String): Map<String, Any?> = parse(mvc.post("/graphql") {
        with(margaret)
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
        // one request is one snapshot: who is calling, found by the identity the sign-in carries, the
        // list and the actor's record, then the drugs the interactions name as drugA in one statement
        // and their ingredients in another, and the same two for drugB. The drugs are fetched because
        // following a reference checks that the caller may see its record
        assertTrue(statements.size <= 7, "${statements.size} statements for ${items.size} interactions: ${statements.groupingBy { statement -> statement }.eachCount()}")
    }

    /**
     * What a list of whole records costs: every member of every drug is evaluated, and the records
     * each member reads are fetched for all the drugs together, so the count is one statement per
     * kind of related record and does not grow with the number of drugs.
     */
    @Test
    fun `a list of whole drugs sends one statement per kind of related record, not one per drug`() {
        lateinit var body: String
        val statements = statementsOf {
            body = mvc.get("/api/shapes/Drug") { with(margaret) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        }
        val drugs = (parse(body)["items"] as List<*>).size
        assertEquals(Roster.drugs.size, drugs)
        assertTrue(drugs > 20, "enough drugs that a statement per drug would show")
        assertTrue(statements.size <= 20, "${statements.size} statements for $drugs drugs: ${statements.map { statement -> statement.take(60) }}")
    }

    /**
     * Who is calling costs one statement: PharmacyActors asks for the Staff record whose identityId
     * is the token's `sub`, as a reference. The generated lookup by that key builds the whole record
     * instead, and reads every record that names the staff member to fill its collections.
     */
    @Test
    fun `finding who is calling reads only the staff table, where the generated lookup reads every collection on staff`() {
        val resolving = statementsOf {
            mvc.get("/api/shapes/Ingredient/count") { with(margaret) }.andExpect { status { isOk() } }
        }
        assertEquals(1, resolving.count { statement -> "\"identityId\"" in statement }, resolving.toString())
        assertEquals(emptyList(), resolving.filter { statement -> listOf("\"Fill\"", "\"Verification\"", "\"LicenceRenewal\"").any { table -> table in statement } })

        val generatedLookup = statementsOf {
            pharmacy.system.scope { pharmacy.read.staffByIdentityId(Roster.founder.identityId) }
        }
        val collectionTables = listOf("Fill", "Verification", "LicenceRenewal", "LicenceReminder", "EntryVoid", "StockAdjustment", "CountConfirmation")
        assertTrue(collectionTables.all { table -> generatedLookup.any { statement -> "\"$table\"" in statement } }, generatedLookup.toString())
    }
}
