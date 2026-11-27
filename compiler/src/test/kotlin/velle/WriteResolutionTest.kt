package velle

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the validator says of an assignment's target, one test per way a target fails to name a field (Reference §6). */
class WriteResolutionTest {

    private val library = """
        act Branch {
            name: text
            shelves: many Shelf
            tags: many text
        }

        act Shelf {
            label: text
            books: many Book
        }

        act Book { title: text }

        act Member {
            name: text
            branch: one Branch
        }

        transient act Rename { member: one Member, name: text }
    """.trimIndent()

    private fun messagesOf(body: String): List<String> =
        Validator.validate("$library\n\nrule Write when Rename {\n    $body\n}").map { diagnostic -> diagnostic.toString() }
            .filter { message -> "rule 'Write'" in message }

    @Test
    fun `a target through a reference writes the field it names`() {
        assertEquals(emptyList(), messagesOf("member.branch.name = name"))
    }

    @Test
    fun `a target through a collection at its last hop writes the field of each record`() {
        assertEquals(emptyList(), messagesOf("member.branch.shelves.label = name"))
    }

    @Test
    fun `a target whose first name is no member of the act is refused`() {
        assertEquals(listOf("[F1] rule 'Write' assigns through unknown member 'reader'"), messagesOf("reader.name = name"))
    }

    @Test
    fun `a target whose later name is no member says which shape it was looked for on`() {
        assertEquals(listOf("[F1] rule 'Write' assigns through unknown member 'city' of 'Branch'"), messagesOf("member.branch.city = name"))
    }

    @Test
    fun `a target that goes on through a collection is refused`() {
        val messages = messagesOf("member.branch.shelves.books.title = name")
        assertEquals(1, messages.size, messages.toString())
        assertEquals(true, messages.single().startsWith("[V20] rule 'Write' assigns through 'Branch.shelves' and onward"), messages.toString())
    }

    @Test
    fun `a target through a collection of values is refused`() {
        assertEquals(listOf("[V20] rule 'Write' assigns through scalar collection 'Branch.tags'"), messagesOf("member.branch.tags.size = name"))
    }

    @Test
    fun `a target that names a value and goes on is refused by the type check alone`() {
        assertEquals(emptyList(), messagesOf("member.name.first = name").filter { message -> "assigns" in message })
    }
}
