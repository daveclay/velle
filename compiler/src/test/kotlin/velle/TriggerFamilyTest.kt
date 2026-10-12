package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The trigger family (README §13, "Deletion as a second exit cause";
 * OQ37-R11–R20): `when leaving X` fires only when the predicate flips,
 * `when deleted X` only at deletion, `when leaving or deleted X` for either
 * cause, `when leaving not deleted X` the predicate flip stated out loud —
 * each over a bare state name or an inline refinement. The runtime probes
 * drive the README's own worked case; the validator probes cover the
 * checks the family adds (V3's bare-shape edge, V24 and V28 on the deletion
 * side, A6, A2's dead halves).
 */
class TriggerFamilyTest {

    private fun accepted(r: CommitResult): Long {
        assertIs<CommitResult.Accepted>(r, "commit refused: $r")
        return (r as CommitResult.Accepted).id
    }

    private fun assertHas(code: String, source: String, contains: String? = null) {
        val diags = Validator.validate(source)
        assertTrue(diags.any { it.code == code && (contains == null || contains in it.message) },
            "expected a $code${contains?.let { " mentioning '$it'" } ?: ""}, got: $diags")
    }

    private fun assertLacks(code: String, source: String) {
        val diags = Validator.validate(source)
        assertTrue(diags.none { it.code == code }, "expected no $code, got: $diags")
    }

    private fun assertAdvisory(code: String, source: String, contains: String? = null) {
        val diags = Validator.advisories(source)
        assertTrue(diags.any { it.code == code && (contains == null || contains in it.message) },
            "expected advisory $code${contains?.let { " mentioning '$it'" } ?: ""}, got: $diags")
    }

    private fun assertNoAdvisory(code: String, source: String) {
        val diags = Validator.advisories(source)
        assertTrue(diags.none { it.code == code }, "expected no advisory $code, got: $diags")
    }

    // ── grammar ──────────────────────────────────────────────────────────────

    @Test
    fun `the four exit forms parse and print back`() {
        for (form in listOf("leaving", "deleted", "leaving or deleted", "leaving not deleted")) {
            val src = "rule R when $form Draft {\n    Note from { title: title }\n}"
            val decl = Parser.parse("expose shape Listing { title: text, isDraft: boolean initially true }\n" +
                "shape Draft = Listing where isDraft\nshape Note { title: text }\n$src")
                .filterIsInstance<RuleDecl>().single()
            assertTrue(Printer.print(decl).startsWith("rule R when $form Draft"), Printer.print(decl))
        }
    }

    @Test
    fun `an inline condition is legal on every form`() {
        val src = """
            expose shape Seller { name: text }
            expose shape Listing { title: text, seller: one Seller?, isDraft: boolean initially true }
            shape Draft = Listing where isDraft
            shape SellerNotice { seller: one Seller, listingTitle: text }
            expose transient shape Publish { listing: one Listing }
            rule ApplyPublish when Publish { listing.isDraft = false }
            expose transient shape DiscardDraft { listing: one Listing }
            rule ApplyDiscard when (DiscardDraft where listing is Draft) { delete listing }
            rule RefuseDiscard when (DiscardDraft where listing is not Draft) { Refusal from { title: listing.title } }
            shape Refusal { title: text }
            rule NoteWithdrawal when leaving not deleted (Draft where seller is some) {
                SellerNotice from { seller: seller, listingTitle: title }
            }
            rule NoteDiscard when deleted (Draft where seller is some) {
                SellerNotice from { seller: seller, listingTitle: title }
            }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(src))
        val sys = VelleSystem(Model(Parser.parse(src)))
        val seller = accepted(sys.commit("Seller", mapOf("name" to "Ann")))
        val withSeller = accepted(sys.commit("Listing", mapOf("title" to "Bike", "seller" to seller)))
        val orphan = accepted(sys.commit("Listing", mapOf("title" to "Lamp")))
        accepted(sys.commit("DiscardDraft", mapOf("listing" to orphan)))
        assertEquals(emptyList(), sys.instancesOf("SellerNotice")) // no seller — outside the inline condition
        accepted(sys.commit("DiscardDraft", mapOf("listing" to withSeller)))
        assertEquals("Bike", sys.get(sys.instancesOf("SellerNotice").single(), "listingTitle"))
    }

    // ── the README's worked case ─────────────────────────────────────────────

    private val workedCase = """
        expose shape Seller { name: text }
        expose shape Listing { title: text, seller: one Seller, isDraft: boolean initially true }
        shape Draft = Listing where isDraft
        shape PublicationNotice { title: text }
        shape SellerNotice { seller: one Seller, listingTitle: text }
        rule AnnouncePublication when leaving not deleted Draft {
            PublicationNotice from { title: title }
        }
        rule NoteDiscard when deleted Draft {
            SellerNotice from { seller: seller, listingTitle: title }
        }
        expose transient shape Publish { listing: one Listing }
        rule ApplyPublish when Publish { listing.isDraft = false }
        expose transient shape DiscardDraft { listing: one Listing }
        rule ApplyDiscard when (DiscardDraft where listing is Draft) { delete listing }
        rule RefuseDiscard when (DiscardDraft where listing is not Draft) { Refusal from { title: listing.title } }
        shape Refusal { title: text }
    """.trimIndent()

    private fun worked(): Triple<VelleSystem, Long, Long> {
        assertEquals(emptyList(), Validator.validate(workedCase))
        assertEquals(emptyList(), Validator.advisories(workedCase))
        val sys = VelleSystem(Model(Parser.parse(workedCase)))
        val seller = accepted(sys.commit("Seller", mapOf("name" to "Ann")))
        val listing = accepted(sys.commit("Listing", mapOf("title" to "Bike", "seller" to seller)))
        return Triple(sys, seller, listing)
    }

    @Test
    fun `publishing fires the stated predicate-exit rule and not the deletion rule`() {
        val (sys, _, listing) = worked()
        accepted(sys.commit("Publish", mapOf("listing" to listing)))
        assertEquals("Bike", sys.get(sys.instancesOf("PublicationNotice").single(), "title"))
        assertEquals(emptyList(), sys.instancesOf("SellerNotice"))
    }

    @Test
    fun `discarding fires the deletion rule as a last reader and not the predicate-exit rule`() {
        val (sys, _, listing) = worked()
        accepted(sys.commit("DiscardDraft", mapOf("listing" to listing)))
        assertEquals(emptyList(), sys.instancesOf("Listing"))
        assertEquals(emptyList(), sys.instancesOf("PublicationNotice"))
        assertEquals("Bike", sys.get(sys.instancesOf("SellerNotice").single(), "listingTitle"))
    }

    @Test
    fun `the combined form fires for either cause`() {
        val src = """
            expose shape Lead { company: text, won: boolean initially false }
            shape OpenLead = Lead where not won
            shape FollowUpStop { company: text }
            rule NoteFollowUpStop when leaving or deleted OpenLead { FollowUpStop from { company: company } }
            expose transient shape MarkWon { lead: one Lead }
            rule ApplyWon when MarkWon { lead.won = true }
            expose transient shape PurgeLead { lead: one Lead }
            rule ApplyPurge when PurgeLead { delete lead }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(src))
        assertEquals(emptyList(), Validator.advisories(src))
        val sys = VelleSystem(Model(Parser.parse(src)))
        val a = accepted(sys.commit("Lead", mapOf("company" to "Acme")))
        val b = accepted(sys.commit("Lead", mapOf("company" to "Bolt")))
        accepted(sys.commit("MarkWon", mapOf("lead" to a)))
        accepted(sys.commit("PurgeLead", mapOf("lead" to b)))
        assertEquals(setOf("Acme", "Bolt"), sys.instancesOf("FollowUpStop").map { sys.get(it, "company") }.toSet())
    }

    @Test
    fun `a deletion-side rule reads the state's captures as their last reader`() {
        val src = """
            expose shape Account { owner: text, balance: decimal }
            expose transient shape Overdraw { account: one Account, amount: decimal }
            rule ApplyOverdraw when Overdraw { account.balance = account.balance - amount }
            shape Delinquent = Account where balance < 0 {
                captured balanceWhenDelinquent: decimal = balance
            }
            shape ClosureNote { owner: text, owed: decimal }
            rule NoteClosure when deleted Delinquent {
                ClosureNote from { owner: owner, owed: balanceWhenDelinquent }
            }
            expose transient shape Close { account: one Account }
            rule ApplyClose when Close { delete account }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(src))
        val sys = VelleSystem(Model(Parser.parse(src)))
        val acct = accepted(sys.commit("Account", mapOf("owner" to "Ann", "balance" to java.math.BigDecimal("10"))))
        accepted(sys.commit("Overdraw", mapOf("account" to acct, "amount" to java.math.BigDecimal("25"))))
        accepted(sys.commit("Close", mapOf("account" to acct)))
        val note = sys.instancesOf("ClosureNote").single()
        assertEquals("Ann", sys.get(note, "owner"))
        assertEquals(0, java.math.BigDecimal("-15").compareTo(sys.get(note, "owed") as java.math.BigDecimal))
    }

    @Test
    fun `a bare leaving rule keeps firing on the predicate flip when the deleter is provably disjoint`() {
        // the disjoint deleter draws no advisory (A6's discharge by partition),
        // and bare `when leaving` still fires for the transition it was written for
        val src = """
            expose shape Lead { company: text, won: boolean initially false }
            shape OpenLead = Lead where not won
            shape FollowUpStop { company: text }
            rule NoteFollowUpStop when leaving OpenLead { FollowUpStop from { company: company } }
            expose transient shape MarkWon { lead: one Lead }
            rule ApplyWon when MarkWon { lead.won = true }
            expose transient shape PurgeWon { lead: one Lead }
            rule ApplyPurge when (PurgeWon where lead is not OpenLead) { delete lead }
            rule RefusePurge when (PurgeWon where lead is OpenLead) { Refusal from { company: lead.company } }
            shape Refusal { company: text }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(src))
        assertEquals(emptyList(), Validator.advisories(src))
        val sys = VelleSystem(Model(Parser.parse(src)))
        val lead = accepted(sys.commit("Lead", mapOf("company" to "Acme")))
        accepted(sys.commit("MarkWon", mapOf("lead" to lead)))
        assertEquals("Acme", sys.get(sys.instancesOf("FollowUpStop").single(), "company"))
        // purging the won lead deletes it and fires nothing further
        accepted(sys.commit("PurgeWon", mapOf("lead" to lead)))
        assertEquals(emptyList(), sys.instancesOf("Lead"))
        assertEquals(1, sys.instancesOf("FollowUpStop").size)
    }

    @Test
    fun `leaving not deleted with an inline condition fires on the flip and not on the delete`() {
        val src = """
            expose shape Seller { name: text }
            expose shape Listing { title: text, seller: one Seller?, isDraft: boolean initially true }
            shape Draft = Listing where isDraft
            shape SellerNotice { seller: one Seller, listingTitle: text }
            expose transient shape Publish { listing: one Listing }
            rule ApplyPublish when Publish { listing.isDraft = false }
            expose transient shape DiscardDraft { listing: one Listing }
            rule ApplyDiscard when (DiscardDraft where listing is Draft) { delete listing }
            rule RefuseDiscard when (DiscardDraft where listing is not Draft) { Refusal from { title: listing.title } }
            shape Refusal { title: text }
            rule NotePublication when leaving not deleted (Draft where seller is some) {
                SellerNotice from { seller: seller, listingTitle: title }
            }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(src))
        assertEquals(emptyList(), Validator.advisories(src))
        val sys = VelleSystem(Model(Parser.parse(src)))
        val seller = accepted(sys.commit("Seller", mapOf("name" to "Ann")))
        val published = accepted(sys.commit("Listing", mapOf("title" to "Bike", "seller" to seller)))
        val discarded = accepted(sys.commit("Listing", mapOf("title" to "Lamp", "seller" to seller)))
        val orphan = accepted(sys.commit("Listing", mapOf("title" to "Rug")))
        accepted(sys.commit("DiscardDraft", mapOf("listing" to discarded)))
        assertEquals(emptyList(), sys.instancesOf("SellerNotice")) // deletion is not this rule's cause
        accepted(sys.commit("Publish", mapOf("listing" to orphan)))
        assertEquals(emptyList(), sys.instancesOf("SellerNotice")) // no seller — outside the inline condition
        accepted(sys.commit("Publish", mapOf("listing" to published)))
        assertEquals("Bike", sys.get(sys.instancesOf("SellerNotice").single(), "listingTitle"))
    }

    // ── the checks the family adds ───────────────────────────────────────────

    @Test
    fun `V3 - a predicate-exit trigger over a bare shape can never fire`() {
        assertHas("V3", """
            expose shape Listing { title: text }
            shape GoneNotice { title: text }
            rule NoteGone when leaving Listing { GoneNotice from { title: title } }
            expose transient shape Drop { listing: one Listing }
            rule ApplyDrop when Drop { delete listing }
        """.trimIndent(), contains = "when deleted Listing")
    }

    @Test
    fun `V3 - a deletion trigger with no deleter can never fire`() {
        assertHas("V3", """
            expose shape Listing { title: text }
            shape GoneNotice { title: text }
            rule NoteGone when deleted Listing { GoneNotice from { title: title } }
        """.trimIndent(), contains = "no rule deletes")
    }

    @Test
    fun `the combined form over a bare shape fires by deletion - not V3, but its leaving half is dead`() {
        val src = """
            expose shape Listing { title: text }
            shape GoneNotice { title: text }
            rule NoteGone when leaving or deleted Listing { GoneNotice from { title: title } }
            expose transient shape Drop { listing: one Listing }
            rule ApplyDrop when Drop { delete listing }
        """.trimIndent()
        assertLacks("V3", src)
        assertAdvisory("A2", src, contains = "'leaving' half is inert")
    }

    @Test
    fun `V24 - a deletion-side body may not keep a reference to its subject`() {
        assertHas("V24", """
            expose shape Order { total: decimal, settled: boolean initially false }
            shape SettledOrder = Order where settled
            shape Reversal { order: one Order }
            rule NoteReversal when deleted SettledOrder { Reversal from { order: this } }
            expose transient shape Scrap { order: one Order }
            rule ApplyScrap when Scrap { delete order }
        """.trimIndent(), contains = "reference to its subject")
    }

    @Test
    fun `V28 - a deletion-side rule is transaction-bound`() {
        assertHas("V28", """
            expose shape Task { name: text, done: boolean initially false }
            shape OpenTask = Task where not done
            expose transient shape Drop { task: one Task }
            rule ApplyDrop when Drop { delete task }
            rule LogDrop when deleted OpenTask after commit { DropLog from { name: name } }
            shape DropLog { name: text }
        """.trimIndent(), contains = "transaction-bound")
    }

    @Test
    fun `A6 - a deleter reaching a bare leaving rule's state is advisory, discharged by stating the cause`() {
        fun spec(form: String) = """
            expose shape Lead { company: text, won: boolean initially false }
            shape OpenLead = Lead where not won
            shape FollowUpStop { company: text }
            rule NoteFollowUpStop when $form OpenLead { FollowUpStop from { company: company } }
            expose transient shape MarkWon { lead: one Lead }
            rule ApplyWon when MarkWon { lead.won = true }
            expose transient shape PurgeLead { lead: one Lead }
            rule ApplyPurge when PurgeLead { delete lead }
        """.trimIndent()
        assertAdvisory("A6", spec("leaving"), contains = "NoteFollowUpStop")
        assertEquals(emptyList(), Validator.validate(spec("leaving")))
        assertNoAdvisory("A6", spec("leaving not deleted"))
        assertNoAdvisory("A6", spec("leaving or deleted"))
    }

    @Test
    fun `A6 - a deleter provably disjoint from the state draws nothing`() {
        assertNoAdvisory("A6", """
            expose shape Lead { company: text, won: boolean initially false }
            shape OpenLead = Lead where not won
            shape FollowUpStop { company: text }
            rule NoteFollowUpStop when leaving OpenLead { FollowUpStop from { company: company } }
            expose transient shape MarkWon { lead: one Lead }
            rule ApplyWon when MarkWon { lead.won = true }
            expose transient shape PurgeWon { lead: one Lead }
            rule ApplyPurge when (PurgeWon where lead is not OpenLead) { delete lead }
            rule RefusePurge when (PurgeWon where lead is OpenLead) { Refusal from { company: lead.company } }
            shape Refusal { company: text }
        """.trimIndent())
    }

    @Test
    fun `A2 - not deleted where no deleter can reach the state is dead machinery`() {
        assertAdvisory("A2", """
            expose shape Lead { company: text, won: boolean initially false }
            shape OpenLead = Lead where not won
            shape FollowUpStop { company: text }
            rule NoteFollowUpStop when leaving not deleted OpenLead { FollowUpStop from { company: company } }
            expose transient shape MarkWon { lead: one Lead }
            rule ApplyWon when MarkWon { lead.won = true }
        """.trimIndent(), contains = "refuses nothing")
    }
}
