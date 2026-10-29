package velle.orders

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The application booted over an in-memory SQLite database in place of the
 * PostgreSQL that `bootRun` starts: the starter finds the system bean, creates
 * the schema, describes the model, and serves the console.
 */
@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false",
    "spring.datasource.url=jdbc:sqlite:file:orders_app_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
])
@AutoConfigureMockMvc
class OrdersApplicationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    @Test
    fun `the orders system is served over sqlite`() {
        val described = parse(mvc.get("/api").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue((described["acts"] as List<*>).isNotEmpty(), described.toString())
        assertEquals(emptyList<String>(), (described["schedules"] as List<*>).map { it.toString() }.sorted())
        mvc.get("/velle").andExpect { status { isOk() } }
    }
}
