package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The agreed spelling of a condition (D8 of the comparison of two specs): each fact it reasons
 * from gives two spellings the same agreed spelling, and the runtime agrees that the two mean
 * the same, over records whose values are present and absent. The one rewrite it refuses to make,
 * an ordered comparison traded for its negation, is pinned too, with the record that tells them apart.
 */
class AgreedSpellingTest {

    /** Two spellings of a condition over [act]. */
    private class Rewrite(val fact: String, val act: String, val left: String, val right: String)

    private val rewrites = listOf(
        Rewrite("the sides of and may be swapped", "Maybe", "a > 0 and b > 0", "b > 0 and a > 0"),
        Rewrite("the sides of or may be swapped", "Maybe", "a > 0 or b > 0", "b > 0 or a > 0"),
        Rewrite("not not P is P", "Maybe", "not (not (a > 0))", "a > 0"),
        Rewrite("not (P and Q) is not P or not Q", "Maybe", "not (a > 0 and b > 0)", "not (a > 0) or not (b > 0)"),
        Rewrite("an ordering is one comparator", "Maybe", "a > b", "b < a"),
        Rewrite("equality is one function", "Maybe", "a == b", "b == a"),
        Rewrite("the sides of + on numbers may be swapped", "Sure", "a + b > c", "b + a > c"),
        Rewrite("grouping of + on numbers does not matter", "Sure", "(a + b) + c > 0", "a + (b + c) > 0"),
        Rewrite("the sides of * on numbers may be swapped", "Sure", "a * b > c", "b * a > c"),
        Rewrite("max takes its arguments in any order", "Sure", "max(a, b) > c", "max(b, a) > c"),
        Rewrite("grouping of max does not matter", "Sure", "max(max(a, b), c) > 0", "max(a, max(b, c)) > 0"),
        Rewrite("not (P or Q) is not P and not Q", "Maybe", "not (a > 0 or b > 0)", "not (a > 0) and not (b > 0)"),
        Rewrite("a rewrite inside an exists", "Maybe", "exists (Sure where a > 0 and b > 0)", "exists (Sure where b > 0 and a > 0)"),
        Rewrite("a rewrite inside an exists that names its record", "Maybe", "exists (Sure as other where other.a > 0 and other.b > 0)", "exists (Sure as other where other.b > 0 and other.a > 0)"),
        Rewrite("an extension function states its arguments may be given in any order", "Sure", "larger(a, b) > c", "larger(b, a) > c"),
    )

    /** Two spellings that mean different things, which no fact may make one. */
    private val differences = listOf(
        Rewrite("grouping is never carried across two operators", "Sure", "a * b + c > 0", "a + b + c > 0"),
        Rewrite("an ordered comparison is not its negation", "Maybe", "not (a < b)", "a >= b"),
    )

    private val acts = """
        act Maybe {
            a: decimal?
            b: decimal?
        }

        act Sure {
            a: decimal
            b: decimal
            c: decimal
        }
    """.trimIndent()

    private fun specOf(pairs: List<Pair<String, String>>, act: (Int) -> String): String =
        acts + "\n\n" + pairs.withIndex().joinToString("\n") { (index, pair) ->
            "shape Left$index = ${act(index)} where ${pair.first}\nshape Right$index = ${act(index)} where ${pair.second}"
        }

    private val spec = specOf(rewrites.map { rewrite -> rewrite.left to rewrite.right }) { index -> rewrites[index].act }

    private fun agreed(version: SpecVersion, name: String): String {
        val refinement = version.declarations.map { located -> located.decl }
            .filterIsInstance<RefinementDecl>().single { declaration -> declaration.name == name }
        return Printer.refExpr((AgreedSpelling(version).of(refinement) as RefinementDecl).expr)
    }

    private val maybeValues = listOf(null, BigDecimal("-1"), BigDecimal("0"), BigDecimal("2"))
    private val sureValues = listOf(BigDecimal("-1"), BigDecimal("0"), BigDecimal("2"))

