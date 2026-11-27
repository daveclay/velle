package velle.generated.enrollment

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.EnrollmentStoreOverGeneric
import velle.generated.EnrollmentStoreResolver
import velle.generated.EnrollmentSystem
import velle.sqlite.SqliteStore

/**
 * The enrollment flows over real storage — the collection constructs of
 * Reference §6 persisted: a declared `many` is a junction table, `many text`
 * a value table (kept in order, with duplicates, when the field tolerates
 * duplication), and the inferred inverses scan them. Once through the
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
        sys.system.connect(EnrollmentStoreResolver(store, sys.system.model), backend, backend)
    }
}

/** Collections across processes: what the junction tables hold, and what a fresh process reads from them. */
class EnrollmentPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): EnrollmentSystem = EnrollmentSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: Commit<*, *>) = assertIs<Commit.Accepted<*, *>>(r, "refused: $r")

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
        accept(first.commit(NewStudent("Ada", tags = emptyList(), notes = emptyList(), courses = emptyList())))
        val ada = first.students().last()
        val courses = listOf("Logic", "Sets", "Proofs").map { title ->
            accept(first.commit(NewCourse(title, 30, java.time.Duration.ofDays(84))))
            first.courses().last()
        }
        accept(first.commit(NewSetEnrollment(ada, listOf(courses[0], courses[1]))))
        accept(first.commit(NewEnroll(ada, courses[2])))
        accept(first.commit(NewTag(ada, "honors")))
        accept(first.commit(NewTag(ada, "stem")))
        for (note in listOf("late", "early", "late")) accept(first.commit(NewNote(ada, note)))

        assertEquals(listOf(1L, 2L, 3L), rows("""SELECT target FROM student__courses WHERE owner = 1 ORDER BY pos""").map { it[0] })
        assertEquals(listOf("honors", "stem"), rows("""SELECT value FROM student__tags WHERE owner = 1 ORDER BY pos""").map { it[0] })
        assertEquals(listOf("late", "early", "late"), rows("""SELECT value FROM student__notes WHERE owner = 1 ORDER BY pos""").map { it[0] })

        val second = process()
        val ada2 = second.read.student(1L)
        val (logic, sets, proofs) = listOf(1L, 2L, 3L).map { second.read.course(it) }
        assertEquals(listOf(logic, sets, proofs), second.read(ada2).courses, "membership order is the junction's insertion order")
        assertEquals(listOf("honors", "stem"), second.read(ada2).tags)
        assertEquals(listOf("late", "early", "late"), second.read(ada2).notes, "a collection tolerating duplication reads back in the order written, duplicates kept")
        // a `duration` column is whole seconds, a `uuid` column its canonical text (Reference §5)
        assertEquals(listOf(84L * 86_400), rows("""SELECT term FROM course WHERE id = 1""").map { it[0] })
        assertEquals(java.time.Duration.ofDays(84), second.read(logic).term)
        assertEquals(second.read(logic).code, java.util.UUID.fromString(rows("""SELECT code FROM course WHERE id = 1""").single()[0] as String))
        assertTrue(second.shortCourses().none { it.key == logic.key }, "term < 4 weeks over the seconds column")
        // a `time` column is ISO text, a `bytes` column a BLOB handed back as given (Reference §5)
        accept(second.commit(NewCourse("Early", 30, java.time.Duration.ofDays(84), startsAt = java.time.LocalTime.of(8, 0), syllabus = byteArrayOf(1, 2, 3))))
        val early = second.courses().last()
        assertEquals(listOf("08:00"), rows("""SELECT starts_at FROM course WHERE id = ${early.key.value}""").map { it[0] })
        val third = process()
        val early3 = third.read.course(early.key.value as Long)
        assertEquals(java.time.LocalTime.of(8, 0), third.read(early3).startsAt)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), third.read(early3).syllabus!!.toList())
        assertTrue(third.earlyCourses().any { it.key == early3.key }, "startsAt < \"09:00\" over the ISO text column")
        assertTrue(third.read(logic).syllabus == null)
        assertTrue(second.overloadeds().any { it.key == ada2.key }, "count(courses) > 2 over the junction rows")
        assertTrue(second.untaggeds().none { it.key == ada2.key })
        assertEquals(listOf(ada2), second.read(sets).students, "the m2m inverse scans the declared side's junction")

        // removal rewrites the edge set whole
        accept(second.commit(NewDrop(ada2, logic)))
        assertEquals(listOf(2L, 3L), rows("""SELECT target FROM student__courses WHERE owner = 1 ORDER BY pos""").map { it[0] })
        assertEquals(listOf(sets, proofs), second.read(ada2).courses)
        assertTrue(second.overloadeds().none { it.key == ada2.key })
        assertEquals(emptyList(), second.read(logic).students)
    }

    @Test
    fun `fan-out assignment writes one pointer column per selected child`() {
        val first = process()
        accept(first.commit(NewAdvisor("Turing")))
        val advisor = first.advisors().last()
        val students = listOf("Ada", "Grace", "Edsger").map { name ->
            accept(first.commit(NewStudent(name, tags = emptyList(), notes = emptyList(), courses = emptyList())))
            first.students().last()
        }
        accept(first.commit(NewAssignAdvisor(advisor, listOf(students[0], students[1]))))
        assertEquals(
            listOf(listOf(1L, 1L), listOf(2L, 1L), listOf(3L, null)),
            rows("""SELECT id, advisor FROM student ORDER BY id"""),
        )

        val second = process()
        val advisor2 = second.read.advisor(1L)
        assertEquals(setOf("Ada", "Grace"), second.read(advisor2).students.map { second.read(it).name }.toSet(), "the one-to-many inverse is a join read")
        assertEquals(setOf("Ada", "Grace"), second.read(advisor2).advisees.map { second.read(it).name }.toSet())
    }
}


/** The tables the SQL store creates for `Student` in `examples/enrollment/enrollment.velle`, as SQLite records them; quoted in `wiki/Guides/SQL-store.md`. */
class EnrollmentSchemaOverSqliteTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = connection.close()

    private fun createTableStatement(table: String): String =
        connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").use { select ->
            select.setString(1, table)
            select.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "no table $table" }
        }

    @Test
    fun `a student's table, its collection of tags, and its collection of courses`() {
        EnrollmentSystem().also { enrollment -> SqliteStore.attach(enrollment.system, connection) }
        assertEquals(
            """CREATE TABLE "student" (id INTEGER PRIMARY KEY AUTOINCREMENT, "name" TEXT, "advisor" INTEGER, FOREIGN KEY ("advisor") REFERENCES "advisor"(id))""",
            createTableStatement("student"),
        )
        assertEquals(
            """CREATE TABLE "student__tags" (owner INTEGER NOT NULL, pos INTEGER NOT NULL, value TEXT NOT NULL)""",
            createTableStatement("student__tags"),
        )
        assertEquals(
            """CREATE TABLE "student__courses" (owner INTEGER NOT NULL, pos INTEGER NOT NULL, target INTEGER NOT NULL, FOREIGN KEY ("target") REFERENCES "course"(id))""",
            createTableStatement("student__courses"),
        )
        assertEquals("""CREATE TABLE "velle__tick" (schedule TEXT PRIMARY KEY, ran_at TEXT NOT NULL)""", createTableStatement("velle__tick"))
    }
}
