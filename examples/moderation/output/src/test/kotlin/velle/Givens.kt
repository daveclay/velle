package velle.generated.moderation

import velle.generated.ModerationSystem
import velle.generated.*

/**
 * The human-owned scenarios the generated moderation specs demand (testgen.md).
 * A listing enters as a draft under a seller; it leaves Draft by publication
 * (the predicate flips) or by discard (the row is deleted) — the two exit
 * causes the spec's trigger family tells apart (README §13).
 */
class Givens(private val sys: ModerationSystem) : RequiredGivens {

    private fun seller(): Seller {
        sys.commitSeller("Ada")
        return sys.sellers().last()
    }

    private fun newDraft(seller: Seller = seller()): Listing {
        sys.commitListing("brass lamp", seller)
        return sys.listings().last()
    }

    override fun publish() {
        sys.commitPublish(newDraft())
    }

    override fun draft(): Listing = newDraft()

    override fun exitDraft(listing: Listing) {
        sys.commitPublish(listing)
    }

    override fun listingForNoteDiscard(): Listing = newDraft()

    override fun deleteForNoteDiscard(listing: Listing) {
        sys.commitDiscardDraft(listing, "duplicate")
    }

    /** A report on a draft, then the draft discarded: the report's reference absorbs the deletion. */
    override fun listingReportForNotifyReporter(): ListingReport {
        val draft = newDraft()
        sys.commitListingReport(draft, sys.read(draft).title, "spam")
        val report = sys.listingReports().last()
        sys.commitDiscardDraft(draft, "spam")
        return report
    }

    override fun discardDraftForApplyDiscard() {
        sys.commitDiscardDraft(newDraft(), "duplicate")
    }

    override fun discardDraftForRefuseDiscard() {
        val listing = newDraft()
        sys.commitPublish(listing)
        sys.commitDiscardDraft(listing, "too late")
    }

    override fun closeAccount() {
        sys.commitCloseAccount(seller())
    }

    /** A published listing survives its seller's closure as an orphan; a draft
     *  would be swept in the same commit and could not be returned. */
    override fun listingForSweepOrphanedDrafts(): Listing {
        val ada = seller()
        val listing = newDraft(ada)
        sys.commitPublish(listing)
        sys.commitCloseAccount(ada)
        return listing
    }
}

