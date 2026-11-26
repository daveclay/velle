package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The form a part with no words is held back by, as the work list in
 * `src/test/resources/describe/without-words.md` names it: the smallest expression with no
 * words while everything inside it has them.
 */
class FormsWithoutWordsTest {

    private val subject = Sentences.Subject.described

    private fun formOf(condition: String): String? = FormsWithoutWords.ofCondition(Parser.parsePredicate(condition), subject)

    @Test
    fun `a condition with words has no form to name`() {
        assertNull(formOf("balance > 0 and due < today"))
        assertNull(formOf("exists (fills where fillNumber <= 3)"))
    }

    @Test
    fun `the form named is the smallest part with no words`() {
        assertEquals("`is held`", formOf("seller is held"))
        assertEquals("`is held`", formOf("balance > 0 and seller is held"))
        assertEquals("`is held`", formOf("not (seller is held)"))
        assertEquals("the operator `matches`", formOf("sku matches pattern"))
        assertEquals("`is held`", formOf("count(fills where seller is held) > 1"))
    }

    @Test
    fun `each kind of collection without words is named for what is missing`() {
        assertEquals("`exists` over a collection named with `as`", formOf("exists (fills as fill where fill.number > 1)"))
        assertEquals("`exists` over several collections", formOf("exists (fills as fill, claims as claim where fill.number == claim.number)"))
        assertEquals("`count` over a collection named with `as`", formOf("count(fills as fill where fill.number > 1) > 0"))
        assertEquals("`walk ... until`", formOf("(walk referrer until referrer is none) is some"))
    }

    @Test
    fun `a refinement expression names the form inside its where`() {
        val refinement = RefAnd(RefName("Fill"), RefName("Fill", Parser.parsePredicate("seller is held")))

        assertEquals("`is held`", FormsWithoutWords.of(refinement, setOf("Fill"), subject))
        assertNull(FormsWithoutWords.of(RefName("Fill", Parser.parsePredicate("quantity > 0")), setOf("Fill"), subject))
    }
}
