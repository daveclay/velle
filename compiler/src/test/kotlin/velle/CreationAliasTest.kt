package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `as <name>` on a creation statement (Reference §14; OQ63 §6.2.1): the
 * created record, as a reference, for the other statements of the body —
 * parsing and printing, V33's refusals, and the runtime landing both records
 * in one firing whatever the written order.
 */
class CreationAliasTest {

    private val REVIEWS = """
        act Member { name: text }
        act Flag { member: one Member }

        shape AuditEntry {
            member: one Member
            review: one AccountReview?
            note: text
        }

        shape AccountReview {
            member: one Member
        }

        shape Chronic = Member where count(Flag where member == this) >= 2

        rule OpenReview when (Chronic where not exists (AccountReview where member == this)) {
            AuditEntry from { member: this, review: review, note: "review opened" }
            AccountReview from { member: this } as review
        }
    """.trimIndent()

    private fun diags(spec: String) = Validator.validate(spec)
    private fun codes(spec: String) = diags(spec).map { it.code }

    private fun system(spec: String): VelleSystem {
        val d = diags(spec)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.accept(shape: String, vararg fields: Pair<String, Any?>): Long =
        commit(shape, fields.toMap()).let { assertIs<CommitResult.Accepted>(it, "refused: $it").id }

    @Test
    fun `as parses on both creation forms and prints back`() {
        val decls = Parser.parse(
            """
            act A { t: text }
            shape B { a: one A }
            shape C { b: one B }
            rule R when A {
                B for this as b
                C from { b: b }
            }
            """.trimIndent()
        )
        val rule = decls.filterIsInstance<RuleDecl>().single()
        val b = rule.body.filterIsInstance<Creation>().first()
        assertEquals("b", b.alias)
        val printed = decls.joinToString("\n") { Printer.print(it) }
        assertTrue("B for this as b" in printed, printed)
    }

    @Test
    fun `the named record is created first and the other points at it, whatever the written order`() {
        val sys = system(REVIEWS)
        sys.scope {
            val m = sys.accept("Member", "name" to "Ada")
            sys.accept("Flag", "member" to m)
            assertEquals(0, sys.instancesOf("AccountReview").size)
            sys.accept("Flag", "member" to m)
            val review = sys.instancesOf("AccountReview").single()
            val entry = sys.instancesOf("AuditEntry").single()
            assertEquals(review, sys.get(entry, "review"))
            assertEquals(m, sys.get(review, "member"))
            // once, ever: the third flag re-fires nothing
            sys.accept("Flag", "member" to m)
            assertEquals(1, sys.instancesOf("AccountReview").size)
        }
    }

    @Test
    fun `the for form takes the named record as its target`() {
        val spec = """
            act A { t: text }
            shape B { a: one A }
            shape C { b: one B, t: text }
            rule R when A {
                B for this as made
                C for made t: this.t
            }
        """.trimIndent()
        val sys = system(spec)
        sys.scope {
            val a = sys.accept("A", "t" to "x")
            val b = sys.instancesOf("B").single()
            val c = sys.instancesOf("C").single()
            assertEquals(b, sys.get(c, "b"))
            assertEquals(a, sys.get(b, "a"))
        }
    }

    @Test
    fun `V33 - a name already in scope, a read through the name, an assignment, a delete, a cycle`() {
        val base = """
            act A { t: text, n: integer }
            shape B { a: one A, t: text, other: one C? }
            shape C { a: one A, b: one B? }
        """.trimIndent()
        fun v33(body: String) = diags("$base\nrule R when A {\n$body\n}").filter { it.code == "V33" }.map { it.message }
        assertTrue(v33("B from { a: this, t: this.t } as t").any { "already a name" in it })
        assertTrue(v33("B from { a: this, t: this.t } as b\nC from { a: this, b: b } as b").any { "already a name" in it })
        assertTrue(v33("B from { a: this, t: this.t } as b\nC from { a: this } as c\nB from { a: this, t: b.t }").any { "reads through 'b'" in it })
        assertTrue(v33("B from { a: this, t: this.t } as b\nthis.n = 1").isEmpty())
        assertTrue(v33("B from { a: this, t: this.t } as b\nb.t = \"x\"").any { "assigns through 'b'" in it })
        assertTrue(v33("B from { a: this, t: this.t } as b\ndelete b").any { "deletes 'b'" in it })
        assertTrue(v33("B from { a: this, t: this.t, other: c } as b\nC from { a: this, b: b } as c").any { "point at each other" in it })
        // the legal shape: one way, whole references only
        assertEquals(emptyList(), v33("B from { a: this, t: this.t } as b\nC from { a: this, b: b }"))
    }
}
