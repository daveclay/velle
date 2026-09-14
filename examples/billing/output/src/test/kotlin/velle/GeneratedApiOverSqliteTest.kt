package velle

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import velle.generated.BillingStoreOverGeneric
import velle.generated.BillingStoreResolver
import velle.generated.BillingSystem
import velle.sqlite.SqliteStore

/**
 * The typed-surface scenarios over real storage — once through the generic
 * protocol, once through the generated typed store surface. The dedicated
 * store tests beside this file (hydration candidates, capture persistence,
 * the tuned typed store) cover what only storage can show.
 */
class GeneratedApiOverSqliteTest : GeneratedApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BillingSystem = BillingSystem().also { SqliteStore.attach(it.system, conn) }
}

class GeneratedApiOverTypedStoreTest : GeneratedApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BillingSystem = BillingSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = BillingStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(BillingStoreResolver(store, sys.system.model), backend)
    }
}
