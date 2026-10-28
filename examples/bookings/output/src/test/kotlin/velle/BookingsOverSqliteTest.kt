package velle.generated.bookings

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import velle.generated.*
import velle.sqlite.SqliteStore

/**
 * The bookings flows over real storage: each inherited scenario re-run against
 * SQLite, once through the generic protocol, once through the generated typed
 * store surface.
 */
class BookingsFlowOverSqliteTest : BookingsFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BookingsSystem = BookingsSystem().also { SqliteStore.attach(it.system, conn) }
}

class BookingsFlowOverTypedStoreTest : BookingsFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BookingsSystem = BookingsSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = BookingsStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(BookingsStoreResolver(store, sys.system.model), backend)
    }
}
