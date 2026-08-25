package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * OQ37 §1's exhibits: what happens when a spec combines a `when leaving` rule
 * with a deleter of the same base — the collision the example corpus avoids
 * (its four leaving-rules live in specs with no deleters).
 *
 * Three faces:
 *  - the structural conflict: the leaving-rule's product keeps a required
 *    reference to the leaving record, so any deleter of the base is refused
 *    outright (V24, referential completeness) — the combination cannot even
 *    validate;
 *  - the semantic trap: a leaving-rule whose product only copies fields
 *    validates clean, and under the implemented default (every exit rule
 *    fires at the deleting commit) a deletion counterfeits the transition
 *    the rule was written for — the probe below produces a publication
 *    notice for a listing that was never published, and nothing refuses it;
 *  - the gap: a leaving-rule the author meant as "for any reason, deletion
 *    included" (OQ37 §1a) — under the implemented default it happens to
 *    cover deletion; under the settled semantics it will not, and by
 *    OQ37-R13 the collision itself becomes a hard validation error until
 *    the author addresses it.
 *
 * The runtime probes document the default REJECTED by OQ37-R11/R12: bare
 * `when leaving` fires only when the predicate flips, never at deletion.
 * When that lands (together with the deletion trigger, OQ37 §1b), the
 * counterfeit probe flips to asserting no notice, and the gap spec stops
 * validating at all (R13) — its probes become a validation-error assertion.
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

        rule RefuseDiscard when (DiscardDraft where not (listing is Draft)) {
            DiscardRefusal from { title: listing.title }
        }
    """.trimIndent()

    private fun system(): VelleSystem {
        assertEquals(emptyList(), Validator.validate(fieldCopyingSpec))
        return VelleSystem(Model(Parser.parse(fieldCopyingSpec)))
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
    fun `the counterfeit firing - discarding a draft announces a publication that never happened`() {
        val sys = system()
        val listing = accepted(sys.commit("Listing", mapOf("title" to "Old bike")))

        accepted(sys.commit("DiscardDraft", mapOf("listing" to listing)))

        // the listing is gone — and the implemented (R11-rejected) default fired
        // AnnouncePublication at the deleting commit: a notice for a listing
        // that was never published. When R11's design lands, this flips to
        // asserting NO notice exists.
        assertEquals(emptyList(), sys.instancesOf("Listing"))
        assertEquals("Old bike", sys.get(sys.instancesOf("PublicationNotice").single(), "title"))
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
    fun `the gap spec - today a purge also stops the follow-up, by the rejected default`() {
        val sys = leadSystem()
        val lead = accepted(sys.commit("Lead", mapOf("company" to "Acme")))

        accepted(sys.commit("PurgeLead", mapOf("lead" to lead)))

        // Under the implemented (rejected) default the purge fires
        // NoteFollowUpStop, which here is what the author wanted. Under the
        // settled semantics (OQ37-R12) it will NOT fire — and by OQ37-R13
        // the spec above stops validating: a deleter reaching a leaving-rule's
        // state is a hard error until addressed (deletion-reaction rule,
        // provable disjointness, or the transition-only marker). When the
        // implementation lands, this probe becomes a validation-error
        // assertion instead of a runtime one.
        assertEquals(emptyList(), sys.instancesOf("Lead"))
        assertEquals("Acme", sys.get(sys.instancesOf("FollowUpStop").single(), "company"))
    }
}
