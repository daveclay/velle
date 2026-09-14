package velle

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import velle.generated.PartitionDriftStoreOverGeneric
import velle.generated.PartitionDriftStoreResolver
import velle.generated.PartitionDriftSystem
import velle.sqlite.SqliteStore

/**
 * The exhibit over real storage: the drift hazard and its two remedies are
 * properties of the spec, not of where the state lives — every assertion
 * marked THE DEFECT still passes, and still shouldn't. Once through the
 * generic protocol, once through the generated typed store surface.
 */
class DriftDemonstrationOverSqliteTest : DriftDemonstrationTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PartitionDriftSystem = PartitionDriftSystem().also { SqliteStore.attach(it.system, conn) }
}

class DriftDemonstrationOverTypedStoreTest : DriftDemonstrationTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PartitionDriftSystem = PartitionDriftSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = PartitionDriftStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(PartitionDriftStoreResolver(store, sys.system.model), backend)
    }
}

/** The partitions across processes: handled-once evidence in storage keeps an old act handled; a bare act drifts from its stored row too. */
class DriftPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): PartitionDriftSystem = PartitionDriftSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")

    @Test
    fun `handled-once acts stay handled in a later process`() {
        val first = process()
        accept(first.commitCreateNote("minutes", "v1"))
        val note = first.notes().single()
        accept(first.commitSafeEdit(note, "agenda-by-editor"))
        accept(first.commitLockNote(note))
        assertEquals(1, first.editApplications().size)
        assertEquals(0, first.editRefusals().size)

        val second = process()
        val note2 = second.note(second.system.handleFor("Note", 1L))
        accept(second.commitSafeEdit(note2, "vandalism"))
        assertEquals(1, second.editRefusals().size, "an edit during the lock is refused, once")
        assertEquals("agenda-by-editor", note2.title)
        accept(second.commitUnlockNote(note2))
        assertEquals("agenda-by-editor", note2.title, "the stored application evidence keeps the old edit handled")
        assertEquals(1, second.editApplications().size)
        assertEquals(1, second.editRefusals().size)
    }

    @Test
    fun `the bare partition drifts from stored rows exactly as from memory`() {
        val first = process()
        accept(first.commitCreateNote("minutes", "v1"))
        val note = first.notes().single()
        accept(first.commitBareEdit(note, "v2-by-editor"))
        assertEquals("v2-by-editor", note.body)

        val second = process()
        val note2 = second.note(second.system.handleFor("Note", 1L))
        accept(second.commitLockNote(note2))
        assertEquals(1, second.bareEditRefusals().size) // THE DEFECT: the stored act re-partitions under the lock
    }
}
