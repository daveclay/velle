package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A selection, `first`, `latest`, or `(Shape for expr)`, where a value that is always there is
 * declared (Reference §7, §10, §14; checks.md F2, V11, A9, A10): a required derived or captured
 * property takes one only where a test shows its collection is not empty; a `from` block takes
 * one and leaves the miss to the runtime; a field or step declared optional is absent by
 * declaration after a selection as after any record; `?.` directly after a selection is the
 * plain dot.
 */
class SelectionPresenceTest {

    private val uploadAndRow = """
        shape Parent {
            name: text
        }
        act Upload {
            note: text?
            rows: many Row = (Row where upload == this)
            scores: many integer
        }
        act Row {
            upload: one Upload
            key: text
            newKey: text?
            parent: one Parent?
        }
        shape Kept {
            other: one Other
            label: text
        }
    """

    private fun diagnosticsOf(otherAndRules: String): List<Diagnostic> = Validator.validate(uploadAndRow + otherAndRules)

    private fun advisoriesOf(otherAndRules: String): List<Diagnostic> = Validator.advisories(uploadAndRow + otherAndRules)

    private fun assertRefused(code: String, messagePart: String, diagnostics: List<Diagnostic>) =
        assertTrue(diagnostics.any { diagnostic -> diagnostic.code == code && messagePart in diagnostic.message }, "expected $code with \"$messagePart\", got: $diagnostics")

    private fun assertAccepted(diagnostics: List<Diagnostic>) = assertEquals(emptyList(), diagnostics)

    // ── a required derived or captured property ──────────────────────────────

