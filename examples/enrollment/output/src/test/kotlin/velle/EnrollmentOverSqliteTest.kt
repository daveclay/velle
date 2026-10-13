package velle.generated.enrollment

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.CommitResult
import velle.generated.EnrollmentStoreOverGeneric
import velle.generated.EnrollmentStoreResolver
import velle.generated.EnrollmentSystem
import velle.sqlite.SqliteStore

/**
 * The enrollment flows over real storage — the collection constructs of
 * README §6 persisted: a declared `many` is a junction table, `many text`
 * a value table, and the inferred inverses scan them. Once through the
 * generic protocol, once through the generated typed store surface.
 */
class EnrollmentFlowOverSqliteTest : EnrollmentFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): EnrollmentSystem = EnrollmentSystem().also { SqliteStore.attach(it.system, conn) }
}

class EnrollmentFlowOverTypedStoreTest : EnrollmentFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): EnrollmentSystem = EnrollmentSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = EnrollmentStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(EnrollmentStoreResolver(store, sys.system.model), backend)
    }
}

/** Collections across processes: what the junction tables hold, and what a fresh process reads from them. */
class EnrollmentPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): EnrollmentSystem = EnrollmentSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")

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
    fun `edge sets and value sets live in junction tables and read back in membership order`() {
        val first = process()
        accept(first.commitStudent("Ada", tags = emptyList(), courses = emptyList()))
        val ada = first.students().last()
        val courses = listOf("Logic", "Sets", "Proofs").map { title ->
            accept(first.commitCourse(title, 30))
            first.courses().last()
        }
        accept(first.commitSetEnrollment(ada, listOf(courses[0], courses[1])))
        accept(first.commitEnroll(ada, courses[2]))
        accept(first.commitTag(ada, "honors"))
        accept(first.commitTag(ada, "stem"))

        assertEquals(listOf(1L, 2L, 3L), rows("""SELECT target FROM Student__courses WHERE owner = 1 ORDER BY rowid""").map { it[0] })
        assertEquals(listOf("honors", "stem"), rows("""SELECT value FROM Student__tags WHERE owner = 1 ORDER BY rowid""").map { it[0] })

        val second = process()
        val ada2 = second.read.student(1L)
        val (logic, sets, proofs) = listOf(1L, 2L, 3L).map { second.read.course(it) }
        assertEquals(listOf(logic, sets, proofs), second.read(ada2).courses, "membership order is the junction's insertion order")
        assertEquals(listOf("honors", "stem"), second.read(ada2).tags)
        assertTrue(second.overloadeds().any { it.key == ada2.key }, "count(courses) > 2 over the junction rows")
        assertTrue(second.untaggeds().none { it.key == ada2.key })
        assertEquals(listOf(ada2), second.read(sets).students, "the m2m inverse scans the declared side's junction")

        // removal rewrites the edge set whole
        accept(second.commitDrop(ada2, logic))
        assertEquals(listOf(2L, 3L), rows("""SELECT target FROM Student__courses WHERE owner = 1 ORDER BY rowid""").map { it[0] })
        assertEquals(listOf(sets, proofs), second.read(ada2).courses)
        assertTrue(second.overloadeds().none { it.key == ada2.key })
        assertEquals(emptyList(), second.read(logic).students)
    }

    @Test
    fun `fan-out assignment writes one pointer column per selected child`() {
        val first = process()
        accept(first.commitAdvisor("Turing"))
        val advisor = first.advisors().last()
        val students = listOf("Ada", "Grace", "Edsger").map { name ->
            accept(first.commitStudent(name, tags = emptyList(), courses = emptyList()))
            first.students().last()
        }
        accept(first.commitAssignAdvisor(advisor, listOf(students[0], students[1])))
        assertEquals(
            listOf(listOf(1L, 1L), listOf(2L, 1L), listOf(3L, null)),
            rows("""SELECT id, advisor FROM Student ORDER BY id"""),
        )

        val second = process()
        val advisor2 = second.read.advisor(1L)
        assertEquals(setOf("Ada", "Grace"), second.read(advisor2).students.map { second.read(it).name }.toSet(), "the one-to-many inverse is a join read")
        assertEquals(setOf("Ada", "Grace"), second.read(advisor2).advisees.map { second.read(it).name }.toSet())
    }
}

