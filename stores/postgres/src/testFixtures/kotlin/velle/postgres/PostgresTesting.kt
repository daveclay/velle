package velle.postgres

import org.junit.jupiter.api.Assumptions
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * A PostgreSQL for tests: one container per test JVM (Testcontainers, so Docker
 * must be running; a test that needs it is skipped otherwise), and a fresh,
 * empty schema of its own for every connection handed out.
 */
object PostgresTesting {

    val available: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    /** Skips the calling test when no Docker is available to run the database in. */
    fun assumeAvailable() = Assumptions.assumeTrue(available, "PostgreSQL tests need Docker running")

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    }

    private val schemas = AtomicInteger()

    /** A connection whose search path is a schema no other connection uses. */
    fun connection(): Connection {
        val c = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        val schema = "velle_${schemas.incrementAndGet()}"
        c.createStatement().use { st ->
            st.execute("CREATE SCHEMA $schema")
            st.execute("SET search_path TO $schema")
        }
        return c
    }
}
