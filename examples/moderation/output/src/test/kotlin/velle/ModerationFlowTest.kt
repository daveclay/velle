package velle.generated.moderation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.CommitResult
import velle.generated.ModerationSystem

/**
 * Deletion driven through the generated typed surface (README §12, §13;
 * OQ37) — the business-flow companion to the generated per-rule specs:
 * `delete` as a rule effect, the records a deletion leaves behind, the
 * `undeletable` gate refusing as data, the absorbing reference reading as
 * absent, and a deletion cascading through visible rules.
 */
open class ModerationFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): ModerationSystem = ModerationSystem()
    protected val sys: ModerationSystem by lazy { newSystem() }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")

    private fun seller(name: String): ModerationSystem.SellerView {
        accept(sys.commitSeller(name))
        return sys.sellers().last()
    }

    private fun listing(seller: ModerationSystem.SellerView, title: String): ModerationSystem.ListingView {
        accept(sys.commitListing(title, seller))
        return sys.listings().last()
    }

    @Test
    fun `discarding a draft deletes the listing and leaves the records the rules made`() {
        val ada = seller("Ada")
        val lamp = listing(ada, "brass lamp")
        accept(sys.commitListingReport(lamp, "brass lamp", "spam"))
        val report = sys.listingReports().single()

        accept(sys.commitDiscardDraft(lamp, "duplicate"))

        assertEquals(emptyList(), sys.listings(), "the row is gone")
        assertEquals(emptyList(), ada.listings, "and so is the inverse's view of it")
        assertEquals("duplicate", sys.discardRecords().single().reason, "the explicitly modeled deletion record")
        assertEquals(ada, sys.sellerNotices().single().seller, "`when deleted`: the seller is told")
        assertEquals("brass lamp", sys.draftClosures().single().listingTitle, "`when leaving or deleted`: either cause")
        assertEquals(0, sys.publicationNotices().size, "`when leaving not deleted`: a discard is not a publication")

        // the report outlives its listing: the reference absorbs, the copy survives
        assertNull(report.listing)
        assertEquals("brass lamp", report.listingTitle)
        assertEquals(listOf(report.id), sys.orphanReports().map { it.id }, "deletion-caused drift entry")
        assertEquals(report, sys.reporterNotices().single().report)
    }

    @Test
    fun `a published listing is undeletable - the discard is refused as data`() {
        val ada = seller("Ada")
        val lamp = listing(ada, "brass lamp")
        accept(sys.commitPublish(lamp))
        assertEquals(1, sys.publicationNotices().size)
        assertEquals(1, sys.draftClosures().size)

        accept(sys.commitDiscardDraft(lamp, "too late"))
        assertEquals("too late", sys.discardRefusals().single().reason)
        assertEquals(listOf(lamp), sys.listings(), "the listing stands")
        assertEquals(0, sys.discardRecords().size)
        assertEquals(0, sys.sellerNotices().size)
    }

    @Test
    fun `closing an account deletes the seller, sweeps orphaned drafts, and keeps published listings`() {
        val ada = seller("Ada")
        val draft = listing(ada, "draft lamp")
        val published = listing(ada, "published lamp")
        accept(sys.commitPublish(published))

        accept(sys.commitCloseAccount(ada))

        assertEquals(emptyList(), sys.sellers())
        assertEquals(listOf(published), sys.listings(), "the draft was swept, the published listing stays")
        assertNull(published.seller, "the absorbing reference reads as absent")
        assertEquals(listOf(published.id), sys.orphanedListings().map { it.id })
        assertEquals(
            setOf("draft lamp", "published lamp"),
            sys.draftClosures().map { it.listingTitle }.toSet(),
            "one closure per exit: the publication and the sweep",
        )
        assertEquals(0, sys.sellerNotices().size, "a swept draft's seller was already gone: no one to notify")
        assertEquals(0, sys.discardRecords().size, "the sweep is not a discard")
        assertTrue(draft.id !in sys.listings().map { it.id })
    }

    @Test
    fun `a report outlives its listing by copying what must survive, and the reporter is told once`() {
        val ada = seller("Ada")
        val lamp = listing(ada, "brass lamp")
        accept(sys.commitListingReport(lamp, "brass lamp", "counterfeit"))
        val report = sys.listingReports().single()

        accept(sys.commitCloseAccount(ada)) // the seller's closure sweeps the draft
        assertNull(report.listing)
        assertEquals("brass lamp", report.listingTitle)
        assertEquals(1, sys.reporterNotices().size)

        // the guard is evidence: later commits fire nothing more for this report
        seller("Grace")
        assertEquals(1, sys.reporterNotices().size)
    }
}
