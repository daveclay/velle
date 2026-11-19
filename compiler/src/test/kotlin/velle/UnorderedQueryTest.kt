package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A shape with no member a query may order by, read through the runtime's query, which the
 * generated read API's `list()` and `count()` call (`TypedQuery` in Read.kt): with no order
 * named, the records are answered in key order. The spec is the
 * `Interaction` act of `examples/pharmacy/pharmacy.velle` with the drug it points at. OQ151.
 */
class UnorderedQueryTest {

    private val drugsAndInteractions = """
        Severity is one of "major", "moderate"

        shape Drug {
            name: text
            interactions: many Interaction = (Interaction where drugA == this or drugB == this)
        }

        act Interaction {
            drugA: one Drug
            drugB: one Drug
            severity: Severity
        }
    """

    private fun systemWithThreeInteractions(): VelleSystem {
        val model = Model(Parser.parse(drugsAndInteractions))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val system = VelleSystem(model)
        system.scope {
            val warfarin = system.idOf(system.seed("Drug", mapOf("name" to "warfarin")))
            val aspirin = system.idOf(system.seed("Drug", mapOf("name" to "aspirin")))
            for (severity in listOf("major", "moderate", "major"))
                system.seed("Interaction", mapOf("drugA" to warfarin, "drugB" to aspirin, "severity" to severity))
        }
        return system
    }

    /** Every page of [query], [pageSize] records at a time, as the keys in the order answered. */
    private fun keysPageByPage(system: VelleSystem, query: Query, pageSize: Int): List<Ref.Persisted> {
        val keys = mutableListOf<Ref.Persisted>()
        var after: Cursor? = null
        do {
            val page = system.query(query.copy(page = Page(pageSize, after)))
            keys += page.ids.map { handle -> system.refFor(handle) }
            after = page.next
        } while (after != null)
        return keys
    }

    @Test
    fun `a query with no order is answered in key order, the same every time`() {
        val system = systemWithThreeInteractions()
        system.scope {
            val answered = system.query(Query("Interaction")).ids.map { handle -> system.refFor(handle) }
            assertEquals(3, answered.size)
            assertEquals(answered.sortedBy { ref -> ref.key.value as Long }, answered)
            assertEquals(answered, system.query(Query("Interaction")).ids.map { handle -> system.refFor(handle) })
        }
    }

    @Test
    fun `a query with no order is paged, each record once`() {
        val system = systemWithThreeInteractions()
        system.scope {
            val whole = system.query(Query("Interaction")).ids.map { handle -> system.refFor(handle) }
            assertEquals(whole, keysPageByPage(system, Query("Interaction"), pageSize = 1))
            assertEquals(whole, keysPageByPage(system, Query("Interaction"), pageSize = 2))
        }
    }

    private val patientsAndPrescribers = """
        shape Patient { name: text }
        shape Prescriber { name: text }
        shape Recipient = Patient or Prescriber
    """

    @Test
    fun `a refinement over several shapes is paged with no order, each record once`() {
        val model = Model(Parser.parse(patientsAndPrescribers))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val system = VelleSystem(model)
        system.scope {
            for (name in listOf("Ada", "Ben")) system.seed("Patient", mapOf("name" to name))
            for (name in listOf("Cy", "Di")) system.seed("Prescriber", mapOf("name" to name))
            val whole = system.query(Query("Recipient")).ids.map { handle -> system.refFor(handle) }
            assertEquals(listOf("Patient", "Patient", "Prescriber", "Prescriber"), whole.map { record -> record.shape })
            assertEquals(whole, keysPageByPage(system, Query("Recipient"), pageSize = 1))
            assertEquals(whole, keysPageByPage(system, Query("Recipient"), pageSize = 3))
        }
    }

    @Test
    fun `no member of the shape is accepted as its order`() {
        val system = systemWithThreeInteractions()
        for (member in listOf("drugA", "drugB", "severity")) {
            val refusal = assertFailsWith<QueryRefused> { system.scope { system.query(Query("Interaction", orderBy = listOf(OrderDatum(member)))) } }
            assertTrue("is not orderable" in refusal.diagnostics.single().message, refusal.diagnostics.toString())
        }
    }

    @Test
    fun `a count of the shape is answered`() {
        val system = systemWithThreeInteractions()
        assertEquals(3, system.scope { system.count(Query("Interaction")) })
    }

    @Test
    fun `the records are reached through a record that points at them`() {
        val system = systemWithThreeInteractions()
        val drugsWithAnInteraction = system.scope {
            system.count(Query("Drug", where = Parser.parsePredicate("count(interactions) > 0")))
        }
        assertEquals(2, drugsWithAnInteraction)
    }
}
