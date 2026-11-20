package velle.sqlite

import velle.CommitResult
import velle.Model
import velle.Parser
import velle.Ref
import velle.Validator
import velle.VelleRuntimeError
import velle.VelleSystem
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a read answers today after a row is changed or removed with SQL of the
 * engineer's own, outside any commit (OQ154). Each test makes one such change
 * to the tables of a `Drug` and an `Interaction` that points at it, then reads
 * through the runtime. These pin what runs, not what should.
 */
class OutsideChangeProbeTest {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = connection.close()

    private val drugsAndInteractions = """
        act Drug { name: text, strength: integer }
        act Interaction {
            drug: one Drug
            note: text
            drugName: text = drug.name
        }
        never (Drug where strength < 0)
    """

    private val system: VelleSystem = run {
        val declarations = Parser.parse(drugsAndInteractions)
        val found = Validator.validate(declarations)
        check(found.isEmpty()) { found.toString() }
        VelleSystem(Model(declarations)).also { built -> SqliteStore.attach(built, connection) }
    }

    private fun committed(shape: String, fields: Map<String, Any?>): Ref.Persisted = system.scope {
        system.refFor((system.commit(shape, fields) as CommitResult.Accepted).id)
    }

    private val drug: Ref.Persisted = committed("Drug", mapOf("name" to "Warfarin", "strength" to 5))
    private val interaction: Ref.Persisted =
        system.scope { committed("Interaction", mapOf("drug" to system.idOf(drug), "note" to "bleeding")) }

    private fun outsideVelle(statement: String) {
        connection.createStatement().use { direct -> direct.executeUpdate(statement) }
    }

    private fun read(record: Ref.Persisted, member: String): Any? = system.scope { system.get(system.idOf(record), member) }

    @Test
    fun `a value that breaks a never is read as it stands, with no error`() {
        outsideVelle("""UPDATE "Drug" SET strength = -5""")
        assertEquals(BigDecimal(-5), read(drug, "strength"))
    }

    @Test
    fun `a text in an integer column is read as zero, with no error`() {
        outsideVelle("""UPDATE "Drug" SET strength = 'strong'""")
        assertEquals(BigDecimal.ZERO, read(drug, "strength"))
    }

    @Test
    fun `a required field emptied is read as absent, with no error`() {
        outsideVelle("""UPDATE "Drug" SET name = NULL""")
        assertNull(read(drug, "name"))
        assertNull(read(interaction, "drugName"), "and so is a member derived from it")
    }

    @Test
    fun `a required reference emptied is read as absent, with no error`() {
        outsideVelle("""UPDATE "Interaction" SET drug = NULL""")
        assertNull(read(interaction, "drug"))
    }

    @Test
    fun `a reference to a removed row is read as absent, with no error`() {
        outsideVelle("""DELETE FROM "Drug"""")
        assertNull(read(interaction, "drug"))
    }

    @Test
    fun `a member derived through a removed row fails naming neither the record nor the field`() {
        outsideVelle("""DELETE FROM "Drug"""")
        val failure = assertFailsWith<VelleRuntimeError> { read(interaction, "drugName") }
        assertEquals("'.' through an absent value (validator narrowing gap)", failure.message)
    }

    @Test
    fun `a removed row read by its own reference fails naming the runtime's private number for it`() {
        outsideVelle("""DELETE FROM "Drug"""")
        val failure = assertFailsWith<IllegalArgumentException> { read(drug, "name") }
        assertEquals(true, failure.message!!.matches(Regex("no instance \\d+")), failure.message)
    }
}
