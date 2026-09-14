package velle.generated.moderation

import velle.generated.ModerationSystem

/**
 * The human-owned scenarios the generated moderation specs demand (testgen.md).
 * A listing enters as a draft under a seller; it leaves Draft by publication
 * (the predicate flips) or by discard (the row is deleted) — the two exit
 * causes the spec's trigger family tells apart (README §13).
 */
class Givens(private val sys: ModerationSystem) : RequiredGivens {

    private fun seller(): ModerationSystem.SellerView {
        sys.commitSeller("Ada")
        return sys.sellers().last()
    }

    private fun newDraft(seller: ModerationSystem.SellerView = seller()): ModerationSystem.ListingView {
        sys.commitListing("brass lamp", seller)
        return sys.listings().last()
    }

    override fun publish() {
        sys.commitPublish(newDraft())
    }

    override fun draft(): ModerationSystem.ListingView = newDraft()

    override fun exitDraft(listing: ModerationSystem.ListingView) {
        sys.commitPublish(listing)
    }

    override fun listingForNoteDiscard(): ModerationSystem.ListingView = newDraft()

    override fun deleteForNoteDiscard(listing: ModerationSystem.ListingView) {
        sys.commitDiscardDraft(listing, "duplicate")
    }

    /** A report on a draft, then the draft discarded: the report's reference absorbs the deletion. */
    override fun listingReportForNotifyReporter(): ModerationSystem.ListingReportView {
        val draft = newDraft()
        sys.commitListingReport(draft, draft.title, "spam")
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
    override fun listingForSweepOrphanedDrafts(): ModerationSystem.ListingView {
        val ada = seller()
        val listing = newDraft(ada)
        sys.commitPublish(listing)
        sys.commitCloseAccount(ada)
        return listing
    }
}
