package velle

import velle.generated.BillingSystem
import velle.postgres.PostgresStore
import velle.postgres.PostgresTesting
import kotlin.test.BeforeTest

/** The typed-surface scenarios over PostgreSQL, through the generic protocol; skipped without Docker. */
class GeneratedApiOverPostgresTest : GeneratedApiTest() {

    @BeforeTest
    fun docker() = PostgresTesting.assumeAvailable()


    override fun newSystem(): BillingSystem {
        return BillingSystem().also { PostgresStore.attach(it.system, PostgresTesting.dataSource()) }
    }
}
