package velle.postgres

import org.junit.jupiter.api.Assumptions
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

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

    private fun freshSchema(): String {
        val schema = "velle_${schemas.incrementAndGet()}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
            c.createStatement().use { st -> st.execute("CREATE SCHEMA $schema") }
        }
        return schema
    }

    /**
     * A `DataSource` whose every connection lands in a schema no other test uses:
     * what a store takes a connection per unit of work from. Unpooled, so each
     * unit really opens and closes a connection.
     */
    fun dataSource(): DataSource = PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        user = container.username
        password = container.password
        currentSchema = freshSchema()
    }

    /** A connection whose search path is a schema no other connection uses, for a test that reads the tables itself. */
    fun connection(): Connection {
        val c = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        c.createStatement().use { st -> st.execute("SET search_path TO ${freshSchema()}") }
        return c
    }
}
