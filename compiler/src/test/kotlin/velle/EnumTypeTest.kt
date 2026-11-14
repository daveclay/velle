package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A field that takes one of a few words (Reference §5): `Role is one of "pharmacist",
 * "technician", "admin"` declares a type whose values are exactly those words. A quoted
 * word beside a field of the type is read against the list when the spec is compiled
 * (checks F2), the door refuses any other word (V21), the words have no order, and one
 * answering rule per word covers a transient act (V18).
 */
class EnumTypeTest {

    private val staff = """
        Role is one of "pharmacist", "technician", "admin"

        act Staff {
            name: text
            role: Role
        }

        shape Pharmacist = Staff where role == "pharmacist"
    """

    private fun diagnostics(spec: String): List<String> = Validator.validate(Parser.parse(spec.trimIndent())).map { it.toString() }

    private fun system(spec: String): VelleSystem {
        val decls = Parser.parse(spec.trimIndent())
        val diags = Validator.validate(decls)
        assertEquals(emptyList(), diags)
        return VelleSystem(Model(decls))
    }

    private fun assertOne(diags: List<String>, vararg phrases: String) {
        assertEquals(1, diags.size, diags.toString())
        for (phrase in phrases) assertTrue(phrase in diags.single(), "expected '$phrase' in: ${diags.single()}")
    }

    private fun accepted(result: CommitResult): Long =
        (result as? CommitResult.Accepted)?.id ?: error((result as CommitResult.Refused).reason)

    // ── the declaration ──────────────────────────────────────────────────────

    @Test fun `the declaration parses to its name and its words, and prints back as written`() {
        val decls = Parser.parse(staff.trimIndent())
        val declared = decls.filterIsInstance<EnumDecl>().single()
        assertEquals("Role", declared.name)
        assertEquals(listOf("pharmacist", "technician", "admin"), declared.words)
        assertEquals("Role is one of \"pharmacist\", \"technician\", \"admin\"", Printer.print(declared))
    }

    @Test fun `a spec whose field is of the declared type validates`() {
        assertEquals(emptyList(), diagnostics(staff))
    }

    @Test fun `a word not on the list is refused where it is written`() {
        val diags = diagnostics(staff.replace("role == \"pharmacist\"", "role == \"pharmcist\""))
        assertOne(diags, "[F2]", "'\"pharmcist\"'", "'Role' rejects this constant: \"pharmcist\" is not one of \"pharmacist\", \"technician\", \"admin\"")
    }

    @Test fun `a word not on the list after in, in a from block, in an assignment, and after initially is refused`() {
        val inList = diagnostics(staff.replace("role == \"pharmacist\"", "role in [\"pharmacist\", \"boss\"]"))
        assertOne(inList, "[F2]", "'\"boss\"'", "'Role' rejects this constant")
        val fromBlock = diagnostics(staff + """
            shape Lanyard {
                staff: one Staff
                role: Role
            }
            rule IssueLanyard when Staff {
                Lanyard from { staff: this, role: "boss" }
            }
        """)
        assertOne(fromBlock, "[F2]", "'\"boss\"'", "'Role' rejects this constant")
        val assignment = diagnostics("""
            Role is one of "pharmacist", "technician", "admin"
            shape Staff {
                name: text
                role: Role
            }
            act Promote {
                staff: one Staff
            }
            rule Apply when Promote {
                staff.role = "boss"
            }
        """)
        assertOne(assignment, "[F2]", "'\"boss\"'", "'Role' rejects this constant")
        val initially = diagnostics(staff + """
            shape Lanyard {
                staff: one Staff
                role: Role initially "boss"
            }
        """)
        assertOne(initially, "[F2]", "'\"boss\"'", "'Role' rejects this constant")
    }

    @Test fun `the words have no order`() {
        val diags = diagnostics(staff.replace("role == \"pharmacist\"", "role < \"technician\""))
        assertOne(diags, "[F2]", "'role < \"technician\"'", "the words of 'Role' have no order, so '<' is not defined on them; compare with '==', '!=', or 'in' (Reference §5)")
    }

    @Test fun `a plain text beside the type is refused`() {
        val diags = diagnostics(staff.replace("role: Role", "role: Role\n    wanted: text").replace("role == \"pharmacist\"", "role == wanted"))
        assertOne(diags, "[F2]", "'role == wanted'", "compares a 'Role' with 'wanted', which is not one")
    }

    @Test fun `the declaration lists each word once, is made once, and shares no name with a shape or a builtin`() {
        assertOne(diagnostics(staff.replace("\"admin\"", "\"admin\", \"pharmacist\"")), "[F1]", "'Role is one of ...' lists \"pharmacist\" twice")
        assertOne(diagnostics(staff.replace("act Staff", "Role is one of \"x\"\n\nact Staff")), "[F1]", "'Role' is declared twice with 'is one of'; declare it once")
        assertOne(diagnostics(staff.replace("act Staff", "shape Role {\n    name: text\n}\n\nact Staff")), "[F1]", "'Role' is both a shape and a type declared with 'Role is one of ...'; rename one of them")
        assertTrue(diagnostics(staff.replace("Role", "Date")).any { "[E1] 'Date is one of ...': 'Date' is a builtin type name" in it })
        assertOne(diagnostics(staff.replace("\"admin\"", "\"in-progress\", \"in progress\"")), "[F1]", "\"in-progress\" and \"in progress\" would be one entry, 'in_progress', in the generated Kotlin")
    }

