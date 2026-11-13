package velle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A8 (checks.md; Reference §21): a prohibition over a base shape that only forbids an
 * optional field absent on every record is the required declaration said the long way
 * round, and the compiler says so.
 */
class NeverMakesRequiredTest {

    private fun advisories(spec: String): List<String> = Validator.advisories(spec.trimIndent()).map { it.toString() }

    @Test
    fun `a never forbidding an optional field absent on every record draws A8 naming the declaration`() {
        assertEquals(listOf("[A8] never (Request where key is none) only makes 'key' required on every Request; declare 'key: text' and drop the prohibition"), advisories("""
            act Request {
                key: text?
            }
            never (Request where key is none)
        """))
    }

    @Test
    fun `a requires draws the same, and an or names each field`() {
        assertEquals(listOf(
            "[A8] Request requires (key is some) and (site is some) only makes 'key' and 'site' required on every Request; declare 'key: text' and 'site: one Site' and drop the requirement",
        ), advisories("""
            act Site {
                name: text
            }
            act Request {
                key: text?
                site: one Site?
            }
            Request requires key is some and site is some
        """))
    }

    @Test
    fun `a required field, a conditional prohibition, and one over a refinement draw nothing`() {
        assertEquals(emptyList(), advisories("""
            act Request {
                key: text
                label: text?
                site: text?
            }
            never (Request where key is none)
            never (Request where label is some and site is none)
            shape Labelled = Request where label is some
            never (Labelled where site is none)
        """))
    }
}
