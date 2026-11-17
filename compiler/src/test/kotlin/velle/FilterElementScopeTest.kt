package velle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A bare name inside a filter's `where` is a member of the filter's element, and `this` is the
 * outer subject (Reference §10). When the two shapes differ, the type reading used by F2 and V11
 * resolves the bare name on the element, not on the subject: `this in inheritedBy` where
 * `inheritedBy` is a collection on the element, and `name starts with "a"` where `name` is the
 * element's text and the subject has a number of the same name.
 */
class FilterElementScopeTest {

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long =
        when (val result = commit(shape, mapOf(*fields))) {
            is CommitResult.Accepted -> result.id
            is CommitResult.Refused -> error(result.reason)
        }

    private val membershipSpec = """
        act Schema {
            name: text
            attributes: many Attr = (Attr where schema == this or this in inheritedBy)
        }
        act Attr {
            schema: one Schema
            name: text
            inheritedBy: many Schema = (Schema where name == this.name)
        }
    """.trimIndent()

    @Test
    fun `this in a collection on the element validates when the element is another shape`() {
        assertEquals(emptyList(), Validator.validate(Parser.parse(membershipSpec)).map { it.toString() })
    }

    @Test
    fun `a text operator on the element's field validates when the subject has a number by that name`() {
        val spec = """
            act Account {
                name: integer
                tags: many Tag = (Tag where name starts with "a")
            }
            act Tag { name: text }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(Parser.parse(spec)).map { it.toString() })
    }

    @Test
    fun `the schema an attribute is inherited by holds the attribute`() {
        val sys = VelleSystem(Model(Parser.parse(membershipSpec)))
        sys.scope {
            val owning = sys.mustCommit("Schema", "name" to "owner")
            val inheriting = sys.mustCommit("Schema", "name" to "heir")
            val unrelated = sys.mustCommit("Schema", "name" to "other")
            val attribute = sys.mustCommit("Attr", "schema" to owning, "name" to "heir")
            assertEquals(listOf(attribute), sys.get(owning, "attributes"))
            assertEquals(listOf(attribute), sys.get(inheriting, "attributes"))
            assertEquals(emptyList<Long>(), sys.get(unrelated, "attributes"))
        }
    }
}
