package velle.vendors

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sign-in and the spec's rules together, against a real Authentik started in
 * containers from the same image and blueprint (authentik/vendors.yaml) that
 * `bootRun` uses. Grace and Linus each get a token from Authentik with no
 * browser, by the app password the blueprint gives them, and the application
 * finds each one's Staff record by the token's `sub`:
 *
 * - Grace, an administrator, reads every product with its cost and margin.
 * - Linus, staff assigned to Acme, reads only Acme's products, without them.
 * - Linus lists a product and is refused: `ListProduct` is `by Administrator`.
 * - Nobody signed in is answered 401.
 *
 * Skipped without Docker. Authentik's first start takes a minute or more.
 */
@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false",
    "spring.datasource.url=jdbc:sqlite:file:vendors_sign_in_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
])
@AutoConfigureMockMvc
class VendorsSignInTest {

    companion object {
        private const val AUTHENTIK_IMAGE = "ghcr.io/goauthentik/server:2026.8.6"
        private val authentikNetwork: Network by lazy { Network.newNetwork() }

        private val authentikEnvironment = mapOf(
            "AUTHENTIK_SECRET_KEY" to "vendors-sign-in-test-secret-key",
            "AUTHENTIK_POSTGRESQL__HOST" to "authentik-postgres",
            "AUTHENTIK_POSTGRESQL__NAME" to "authentik",
            "AUTHENTIK_POSTGRESQL__USER" to "authentik",
            "AUTHENTIK_POSTGRESQL__PASSWORD" to "authentik",
            "AUTHENTIK_DISABLE_UPDATE_CHECK" to "true",
            "AUTHENTIK_ERROR_REPORTING__ENABLED" to "false",
        )

        private val authentikDatabase: GenericContainer<*> by lazy {
            GenericContainer("postgres:17-alpine")
                .withNetwork(authentikNetwork)
                .withNetworkAliases("authentik-postgres")
                .withEnv(mapOf("POSTGRES_DB" to "authentik", "POSTGRES_USER" to "authentik", "POSTGRES_PASSWORD" to "authentik"))
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))
        }

        private fun authentik(command: String): GenericContainer<*> =
            GenericContainer(AUTHENTIK_IMAGE)
                .withNetwork(authentikNetwork)
                .withEnv(authentikEnvironment)
                .withCommand(command)
                .withCopyFileToContainer(MountableFile.forHostPath("authentik/vendors.yaml"), "/blueprints/vendors/vendors.yaml")

        private val authentikWorker: GenericContainer<*> by lazy { authentik("worker") }

        // ready once the worker has applied the blueprint, which is when the vendors client exists
        private val authentikServer: GenericContainer<*> by lazy {
            authentik("server")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/application/o/vendors/.well-known/openid-configuration").forPort(9000).withStartupTimeout(Duration.ofMinutes(6)))
        }

        private fun identityProviderAddress() = "http://localhost:${authentikServer.getMappedPort(9000)}"

        @BeforeAll
        @JvmStatic
        fun startAuthentik() {
            Assumptions.assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false), "needs Docker")
            authentikDatabase.start()
            authentikWorker.start()
            authentikServer.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun pointAtAuthentik(properties: DynamicPropertyRegistry) {
            properties.add("velle.identity-provider") { identityProviderAddress() }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Map<String, Any?> = json.readValue(body, Map::class.java) as Map<String, Any?>

    /** A token for the named person, from Authentik, with no browser: the client_credentials grant with their app password. HTTP/1.1, because Authentik refuses the client's offer to upgrade to HTTP/2. */
    private fun tokenFor(username: String): String {
        val form = mapOf(
            "grant_type" to "client_credentials",
            "client_id" to "vendors",
            "client_secret" to "vendors-demo-secret",
            "username" to username,
            "password" to "$username-app-password",
            "scope" to "openid email profile",
        ).entries.joinToString("&") { field -> "${field.key}=${URLEncoder.encode(field.value, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("${identityProviderAddress()}/application/o/token/"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return parse(response.body())["access_token"] as String
    }

    private fun productsSeenWith(token: String): List<Map<*, *>> {
        val body = mvc.get("/api/shapes/Product?orderBy=name") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return (parse(body)["items"] as List<*>).map { item -> item as Map<*, *> }
    }

    @Test
    fun `sign-in says who is calling and the spec says what they may do and see`() {
        val graceToken = tokenFor("grace")
        val linusToken = tokenFor("linus")

        val seenByGrace = productsSeenWith(graceToken)
        assertEquals(3, seenByGrace.size, seenByGrace.toString())
        assertTrue(seenByGrace.all { product -> product["cost"] != null && product["margin"] != null }, seenByGrace.toString())

        val seenByLinus = productsSeenWith(linusToken)
        assertEquals(listOf("M6 hex bolt, box of 100", "M6 washer, box of 500"), seenByLinus.map { product -> product["name"] })
        assertTrue(seenByLinus.all { product -> product["cost"] == null && product["margin"] == null }, seenByLinus.toString())

        val acme = (seenByLinus.first()["vendor"] as Map<*, *>)["key"]
        mvc.post("/api/acts/ListProduct") {
            header("Authorization", "Bearer $linusToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"vendor": $acme, "name": "M8 hex bolt, box of 100", "price": 15, "cost": 9}"""
        }.andExpect { status { isUnprocessableContent() } }

        mvc.get("/api/shapes/Product?orderBy=name").andExpect { status { isUnauthorized() } }
    }
}
