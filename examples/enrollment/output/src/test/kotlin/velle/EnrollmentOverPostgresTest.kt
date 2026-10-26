package velle.generated.enrollment

import velle.generated.EnrollmentSystem
import velle.postgres.PostgresStore
import velle.postgres.PostgresTesting
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** The collection flows over PostgreSQL, through the generic protocol; skipped without Docker. */
class EnrollmentFlowOverPostgresTest : EnrollmentFlowTest() {
    private lateinit var conn: Connection

    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()

    @AfterTest
    fun close() { if (::conn.isInitialized) conn.close() }

    override fun newSystem(): EnrollmentSystem {
        conn = PostgresTesting.connection()
        return EnrollmentSystem().also { PostgresStore.attach(it.system, conn) }
    }
}
