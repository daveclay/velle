package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `x in values` inside an `exists (... as r where ...)` (Reference §10; checks.md F2): the right
 * side resolves in the element's scope, through an optional derived reference narrowed by
 * `is some`, and the prohibition refuses a value outside the options at the door.
 * The spelling is `working-docs/examples/survey/01-in-inside-exists-refused.velle`.
 */
class InInsideExistsTest {

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long =
        when (val result = commit(shape, mapOf(*fields))) {
            is CommitResult.Accepted -> result.id
            is CommitResult.Refused -> error(result.reason)
        }

    private val spec = """
        act Field {
            kindName: text
            name: text
            options: many text
        }

        act Submission {
            kindName: text
            readings: many Reading = (Reading where submission == this)
        }

        act Reading {
            submission: one Submission
            fieldName: text
            value: text?
            field: one Field? =
                if exists (Field where kindName == this.submission.kindName and name == this.fieldName) then first(Field where kindName == this.submission.kindName and name == this.fieldName by name)
                else none
        }

        never (Submission where exists (readings as r where
               r.field is some and r.value is some and r.value not in r.field.options))
    """.trimIndent()

    @Test
    fun `the membership test inside the exists validates`() {
        assertEquals(emptyList(), Validator.validate(Parser.parse(spec)).map { it.toString() })
    }

    @Test
    fun `a reading outside the field's options is refused, one inside is accepted`() {
        val sys = VelleSystem(Model(Parser.parse(spec)))
        sys.scope {
            sys.mustCommit("Field", "kindName" to "survey", "name" to "colour", "options" to listOf("red", "blue"))
            val submission = sys.mustCommit("Submission", "kindName" to "survey")
            sys.mustCommit("Reading", "submission" to submission, "fieldName" to "colour", "value" to "red")
            val outside = sys.commit("Reading", mapOf("submission" to submission, "fieldName" to "colour", "value" to "green"))
            val refused = assertIs<CommitResult.Refused>(outside)
            assertTrue("Submission" in refused.reason, refused.reason)
        }
    }

    // ── a `where` over a `many` of values with an alias (Reference §5) ──

    private val aliasSpec = spec.replace(
        "r.value not in r.field.options",
        "not exists (r.field.options as o where o == r.value)",
    )

    @Test
    fun `a many of values filtered with an alias validates and runs`() {
        assertEquals(emptyList(), Validator.validate(Parser.parse(aliasSpec)).map { it.toString() })
        val sys = VelleSystem(Model(Parser.parse(aliasSpec)))
        sys.scope {
            sys.mustCommit("Field", "kindName" to "survey", "name" to "colour", "options" to listOf("red", "blue"))
            val submission = sys.mustCommit("Submission", "kindName" to "survey")
            sys.mustCommit("Reading", "submission" to submission, "fieldName" to "colour", "value" to "red")
            val outside = sys.commit("Reading", mapOf("submission" to submission, "fieldName" to "colour", "value" to "green"))
            assertIs<CommitResult.Refused>(outside)
        }
    }

    @Test
    fun `a count over the values that pass an alias filter reads the values`() {
        val counting = """
            act Field {
                name: text
                options: many text
                shortOptions: many text = (options as o where o starts with "b")
                shortOptionCount = count(options as o where o starts with "b")
            }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(Parser.parse(counting)).map { it.toString() })
        val sys = VelleSystem(Model(Parser.parse(counting)))
        sys.scope {
            val field = sys.mustCommit("Field", "name" to "colour", "options" to listOf("red", "blue", "black"))
            assertEquals(listOf("blue", "black"), sys.get(field, "shortOptions"))
            assertEquals(java.math.BigDecimal(2), sys.get(field, "shortOptionCount"))
        }
    }

    @Test
    fun `a sum over the values that pass a filter stays refused`() {
        val diags = Validator.validate(Parser.parse("""
            act Order {
                amounts: many decimal
                large = sum(amounts as a where a > 10)
            }
        """.trimIndent())).map { it.toString() }
        assertEquals(1, diags.size, diags.toString())
        assertTrue("a sum adds a whole `many` of values" in diags.single(), diags.single())
    }

    @Test
    fun `a where over a many of values with no alias stays refused`() {
        val diags = Validator.validate(Parser.parse("""
            act Field {
                name: text
                options: many text
                named = count(options where name == "x")
            }
        """.trimIndent())).map { it.toString() }
        assertEquals(1, diags.size, diags.toString())
        assertTrue("'options' is a collection of values, not of records" in diags.single(), diags.single())
    }
}
