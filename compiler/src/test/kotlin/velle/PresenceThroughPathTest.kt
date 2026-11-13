package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A presence test on a path shows every step of the path present (Reference §10,
 * "narrowing"): `site?.name is some` holding means `site` holds a record and that
 * record's `name` holds a text, so the governed branch reads `site.name` with plain
 * dots, and a key filter may compare with `this.site.code` there (checks V36).
 */
class PresenceThroughPathTest {

    private fun diagnostics(spec: String): List<String> = Validator.validate(Parser.parse(spec.trimIndent())).map { it.toString() }

    private val entryWithSite = """
        act Entry {
            key: text unique
            site: one Site?
            NAME
        }
        act Site {
            name: text?
            code: text?
        }
    """

    private fun withName(declaration: String) = entryWithSite.replace("NAME", declaration)

    @Test
    fun `a test through a question-dot path shows every step present in the then branch`() {
        assertEquals(emptyList(), diagnostics(withName("""siteName: text = if site?.name is some then site.name else "none"""")))
    }

    @Test
    fun `a test for absence shows every step present in the else branch`() {
        assertEquals(emptyList(), diagnostics(withName("""siteName: text = if site?.name is none then "none" else site.name""")))
    }

    @Test
    fun `a test on a plain-dot path shows its steps present the same way`() {
        assertEquals(emptyList(), diagnostics(withName("""siteName: text = if site.name is some then site.name else "none"""")))
    }

    @Test
    fun `a test three steps deep shows all three present`() {
        assertEquals(emptyList(), diagnostics("""
            act Entry {
                key: text unique
                site: one Site?
                regionName: text = if site?.region?.name is some then site.region.name else "none"
            }
            act Site {
                region: one Region?
            }
            act Region {
                name: text?
            }
        """))
    }

    @Test
    fun `a test on a sibling path shows nothing about the path read`() {
        val diags = diagnostics(withName("""siteCode: text = if site?.name is some then site.code else "none""""))
        assertEquals(1, diags.size, diags.toString())
        assertTrue("may be absent" in diags.single(), diags.single())
    }

    @Test
    fun `a refinement's test through a path makes the compared key value present in the rule's body`() {
        assertEquals(emptyList(), diagnostics("""
            act Plot {
                code: text unique
            }
            act Site {
                code: text?
            }
            act Submission {
                site: one Site?
            }
            shape Placement {
                submission: one Submission
                plot: one Plot
            }
            shape Sited = Submission where site?.code is some and not exists Placement for this
            never (Sited where not exists (Plot where code == this.site.code))
            rule Place when Sited {
                Placement from { submission: this, plot: (Plot where code == this.site.code) }
            }
        """))
    }
}
