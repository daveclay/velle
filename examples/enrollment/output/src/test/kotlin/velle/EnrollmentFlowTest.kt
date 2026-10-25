package velle.generated.enrollment

import kotlin.test.Test
import velle.generated.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.EnrollmentSystem

/**
 * The collection constructs of Reference §6 driven through the generated typed
 * surface — the business-flow companion to the generated per-rule specs.
 */
open class EnrollmentFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): EnrollmentSystem = EnrollmentSystem()
    protected val sys: EnrollmentSystem by lazy { newSystem() }

    private fun student(name: String = "Ada"): Student {
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewStudent(name, tags = emptyList(), notes = emptyList(), courses = emptyList())))
        return sys.students().last()
    }

    private fun course(title: String): Course {
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewCourse(title, 30)))
        return sys.courses().last()
    }

    @Test
    fun `enrollment lifecycle - reference sets, replacement, union, removal`() {
        val ada = student()
        val logic = course("Logic")
        val sets = course("Sets")
        val proofs = course("Proofs")

        // whole-set replacement: the edge set becomes exactly the committed set
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewSetEnrollment(ada, listOf(logic, sets))))
        assertEquals(listOf(logic, sets), sys.read(ada).courses)

        // union is idempotent; removal is quiet on an absent member
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewEnroll(ada, proofs)))
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewEnroll(ada, proofs)))
        assertEquals(setOf(logic, sets, proofs), sys.read(ada).courses.toSet())
        assertTrue(sys.overloadeds().any { it.key == ada.key }, "count(courses) > 2")

        assertIs<Commit.Accepted<*, *>>(sys.commit(NewDrop(ada, logic)))
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewDrop(ada, logic)))
        assertEquals(setOf(sets, proofs), sys.read(ada).courses.toSet())

        // the inferred inverse follows the declared side
        assertEquals(emptyList(), sys.read(logic).students)
        assertEquals(listOf(ada), sys.read(sets).students)
    }

    @Test
    fun `a duplicate reference is refused at the boundary`() {
        val ada = student()
        val logic = course("Logic")
        val refused = sys.commit(NewSetEnrollment(ada, listOf(logic, logic)))
        val r = assertIs<Commit.Refused<*, *>>(refused)
        assertTrue("duplicate" in r.reason, r.reason)
        assertEquals(emptyList(), sys.read(ada).courses, "a refused act commits nothing")
    }

    @Test
    fun `fan-out assignment and the two views over the declared side`() {
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewAdvisor("Turing")))
        val advisor = sys.advisors().last()
        val ada = student("Ada")
        val grace = student("Grace")
        val edsger = student("Edsger")

        assertIs<Commit.Accepted<*, *>>(sys.commit(NewAssignAdvisor(advisor, listOf(ada, grace))))
        assertEquals(advisor, sys.read(ada).advisor)
        assertEquals(advisor, sys.read(grace).advisor)
        assertEquals(null, sys.read(edsger).advisor, "only the named children were written")

        // the inferred inverse and the renamed derived view agree
        assertEquals(setOf(ada, grace), sys.read(advisor).students.toSet())
        assertEquals(setOf(ada, grace), sys.read(advisor).advisees.toSet())
    }

    @Test
    fun `scalar collections - initially empty, set-union tags`() {
        val ada = student()
        assertEquals(emptyList(), sys.read(ada).tags)
        assertTrue(sys.untaggeds().any { it.key == ada.key })

        assertIs<Commit.Accepted<*, *>>(sys.commit(NewTag(ada, "honors")))
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewTag(ada, "honors")))
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewTag(ada, "stem")))
        assertEquals(listOf("honors", "stem"), sys.read(ada).tags)
        assertTrue(sys.untaggeds().none { it.key == ada.key })
    }

    @Test
    fun `scalar collections - tolerates duplication keeps order and duplicates`() {
        val ada = student()
        assertEquals(emptyList(), sys.read(ada).notes)
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewNote(ada, "late")))
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewNote(ada, "early")))
        assertIs<Commit.Accepted<*, *>>(sys.commit(NewNote(ada, "late")))
        assertEquals(listOf("late", "early", "late"), sys.read(ada).notes)
    }
}

