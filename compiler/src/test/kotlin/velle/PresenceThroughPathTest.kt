package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `a test on a plain-dot path is itself a plain dot through an untested optional`() {
        val diags = diagnostics(withName("""siteName: text = if site.name is some then site.name else "none""""))
        assertEquals(1, diags.size, diags.toString())
        assertTrue(diags.single().startsWith("[V11] 'site.name' reads through 'site', which may be absent"), diags.single())
        assertTrue("write 'site?.name'" in diags.single(), diags.single())
    }

    @Test
    fun `V11 - a plain dot through an untested optional is refused, naming the step and both spellings`() {
        val diags = diagnostics("""
            act Entry {
                key: text unique
                site: one Site?
                inNorth: boolean = site.region.name == "north"
            }
            act Site {
                region: one Region?
            }
            act Region {
                name: text
            }
        """)
        assertEquals(listOf("[V11] 'site.region.name' reads through 'site', which may be absent; " +
            "test 'site is some' first, or write 'site?.region.name' to read absent when it is (Reference §10)"), diags)
    }

    @Test
    fun `V11 - a question-dot through the optional step reads absent and needs no test`() {
        assertEquals(emptyList(), diagnostics(withName("""siteName: text? = site?.name""")))
        assertEquals(emptyList(), diagnostics("""
            act Entry {
                key: text unique
                site: one Site?
                regionName: text? = site?.region?.name
            }
            act Site {
                region: one Region?
            }
            act Region {
                name: text
            }
        """))
    }

    @Test
    fun `V11 - a conjunction, a refinement's where, and a rule's inline where each license the dots that follow`() {
        assertEquals(emptyList(), diagnostics("""
            act Entry {
                key: text unique
                site: one Site?
                named: boolean = site is some and site.name is some and site.name == "x"
            }
            act Site {
                name: text?
            }
            shape Sited = Entry where site?.name is some
            never (Sited where site.name == "forbidden")
            shape Flag {
                entry: one Entry
                siteName: text
            }
            rule FlagSite when (Entry where site?.name is some) {
                Flag from { entry: this, siteName: site.name }
            }
        """))
    }

    @Test
    fun `V11 - a path from an as-alias is checked and licensed under the alias`() {
        val spec = """
            act Reading {
                site: one Site?
                value: text
            }
            act Site {
                name: text?
            }
            act Report {
                key: text unique
                readings: many Reading
                named: integer = count(readings as r where r.site?.name is some and r.site.name == "x")
                unnamed: integer = count(readings as r where r.site.name == "x")
            }
        """
        val diags = diagnostics(spec)
        assertEquals(1, diags.size, diags.toString())
        assertTrue(diags.single().startsWith("[V11] 'r.site.name' reads through 'r.site', which may be absent"), diags.single())
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

    // ── a comparison that holds, and the right side of an `or` (Reference §10) ───────────────

    private val valueWithAttr = """
        act Attr {
            kind: text?
            weight: integer?
            relations: many text
            hasUnits: boolean
        }
        act Value {
            attr: one Attr?
            x: text
            units: text?
            PROPERTY
        }
        PROHIBITION
    """

    private fun withProperty(declaration: String) = valueWithAttr.replace("PROPERTY", declaration).replace("PROHIBITION", "")

    private fun withProhibition(prohibition: String) = valueWithAttr.replace("PROPERTY", "").replace("PROHIBITION", prohibition)

    private fun v11ThroughAttr(spec: String) {
        val diags = diagnostics(spec)
        assertEquals(1, diags.size, diags.toString())
        assertTrue(diags.single().startsWith("[V11] '") && "reads through 'attr', which may be absent" in diags.single(), diags.single())
    }

    @Test
    fun `an equality on a question-dot path holding shows every step present in the rest of the conjunction`() {
        assertEquals(emptyList(), diagnostics(withProhibition("""never (Value where attr?.kind == "relation" and x not in attr.relations)""")))
    }

    @Test
    fun `an in test and an ordered comparison on a question-dot path holding show the step present`() {
        assertEquals(emptyList(), diagnostics(withProhibition("""never (Value where attr?.kind in ["relation", "link"] and x not in attr.relations)""")))
        assertEquals(emptyList(), diagnostics(withProhibition("""never (Value where attr?.weight > 3 and not attr.hasUnits)""")))
    }

    @Test
    fun `an equality holding shows the step present in the then branch and nothing in the else branch`() {
        assertEquals(emptyList(), diagnostics(withProperty("""linked: boolean = if attr?.kind == "relation" then attr.hasUnits else false""")))
        v11ThroughAttr(withProperty("""linked: boolean = if attr?.kind == "relation" then false else attr.hasUnits"""))
    }

    @Test
    fun `an inequality or a not-in holding shows nothing, since both hold on an absent value`() {
        v11ThroughAttr(withProhibition("""never (Value where attr?.kind != "relation" and x not in attr.relations)"""))
        v11ThroughAttr(withProhibition("""never (Value where attr?.kind not in ["relation"] and x not in attr.relations)"""))
    }

    @Test
    fun `an equality with a side that may be absent shows nothing, since two absent values compare equal`() {
        v11ThroughAttr(withProhibition("""never (Value where attr?.kind == units and not attr.hasUnits)"""))
        assertEquals(emptyList(), diagnostics(withProhibition("""never (Value where attr?.kind == x and not attr.hasUnits)""")))
    }

    @Test
    fun `an inequality failing is the equality holding, so the else branch reads the step`() {
        assertEquals(emptyList(), diagnostics(withProperty("""linked: boolean = if attr?.kind != "relation" then false else attr.hasUnits""")))
    }

    @Test
    fun `the right side of an or is read where its left side failed`() {
        assertEquals(emptyList(), diagnostics(withProhibition("""never (Value where units is some and (attr is none or not attr.hasUnits))""")))
    }

    @Test
    fun `the right side of an or after a presence test is refused, since it runs where the step is absent`() {
        v11ThroughAttr(withProhibition("""never (Value where units is some and (attr is some or attr.hasUnits))"""))
    }

    @Test
    fun `the right side of an or after a union test reads the other operand`() {
        assertEquals(emptyList(), diagnostics("""
            act Member { name: text }
            act Post { author: one Member, body: text }
            act Photo { author: one Member, caption: text }
            shape Content = Post or Photo
            act Comment { target: one Content, body: text }
            shape Captioned = Comment where target is Post or target.caption != ""
        """))
    }

    @Test
    fun `at run time the right side of an or is not evaluated where its left side held`() {
        val sys = VelleSystem(Model(Parser.parse(withProhibition("""never (Value where units is some and (attr is none or not attr.hasUnits))""").trimIndent())))
        sys.scope {
            val unitless = assertIs<CommitResult.Accepted>(sys.commit("Attr", mapOf("hasUnits" to false, "relations" to emptyList<String>()))).id
            val withUnits = assertIs<CommitResult.Accepted>(sys.commit("Attr", mapOf("hasUnits" to true, "relations" to emptyList<String>()))).id
            assertIs<CommitResult.Refused>(sys.commit("Value", mapOf("x" to "1", "units" to "kg")))
            assertIs<CommitResult.Refused>(sys.commit("Value", mapOf("attr" to unitless, "x" to "1", "units" to "kg")))
            assertIs<CommitResult.Accepted>(sys.commit("Value", mapOf("attr" to withUnits, "x" to "1", "units" to "kg")))
            assertIs<CommitResult.Accepted>(sys.commit("Value", mapOf("x" to "1")))
        }
    }
}
