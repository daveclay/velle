package velle.generated.enrollment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.CommitResult
import velle.generated.EnrollmentSystem

/**
 * The collection constructs of README §6 driven through the generated typed
 * surface — the business-flow companion to the generated per-rule specs.
 */
class EnrollmentFlowTest {

    private val sys = EnrollmentSystem()

    private fun student(name: String = "Ada"): EnrollmentSystem.StudentView {
        assertIs<CommitResult.Accepted>(sys.commitStudent(name, tags = emptyList(), courses = emptyList()))
        return sys.students().last()
    }

    private fun course(title: String): EnrollmentSystem.CourseView {
        assertIs<CommitResult.Accepted>(sys.commitCourse(title, 30))
        return sys.courses().last()
    }

    @Test
    fun `enrollment lifecycle - reference sets, replacement, union, removal`() {
        val ada = student()
        val logic = course("Logic")
        val sets = course("Sets")
        val proofs = course("Proofs")

        // whole-set replacement: the edge set becomes exactly the committed set
        assertIs<CommitResult.Accepted>(sys.commitSetEnrollment(ada, listOf(logic, sets)))
        assertEquals(listOf(logic, sets), ada.courses)

        // union is idempotent; removal is quiet on an absent member
        assertIs<CommitResult.Accepted>(sys.commitEnroll(ada, proofs))
        assertIs<CommitResult.Accepted>(sys.commitEnroll(ada, proofs))
        assertEquals(setOf(logic, sets, proofs), ada.courses.toSet())
        assertTrue(sys.overloadeds().any { it.id == ada.id }, "count(courses) > 2")

        assertIs<CommitResult.Accepted>(sys.commitDrop(ada, logic))
        assertIs<CommitResult.Accepted>(sys.commitDrop(ada, logic))
        assertEquals(setOf(sets, proofs), ada.courses.toSet())

        // the inferred inverse follows the declared side
        assertEquals(emptyList(), logic.students)
        assertEquals(listOf(ada), sets.students)
    }

    @Test
    fun `a duplicate reference is refused at the boundary`() {
        val ada = student()
        val logic = course("Logic")
        val refused = sys.commitSetEnrollment(ada, listOf(logic, logic))
        val r = assertIs<CommitResult.Refused>(refused)
        assertTrue("duplicate" in r.reason, r.reason)
        assertEquals(emptyList(), ada.courses, "a refused act commits nothing")
    }

    @Test
    fun `fan-out assignment and the two views over the declared side`() {
        assertIs<CommitResult.Accepted>(sys.commitAdvisor("Turing"))
        val advisor = sys.advisors().last()
        val ada = student("Ada")
        val grace = student("Grace")
        val edsger = student("Edsger")

        assertIs<CommitResult.Accepted>(sys.commitAssignAdvisor(advisor, listOf(ada, grace)))
        assertEquals(advisor, ada.advisor)
        assertEquals(advisor, grace.advisor)
        assertEquals(null, edsger.advisor, "only the named children were written")

        // the inferred inverse and the renamed derived view agree
        assertEquals(setOf(ada, grace), advisor.students.toSet())
        assertEquals(setOf(ada, grace), advisor.advisees.toSet())
    }

    @Test
    fun `scalar collections - initially empty, set-union tags`() {
        val ada = student()
        assertEquals(emptyList(), ada.tags)
        assertTrue(sys.untaggeds().any { it.id == ada.id })

        assertIs<CommitResult.Accepted>(sys.commitTag(ada, "honors"))
        assertIs<CommitResult.Accepted>(sys.commitTag(ada, "honors"))
        assertIs<CommitResult.Accepted>(sys.commitTag(ada, "stem"))
        assertEquals(listOf("honors", "stem"), ada.tags)
        assertTrue(sys.untaggeds().none { it.id == ada.id })
    }
}
