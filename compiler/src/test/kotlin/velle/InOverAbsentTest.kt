package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `x in coll` where `coll` is absent (Reference §10, "Membership"): nothing is in an absent
 * collection, as an absent value is in nothing. The right side here is the `options` of a
 * `first` that may match no record.
 */
class InOverAbsentTest {

    private val spec = """
        act Field {
            name: text
            options: many text
        }

        act Reading {
            fieldName: text
            value: text?
            allowed: boolean = value in first(Field where name == this.fieldName by name).options
        }

        never (Reading where value is some
            and not (value in first(Field where name == this.fieldName by name).options))
    """.trimIndent()

    private fun system(): VelleSystem {
        val decls = Parser.parse(spec)
        assertEquals(emptyList(), Validator.validate(decls))
        return VelleSystem(Model(decls))
    }

    @Test
    fun `a value among the field's options passes, one outside them is refused`() {
        val sys = system()
        sys.scope {
            assertIs<CommitResult.Accepted>(sys.commit("Field", mapOf("name" to "color", "options" to listOf("red", "blue"))))
            val accepted = assertIs<CommitResult.Accepted>(sys.commit("Reading", mapOf("fieldName" to "color", "value" to "red")))
            assertEquals(true, sys.get(accepted.id, "allowed"))
            val refused = assertIs<CommitResult.Refused>(sys.commit("Reading", mapOf("fieldName" to "color", "value" to "green")))
            assertTrue("Reading" in refused.reason, refused.reason)
        }
    }

    @Test
    fun `a value whose field does not exist is in nothing, and the prohibition refuses it`() {
        val sys = system()
        sys.scope {
            val refused = assertIs<CommitResult.Refused>(sys.commit("Reading", mapOf("fieldName" to "size", "value" to "large")))
            assertTrue("Reading" in refused.reason, refused.reason)
            val unanswered = assertIs<CommitResult.Accepted>(sys.commit("Reading", mapOf("fieldName" to "size")))
            assertEquals(false, sys.get(unanswered.id, "allowed"))
        }
    }
}
