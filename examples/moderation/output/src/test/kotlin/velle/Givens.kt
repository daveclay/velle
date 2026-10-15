package velle.generated.moderation

import velle.generated.ModerationSystem
import velle.generated.*

/**
 * The human-owned scenarios the generated moderation specs demand (testgen.md).
 * A listing enters as a draft under a seller; it leaves Draft by publication
 * (the predicate flips) or by discard (the row is deleted) — the two exit
 * causes the spec's trigger family tells apart (Reference §13).
 */
class Givens(private val sys: ModerationSystem) : RequiredGivens {

    private fun seller(): Seller {
        sys.commit(NewSeller("Ada"))
        return sys.sellers().last()
    }

    private fun newDraft(seller: Seller = seller()): Listing {
        sys.commit(NewListing("brass lamp", seller))
        return sys.listings().last()
    }

    override fun publish() {
        sys.commit(NewPublish(newDraft()))
    }

    override fun draft(): Listing = newDraft()

    override fun exitDraft(listing: Listing) {
        sys.commit(NewPublish(listing))
    }

    override fun listingForNoteDiscard(): Listing = newDraft()

    override fun deleteForNoteDiscard(listing: Listing) {
        sys.commit(NewDiscardDraft(listing, "duplicate"))
    }

    /** A report on a draft, then the draft discarded: the report's reference absorbs the deletion. */
    override fun listingReportForNotifyReporter(): ListingReport {
        val draft = newDraft()
        sys.commit(NewListingReport(draft, sys.read(draft).title, "spam"))
        val report = sys.listingReports().last()
        sys.commit(NewDiscardDraft(draft, "spam"))
        return report
    }

    override fun discardDraftForApplyDiscard() {
        sys.commit(NewDiscardDraft(newDraft(), "duplicate"))
    }

    override fun discardDraftForRefuseDiscard() {
        val listing = newDraft()
        sys.commit(NewPublish(listing))
        sys.commit(NewDiscardDraft(listing, "too late"))
    }

    override fun closeAccount() {
        sys.commit(NewCloseAccount(seller()))
    }

    /** A published listing survives its seller's closure as an orphan; a draft
     *  would be swept in the same commit and could not be returned. */
    override fun listingForSweepOrphanedDrafts(): Listing {
        val ada = seller()
        val listing = newDraft(ada)
        sys.commit(NewPublish(listing))
        sys.commit(NewCloseAccount(ada))
        return listing
    }
}

