package velle.generated.pharmacy

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import kotlin.test.AfterTest
import velle.generated.PharmacyStoreOverGeneric
import velle.generated.PharmacyStoreResolver
import velle.generated.PharmacySystem
import velle.sqlite.SqliteStore

/**
 * The pharmacy flows over real storage: the closure commit, the edge-set
 * writes, the sibling joins, the captures, the deletion of a price change,
 * and the ticks all hydrated through the store. Once through the generic
 * protocol, once through the generated typed store surface.
 */
class PharmacyFlowOverSqliteTest : PharmacyFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PharmacySystem = PharmacySystem().also { SqliteStore.attach(it.system, conn) }
}

class PharmacyFlowOverTypedStoreTest : PharmacyFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PharmacySystem = PharmacySystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = PharmacyStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(PharmacyStoreResolver(store, sys.system.model), backend)
    }
}