    private fun maybeRecords(): List<Map<String, Any?>> =
        maybeValues.flatMap { a -> maybeValues.map { b -> mapOf("a" to a, "b" to b).filterValues { value -> value != null } } }

    private fun sureRecords(): List<Map<String, Any?>> =
        sureValues.flatMap { a -> sureValues.flatMap { b -> sureValues.map { c -> mapOf("a" to a, "b" to b, "c" to c) } } }

    @Test
    fun `each fact gives both spellings one agreed spelling`() {
        assertEquals(emptyList(), Validator.validate(spec))
        val version = SpecVersion("facts.velle", spec)
        for ((index, rewrite) in rewrites.withIndex()) {
            assertEquals(agreed(version, "Left$index"), agreed(version, "Right$index"), rewrite.fact)
        }
    }

    @Test
    fun `two spellings that mean different things keep different agreed spellings`() {
        val differenceSpec = specOf(differences.map { difference -> difference.left to difference.right }) { index -> differences[index].act }
        assertEquals(emptyList(), Validator.validate(differenceSpec))
        val version = SpecVersion("differences.velle", differenceSpec)
        for ((index, difference) in differences.withIndex()) {
            assertNotEquals(agreed(version, "Left$index"), agreed(version, "Right$index"), difference.fact)
        }
    }

    @Test
    fun `a rewrite that rests on an extension function's claim names the library and the claim`() {
        val version = SpecVersion("claims.velle", spec)
        val index = rewrites.indexOfFirst { rewrite -> rewrite.left == "larger(a, b) > c" }
        val reordered = version.declarations.map { located -> located.decl }
            .filterIsInstance<RefinementDecl>().single { declaration -> declaration.name == "Right$index" }
        val spelling = AgreedSpelling(version)
        spelling.of(reordered)
        assertEquals(setOf("`velle.test` is right that `larger` takes its arguments in any order and in any grouping"), spelling.claimsReliedOn)

        val builtin = version.declarations.map { located -> located.decl }
            .filterIsInstance<RefinementDecl>().single { declaration -> declaration.name == "Right${rewrites.indexOfFirst { rewrite -> rewrite.left == "max(a, b) > c" }}" }
        val builtinSpelling = AgreedSpelling(version)
        builtinSpelling.of(builtin)
        assertEquals(emptySet(), builtinSpelling.claimsReliedOn, "a claim of Velle's own functions is not named")
    }

    @Test
    fun `the runtime agrees that both spellings mean the same, over present and absent values`() {
        val system = VelleSystem(Model(Parser.parse(spec)))
        system.scope {
            val committed = maybeRecords().map { fields -> "Maybe" to idOf(system.commit("Maybe", fields)) } +
                sureRecords().map { fields -> "Sure" to idOf(system.commit("Sure", fields)) }
            for ((index, rewrite) in rewrites.withIndex()) {
                for ((act, id) in committed.filter { (act, _) -> act == rewrite.act }) {
                    assertEquals(system.isMember(id, "Left$index"), system.isMember(id, "Right$index"), "${rewrite.fact}, record $id of $act")
                }
            }
        }
    }

    @Test
    fun `an ordered comparison is never traded for its negation, since an absent value meets neither`() {
        val negationSpec = specOf(listOf("not (a < b)" to "a >= b")) { "Maybe" }
        val version = SpecVersion("negation.velle", negationSpec)
        assertNotEquals(agreed(version, "Left0"), agreed(version, "Right0"))

        val system = VelleSystem(Model(Parser.parse(negationSpec)))
        system.scope {
            val withoutA = idOf(system.commit("Maybe", mapOf("b" to BigDecimal("1"))))
            assertEquals(true, system.isMember(withoutA, "Left0"), "not (a < b) holds when a is absent")
            assertEquals(false, system.isMember(withoutA, "Right0"), "a >= b does not")
        }
    }

    private fun idOf(result: CommitResult): Long = assertIs<CommitResult.Accepted>(result).id
}
