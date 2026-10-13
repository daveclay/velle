package velle.generated.peptides

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import kotlin.test.AfterTest
import velle.generated.PeptidesStoreOverGeneric
import velle.generated.PeptidesStoreResolver
import velle.generated.PeptidesSystem
import velle.sqlite.SqliteStore

/**
 * The pipeline flows over real storage: the series selectors, the recurrences,
 * the deletion of a series point, and the after-commit closer all hydrated
 * through the store. Once through the generic protocol, once through the
 * generated typed store surface.
 */
class PeptidesPipelineOverSqliteTest : PeptidesPipelineTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PeptidesSystem = PeptidesSystem().also { SqliteStore.attach(it.system, conn) }
}

class PeptidesPipelineOverTypedStoreTest : PeptidesPipelineTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PeptidesSystem = PeptidesSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = PeptidesStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(PeptidesStoreResolver(store, sys.system.model), backend)
    }
}
