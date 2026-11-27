package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a condition is taken to read when it finds records with `Shape for <record>`
 * (Reference §10): the reference field of the shape that `for` matched, for every spelling of
 * the record after `for`. Where the record's shape cannot be told, or no field of the shape
 * takes it, the summary says it may read anything, so no check reads it as reading less.
 */
class ForReadSummaryTest {

    private val lending = """
        act Member { name: text }

        act Card { holder: one Member }

        act Loan {
            member: one Member
            dueOn: Date
        }

        shape Fine { loan: one Loan }

        act Tagged { tags: many text }
    """.trimIndent()

    private fun readsOf(refinement: String): ReadSummary {
        val spec = lending + "\n\n" + refinement
        val model = Model(Parser.parse(spec))
        return model.predicateSummary("Watched")
    }

    private fun assertValidates(refinement: String) =
        assertEquals(emptyList(), Validator.validate(lending + "\n\n" + refinement), "the spec validates")

    private fun assertReadsField(shape: String, field: String, reads: ReadSummary) {
        assertTrue((shape to field) in reads.fields, "expected $shape.$field among ${reads.fields}")
        assertFalse(reads.opaque, "the read is attributed, so the summary is not 'may read anything'")
    }

    @Test
    fun `the loans for a member picked by a selection read the loan's member`() {
        val refinement = "shape Watched = Member where count(Loan for latest(Member where name == this.name by name)) > 0"
        assertValidates(refinement)

        assertReadsField("Loan", "member", readsOf(refinement))
    }

    @Test
    fun `the one loan for a member picked by a selection reads the loan's member`() {
        val refinement = "shape Watched = Member where (Loan for latest(Member where name == this.name by name)) is some"
        assertValidates(refinement)

        assertReadsField("Loan", "member", readsOf(refinement))
    }

    @Test
    fun `the loans for a member reached through another record's field read the loan's member`() {
        val refinement = "shape Watched = Member where count(Loan for (Card for this).holder) > 0"
        assertValidates(refinement)

        assertReadsField("Loan", "member", readsOf(refinement))
    }

    @Test
    fun `the fine for the one loan of a member reads the fine's loan`() {
        val refinement = "shape Watched = Member where (Fine for (Loan for this)) is some"
        assertValidates(refinement)

        assertReadsField("Fine", "loan", readsOf(refinement))
    }

    @Test
    fun `the loans for an as name read the loan's member`() {
        val refinement = "shape Watched = Member where count(Member as other where exists Loan for other) > 0"
        assertValidates(refinement)

        assertReadsField("Loan", "member", readsOf(refinement))
    }

    @Test
    fun `the loans for this inside a filter over another member read the loan's member`() {
        val refinement = "shape Watched = Member where exists (Member where name == this.name and count(Loan for this) > 0)"
        assertValidates(refinement)

        assertReadsField("Loan", "member", readsOf(refinement))
    }

    @Test
    fun `the one loan for a for each name in a rule body reads the loan's member`() {
        val model = Model(Parser.parse(lending))
        val loanForMember = SingularFor("Loan", PathExpr("member"))
        val reads = ReadSummary().also { summary -> model.collectExpr(loanForMember, "Card", summary, mapOf("member" to "Member")) }

        assertReadsField("Loan", "member", reads)
    }

    @Test
    fun `the loans for a record of a union read the field that takes every operand`() {
        val borrowers = """
            act Member { name: text }

            act Library { name: text }

            shape Borrower = Member or Library

            act Loan {
                member: one Borrower
                dueOn: Date
            }

            act Hold { holder: one Borrower }

            shape Watched = Hold where count(Loan for holder) > 0
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(borrowers), "the spec validates")

        assertReadsField("Loan", "member", Model(Parser.parse(borrowers)).predicateSummary("Watched"))
    }

    @Test
    fun `the loans for a text value may read anything`() {
        val reads = readsOf("shape Watched = Tagged where count(tags as tag where count(Loan for tag) > 0) > 0")

        assertTrue(reads.opaque, "no field of Loan takes a text, so what the read finds cannot be told")
    }

    @Test
    fun `the loans for a record no field of Loan takes may read anything`() {
        val reads = readsOf("shape Watched = Member where count(Loan for (Loan for this)) > 0")

        assertTrue(reads.opaque, "Loan has no field that takes a Loan, so what the read finds cannot be told")
    }
}
