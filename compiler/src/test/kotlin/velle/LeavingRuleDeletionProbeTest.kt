package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * OQ37's exhibits: what happens when a spec combines a `when leaving` rule
 * with a deleter of the same base — the collision the example corpus avoids
 * (its four leaving-rules live in specs with no deleters).
 *
 * Three faces:
 *  - the structural conflict: the leaving-rule's product keeps a required
 *    reference to the leaving record, so any deleter of the base is refused
 *    outright (V24, referential completeness) — the combination cannot even
 *    validate;
 *  - the semantic trap: a leaving-rule whose product only copies fields
 *    validates clean, and under v0's original default (every exit rule
 *    fired at the deleting commit) a deletion counterfeited the transition
 *    the rule was written for — a publication notice for a listing that
 *    was never published. Under the settled semantics (OQ37-R12, README
 *    §13) bare `when leaving` fires only when the predicate flips, never at
 *    deletion: the probe asserts no notice, and the A6 advisory (OQ37-R18)
 *    names the reaching deleter;
 *  - the gap: a leaving-rule the author meant as "for any reason, deletion
 *    included" — the original default happened to cover deletion; the
 *    settled semantics do not, and the same advisory says so. That spec's
 *    fix is `when leaving or deleted OpenLead` (OQ37-R16), exercised in
 *    TriggerFamilyTest.
 */
class LeavingRuleDeletionProbeTest {

    // ── Face 1: the product keeps a reference — the validator refuses ────────

    private val referenceKeepingSpec = """
        expose shape Order {
            total: decimal
            settled: boolean initially false
        }

        shape SettledOrder = Order where settled

        expose transient shape Settle {
            order: one Order
        }

        rule ApplySettle when Settle {
            order.settled = true
        }

        shape SettlementReversal {
            order: one Order
        }

        rule NoteReversal when leaving SettledOrder {
            SettlementReversal from { order: this }
        }

        expose transient shape Scrap {
            order: one Order
        }

        rule ApplyScrap when Scrap {
            delete order
        }
    """.trimIndent()

    @Test
    fun `a leaving-rule product holding a required reference refuses any deleter of the base - V24`() {
        val codes = Validator.validate(referenceKeepingSpec).map { it.code }
        assertTrue(codes.contains("V24"), "expected V24, got: $codes")
    }

    // ── Face 2: the product copies fields — validates clean, traps at runtime ─

    private val fieldCopyingSpec = """
        expose shape Listing {
            title: text
            isDraft: boolean initially true
        }

        shape Draft = Listing where isDraft

        shape PublicationNotice {
            title: text
        }

        -- Written to mean "the listing was published": short of deletion, the
        -- only way a listing leaves Draft is ApplyPublish below.
        rule AnnouncePublication when leaving Draft {
            PublicationNotice from { title: title }
        }

        expose transient shape Publish {
            listing: one Listing
        }

        rule ApplyPublish when Publish {
            listing.isDraft = false
        }

        expose transient shape DiscardDraft {
            listing: one Listing
        }

        rule ApplyDiscard when (DiscardDraft where listing is Draft) {
            delete listing
        }

        shape DiscardRefusal {
            title: text
        }

        rule RefuseDiscard when (DiscardDraft where listing is not Draft) {
            DiscardRefusal from { title: listing.title }
        }
    """.trimIndent()

    private fun system(): VelleSystem {
        assertEquals(emptyList(), Validator.validate(fieldCopyingSpec))
        return VelleSystem(Model(Parser.parse(fieldCopyingSpec)))
    }

    @Test
    fun `the collision is surfaced - the deleter reaching Draft draws the A6 advisory`() {
        val advisories = Validator.advisories(fieldCopyingSpec)
        assertTrue(advisories.any { it.code == "A6" && "AnnouncePublication" in it.message }, "expected A6, got: $advisories")
    }

    private fun accepted(r: CommitResult): Long {
        assertIs<CommitResult.Accepted>(r, "commit refused: $r")
        return (r as CommitResult.Accepted).id
    }

    @Test
    fun `the intended firing - publishing a draft announces it`() {
        val sys = system()
        val listing = accepted(sys.commit("Listing", mapOf("title" to "Old bike")))

        accepted(sys.commit("Publish", mapOf("listing" to listing)))

        assertEquals("Old bike", sys.get(sys.instancesOf("PublicationNotice").single(), "title"))
    }

    @Test
    fun `no counterfeit - discarding a draft announces nothing`() {
        val sys = system()
        val listing = accepted(sys.commit("Listing", mapOf("title" to "Old bike")))

        accepted(sys.commit("DiscardDraft", mapOf("listing" to listing)))

        // the listing is gone, and AnnouncePublication did not fire: bare
        // `when leaving` reacts to the predicate flipping, never to deletion
        // (OQ37-R12) — no notice for a listing that was never published
        assertEquals(emptyList(), sys.instancesOf("Listing"))
        assertEquals(emptyList(), sys.instancesOf("PublicationNotice"))
    }

    // ── Face 3: a rule meant as "for any reason" — the settled semantics' gap ─

    private val forAnyReasonSpec = """
        expose shape Lead {
            company: text
            won: boolean initially false
        }

        shape OpenLead = Lead where not won

        shape FollowUpStop {
            company: text
        }

        -- Written to mean "stop chasing this lead, for any reason": the
        -- outreach team clears its queue when a FollowUpStop lands.
        rule NoteFollowUpStop when leaving OpenLead {
            FollowUpStop from { company: company }
        }

        expose transient shape MarkWon {
            lead: one Lead
        }

        rule ApplyWon when MarkWon {
            lead.won = true
        }

        expose transient shape PurgeLead {
            lead: one Lead
        }

        rule ApplyPurge when PurgeLead {
            delete lead
        }
    """.trimIndent()

    private fun leadSystem(): VelleSystem {
        assertEquals(emptyList(), Validator.validate(forAnyReasonSpec))
        return VelleSystem(Model(Parser.parse(forAnyReasonSpec)))
    }

    @Test
    fun `the gap spec - winning a lead stops the follow-up`() {
        val sys = leadSystem()
        val lead = accepted(sys.commit("Lead", mapOf("company" to "Acme")))

        accepted(sys.commit("MarkWon", mapOf("lead" to lead)))

        assertEquals("Acme", sys.get(sys.instancesOf("FollowUpStop").single(), "company"))
    }

    @Test
    fun `the gap spec - a purge no longer stops the follow-up, and the advisory says so`() {
        val sys = leadSystem()
        val lead = accepted(sys.commit("Lead", mapOf("company" to "Acme")))

        accepted(sys.commit("PurgeLead", mapOf("lead" to lead)))

        // The author wanted the purge to stop the follow-up too; bare `when
        // leaving` does not fire at deletion (OQ37-R12), so nothing lands —
        // and the spec validates with the A6 advisory (OQ37-R18) naming the
        // reaching deleter and the explicit forms that state the cause. The
        // fix is `when leaving or deleted OpenLead` (TriggerFamilyTest).
        assertEquals(emptyList(), sys.instancesOf("Lead"))
        assertEquals(emptyList(), sys.instancesOf("FollowUpStop"))
        val advisories = Validator.advisories(forAnyReasonSpec)
        assertTrue(advisories.any { it.code == "A6" && "NoteFollowUpStop" in it.message }, "expected A6, got: $advisories")
    }
}
