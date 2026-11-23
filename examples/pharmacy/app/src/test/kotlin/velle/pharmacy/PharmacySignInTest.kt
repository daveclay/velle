package velle.pharmacy

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
 * containers from the same image and blueprint (authentik/pharmacy.yaml) that
 * `bootRun` uses, over five weeks of seeded business. Margaret and Tom each get
 * a token from Authentik with no browser, by the app password the blueprint
 * gives them, and the application finds each one's Staff record by the token's
 * `sub`:
 *
 * - Margaret, an administrator, reads the claims.
 * - Tom, a technician, reads the patients and none of the claims, which the
 *   spec shows to pharmacists and administrators only.
 * - Tom hires someone and is refused: `HireStaff` is `by Administrator`.
 * - Nobody signed in is answered 401.
 *
 * Skipped without Docker. Authentik's first start takes a minute or more.
 */
@SpringBootTest(properties = [
    "spring.docker.compose.enabled=false",
    "spring.datasource.url=jdbc:sqlite:file:pharmacy_sign_in_test?mode=memory&cache=shared",
    "spring.datasource.driver-class-name=org.sqlite.JDBC",
    "pharmacy.seed.days=36",
])
@AutoConfigureMockMvc
class PharmacySignInTest {

    companion object {
        private const val AUTHENTIK_IMAGE = "ghcr.io/goauthentik/server:2026.8.6"
        private val authentikNetwork: Network by lazy { Network.newNetwork() }

        private val authentikEnvironment = mapOf(
            "AUTHENTIK_SECRET_KEY" to "pharmacy-sign-in-test-secret-key",
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
                .withCopyFileToContainer(MountableFile.forHostPath("authentik/pharmacy.yaml"), "/blueprints/pharmacy/pharmacy.yaml")

        private val authentikWorker: GenericContainer<*> by lazy { authentik("worker") }

        // ready once the worker has applied the blueprint, which is when the pharmacy client exists
        private val authentikServer: GenericContainer<*> by lazy {
            authentik("server")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/application/o/pharmacy/.well-known/openid-configuration").forPort(9000).withStartupTimeout(Duration.ofMinutes(6)))
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
            "client_id" to "pharmacy",
            "client_secret" to "pharmacy-demo-secret",
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

    private fun countSeenWith(token: String, shape: String): Int {
        val body = mvc.get("/api/shapes/$shape/count") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return parse(body)["count"] as Int
    }

    @Test
    fun `sign-in says who is calling and the spec says what they may do and see`() {
        val margaretToken = tokenFor("margaret")
        val tomToken = tokenFor("tom")

        assertTrue(countSeenWith(margaretToken, "Claim") > 0, "Margaret reads the claims")

        assertEquals(Roster.patients.size, countSeenWith(tomToken, "Patient"))
        assertEquals(0, countSeenWith(tomToken, "Claim"))

        mvc.post("/api/acts/HireStaff") {
            header("Authorization", "Bearer $tomToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "Rita Moreno", "email": "rita@pharmacy.example", "identityId": "1012", "role": "technician"}"""
        }.andExpect { status { isUnprocessableContent() } }

        mvc.get("/api/shapes/Patient/count").andExpect { status { isUnauthorized() } }
    }
}
