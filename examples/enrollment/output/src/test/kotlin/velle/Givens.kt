package velle.generated.enrollment

import velle.generated.EnrollmentSystem
import velle.generated.*

/**
 * The human-owned scenarios the generated enrollment specs demand (testgen.md).
 * Every act here is transient — the given performs the commit; the durable
 * outcome is the edge set / pointer / tag set the rule wrote.
 */
class Givens(private val sys: EnrollmentSystem) : RequiredGivens {

    private fun student(): Student {
        sys.commit(NewStudent("Ada", tags = emptyList(), courses = emptyList()))
        return sys.students().last()
    }

    private fun course(): Course {
        sys.commit(NewCourse("Logic", 30))
        return sys.courses().last()
    }

    override fun setEnrollment() {
        sys.commit(NewSetEnrollment(student(), listOf(course())))
    }

    override fun enroll() {
        sys.commit(NewEnroll(student(), course()))
    }

    override fun drop() {
        val s = student()
        val c = course()
        sys.commit(NewEnroll(s, c))
        sys.commit(NewDrop(s, c))
    }

    override fun tag() {
        sys.commit(NewTag(student(), "honors"))
    }

    override fun assignAdvisor() {
        sys.commit(NewAdvisor("Turing"))
        sys.commit(NewAssignAdvisor(sys.advisors().last(), listOf(student())))
    }
}
