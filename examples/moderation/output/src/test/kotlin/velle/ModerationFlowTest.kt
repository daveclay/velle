package velle.generated.moderation

import kotlin.test.Test
import velle.generated.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.Commit
import velle.generated.ModerationSystem

/**
 * Deletion driven through the generated typed surface (Reference §12, §13;
 * OQ37) — the business-flow companion to the generated per-rule specs:
 * `delete` as a rule effect, the records a deletion leaves behind, the
 * `undeletable` gate refusing as data, the absorbing reference reading as
 * absent, and a deletion cascading through visible rules.
 */
open class ModerationFlowTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): ModerationSystem = ModerationSystem()
    protected val sys: ModerationSystem by lazy { newSystem() }

    private fun accept(r: Commit<*, *>) = assertIs<Commit.Accepted<*, *>>(r, "refused: $r")

    private fun seller(name: String): Seller {
        accept(sys.commit(NewSeller(name)))
        return sys.sellers().last()
    }

    private fun listing(seller: Seller, title: String): Listing {
        accept(sys.commit(NewListing(title, seller)))
        return sys.listings().last()
    }

    @Test
    fun `discarding a draft deletes the listing and leaves the records the rules made`() {
        val ada = seller("Ada")
        val lamp = listing(ada, "brass lamp")
        accept(sys.commit(NewListingReport(lamp, "brass lamp", "spam")))
        val report = sys.listingReports().single()

        accept(sys.commit(NewDiscardDraft(lamp, "duplicate")))

        assertEquals(emptyList(), sys.listings(), "the row is gone")
        assertEquals(emptyList(), sys.read(ada).listings, "and so is the inverse's view of it")
        assertEquals("duplicate", sys.read(sys.discardRecords().single()).reason, "the explicitly modeled deletion record")
        assertEquals(ada, sys.read(sys.sellerNotices().single()).seller, "`when deleted`: the seller is told")
        assertEquals("brass lamp", sys.read(sys.draftClosures().single()).listingTitle, "`when leaving or deleted`: either cause")
        assertEquals(0, sys.publicationNotices().size, "`when leaving not deleted`: a discard is not a publication")

        // the report outlives its listing: the reference absorbs, the copy survives
        assertNull(sys.read(report).listing)
        assertEquals("brass lamp", sys.read(report).listingTitle)
        assertEquals(listOf(report.key), sys.orphanReports().map { it.key }, "deletion-caused drift entry")
        assertEquals(report, sys.read(sys.reporterNotices().single()).report)
    }

    @Test
    fun `a published listing is undeletable - the discard is refused as data`() {
        val ada = seller("Ada")
        val lamp = listing(ada, "brass lamp")
        accept(sys.commit(NewPublish(lamp)))
        assertEquals(1, sys.publicationNotices().size)
        assertEquals(1, sys.draftClosures().size)

        accept(sys.commit(NewDiscardDraft(lamp, "too late")))
        assertEquals("too late", sys.read(sys.discardRefusals().single()).reason)
        assertEquals(listOf(lamp), sys.listings(), "the listing stands")
        assertEquals(0, sys.discardRecords().size)
        assertEquals(0, sys.sellerNotices().size)
    }

    @Test
    fun `closing an account deletes the seller, sweeps orphaned drafts, and keeps published listings`() {
        val ada = seller("Ada")
        val draft = listing(ada, "draft lamp")
        val published = listing(ada, "published lamp")
        accept(sys.commit(NewPublish(published)))

        accept(sys.commit(NewCloseAccount(ada)))

        assertEquals(emptyList(), sys.sellers())
        assertEquals(listOf(published), sys.listings(), "the draft was swept, the published listing stays")
        assertNull(sys.read(published).seller, "the absorbing reference reads as absent")
        assertEquals(listOf(published.key), sys.orphanedListings().map { it.key })
        assertEquals(
            setOf("draft lamp", "published lamp"),
            sys.draftClosures().map { sys.read(it).listingTitle }.toSet(),
            "one closure per exit: the publication and the sweep",
        )
        assertEquals(0, sys.sellerNotices().size, "a swept draft's seller was already gone: no one to notify")
        assertEquals(0, sys.discardRecords().size, "the sweep is not a discard")
        assertTrue(draft.key !in sys.listings().map { it.key })
    }

    @Test
    fun `a report outlives its listing by copying what must survive, and the reporter is told once`() {
        val ada = seller("Ada")
        val lamp = listing(ada, "brass lamp")
        accept(sys.commit(NewListingReport(lamp, "brass lamp", "counterfeit")))
        val report = sys.listingReports().single()

        accept(sys.commit(NewCloseAccount(ada))) // the seller's closure sweeps the draft
        assertNull(sys.read(report).listing)
        assertEquals("brass lamp", sys.read(report).listingTitle)
        assertEquals(1, sys.reporterNotices().size)

        // the guard is evidence: later commits fire nothing more for this report
        seller("Grace")
        assertEquals(1, sys.reporterNotices().size)
    }
}

