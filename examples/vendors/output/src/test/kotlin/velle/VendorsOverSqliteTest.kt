package velle.generated.vendors

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import velle.generated.VendorsStoreOverGeneric
import velle.generated.VendorsStoreResolver
import velle.generated.VendorsSystem
import velle.sqlite.SqliteStore

/** The vendors flows over real storage: the stored actor, the seeded fixtures, and the viewer reads hydrated through the store. */
class VendorsFlowOverSqliteTest : VendorsFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): VendorsSystem = VendorsSystem().also { SqliteStore.attach(it.system, conn) }
}

class VendorsFlowOverTypedStoreTest : VendorsFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): VendorsSystem = VendorsSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = VendorsStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(VendorsStoreResolver(store, sys.system.model), backend)
    }
}
