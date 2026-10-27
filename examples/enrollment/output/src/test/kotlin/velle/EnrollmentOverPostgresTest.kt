package velle.generated.enrollment

import velle.generated.EnrollmentSystem
import velle.postgres.PostgresStore
import velle.postgres.PostgresTesting
import kotlin.test.BeforeTest

/** The collection flows over PostgreSQL, through the generic protocol; skipped without Docker. */
class EnrollmentFlowOverPostgresTest : EnrollmentFlowTest() {

    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()


    override fun newSystem(): EnrollmentSystem {
        return EnrollmentSystem().also { PostgresStore.attach(it.system, PostgresTesting.dataSource()) }
    }
}
