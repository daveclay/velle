package velle.vendors

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The application booted over an in-memory SQLite database in place of the
 * PostgreSQL that `bootRun` starts, and with no identity provider: a signed-in
 * person is presented through Spring Security's test support, a token whose
 * `sub` is the one the fixtures give Grace or Linus. The starter finds the
 * system bean, creates the schema, describes the model, and serves the console.
 * VendorsSignInTest runs the same against a real Authentik.
 */
@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false",
    "spring.datasource.url=jdbc:sqlite:file:vendors_app_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
])
@AutoConfigureMockMvc
class VendorsApplicationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private val grace = jwt().jwt { token -> token.subject("1001") }
    private val linus = jwt().jwt { token -> token.subject("1002") }
    private val strangerTheProviderKnows = jwt().jwt { token -> token.subject("9999") }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    private fun productNamesSeenBy(person: org.springframework.test.web.servlet.request.RequestPostProcessor): List<Map<*, *>> {
        val body = mvc.get("/api/shapes/Product?orderBy=name") { with(person) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return (parse(body)["items"] as List<*>).map { item -> item as Map<*, *> }
    }

    @Test
    fun `the vendors system is served over sqlite to whoever signed in`() {
        val described = parse(mvc.get("/api") { with(grace) }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue((described["acts"] as List<*>).isNotEmpty(), described.toString())
        assertEquals(emptyList<String>(), (described["schedules"] as List<*>).map { schedule -> schedule.toString() }.sorted())
        mvc.get("/velle") { with(grace) }.andExpect { status { isOk() } }
    }

    @Test
    fun `nobody signed in is answered 401 at the api and sent to sign in at the console`() {
        mvc.get("/api/shapes/Product?orderBy=name").andExpect { status { isUnauthorized() } }
        mvc.get("/velle").andExpect {
            status { is3xxRedirection() }
            header { string("Location", org.hamcrest.Matchers.endsWith("/oauth2/authorization/authentik")) }
        }
    }

    @Test
    fun `the header no longer names an actor`() {
        mvc.get("/api/shapes/Product?orderBy=name") { header("X-Velle-Actor", "Staff:1") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `each signed-in staff member sees what the spec shows them`() {
        val seenByGrace = productNamesSeenBy(grace)
        assertEquals(3, seenByGrace.size, seenByGrace.toString())
        assertTrue(seenByGrace.all { product -> product["cost"] != null }, seenByGrace.toString())

        val seenByLinus = productNamesSeenBy(linus)
        assertEquals(listOf("M6 hex bolt, box of 100", "M6 washer, box of 500"), seenByLinus.map { product -> product["name"] })
        assertTrue(seenByLinus.all { product -> product["cost"] == null && product["margin"] == null }, seenByLinus.toString())
    }

    @Test
    fun `a person the provider knows and no staff record names is no actor`() {
        mvc.get("/api/shapes/Product?orderBy=name") { with(strangerTheProviderKnows) }.andExpect { status { isUnauthorized() } }
    }
}
