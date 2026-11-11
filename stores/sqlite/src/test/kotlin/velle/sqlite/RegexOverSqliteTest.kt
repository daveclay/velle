package velle.sqlite

import velle.CommitResult
import velle.Model
import velle.Parser
import velle.Validator
import velle.VelleSystem
import velle.ext.builtin.RegexValue
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A `Regex` (Reference §5) over SQLite through the binding shipped with the SQL layer: held as its text, equal when read back. */
class RegexOverSqliteTest {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun accepted(result: CommitResult): Long = (result as? CommitResult.Accepted)?.id ?: error((result as CommitResult.Refused).reason)

    @Test
    fun `a Regex is held as its text and comes back equal, and a quoted text beside it filters`() {
        val decls = Parser.parse("act Field {\n valuePattern: Regex\n}\nshape Digits = Field where valuePattern == \"\\\\d+\"")
        val found = Validator.validate(decls)
        check(found.isEmpty()) { found.toString() }
        val sys = VelleSystem(Model(decls))
        SqliteStore.attach(sys, conn)
        val digits = sys.scope { sys.refFor(accepted(sys.commit("Field", mapOf("valuePattern" to RegexValue("\\d+"))))) }
        sys.scope { accepted(sys.commit("Field", mapOf("valuePattern" to RegexValue("[a-z]+")))) }
        sys.scope {
            assertEquals(RegexValue("\\d+"), sys.get(sys.idOf(digits), "valuePattern"))
            assertEquals(listOf(sys.idOf(digits)), sys.instancesOf("Digits"))
        }
        conn.prepareStatement("SELECT valuePattern FROM Field").use { statement ->
            statement.executeQuery().use { rows ->
                val stored = mutableSetOf<String>()
                while (rows.next()) stored.add(rows.getString(1))
                assertEquals(setOf("\\d+", "[a-z]+"), stored)
            }
        }
    }
}
