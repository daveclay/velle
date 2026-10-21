package velle.generated.batches

import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.*
import velle.sqlite.SqliteStore

/**
 * The batches flows over real storage. With a store connected the runtime
 * keeps no state of its own: every transaction opens a fresh snapshot and
 * faults rows in through the resolver (evaluation.md U1), so each inherited
 * scenario is the in-memory scenario re-run against SQLite — once through
 * the generic protocol, once through the generated typed store surface.
 */
class BatchesFlowOverSqliteTest : BatchesFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BatchesSystem = BatchesSystem().also { SqliteStore.attach(it.system, conn) }
}

class BatchesFlowOverTypedStoreTest : BatchesFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): BatchesSystem = BatchesSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = BatchesStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(BatchesStoreResolver(store, sys.system.model), backend)
    }
}

/**
 * What only storage can show: the union-typed `origin` persisted with its
 * concrete shape, and a second process reading the batch back, narrowing it,
 * and deriving what is left from stored component rows.
 */
class BatchesPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): BatchesSystem = BatchesSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: Commit<*>) = assertIs<Commit.Accepted<*>>(r, "refused: $r")

    private fun rows(sql: String): List<List<Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { i ->
                        rs.getObject(i).let { if (it is Number) it.toLong() else it }
                    })
                }
            }
        }

    @Test
    fun `the origin is stored with its shape and read back narrowed in a second process`() {
        val first = process()
        accept(first.commit(NewPeptide("PEP-7")))
        val pep = first.peptides().last()
        accept(first.commit(NewBacteriaStrain("B. subtilis 168")))
        accept(first.commit(NewFermenterRun("F-1", LocalDate.of(2026, 3, 2))))
        accept(first.commit(NewVendor("Bachem")))
        // each batch arrives with its new origin nested inside it (Reference §6)
        accept(first.commit(NewPeptideBatch(pep, BigDecimal("100"),
            OriginReference.New(NewFermentation(first.bacteriaStrains().last(), first.fermenterRuns().last())))))
        val fermented = first.peptideBatchs().last()
        accept(first.commit(NewPeptideBatch(pep, BigDecimal("50"),
            OriginReference.New(NewExternalOrder(first.vendors().last(), "PO-4471/3")))))
        val ordered = first.peptideBatchs().last()
        accept(first.commit(NewPeptideBatch(pep, BigDecimal("30"),
            OriginReference.New(NewComposition(LocalDate.of(2026, 4, 1), listOf(NewComponent(fermented, BigDecimal("20")), NewComponent(ordered, BigDecimal("10"))))))))

        // Fermentation#1, ExternalOrder#1, Composition#1 share a key; the discriminator tells them apart
        assertEquals(
            listOf(listOf(1L, "Fermentation"), listOf(1L, "ExternalOrder"), listOf(1L, "Composition")),
            rows("""SELECT origin, origin__shape FROM PeptideBatch ORDER BY id"""),
        )

        val second = process()
        val b1 = second.read.peptideBatch(1L)
        val b2 = second.read.peptideBatch(2L)
        val b3 = second.read.peptideBatch(3L)
        assertEquals(listOf("fermented", "ordered", "composite"), listOf(b1, b2, b3).map { it.kind })
        assertIs<Fermentation>(second.read(b1.origin))
        assertIs<ExternalOrder>(second.read(b2.origin))
        val c = assertIs<Composition>(second.read(b3.origin))
        assertEquals(2, c.components.size)
        assertEquals(BigDecimal("80"), b1.remainingMg)
        assertEquals(BigDecimal("40"), b2.remainingMg)
        assertEquals(BigDecimal("30"), b3.remainingMg)
        assertEquals(1, second.read(second.read.fermentation(1L)).peptideBatches.size)

        // the one-origin-one-batch refusal is decided against stored rows
        assertIs<Commit.Refused<*>>(second.commit(NewPeptideBatch(second.read.peptide(1L), BigDecimal("1"), OriginReference.Existing(second.read.fermentation(1L).ref))))
        assertEquals(listOf(listOf(3L)), rows("""SELECT COUNT(*) FROM PeptideBatch"""))
    }
}
