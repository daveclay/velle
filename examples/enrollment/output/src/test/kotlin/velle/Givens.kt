package velle.generated.enrollment

import velle.generated.EnrollmentSystem

/**
 * The human-owned scenarios the generated enrollment specs demand (testgen.md).
 * Every act here is transient — the given performs the commit; the durable
 * outcome is the edge set / pointer / tag set the rule wrote.
 */
class Givens(private val sys: EnrollmentSystem) : RequiredGivens {

    private fun student(): EnrollmentSystem.StudentView {
        sys.commitStudent("Ada", tags = emptyList(), courses = emptyList())
        return sys.students().last()
    }

    private fun course(): EnrollmentSystem.CourseView {
        sys.commitCourse("Logic", 30)
        return sys.courses().last()
    }

    override fun setEnrollment() {
        sys.commitSetEnrollment(student(), listOf(course()))
    }

    override fun enroll() {
        sys.commitEnroll(student(), course())
    }

    override fun drop() {
        val s = student()
        val c = course()
        sys.commitEnroll(s, c)
        sys.commitDrop(s, c)
    }

    override fun tag() {
        sys.commitTag(student(), "honors")
    }

    override fun assignAdvisor() {
        sys.commitAdvisor("Turing")
        sys.commitAssignAdvisor(sys.advisors().last(), listOf(student()))
    }
}
