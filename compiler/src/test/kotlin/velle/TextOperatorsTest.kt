package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The text operators (Reference §5, §10; OQ51 R1): `contains`, `starts with`,
 * `ends with`, `like` — contextual words in comparison position, exact and
 * case-sensitive, typed text-on-both-sides, evaluated in memory, and rendered
 * to the pre-filter as a column test the authoritative predicate implies.
 */
class TextOperatorsTest {

    private val spec = """
        act Customer {
            name: text
            email: text
            tier: integer
        }

        shape AcmeCustomer   = Customer where name starts with "Acme"
        shape CorpCustomer   = Customer where name ends with "Corp"
        shape MentionsLtd    = Customer where name contains "Ltd"
        shape ExampleMailbox = Customer where email like "%@example._o_"
        shape LowerAcme      = Customer where lowercase(name) contains "acme"
    """.trimIndent()

    private fun model(): Model {
        val decls = Parser.parse(spec)
        val diags = Validator.validate(decls)
        check(diags.isEmpty()) { diags.toString() }
        return Model(decls)
    }

    @Test
    fun `the operators parse in comparison position and print back as written`() {
        val m = model()
        val acme = (m.refinements.getValue("AcmeCustomer").expr as RefName).where
        assertIs<Binary>(acme)
        assertEquals("starts with", acme.op)
        assertEquals("name starts with \"Acme\"", Printer.expr(acme))
        val corp = (m.refinements.getValue("CorpCustomer").expr as RefName).where as Binary
        assertEquals("ends with", corp.op)
        val like = (m.refinements.getValue("ExampleMailbox").expr as RefName).where as Binary
        assertEquals("like", like.op)
        // the words stay ordinary identifiers elsewhere: a field may be named `contains`
        val decls = Parser.parse("act Thing { contains: text\n like: integer }")
        assertEquals(listOf("contains", "like"), (decls.single() as ShapeDecl).members.filterIsInstance<StoredProp>().map { it.name })
    }

    @Test
    fun `both sides must be text`() {
        val diags = Validator.validate(
            """
            act Customer { name: text
                tier: integer }
            shape Odd = Customer where tier contains "1"
            """.trimIndent()
        )
        assertEquals(1, diags.size, diags.toString())
        assertTrue(diags.single().message.contains("compares text"), diags.single().message)
    }

    @Test
    fun `evaluation is exact and case-sensitive, and like's wildcards and escapes hold`() {
        val sys = VelleSystem(model())
        sys.scope {
            fun customer(name: String, email: String): Long =
                assertIs<CommitResult.Accepted>(sys.commit("Customer", mapOf("name" to name, "email" to email, "tier" to 1))).id
            val acme = customer("Acme Ltd", "a@example.com")
            val lower = customer("acme corp", "b@example.bot")
            val widgets = customer("Widgets Corp", "c@example.co")
            val under = customer("Under_score", "d@examplexcom")

            assertEquals(listOf(acme), sys.instancesOf("AcmeCustomer"))
            assertEquals(listOf(widgets), sys.instancesOf("CorpCustomer")) // "acme corp" is lowercase: no match
            assertEquals(listOf(acme), sys.instancesOf("MentionsLtd"))
            assertEquals(listOf(acme, lower), sys.instancesOf("LowerAcme"))
            // `%@example._o_`: "example.com", "example.bot" match; "example.co" is too short; "examplexcom" has no dot
            assertEquals(listOf(acme, lower), sys.instancesOf("ExampleMailbox"))
            assertTrue(under !in sys.instancesOf("ExampleMailbox"))
        }
    }

    @Test
    fun `the pre-filter renders each operator as a column test, constant on the right only`() {
        val m = model()
        val qc = QueryCompiler(m, LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(QF.Cmp("name", "starts with", QConst.QText("Acme")), qc.filterFor(RefName("AcmeCustomer")))
        assertEquals(QF.Cmp("name", "ends with", QConst.QText("Corp")), qc.filterFor(RefName("CorpCustomer")))
        assertEquals(QF.Cmp("name", "contains", QConst.QText("Ltd")), qc.filterFor(RefName("MentionsLtd")))
        assertEquals(QF.Cmp("email", "like", QConst.QText("%@example._o_")), qc.filterFor(RefName("ExampleMailbox")))
        // `lowercase(name)` is not a column: degrades to true (the evaluator decides)
        assertEquals(QF.True, qc.filterFor(RefName("LowerAcme")))
        // constant on the left is not a column test either
        val flipped = RefName("Customer", Binary("contains", TextLit("Acme Ltd"), PathExpr("name")))
        assertEquals(QF.True, qc.filterFor(flipped))
    }
}
