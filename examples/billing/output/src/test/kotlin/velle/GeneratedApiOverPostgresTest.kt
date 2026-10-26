package velle

import velle.generated.BillingSystem
import velle.postgres.PostgresStore
import velle.postgres.PostgresTesting
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** The typed-surface scenarios over PostgreSQL, through the generic protocol; skipped without Docker. */
class GeneratedApiOverPostgresTest : GeneratedApiTest() {
    private lateinit var conn: Connection

    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()

    @AfterTest
    fun close() { if (::conn.isInitialized) conn.close() }

    override fun newSystem(): BillingSystem {
        conn = PostgresTesting.connection()
        return BillingSystem().also { PostgresStore.attach(it.system, conn) }
    }
}
