package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OQ37's deleting-commit semantics, executed: the instance is fully present
 * within the deleting transaction (rules fired by the commit are its last
 * readers), removed at close; references at it absorb (`is none`); deletion
 * is a commit, so drift — entries, exits, cascades — fires from it.
 */
class DeleteRuntimeTest {

    private fun moderation(): VelleSystem {
        val decls = Parser.parse(File("../examples/moderation/moderation.velle").readText())
        assertEquals(emptyList(), Validator.validate(File("../examples/moderation/moderation.velle").readText()))
        return VelleSystem(Model(decls))
    }

    private fun accepted(r: CommitResult): Long {
        assertIs<CommitResult.Accepted>(r, "commit refused: $r")
        return (r as CommitResult.Accepted).id
    }

    @Test
    fun `discarding a draft deletes it, the record and the reporter notice land in the same transaction`() {
        val sys = moderation()
        val seller = accepted(sys.commit("Seller", mapOf("name" to "Ada")))
        val listing = accepted(sys.commit("Listing", mapOf("title" to "Old bike", "seller" to seller)))
        val report = accepted(sys.commit("ListingReport", mapOf(
            "listing" to listing, "listingTitle" to "Old bike", "reason" to "spam")))

        accepted(sys.commit("DiscardDraft", mapOf("listing" to listing, "reason" to "seller request")))

        // the listing is gone; the deletion record copied its title (a last read)
        assertEquals(emptyList(), sys.instancesOf("Listing"))
        val record = sys.instancesOf("DiscardRecord").single()
        assertEquals("Old bike", sys.get(record, "listingTitle"))

        // the report absorbed the deletion: entry into OrphanReport was drift at
        // the deleting commit, and NotifyReporter fired within that transaction
        assertNull(sys.get(report, "listing"))
        assertTrue(sys.isMember(report, "OrphanReport"))
        val notice = sys.instancesOf("ReporterNotice").single()
        assertEquals("Old bike", sys.get(notice, "listingTitle"))
        assertEquals(report, sys.get(notice, "report"))
    }

    @Test
    fun `the undeletable gate makes refusal data - a published listing survives its discard request`() {
        val sys = moderation()
        val seller = accepted(sys.commit("Seller", mapOf("name" to "Ada")))
        val listing = accepted(sys.commit("Listing", mapOf("title" to "Rare book", "seller" to seller)))
        accepted(sys.commit("Publish", mapOf("listing" to listing)))
        assertTrue(sys.isMember(listing, "PublishedListing"))

        accepted(sys.commit("DiscardDraft", mapOf("listing" to listing, "reason" to "nope")))

        // the act was answered — by the refusal record, not the delete
        assertEquals(listOf(listing), sys.instancesOf("Listing"))
        assertEquals(emptyList(), sys.instancesOf("DiscardRecord"))
        val refusal = sys.instancesOf("DiscardRefusal").single()
        assertEquals("Rare book", sys.get(refusal, "listingTitle"))
    }

    @Test
    fun `closing an account cascades as drift - drafts swept, published listings orphaned but kept`() {
        val sys = moderation()
        val seller = accepted(sys.commit("Seller", mapOf("name" to "Ada")))
        val draft = accepted(sys.commit("Listing", mapOf("title" to "Draft thing", "seller" to seller)))
        val published = accepted(sys.commit("Listing", mapOf("title" to "Live thing", "seller" to seller)))
        accepted(sys.commit("Publish", mapOf("listing" to published)))
        val report = accepted(sys.commit("ListingReport", mapOf(
            "listing" to draft, "listingTitle" to "Draft thing", "reason" to "dup")))

        accepted(sys.commit("CloseAccount", mapOf("seller" to seller)))

        // the seller is gone; the draft was swept by deletion-caused drift at
        // the same commit; the published listing absorbed and stayed
        assertEquals(emptyList(), sys.instancesOf("Seller"))
        assertEquals(listOf(published), sys.instancesOf("Listing"))
        assertNull(sys.get(published, "seller"))
        assertTrue(sys.isMember(published, "OrphanedListing"))

        // the swept draft's report rode the second hop of the cascade
        assertTrue(sys.isMember(report, "OrphanReport"))
        assertEquals("Draft thing", sys.get(sys.instancesOf("ReporterNotice").single(), "listingTitle"))
    }

    @Test
    fun `initially required refuses an absent reference at the boundary`() {
        val sys = moderation()
        val r = sys.commit("ListingReport", mapOf("listingTitle" to "x", "reason" to "spam"))
        assertIs<CommitResult.Refused>(r)
    }

    @Test
    fun `an exit rule fires at the deleting commit as the instance's last reader`() {
        val source = """
            expose shape Task { name: text, done: boolean initially false }
            shape OpenTask = Task where not done
            expose transient shape Drop { task: one Task }
            rule ApplyDrop when Drop { delete task }
            rule LogExit when leaving OpenTask {
                ExitLog from { name: name }
            }
            shape ExitLog { name: text }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(source))
        val sys = VelleSystem(Model(Parser.parse(source)))
        val task = accepted(sys.commit("Task", mapOf("name" to "water plants")))
        accepted(sys.commit("Drop", mapOf("task" to task)))
        assertEquals(emptyList(), sys.instancesOf("Task"))
        // the exit rule read the deleted task's name — the last-reader contract
        assertEquals("water plants", sys.get(sys.instancesOf("ExitLog").single(), "name"))
    }

    @Test
    fun `a scheduled prune deletes its own subjects and the guard stays quiet`() {
        val source = """
            expose shape Invoice { code: text, lastRemindedOn: Date? }
            shape Reminder { invoice: one Invoice? initially required, sentOn: Date }
            rule RemindOverdue
                when (Invoice where lastRemindedOn is none or lastRemindedOn <= today - 3 days)
                on Daily {
                Reminder from { invoice: this, sentOn: today }
                this.lastRemindedOn = today
            }
            rule PruneOldReminders when (Reminder where sentOn < today - 90 days) on Monthly {
                delete this
            }
        """.trimIndent()
        assertEquals(emptyList(), Validator.validate(source))
        val sys = VelleSystem(Model(Parser.parse(source)))
        accepted(sys.commit("Invoice", mapOf("code" to "A-1")))
        sys.tick("Daily")
        assertEquals(1, sys.instancesOf("Reminder").size)

        // 120 days later the evidence is prunable; the field witness still
        // rate-limits, so pruning re-applies nothing
        sys.advanceDays(120)
        sys.tick("Monthly")
        assertEquals(emptyList(), sys.instancesOf("Reminder"))
        sys.tick("Daily") // due again by the field witness — one new reminder, not a re-fold
        assertEquals(1, sys.instancesOf("Reminder").size)
        assertEquals(emptyList<String>(), sys.failures)
    }
}
