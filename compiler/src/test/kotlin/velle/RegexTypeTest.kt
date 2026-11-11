package velle

import velle.ext.builtin.RegexValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `Regex` type and `regex(text)` (Reference §5), and a text literal with
 * pieces as a pattern (Reference §10): a piece's type says whether it is
 * characters or pattern, a literal beside `matches`, as the argument of
 * `regex(...)`, or as the value of a `Regex` property is read piece by piece,
 * and `regex(expr)` over anything else reads the whole text as a pattern.
 * Settled as OQ80.
 */
class RegexTypeTest {

    private fun diags(spec: String) = Validator.validate(spec)

    private fun model(spec: String): Model {
        val decls = Parser.parse(spec)
        val found = Validator.validate(decls)
        check(found.none { !it.advisory }) { found.toString() }
        return Model(decls)
    }

    private fun accepted(result: CommitResult): Long = (result as? CommitResult.Accepted)?.id ?: error((result as CommitResult.Refused).reason)

    private val fields = """
        act Field {
            name: text
            suffix: text
            valuePattern: Regex
        }
        act Reading {
            field: one Field
            value: text?
        }
        shape Ok = Reading where value is some and value matches regex("^${'$'}{field.name}-\\d+${'$'}{field.suffix}$")
        shape Same = Reading where value is some and value matches "^${'$'}{field.name}-\\d+${'$'}{field.suffix}$"
        shape Loose = Reading where value is some and value matches regex("^${'$'}{field.name}-${'$'}{regex(field.suffix)}$")
        shape WellFormed = Reading where value is some and value matches "^${'$'}{field.valuePattern}$"
        shape Stored = Reading where value is some and value matches field.valuePattern
    """.trimIndent()

    private fun regexOf(value: Value): RegexValue = (value as Value.VExt).v as RegexValue

    @Test
    fun `a malformed pattern cannot be constructed, and regex of a text is absent when it does not compile`() {
        assertFailsWith<velle.ext.LiteralRejected> { RegexValue("(") }
        val spec = """
            act Rule {
                source: text
                pattern: Regex? = regex(source)
            }
        """.trimIndent()
        val sys = VelleSystem(model(spec))
        sys.scope {
            val good = accepted(sys.commit("Rule", mapOf("source" to "[a-z]+")))
            val bad = accepted(sys.commit("Rule", mapOf("source" to "(")))
            assertEquals(RegexValue("[a-z]+"), regexOf(sys.evaluator.readMember(good, "Rule", "pattern")))
            assertEquals(Value.VNone, sys.evaluator.readMember(bad, "Rule", "pattern"))
        }
    }

    @Test
    fun `a text piece is characters and a Regex piece is pattern, inline and beside matches alike`() {
        val sys = VelleSystem(model(fields))
        sys.scope {
            val field = accepted(sys.commit("Field", mapOf("name" to "a.b", "suffix" to "x+", "valuePattern" to RegexValue("[0-9]{2}"))))
            fun reading(value: String): Long = accepted(sys.commit("Reading", mapOf("field" to field, "value" to value)))
            fun member(id: Long, refinement: String): Boolean = sys.isMember(id, refinement)

            val exact = reading("a.b-12x+")
            assertTrue(member(exact, "Ok"), "name and suffix are characters: the dot and the plus match themselves")
            assertTrue(member(exact, "Same"), "the literal beside matches reads the same way")
            val dotAsAny = reading("axb-12x+")
            assertTrue(!member(dotAsAny, "Ok"), "the dot in a text piece is not a wildcard")
            val suffixAsPattern = reading("a.b-xxx")
            assertTrue(member(suffixAsPattern, "Loose"), "regex(field.suffix) splices the suffix as pattern: x+ matches xxx")
            assertTrue(!member(exact, "Loose"), "under the pattern reading, 12x+ does not match x+")
            val twoDigits = reading("42")
            assertTrue(member(twoDigits, "WellFormed"), "a stored Regex piece is pattern")
            assertTrue(member(twoDigits, "Stored"), "a Regex on the right of matches is the pattern itself")
            assertTrue(!member(reading("4"), "WellFormed"))
        }
    }

    @Test
    fun `a pattern built in a Regex property keeps a text piece as characters, and one built in a text does not`() {
        val spec = """
            act Field {
                name: text
                built: text = "^${'$'}{name}-\\d+$"
                kept: Regex = "^${'$'}{name}-\\d+$"
            }
            act Reading {
                field: one Field
                value: text
            }
            shape Lost = Reading where value matches "${'$'}{regex(field.built)}"
            shape Right = Reading where value matches "${'$'}{field.kept}"
            shape Notice {
                pattern: Regex
            }
            rule Note when Field {
                Notice from { pattern: "^${'$'}{name}$" }
            }
        """.trimIndent()
        assertEquals(emptyList(), diags(spec), "nothing is refused")
        val found = Validator.advisories(spec)
        assertEquals(listOf("A7"), found.map { it.code }, found.toString())
        assertEquals("'regex(field.built)' — 'built' is a text built from pieces, and reading it as a pattern reads its pieces as pattern too; " +
            "declare 'built' as `Regex` to keep a text piece as characters", found.single().message)
        val sys = VelleSystem(Model(Parser.parse(spec)))
        sys.scope {
            val field = accepted(sys.commit("Field", mapOf("name" to "a.b")))
            val reading = accepted(sys.commit("Reading", mapOf("field" to field, "value" to "axb-7")))
            assertTrue(sys.isMember(reading, "Lost"), "read back from a text, the dot became a wildcard")
            assertTrue(!sys.isMember(reading, "Right"), "built in a Regex property, the dot stayed a character")
            val notice = sys.instancesOf("Notice").single()
            assertEquals(RegexValue("^\\Qa.b\\E$"), regexOf(sys.evaluator.readMember(notice, "Notice", "pattern")))
        }
    }

    @Test
    fun `matches takes a quoted pattern, a literal with pieces, or a Regex, and checks the fixed runs ahead`() {
        val spec = """
            act Field {
                name: text
                n: integer
            }
            shape ByNumber = Field where name matches n
            shape Unbalanced = Field where name matches "(${'$'}{name}"
            shape Fine = Field where name matches "(${'$'}{name})?"
        """.trimIndent()
        val messages = diags(spec).filter { it.code == "F2" }.map { it.message }
        assertEquals(2, messages.size, messages.toString())
        assertEquals("'name matches n' — 'matches' takes a quoted regular expression, a text literal with pieces, or a Regex on the right", messages[0])
        assertTrue(messages[1].startsWith("'name matches \"(\${name}\"' — the pattern is not a regular expression around its pieces: "), messages[1])
    }
}
