package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The collection constructs of README §6, driven end-to-end through
 * enrollment.velle: declared m2m `many` with its inferred inverse,
 * `many <scalar>`, reference-set act fields, whole-set replacement, `+`/`-`
 * union and removal, fan-out assignment, `initially empty`, derived collection
 * views, the boundary's duplicate refusal — and the V19/V20 validator checks.
 */
class CollectionsTest {

    private fun newSystem(): VelleSystem {
        val source = File("../examples/enrollment/enrollment.velle").readText()
        val diags = Validator.validate(source)
        check(diags.isEmpty()) { diags.toString() }
        return VelleSystem(Model(Parser.parse(source)))
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    private fun coll(v: Any?): List<*> = assertIs<List<*>>(v)

    private fun VelleSystem.freshStudentAndCourses(): Triple<Long, Long, Long> {
        val s = mustCommit("Student", "name" to "Ada", "courses" to emptyList<Long>())
        val c1 = mustCommit("Course", "title" to "Logic", "capacity" to 30)
        val c2 = mustCommit("Course", "title" to "Sets", "capacity" to 30)
        return Triple(s, c1, c2)
    }

    @Test
    fun `a committed reference set lands as the edge set, and the inverse is inferred`() {
        val sys = newSystem()
        val c1 = sys.mustCommit("Course", "title" to "Logic", "capacity" to 30)
        val c2 = sys.mustCommit("Course", "title" to "Sets", "capacity" to 30)
        val s = sys.mustCommit("Student", "name" to "Ada", "courses" to listOf(c1, c2))
        assertEquals(setOf(c1, c2), coll(sys.get(s, "courses")).toSet())
        // Course.students is the inferred inverse of the declared many (README §6)
        assertEquals(listOf(s), coll(sys.get(c1, "students")))
        assertTrue(sys.isMember(s, "Untagged")) // initially empty scalar collection
    }

    @Test
    fun `an absent many is the empty collection`() {
        val sys = newSystem()
        val s = sys.mustCommit("Student", "name" to "Ada")
        assertEquals(emptyList<Long>(), coll(sys.get(s, "courses")))
        assertEquals(emptyList<Any?>(), coll(sys.get(s, "tags")))
    }

    @Test
    fun `a duplicate reference is refused at the boundary`() {
        val sys = newSystem()
        val c1 = sys.mustCommit("Course", "title" to "Logic", "capacity" to 30)
        val before = sys.instancesOf("Student").size
        val r = sys.commit("Student", mapOf("name" to "Ada", "courses" to listOf(c1, c1)))
        val refused = assertIs<CommitResult.Refused>(r)
        assertTrue("duplicate" in refused.reason, refused.reason)
        assertEquals(before, sys.instancesOf("Student").size, "a refused act commits nothing")
    }

    @Test
    fun `whole-set replacement makes the edge set exactly the committed set`() {
        val sys = newSystem()
        val (s, c1, c2) = sys.freshStudentAndCourses()
        sys.mustCommit("SetEnrollment", "student" to s, "courses" to listOf(c1))
        assertEquals(listOf(c1), coll(sys.get(s, "courses")))
        sys.mustCommit("SetEnrollment", "student" to s, "courses" to listOf(c2))
        assertEquals(listOf(c2), coll(sys.get(s, "courses")), "replacement, not accumulation")
        assertEquals(emptyList<Long>(), coll(sys.get(c1, "students")), "the inverse view followed")
    }

    @Test
    fun `plus is union and idempotent, minus is removal and quiet on absence`() {
        val sys = newSystem()
        val (s, c1, c2) = sys.freshStudentAndCourses()
        sys.mustCommit("Enroll", "student" to s, "course" to c1)
        sys.mustCommit("Enroll", "student" to s, "course" to c2)
        sys.mustCommit("Enroll", "student" to s, "course" to c1) // present: a no-op
        assertEquals(setOf(c1, c2), coll(sys.get(s, "courses")).toSet())
        sys.mustCommit("Drop", "student" to s, "course" to c1)
        assertEquals(listOf(c2), coll(sys.get(s, "courses")))
        sys.mustCommit("Drop", "student" to s, "course" to c1) // absent: a no-op
        assertEquals(listOf(c2), coll(sys.get(s, "courses")))
    }

    @Test
    fun `many scalar accumulates as a set`() {
        val sys = newSystem()
        val s = sys.mustCommit("Student", "name" to "Ada", "tags" to listOf("honors"))
        sys.mustCommit("Tag", "student" to s, "tag" to "stem")
        sys.mustCommit("Tag", "student" to s, "tag" to "stem") // set semantics
        assertEquals(listOf("honors", "stem"), coll(sys.get(s, "tags")))
        assertTrue(!sys.isMember(s, "Untagged"))
    }

    @Test
    fun `fan-out assignment writes the stored pointer of every named child`() {
        val sys = newSystem()
        val a = sys.mustCommit("Advisor", "name" to "Turing")
        val s1 = sys.mustCommit("Student", "name" to "Ada")
        val s2 = sys.mustCommit("Student", "name" to "Grace")
        val s3 = sys.mustCommit("Student", "name" to "Edsger")
        sys.mustCommit("AssignAdvisor", "advisor" to a, "students" to listOf(s1, s2))
        assertEquals(a, sys.get(s1, "advisor"))
        assertEquals(a, sys.get(s2, "advisor"))
        assertEquals(null, sys.get(s3, "advisor"), "only the named children were written")
        // both views over the declared side agree: the inferred inverse and the renamed derived view
        assertEquals(setOf(s1, s2), coll(sys.get(a, "students")).toSet())
        assertEquals(setOf(s1, s2), coll(sys.get(a, "advisees")).toSet())
    }

    @Test
    fun `a refinement reads the declared many`() {
        val sys = newSystem()
        val s = sys.mustCommit("Student", "name" to "Ada")
        val courses = (1..3).map { sys.mustCommit("Course", "title" to "C$it", "capacity" to 10) }
        assertTrue(!sys.isMember(s, "Overloaded"))
        sys.mustCommit("SetEnrollment", "student" to s, "courses" to courses)
        assertTrue(sys.isMember(s, "Overloaded"))
    }

    // ── validator: V19/V20 (README §6; checks.md) ────────────────────────────

    @Test
    fun `V19 - declaring both sides of one relationship is refused`() {
        val diags = Validator.validate(
            """
            shape Student { name: text, courses: many Course }
            shape Course { title: text, owner: one Student }
            expose Student
            expose Course
            """.trimIndent()
        )
        assertTrue(diags.any { it.code == "V19" }, diags.toString())
    }

    @Test
    fun `V20 - a collection path writes a field of the members, never through them`() {
        val diags = Validator.validate(
            """
            shape Tier { label: text }
            shape Customer { name: text, tier: one Tier }
            shape Invoice { customer: one Customer }
            expose Tier
            expose Customer
            expose Invoice
            expose transient shape Retier { customer: one Customer, tier: one Tier }
            rule ApplyRetier when Retier {
                this.customer.invoices.customer.tier = this.tier
            }
            """.trimIndent()
        )
        assertTrue(diags.any { it.code == "V20" }, diags.toString())
    }

    @Test
    fun `V20 - an inferred inverse is a view, never an assignment target`() {
        val diags = Validator.validate(
            """
            shape Customer { name: text }
            shape Invoice { customer: one Customer }
            expose Customer
            expose Invoice
            expose transient shape Claim { customer: one Customer, invoices: many Invoice }
            rule ApplyClaim when Claim {
                this.customer.invoices = this.invoices
            }
            """.trimIndent()
        )
        assertTrue(diags.any { it.code == "V20" }, diags.toString())
    }
}