    @Test
    fun `a required derived property computed through a selection is refused`() {
        assertRefused("F2", "'Other.keyFromFirst' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = first(upload.rows by key).key
            }
        """))
    }

    @Test
    fun `a required derived property that is the selected record is refused`() {
        assertRefused("F2", "'Other.firstRow' is declared a Row and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                firstRow: one Row = first(upload.rows by key)
            }
        """))
    }

    @Test
    fun `a required derived property computed through the one related record is refused`() {
        assertRefused("F2", "'Other.keptLabel' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                keptLabel: text = (Kept for this).label
            }
        """))
    }

    @Test
    fun `an optional derived property computed through a selection is accepted`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keyOrNone: text? = first(upload.rows by key).key
            }
        """))
    }

    @Test
    fun `a required property of a type declared by its words is checked as any other`() {
        val verdicts = """
            Verdict is one of "approved", "declined"
            act Attempt {
                note: text?
                responses: many Response = (Response where attempt == this)
            }
            act Response {
                attempt: one Attempt
                outcome: Verdict
                earlier: Verdict?
                at: DateTime
            }
        """
        assertRefused("F2", "'Review.required' is declared a Verdict and may be absent", Validator.validate(verdicts + """
            act Review {
                response: one Response
                required: Verdict = response.earlier
            }
        """))
        assertRefused("F2", "'Answered.outcome' is declared a Verdict and may be absent", Validator.validate(verdicts + """
            shape Answered = Attempt where note is some {
                outcome: Verdict = latest(responses by at).outcome
            }
        """))
        assertAccepted(Validator.validate(verdicts + """
            shape Answered = Attempt where exists (responses) {
                outcome: Verdict = latest(responses by at).outcome
            }
        """))
    }

    // ── a test that the collection is not empty ──────────────────────────────

    @Test
    fun `the else branch of is empty shows a selection over the same collection present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = if upload.rows is empty then "none" else first(upload.rows by key).key
            }
        """))
    }

    @Test
    fun `is not empty shows a selection over the same collection present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = if upload.rows is not empty then latest(upload.rows by key).key else "none"
            }
        """))
    }

    @Test
    fun `exists in an if shows a selection over the same collection present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keptLabel: text = if exists Kept for this then (Kept for this).label else "none"
                latestLabel: text = if not exists Kept for this then "none" else latest(Kept for this by label).label
                keyed: text = if exists (Row where key == this.upload.note) then first(Row where key == this.upload.note by key).key else "none"
            }
        """))
    }

    @Test
    fun `a refinement's where shows a selection in its body present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
            }
            shape KeptOther = Other where exists Kept for this {
                keptLabel: text = latest(Kept for this by label).label
            }
        """))
    }

    @Test
    fun `a test of another collection shows nothing`() {
        assertRefused("F2", "'Other.keyFromFirst' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = if exists Kept for this then first(upload.rows by key).key else "none"
            }
        """))
    }

    @Test
    fun `the branch where the collection is empty shows nothing`() {
        assertRefused("F2", "'Other.keyFromFirst' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = if upload.rows is empty then first(upload.rows by key).key else "none"
            }
        """))
    }

    @Test
    fun `a test of the unfiltered collection shows nothing about a filtered selection`() {
        assertRefused("F2", "'Other.keyFromFirst' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = if upload.rows is empty then "none" else first(upload.rows where newKey is some by key).key
            }
        """))
    }

    @Test
    fun `max over a collection shown not empty is present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                best: integer = if upload.scores is empty then 0 else max(upload.scores)
                least: integer = if upload.scores is not empty then min(upload.scores) else 0
            }
        """))
    }

    @Test
    fun `max over a collection not shown not empty is refused`() {
        assertRefused("F2", "'Other.best' is declared an integer and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                best: integer = max(upload.scores)
            }
        """))
    }

    @Test
    fun `a property that states no type takes a required type from a selection shown present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst = if upload.rows is empty then "none" else first(upload.rows by key).key
                keyAgain: text = keyFromFirst
            }
        """))
    }

    // ── a from block and an assignment ───────────────────────────────────────

    @Test
    fun `a from block takes a selection into a required field`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
            }
            shape FirstRow {
                other: one Other
                row: one Row
            }
            rule Keep when Other {
                Kept from { other: this, label: first(upload.rows by key).key }
                FirstRow from { other: this, row: first(upload.rows by key) }
            }
        """))
    }

    @Test
    fun `a question mark directly after a selection reads as the plain dot`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keyOrNone: text? = first(upload.rows by key)?.key
            }
            rule Keep when Other {
                Kept from { other: this, label: first(upload.rows by key)?.key }
            }
        """))
    }

    // ── a field or step declared optional, after a selection ─────────────────

    @Test
    fun `a from block refuses a field declared optional read off a selected record`() {
        assertRefused("F2", "declared a text, given a value that may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
            }
            rule Keep when Other {
                Kept from { other: this, label: first(upload.rows by key).newKey }
            }
        """))
    }

    @Test
    fun `a selection shown present still refuses a field declared optional`() {
        assertRefused("F2", "'Other.newKeyFromFirst' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                newKeyFromFirst: text = if upload.rows is empty then "none" else first(upload.rows by key).newKey
            }
        """))
    }

    @Test
    fun `a plain dot through an optional step after a selection is refused`() {
        assertRefused("V11", "'first(upload.rows by key).parent.name' reads through 'first(upload.rows by key).parent', which may be absent; " +
            "test 'first(upload.rows by key).parent is some' first, or write 'first(upload.rows by key).parent?.name'", diagnosticsOf("""
            act Other {
                upload: one Upload
                parentName: text? = first(upload.rows by key).parent.name
            }
        """))
    }

    @Test
    fun `a question mark after an optional step after a selection reads absent`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                parentName: text? = first(upload.rows by key).parent?.name
            }
        """))
        assertRefused("F2", "'Other.parentName' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                parentName: text = if upload.rows is empty then "none" else first(upload.rows by key).parent?.name
            }
        """))
    }

    @Test
    fun `an is some test on a read through a selection shows the same read present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                newKeyFromFirst: text = if first(upload.rows by key).newKey is some then first(upload.rows by key).newKey else "none"
                parentName: text = if first(upload.rows by key).parent is some then first(upload.rows by key).parent.name else "none"
            }
            rule Keep when (Other where first(upload.rows by key).newKey is some) {
                Kept from { other: this, label: first(upload.rows by key).newKey }
            }
        """))
    }

    @Test
    fun `an is some test on one selection shows nothing about another`() {
        assertRefused("F2", "declared a text, given a value that may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
            }
            rule Keep when (Other where first(upload.rows by key).newKey is some) {
                Kept from { other: this, label: latest(upload.rows by key).newKey }
            }
        """))
    }

    @Test
    fun `an is some test on a selection shows it present`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
                keyFromFirst: text = if first(upload.rows by key) is some then first(upload.rows by key).key else "none"
                keptLabel: text = if (Kept for this) is none then "none" else (Kept for this).label
            }
        """))
    }

    @Test
    fun `a selection over a filtered collection carries what the filter shows`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
            }
            rule Keep when Other {
                Kept from { other: this, label: first(upload.rows where newKey is some by key).newKey }
            }
        """))
    }

    // ── the advisories ───────────────────────────────────────────────────────

    private val afterCommitLookup = """
        act Other {
            upload: one Upload
        }
        shape Unkept = Other where not exists Kept for this
    """

    @Test
    fun `an after commit rule filling a required field with a selection draws A9`() {
        val advisories = advisoriesOf(afterCommitLookup + """
            rule Keep when Unkept after commit, Hourly {
                Kept from { other: this, label: first(upload.rows by key).key }
            }
        """)
        assertRefused("A9", "rule 'Keep'", advisories)
    }

    @Test
    fun `a scheduled rule filling a required field with a selection draws A9`() {
        val advisories = advisoriesOf(afterCommitLookup + """
            rule Keep when Unkept on Hourly {
                Kept from { other: this, label: first(upload.rows by key).key }
            }
        """)
        assertRefused("A9", "rule 'Keep'", advisories)
    }

    @Test
    fun `the rule's condition showing the selection matches silences A9`() {
        val advisories = advisoriesOf(afterCommitLookup + """
            rule Keep when (Unkept where exists (upload.rows)) after commit, Hourly {
                Kept from { other: this, label: first(upload.rows by key).key }
            }
        """)
        assertEquals(emptyList(), advisories.filter { advisory -> advisory.code == "A9" })
    }

    @Test
    fun `A9 finds the selection inside a call, arithmetic, and a branch`() {
        val advisories = advisoriesOf(afterCommitLookup + """
            shape Tally {
                other: one Other
                size: integer
                best: integer
                label: text
            }
            shape Untallied = Other where not exists Tally for this
            rule Count when Untallied after commit, Hourly {
                Tally from {
                    other: this,
                    size: length(first(upload.rows by key).key) + 1,
                    best: -length(latest(upload.rows by key).key),
                    label: if upload.note is some then lowercase((Kept for this).label) else "none"
                }
            }
        """).filter { advisory -> advisory.code == "A9" }.map { advisory -> advisory.message }
        assertEquals(3, advisories.size, "$advisories")
        assertTrue(advisories.any { message -> "'first(upload.rows by key)' may match nothing" in message && "'exists (upload.rows)'" in message }, "$advisories")
        assertTrue(advisories.any { message -> "'latest(upload.rows by key)' may match nothing" in message }, "$advisories")
        assertTrue(advisories.any { message -> "'(Kept for this)' may match nothing" in message && "'exists Kept for this'" in message }, "$advisories")
    }

    @Test
    fun `a filter inside another shape's filter keeps its own relationship apart from the subject's`() {
        assertRefused("F2", "'Other.keyFromFirst' is declared a text and may be absent", diagnosticsOf("""
            act Other {
                upload: one Upload
                rows: many Row = (Row where upload == this.upload)
                keyFromFirst: text = if exists (Upload where rows is not empty) then first(rows by key).key else "none"
            }
        """))
    }

    @Test
    fun `a rule inside the act's transaction draws no A9`() {
        val advisories = advisoriesOf(afterCommitLookup + """
            rule Keep when Unkept {
                Kept from { other: this, label: first(upload.rows by key).key }
            }
        """)
        assertEquals(emptyList(), advisories.filter { advisory -> advisory.code == "A9" })
    }

    @Test
    fun `a question mark directly after a selection draws A10`() {
        val advisories = advisoriesOf("""
            act Other {
                upload: one Upload
                keyOrNone: text? = first(upload.rows by key)?.key
            }
        """)
        assertRefused("A10", "write 'first(upload.rows by key).key'", advisories)
    }

    @Test
    fun `a question mark after an optional step draws no A10`() {
        val advisories = advisoriesOf("""
            act Other {
                upload: one Upload
                keyOrNone: text? = first(upload.rows by key).key
                parentName: text? = first(upload.rows by key).parent?.name
            }
        """)
        assertEquals(emptyList(), advisories.filter { advisory -> advisory.code == "A10" })
    }

    // ── a required stored field started by `initially` ───────────────────────

    private val summaryOfAnUpload = """
        act Other {
            upload: one Upload
        }
        rule Summarize when Other {
            Summary from { upload: upload }
        }
    """

    @Test
    fun `a required initially through a selection is refused`() {
        val diagnostics = diagnosticsOf(summaryOfAnUpload + """
            shape Summary {
                upload: one Upload
                firstKey: text initially first(upload.rows by key).key
            }
        """)
        assertRefused("F2", "'Summary.firstKey' is declared a text and may start absent: 'first(upload.rows by key)' may match nothing; " +
            "declare it 'firstKey: text?', or start it where a test shows the selection matches, such as 'exists (upload.rows)' (Reference §10)", diagnostics)
        assertEquals(1, diagnostics.size, "$diagnostics")
    }

    @Test
    fun `a required initially with the selection under a test is accepted`() {
        assertAccepted(diagnosticsOf(summaryOfAnUpload + """
            shape Summary {
                upload: one Upload
                firstKey: text initially if exists (upload.rows) then first(upload.rows by key).key else "none"
            }
        """))
    }

    @Test
    fun `an optional initially through a selection is accepted`() {
        assertAccepted(diagnosticsOf(summaryOfAnUpload + """
            shape Summary {
                upload: one Upload
                firstKey: text? initially first(upload.rows by key).key
            }
        """))
    }

    @Test
    fun `the selection written in the from block of the creating rule is accepted`() {
        assertAccepted(diagnosticsOf("""
            act Other {
                upload: one Upload
            }
            shape Summary {
                upload: one Upload
                firstKey: text
            }
            rule Summarize when Other {
                Summary from { upload: upload, firstKey: first(upload.rows by key).key }
            }
        """))
    }

    // ── an `initially` reading the records that point at the record being created ──

    private val summaryWithLines = summaryOfAnUpload + """
        shape Line {
            summary: one Summary
            body: text
        }
    """

    private fun ownCollectionAdvisories(summary: String): List<String> =
        advisoriesOf(summaryWithLines + summary).filter { advisory -> advisory.code == "A2" }.map { advisory -> advisory.message }

    @Test
    fun `an initially counting the record's own declared collection draws A2`() {
        val advisories = ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                entries: many Line = (Line where summary == this)
                lineCount: integer initially count(entries)
            }
        """)
        assertEquals(listOf("'Summary.lineCount' starts from 'entries', the Line records that point at this Summary. None can when the Summary is " +
            "created, so 'entries' is empty every time; write the value 'lineCount' starts with, or declare it with '=' to keep it current (Reference §5)"), advisories)
    }

    @Test
    fun `an initially reading the inferred collection draws A2`() {
        val advisories = ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                lineCount: integer initially count(this.lines)
            }
        """)
        assertEquals(1, advisories.size, "$advisories")
        assertTrue("'Summary.lineCount' starts from 'this.lines', the Line records that point at this Summary" in advisories.single(), "$advisories")
    }

    @Test
    fun `an initially whose test of its own collection can never hold draws A2`() {
        val advisories = ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                firstBody: text initially if exists (lines) then first(lines by body).body else "none"
            }
        """)
        assertEquals(1, advisories.size, "$advisories")
        assertTrue("'Summary.firstBody' starts from 'lines'" in advisories.single(), "$advisories")
    }

    @Test
    fun `an initially reading the filter written in place draws A2`() {
        val advisories = ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                lineCount: integer initially count(Line where summary == this and body != "")
            }
        """)
        assertEquals(1, advisories.size, "$advisories")
        assertTrue("'Summary.lineCount' starts from '(Line where (summary == this) and (body != \"\"))'" in advisories.single(), "$advisories")
    }

    @Test
    fun `an initially reading a collection declared over its own collection draws A2`() {
        val advisories = ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                filled: many Line = (lines where body != "")
                filledCount: integer initially count(filled)
            }
        """)
        assertEquals(1, advisories.size, "$advisories")
    }

    @Test
    fun `an initially reading the same kind of collection on another record draws no A2`() {
        assertEquals(emptyList(), ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                rowCount: integer initially count(upload.rows)
            }
        """))
    }

    @Test
    fun `an initially reading a filter over values draws no A2`() {
        assertEquals(emptyList(), ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                title: text
                sameBodyCount: integer initially count(Line where body == this.title)
            }
        """))
    }

    @Test
    fun `a derived property over the record's own collection draws no A2`() {
        assertEquals(emptyList(), ownCollectionAdvisories("""
            shape Summary {
                upload: one Upload
                lineCount: integer = count(lines)
            }
        """))
    }
}
