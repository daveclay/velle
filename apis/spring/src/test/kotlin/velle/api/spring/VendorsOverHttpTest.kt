package velle.api.spring

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import velle.VelleSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The starter over the vendors spec, in memory: the model described, acts committed
 * and records read over REST and GraphQL with the actor the resolver found, the
 * console served, ticks hidden until configured.
 */
@SpringBootTest(classes = [VendorsApp::class], properties = [
    // in memory: no DataSource in this application (the test classpath carries JDBC for the SQLite test beside it)
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
])
@AutoConfigureMockMvc
class VendorsOverHttpTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var system: VelleSystem
    @Autowired lateinit var json: ObjectMapper

    private fun seed(): Triple<Long, Long, Long> {
        val admin = system.seed("Staff", mapOf("name" to "Grace", "role" to "admin"))
        val staff = system.seed("Staff", mapOf("name" to "Linus", "role" to "staff"))
        val vendor = system.seed("Vendor", mapOf("name" to "Acme"))
        return Triple(admin.key.value as Long, staff.key.value as Long, vendor.key.value as Long)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.at(vararg path: String): Any? = path.fold(this as Any?) { acc, k -> (acc as Map<String, Any?>)[k] }

    @Test
    fun `rest describes, refuses the anonymous, commits as the actor, and projects for the viewer`() {
        val (admin, staff, vendor) = seed()

        val described = parse(mvc.get("/api").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(true, described["authorization"])
        assertTrue((described["acts"] as List<*>).any { (it as Map<*, *>)["name"] == "ChangePrice" })

        mvc.get("/api/shapes/Vendor/$vendor").andExpect { status { isUnauthorized() } }
        mvc.post("/api/acts/ListProduct") { contentType = MediaType.APPLICATION_JSON; content = """{"vendor": $vendor, "name": "Widget", "price": 10, "cost": 4}""" }
            .andExpect { status { isUnauthorized() } }

        val listed = mvc.post("/api/acts/ListProduct") {
            header("X-Velle-Actor", "Staff:$admin"); contentType = MediaType.APPLICATION_JSON
            content = """{"vendor": $vendor, "name": "Widget", "price": 10, "cost": 4}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(true, parse(listed)["accepted"])
        mvc.post("/api/acts/AssignVendor") {
            header("X-Velle-Actor", "Staff:$admin"); contentType = MediaType.APPLICATION_JSON
            content = """{"staff": $staff, "vendor": $vendor}"""
        }.andExpect { status { isOk() } }

        val asStaff = parse(mvc.get("/api/shapes/Product?orderBy=name") { header("X-Velle-Actor", "Staff:$staff") }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        val product = (asStaff["items"] as List<*>).single() as Map<*, *>
        assertNull(product["cost"])
        assertEquals(10, product["price"])
        val asAdmin = parse(mvc.get("/api/shapes/Product/${product["key"]}") { header("X-Velle-Actor", "Staff:$admin") }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(4, asAdmin["cost"])

        // a refusal is 422 with the rejection as data; the gate refuses the wrong role
        val below = mvc.post("/api/acts/ChangePrice") {
            header("X-Velle-Actor", "Staff:$staff"); contentType = MediaType.APPLICATION_JSON
            content = """{"product": ${product["key"]}, "price": 3}"""
        }.andExpect { status { isUnprocessableContent() } }.andReturn().response.contentAsString
        assertEquals("ChangePrice", parse(below).at("rejection", "targetShape"))

        // a bad predicate is 400 with the diagnostics; an unknown root 404; ticks are not exposed
        val bad = parse(mvc.get("/api/shapes/Product") { param("where", "colour == 1"); param("orderBy", "name"); header("X-Velle-Actor", "Staff:$admin") }.andExpect { status { isBadRequest() } }.andReturn().response.contentAsString)
        assertTrue((bad["details"] as List<*>).isNotEmpty(), bad.toString())
        mvc.get("/api/shapes/Nothing/1") { header("X-Velle-Actor", "Staff:$admin") }.andExpect { status { isNotFound() } }
        mvc.post("/api/ticks/Daily").andExpect { status { isNotFound() } }

        val count = parse(mvc.get("/api/shapes/Product/count") { header("X-Velle-Actor", "Staff:$admin") }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(1, count["count"])
    }

    @Test
    fun `graphql runs over the same system with the same actor`() {
        val (admin, staff, vendor) = seed()
        fun gql(query: String, actor: Long?, variables: String = "{}"): Map<String, Any?> = parse(mvc.post("/graphql") {
            if (actor != null) header("X-Velle-Actor", "Staff:$actor")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("query" to query, "variables" to json.readValue(variables, Map::class.java)))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString)

        val anonymous = gql("""{ vendors(orderBy: ["name"]) { items { name } } }""", null)
        assertEquals("UNAUTHORIZED", ((anonymous["errors"] as List<*>).single() as Map<*, *>).let { (it["extensions"] as Map<*, *>)["kind"] })

        val listed = gql("""mutation(${'$'}v: ID!, ${'$'}s: ID!) { listProduct(input: {vendor: ${'$'}v, name: "Widget", price: 10, cost: 4}) { accepted } assignVendor(input: {vendor: ${'$'}v, staff: ${'$'}s}) { accepted } }""",
            admin, """{"v": "$vendor", "s": "$staff"}""")
        assertEquals(true, listed.at("data", "listProduct", "accepted"), listed.toString())

        val asStaff = gql("""{ products(orderBy: ["name"]) { items { name price cost vendor { name } } } }""", staff)
        val product = ((asStaff.at("data", "products", "items")) as List<*>).single() as Map<*, *>
        assertNull(product["cost"])
        assertEquals("Acme", (product["vendor"] as Map<*, *>)["name"])
    }

    @Test
    fun `the console is served, built from the api path`() {
        val html = mvc.get("/velle").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue("const API = \"/api\"" in html, html.take(300))
        assertTrue("__API__" !in html)
    }
}
