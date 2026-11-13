package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A prohibition the rules cannot move is a fact the compiler reads (Reference §21; checks.md
 * V10, V11): `never (Submission where parentLabel is some and parent is none)` shows `parent`
 * present wherever `parentLabel is some` is established, in a rule's inline `where`, a
 * refinement's `where`, or a governing test, so `parent.key` reads with a plain dot there.
 * A prohibition a rule can move, or one reading through a shape a rule deletes, shows nothing.
 */
class ConditionalPresenceTest {

    private fun diagnostics(spec: String): List<String> = Validator.validate(Parser.parse(spec.trimIndent())).map { it.toString() }

    private val entryAndSubmission = """
        act Entry {
            key: text unique
        }

        act Submission {
            parentLabel: text?
            parent: one Entry?
        }

        shape Link {
            submission: one Submission
            parentKey: text
        }
    """

    private fun spec(prohibition: String, rule: String) = entryAndSubmission + "\n" + prohibition + "\n" + rule

    private val linkOnLabelled = """
        rule LinkParent when (Submission where parentLabel is some) {
            Link from { submission: this, parentKey: parent.key }
        }
    """

    @Test
    fun `a never shows the field present where its condition is the rule's inline where`() {
        assertEquals(emptyList(), diagnostics(spec("never (Submission where parentLabel is some and parent is none)", linkOnLabelled)))
    }

    @Test
    fun `a requires is read through the never it becomes`() {
        assertEquals(emptyList(), diagnostics(spec("Submission requires parentLabel is none or parent is some", linkOnLabelled)))
    }

    @Test
    fun `a rule that does not establish the condition is refused as today`() {
        val diags = diagnostics(spec("never (Submission where parentLabel is some and parent is none)", """
            rule LinkParent when Submission {
                Link from { submission: this, parentKey: parent.key }
            }
        """))
        assertEquals(1, diags.size, diags.toString())
        assertTrue(diags.single().startsWith("[V11] 'parent.key' reads through 'parent', which may be absent"), diags.single())
    }

    @Test
    fun `a governing test and a refinement's where establish the condition too`() {
        assertEquals(emptyList(), diagnostics(spec("never (Submission where parentLabel is some and parent is none)", """
            shape Labelled = Submission where parentLabel is some {
                parentKey: text = parent.key
            }
            shape Note {
                submission: one Submission
                parentKey: text
            }
            rule NoteParent when Submission {
                Note from { submission: this, parentKey: if parentLabel is some then parent.key else "none" }
            }
        """)))
    }

    @Test
    fun `a never over a refinement with no other condition shows the field present within it`() {
        assertEquals(emptyList(), diagnostics(spec("""
            shape Labelled = Submission where parentLabel is some
            never (Labelled where parent is none)
        """, """
            shape Reviewed = Labelled where parentLabel == "reviewed"
            rule LinkParent when Reviewed {
                Link from { submission: this, parentKey: parent.key }
            }
        """)))
    }

    @Test
    fun `a never a rule can move shows nothing`() {
        val diags = diagnostics(spec("never (Submission where parentLabel is some and parent is none)", linkOnLabelled + """
            act Unlink {
                submission: one Submission
            }
            rule Unlink when Unlink {
                submission.parent = none
            }
        """))
        assertTrue(diags.any { it.startsWith("[V11] 'parent.key' reads through 'parent'") }, diags.toString())
    }

    @Test
    fun `a never reading through a shape a rule deletes shows nothing`() {
        val diags = diagnostics(entryAndSubmission + """
            act Region {
                name: text
            }
            act Site {
                region: one Region?
                regionLabel: text?
            }
            never (Site where regionLabel is some and region.name is none)
            shape Tag {
                site: one Site
                regionName: text
            }
            rule TagSite when (Site where regionLabel is some) {
                Tag from { site: this, regionName: region.name }
            }
            act Retire {
                region: one Region
            }
            rule RetireRegion when Retire {
                delete region
            }
        """)
        assertTrue(diags.any { it.startsWith("[V11] 'region.name' reads through 'region'") }, diags.toString())
    }

    @Test
    fun `the key filter compares with a value a never shows present`() {
        assertEquals(emptyList(), diagnostics("""
            act Entry {
                key: text unique
            }
            act Submission {
                parentLabel: text?
                parentKey: text?
            }
            never (Submission where parentLabel is some and parentKey is none)
            never (Submission where parentLabel is some and not exists (Entry where key == this.parentKey))
            shape Link {
                submission: one Submission
                parent: one Entry
            }
            rule LinkParent when (Submission where parentLabel is some) {
                Link from { submission: this, parent: (Entry where key == this.parentKey) }
            }
        """))
    }
}
