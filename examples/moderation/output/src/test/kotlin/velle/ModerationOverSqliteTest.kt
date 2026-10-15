package velle.generated.moderation

import java.sql.Connection
import velle.generated.*
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import velle.Commit
import velle.generated.ModerationStoreOverGeneric
import velle.generated.ModerationStoreResolver
import velle.generated.ModerationSystem
import velle.sqlite.SqliteStore

/**
 * The moderation flows over real storage — the one place the commit set's
 * deletion channel is exercised. Once through the generic protocol, once
 * through the generated typed store surface.
 */
class ModerationFlowOverSqliteTest : ModerationFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): ModerationSystem = ModerationSystem().also { SqliteStore.attach(it.system, conn) }
}

class ModerationFlowOverTypedStoreTest : ModerationFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): ModerationSystem = ModerationSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = ModerationStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(ModerationStoreResolver(store, sys.system.model), backend)
    }
}

/**
 * Deletion in storage (Reference §12): the row leaves, its key is never reused,
 * and a surviving row's reference at it stays in the column — the absorbing
 * reference the runtime reads as absent, in this process and the next.
 */
class ModerationPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): ModerationSystem = ModerationSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: Commit<*>) = assertIs<Commit.Accepted<*>>(r, "refused: $r")

    private fun rows(sql: String): List<List<Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { i ->
                        rs.getObject(i).let { if (it is Number) it.toLong() else it }
                    })
                }
            }
        }

    @Test
    fun `a deleted row leaves storage, its key is never reused, and a reference at it reads as absent`() {
        val first = process()
        accept(first.commit(NewSeller("Ada")))
        val ada = first.sellers().last()
        accept(first.commit(NewListing("brass lamp", ada)))
        val lamp = first.listings().last()
        accept(first.commit(NewListingReport(lamp, "brass lamp", "spam")))

        accept(first.commit(NewDiscardDraft(lamp, "duplicate")))
        assertEquals(emptyList(), rows("""SELECT id FROM Listing"""), "the deletion landed")
        assertEquals(listOf(listOf(1L)), rows("""SELECT listing FROM ListingReport"""), "the report's column still names the dead key")
        assertEquals(listOf(listOf("brass lamp")), rows("""SELECT listingTitle FROM DiscardRecord"""))
        assertEquals(listOf(listOf(1L)), rows("""SELECT COUNT(*) FROM SellerNotice"""))

        // a later listing must not inherit the dead key, or the report would point at it
        accept(first.commit(NewListing("vase", ada)))
        assertEquals(listOf(listOf(2L)), rows("""SELECT id FROM Listing"""))

        val second = process()
        val report = second.read.listingReport(1L)
        assertNull(second.read(report).listing, "the absorbing reference, read from a dangling key")
        assertEquals("brass lamp", second.read(report).listingTitle)
        assertEquals(listOf(report.key), second.orphanReports().map { it.key })
        assertEquals(1, second.reporterNotices().size, "fired at the deleting commit, in the first process")
        accept(second.commit(NewSeller("Grace")))
        assertEquals(1, second.reporterNotices().size, "the guard reads its evidence from storage")
    }

    @Test
    fun `closing an account in a later process sweeps the draft and orphans the published listing`() {
        val first = process()
        accept(first.commit(NewSeller("Ada")))
        val ada = first.sellers().last()
        accept(first.commit(NewListing("draft lamp", ada)))
        accept(first.commit(NewListing("published lamp", ada)))
        accept(first.commit(NewPublish(first.listings().last())))

        val second = process()
        accept(second.commit(NewCloseAccount(second.read.seller(1L))))
        assertEquals(listOf(listOf(0L)), rows("""SELECT COUNT(*) FROM Seller"""))
        assertEquals(listOf(listOf(2L, 1L)), rows("""SELECT id, seller FROM Listing"""), "the survivor keeps its dangling seller key")

        val third = process()
        val published = third.read.listing(2L)
        assertNull(third.read(published).seller)
        assertEquals(listOf(published.key), third.orphanedListings().map { it.key })
        assertEquals(1, third.listings().size)
        assertEquals(2, third.draftClosures().size, "the publication's and the sweep's")
    }
}

