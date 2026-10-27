package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The collection constructs of Reference §6, driven end-to-end through
 * enrollment.velle: declared m2m `many` with its inferred inverse,
 * `many <scalar>` with and without `tolerates duplication` (Reference §5), reference-set act fields, whole-set replacement, `+`/`-`
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
        val c1 = mustCommit("Course", "title" to "Logic", "capacity" to 30, "term" to java.time.Duration.ofDays(84))
        val c2 = mustCommit("Course", "title" to "Sets", "capacity" to 30, "term" to java.time.Duration.ofDays(84))
        return Triple(s, c1, c2)
    }

    @Test
    fun `a committed reference set lands as the edge set, and the inverse is inferred`() {
        val sys = newSystem()
        sys.scope {
            val c1 = sys.mustCommit("Course", "title" to "Logic", "capacity" to 30, "term" to java.time.Duration.ofDays(84))
            val c2 = sys.mustCommit("Course", "title" to "Sets", "capacity" to 30, "term" to java.time.Duration.ofDays(84))
            val s = sys.mustCommit("Student", "name" to "Ada", "courses" to listOf(c1, c2))
            assertEquals(setOf(c1, c2), coll(sys.get(s, "courses")).toSet())
            // Course.students is the inferred inverse of the declared many (Reference §6)
            assertEquals(listOf(s), coll(sys.get(c1, "students")))
            assertTrue(sys.isMember(s, "Untagged")) // initially empty scalar collection
        }
    }

    @Test
    fun `an absent many is the empty collection`() {
        val sys = newSystem()
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada")
            assertEquals(emptyList<Long>(), coll(sys.get(s, "courses")))
            assertEquals(emptyList<Any?>(), coll(sys.get(s, "tags")))
        }
    }

    @Test
    fun `a duplicate reference is refused at the boundary`() {
        val sys = newSystem()
        sys.scope {
            val c1 = sys.mustCommit("Course", "title" to "Logic", "capacity" to 30, "term" to java.time.Duration.ofDays(84))
            val before = sys.instancesOf("Student").size
            val r = sys.commit("Student", mapOf("name" to "Ada", "courses" to listOf(c1, c1)))
            val refused = assertIs<CommitResult.Refused>(r)
            assertTrue("duplicate" in refused.reason, refused.reason)
            assertEquals(before, sys.instancesOf("Student").size, "a refused act commits nothing")
        }
    }

    @Test
    fun `whole-set replacement makes the edge set exactly the committed set`() {
        val sys = newSystem()
        sys.scope {
            val (s, c1, c2) = sys.freshStudentAndCourses()
            sys.mustCommit("SetEnrollment", "student" to s, "courses" to listOf(c1))
            assertEquals(listOf(c1), coll(sys.get(s, "courses")))
            sys.mustCommit("SetEnrollment", "student" to s, "courses" to listOf(c2))
            assertEquals(listOf(c2), coll(sys.get(s, "courses")), "replacement, not accumulation")
            assertEquals(emptyList<Long>(), coll(sys.get(c1, "students")), "the inverse view followed")
        }
    }

    @Test
    fun `plus is union and idempotent, minus is removal and quiet on absence`() {
        val sys = newSystem()
        sys.scope {
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
    }

    @Test
    fun `many scalar accumulates as a set`() {
        val sys = newSystem()
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada", "tags" to listOf("honors"))
            sys.mustCommit("Tag", "student" to s, "tag" to "stem")
            sys.mustCommit("Tag", "student" to s, "tag" to "stem") // set semantics
            assertEquals(listOf("honors", "stem"), coll(sys.get(s, "tags")))
            assertTrue(!sys.isMember(s, "Untagged"))
        }
    }

    @Test
    fun `a committed many with a duplicate is refused, one tolerating duplication keeps them in order`() {
        val sys = newSystem()
        sys.scope {
            val dup = sys.commit("Student", mapOf("name" to "Ada", "tags" to listOf("stem", "honors", "stem")))
            assertTrue("`tolerates duplication`" in assertIs<CommitResult.Refused>(dup).reason)
            val s = sys.mustCommit("Student", "name" to "Ada", "notes" to listOf("late", "early", "late"))
            assertEquals(listOf("late", "early", "late"), coll(sys.get(s, "notes")))
        }
    }

    @Test
    fun `a many tolerating duplication appends on plus, in order, duplicates kept`() {
        val sys = newSystem()
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada")
            assertEquals(emptyList<Any>(), coll(sys.get(s, "notes")), "an absent many is the empty collection")
            sys.mustCommit("Note", "student" to s, "note" to "late")
            sys.mustCommit("Note", "student" to s, "note" to "early")
            sys.mustCommit("Note", "student" to s, "note" to "late")
            assertEquals(listOf("late", "early", "late"), coll(sys.get(s, "notes")))
        }
    }

    @Test
    fun `a many tolerating duplication removes every occurrence on minus`() {
        val source = File("../examples/enrollment/enrollment.velle").readText() + """
            transient act Strike {
                student: one Student
                note: text
            }
            rule ApplyStrike when Strike {
                this.student.notes = this.student.notes - this.note
            }
        """
        val diags = Validator.validate(source)
        check(diags.isEmpty()) { diags.toString() }
        val sys = VelleSystem(Model(Parser.parse(source)))
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada", "notes" to listOf("late", "early", "late"))
            sys.mustCommit("Strike", "student" to s, "note" to "late")
            assertEquals(listOf("early"), coll(sys.get(s, "notes")))
            sys.mustCommit("Strike", "student" to s, "note" to "late") // absent: nothing changes
            assertEquals(listOf("early"), coll(sys.get(s, "notes")))
        }
    }

    @Test
    fun `count and derived views over a many of values`() {
        val source = """
            act Student { name: text, tags: many text, notes: many text tolerates duplication }
            act Enrollment {
                student: one Student
                tagsSeen: many text = student.tags
                notesSeen: many text = student.notes
                noteCount: integer = count(student.notes)
                tagCount: integer = count(student.tags)
            }
            shape Noted = Enrollment where notesSeen is not empty
        """
        val diags = Validator.validate(source)
        check(diags.isEmpty()) { diags.toString() }
        val sys = VelleSystem(Model(Parser.parse(source)))
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada", "tags" to listOf("x"), "notes" to listOf("a", "b", "a"))
            val e = sys.mustCommit("Enrollment", "student" to s)
            assertEquals(listOf("x"), coll(sys.get(e, "tagsSeen")))
            assertEquals(listOf("a", "b", "a"), coll(sys.get(e, "notesSeen")))
            assertEquals(3, (sys.get(e, "noteCount") as Number).toInt())
            assertEquals(1, (sys.get(e, "tagCount") as Number).toInt())
            assertTrue(sys.isMember(e, "Noted"))
        }
    }

    @Test
    fun `contains over a many of values is membership under the element's equality`() {
        val sys = newSystem()
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada", "tags" to listOf("stem"))
            assertTrue(!sys.isMember(s, "Honors"))
            sys.mustCommit("Tag", "student" to s, "tag" to "honors")
            assertTrue(sys.isMember(s, "Honors"))
            val diags = Validator.validate("act S { scores: many integer }\nshape X = S where scores contains \"a\"")
            assertTrue(diags.any { it.code == "F2" }, diags.toString())
            assertTrue(Validator.validate("act S { scores: many integer, n: integer }\nshape X = S where scores contains n").none { it.code == "F2" })
        }
    }

    @Test
    fun `F2 - a collection of values cannot be filtered, summed, or ordered`() {
        fun diags(body: String) = Validator.validate("""
            act Student { name: text, scores: many integer tolerates duplication }
            act Report { student: one Student, x: integer = $body }
        """).filter { it.code == "F2" }
        assertTrue(diags("count(student.scores where this > 1)").isNotEmpty())
        assertTrue(diags("sum(student.scores, value)").isNotEmpty())
        assertTrue(diags("count(student.scores)").isEmpty())
    }

    @Test
    fun `V8 - a fold on a many tolerating duplication is order-dependent on a tick, a set fold is not`() {
        fun spec(clause: String) = """
            act Log { entries: many text $clause }
            transient act Append { log: one Log, entry: text }
            rule Keep when Append {
                this.log.entries = this.log.entries + this.entry
            }
            rule Check when Log on Nightly {
                this.entries = this.entries + "checked"
            }
        """
        assertTrue(Validator.validate(spec("")).none { it.code == "V8" }, "set union: adding a value twice is adding it once")
        assertTrue(Validator.validate(spec("tolerates duplication")).any { it.code == "V8" }, "append: a repeated step shows")
    }

    @Test
    fun `tolerates duplication on a many of values sets the ordered flag, on a many of records is F3`() {
        val decl = Parser.parse("act Student { tags: many text\n notes: many text tolerates duplication }").single() as ShapeDecl
        val props = decl.members.filterIsInstance<StoredProp>()
        assertEquals(ScalarType("text", optional = false, many = true), props[0].type)
        assertEquals(ScalarType("text", optional = false, many = true, ordered = true), props[1].type)
        val diags = Validator.validate("act Course { title: text }\n act Student { courses: many Course tolerates duplication }")
        assertTrue(diags.any { it.code == "F3" && "cannot tolerate duplication" in it.message }, diags.toString())
    }

    @Test
    fun `fan-out assignment writes the stored pointer of every named child`() {
        val sys = newSystem()
        sys.scope {
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
    }

    @Test
    fun `a refinement reads the declared many`() {
        val sys = newSystem()
        sys.scope {
            val s = sys.mustCommit("Student", "name" to "Ada")
            val courses = (1..3).map { sys.mustCommit("Course", "title" to "C$it", "capacity" to 10, "term" to java.time.Duration.ofDays(84)) }
            assertTrue(!sys.isMember(s, "Overloaded"))
            sys.mustCommit("SetEnrollment", "student" to s, "courses" to courses)
            assertTrue(sys.isMember(s, "Overloaded"))
        }
    }

    // ── validator: V19/V20 (Reference §6; checks.md) ────────────────────────────

    @Test
    fun `V19 - declaring both sides of one relationship is refused`() {
        val diags = Validator.validate(
            """
            act Student { name: text, courses: many Course }
            act Course { title: text, owner: one Student }
            """.trimIndent()
        )
        assertTrue(diags.any { it.code == "V19" }, diags.toString())
    }

    @Test
    fun `V20 - a collection path writes a field of the members, never through them`() {
        val diags = Validator.validate(
            """
            act Tier { label: text }
            act Customer { name: text, tier: one Tier }
            act Invoice { customer: one Customer }
            transient act Retier { customer: one Customer, tier: one Tier }
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
            act Customer { name: text }
            act Invoice { customer: one Customer }
            transient act Claim { customer: one Customer, invoices: many Invoice }
            rule ApplyClaim when Claim {
                this.customer.invoices = this.invoices
            }
            """.trimIndent()
        )
        assertTrue(diags.any { it.code == "V20" }, diags.toString())
    }
}