    @Test fun `a field of the type may be optional or a many`() {
        assertEquals(emptyList(), diagnostics(staff.replace("role: Role", "role: Role?\n    roles: many Role").replace("role == \"pharmacist\"", "role == \"pharmacist\" and roles contains \"admin\"")))
    }

    // ── the door and the rules ───────────────────────────────────────────────

    @Test fun `the door takes a listed word and refuses any other, naming the list`() {
        val sys = system(staff)
        sys.scope {
            val ada = accepted(sys.commit("Staff", mapOf("name" to "Ada", "role" to "pharmacist")))
            assertEquals(true, sys.isMember(ada, "Pharmacist"))
            val bob = accepted(sys.commit("Staff", mapOf("name" to "Bob", "role" to "technician")))
            assertEquals(false, sys.isMember(bob, "Pharmacist"))
            val boss = sys.commit("Staff", mapOf("name" to "Cy", "role" to "boss"))
            assertIs<CommitResult.Refused>(boss)
            assertEquals("type: Role — \"boss\" is not one of \"pharmacist\", \"technician\", \"admin\"", boss.reason)
            val number = sys.commit("Staff", mapOf("name" to "Di", "role" to 7))
            assertIs<CommitResult.Refused>(number)
            assertTrue(number.reason.startsWith("type:"), number.reason)
        }
    }

    @Test fun `a rule reads the word with in, and a derived property computes one`() {
        val sys = system(staff + """
            shape Senior = Staff where role in ["pharmacist", "admin"] {
                title: text = if role == "admin" then "Administrator" else "Pharmacist"
            }
        """)
        sys.scope {
            val ada = accepted(sys.commit("Staff", mapOf("name" to "Ada", "role" to "admin")))
            val bob = accepted(sys.commit("Staff", mapOf("name" to "Bob", "role" to "technician")))
            assertEquals(true, sys.isMember(ada, "Senior"))
            assertEquals(false, sys.isMember(bob, "Senior"))
        }
    }

    // ── the reading function ─────────────────────────────────────────────────

    @Test fun `the type's reading function turns a text into a word, absent when it is not one`() {
        val spec = staff.replace("role: Role", "role: Role\n    wanted: text") + """
            shape Matched = Staff where role(wanted) == role
            shape Unreadable = Staff where role(wanted) is none
        """
        assertEquals(emptyList(), diagnostics(spec))
        val sys = system(spec)
        sys.scope {
            val ada = accepted(sys.commit("Staff", mapOf("name" to "Ada", "role" to "admin", "wanted" to "admin")))
            val bob = accepted(sys.commit("Staff", mapOf("name" to "Bob", "role" to "admin", "wanted" to "boss")))
            assertEquals(true, sys.isMember(ada, "Matched"))
            assertEquals(false, sys.isMember(ada, "Unreadable"))
            assertEquals(false, sys.isMember(bob, "Matched"))
            assertEquals(true, sys.isMember(bob, "Unreadable"))
        }
    }

    @Test fun `a declaration whose reading function would be a builtin's name is refused`() {
        assertOne(diagnostics(staff.replace("Role", "Number")), "[E1]", "'Number is one of ...': its reading function 'number' is a builtin function name; choose another name")
    }

    // ── every request answered (V18) ─────────────────────────────────────────

    private val review = """
        Outcome is one of "approved", "declined", "error"

        shape Entry {
            name: text unique
        }

        transient act Review {
            entry: one Entry
            outcome: Outcome
        }

        shape Approval {
            entry: one Entry
        }

        shape Failure {
            entry: one Entry
            outcome: Outcome
        }

        rule RecordApproval when (Review where outcome == "approved") {
            Approval from { entry: entry }
        }

        rule RecordFailure when (Review where outcome in ["declined", "error"]) {
            Failure from { entry: entry, outcome: outcome }
        }
    """

    @Test fun `one answer per word covers a transient act`() {
        assertEquals(emptyList(), diagnostics(review))
        assertEquals(emptyList(), diagnostics(review.replace("outcome in [\"declined\", \"error\"]", "outcome == \"declined\" or outcome == \"error\"")))
        assertEquals(emptyList(), diagnostics(review.replace("rule RecordFailure", "never (Review where outcome == \"error\")\n\nrule RecordFailure").replace("outcome in [\"declined\", \"error\"]", "outcome == \"declined\"")))
    }

    @Test fun `a word no answer names is reported by name`() {
        val diags = diagnostics(review.replace("outcome in [\"declined\", \"error\"]", "outcome == \"declined\""))
        assertOne(diags, "[V18]", "a 'Review' whose 'outcome' is \"error\" can arrive and no rule or 'never' answers it, and it is transient, so it would be ignored with no record that it arrived; the answers name \"approved\" and \"declined\" of the three words of 'Outcome'. Answer \"error\" in a rule, or refuse it with 'never (Review where outcome == \"error\")' (Reference §4)")
    }
}
