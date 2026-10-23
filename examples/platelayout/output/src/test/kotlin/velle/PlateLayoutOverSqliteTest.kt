package velle.generated.platelayout

import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import velle.Commit
import velle.generated.*
import velle.sqlite.SqliteStore

/**
 * The plate-layout flows over real storage: each inherited scenario re-run
 * against SQLite, once through the generic protocol, once through the
 * generated typed store surface.
 */
class PlateLayoutFlowOverSqliteTest : PlateLayoutFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PlateLayoutSystem = PlateLayoutSystem().also { SqliteStore.attach(it.system, conn) }
}

class PlateLayoutFlowOverTypedStoreTest : PlateLayoutFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PlateLayoutSystem = PlateLayoutSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = PlateLayoutStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(PlateLayoutStoreResolver(store, sys.system.model), backend)
    }
}

/**
 * What only storage can show: the whole layout written in the design's own
 * transaction, and a second process reading the counts back off the rows.
 */
class PlateLayoutPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): PlateLayoutSystem = PlateLayoutSystem().also { SqliteStore.attach(it.system, conn) }

    private fun count(table: String): Long =
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) } }

    @Test
    fun `the layout is stored with the design and read back in a second process`() {
        val first = process()
        assertIs<Commit.Accepted<*>>(first.commit(NewContainerType("96-well", 96)))
        assertIs<Commit.Accepted<*>>(first.commit(NewExperimentDesign(
            first.containerTypes().last(), 3, 2, 24, (1..4).map { NewCondition(it, BigDecimal(it)) })))
        assertEquals(6L, count("Container"))
        assertEquals(576L, count("Well"))

        val second = process()
        val design = second.read.experimentDesign(1L)
        assertEquals(6, design.laidOutContainers)
        assertEquals(576, design.laidOutWells)
        val container = second.read.container(1L)
        assertEquals(96, container.wellCount)
        assertEquals(96, container.wells.size)
        assertEquals(design, second.read(container.design))
    }
}
