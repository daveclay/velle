package velle.sqlite

import velle.Cursor
import velle.Model
import velle.Page
import velle.Parser
import velle.Query
import velle.Ref
import velle.Validator
import velle.VelleSystem
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Paging a refinement over several shapes on a store that keys each shape on its own, so a
 * `Patient` and a `Prescriber` hold the same key. Every record is answered once, in the same
 * order whatever the page size, with no order named.
 */
class UnorderedQueryOverSqliteTest {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = connection.close()

    private val patientsAndPrescribers = """
        act Patient { name: text }
        act Prescriber { name: text }
        shape Recipient = Patient or Prescriber
    """

    private fun systemWithTwoOfEach(): VelleSystem {
        val declarations = Parser.parse(patientsAndPrescribers)
        val found = Validator.validate(declarations)
        check(found.isEmpty()) { found.toString() }
        val system = VelleSystem(Model(declarations))
        SqliteStore.attach(system, connection)
        for (shape in listOf("Patient", "Prescriber", "Patient", "Prescriber"))
            system.scope { system.commit(shape, mapOf("name" to "same name")) }
        return system
    }

    /** Every page of [query], [pageSize] records at a time, as references in the order answered. */
    private fun recordsPageByPage(system: VelleSystem, query: Query, pageSize: Int): List<Ref.Persisted> {
        val records = mutableListOf<Ref.Persisted>()
        var after: Cursor? = null
        do {
            val page = system.query(query.copy(page = Page(pageSize, after)))
            records += page.ids.map { handle -> system.refFor(handle) }
            after = page.next
        } while (after != null)
        return records
    }

    private fun assertPagedLikeTheWhole(query: Query) {
        val system = systemWithTwoOfEach()
        system.scope {
            val whole = system.query(query).ids.map { handle -> system.refFor(handle) }
            assertEquals(4, whole.toSet().size, whole.toString())
            assertEquals(2, whole.map { record -> record.key }.toSet().size, "the two shapes share their keys: $whole")
            for (pageSize in 1..3) assertEquals(whole, recordsPageByPage(system, query, pageSize), "page size $pageSize")
        }
    }

    @Test
    fun `with no order named, every record of the refinement is answered once`() =
        assertPagedLikeTheWhole(Query("Recipient"))
}
