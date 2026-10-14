package velle

import kotlin.test.Test
import velle.generated.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import velle.generated.PartitionDriftSystem

/**
 * The exhibit itself: the same lock/edit/unlock story driven through both
 * spellings of the act partition. The bare spelling misbehaves — every
 * assertion marked THE DEFECT passes and *shouldn't*; the handled-once
 * spelling behaves. Read the two tests side by side.
 */
open class DriftDemonstrationTest {

    /** The system under test; the storage-backed variants override this. */
    protected open fun newSystem(): PartitionDriftSystem = PartitionDriftSystem()

    // ── the drift-exposed spelling ───────────────────────────────────────────

    @Test
    fun `bare partition - applied edits drift into refusal and back`() {
        val sys = newSystem()
        sys.commit(NewCreateNote("minutes", "v1"))
        val note = sys.notes().single()

        // an editor's edit, applied while the note is open: correct so far
        sys.commit(NewBareEdit(note, "v2-by-editor"))
        assertEquals("v2-by-editor", sys.read(note).body)
        assertEquals(0, sys.bareEditRefusals().size)

        // an admin locks the note. Nothing about the old edit changed — but it
        // drifts into RefusedBareEdit, and a refusal is recorded for an edit
        // that was applied long ago.
        sys.commit(NewLockNote(note))
        assertEquals(1, sys.bareEditRefusals().size)                    // THE DEFECT: spurious refusal
        assertEquals(sys.bareEdits().single(), sys.read(sys.bareEditRefusals().single()).edit)

        // the admin fixes the text through the lock-exempt rename
        sys.commit(NewRenameText(note, "final-by-admin"))
        assertEquals("final-by-admin", sys.read(note).body)

        // unlocking drifts the old edit back into ApplicableBareEdit — the
        // rule re-fires, and the superseded text resurrects, silently
        // clobbering the admin's newer value.
        sys.commit(NewUnlockNote(note))
        assertEquals("v2-by-editor", sys.read(note).body)                         // THE DEFECT: stale write resurrected

        // and every future lock refuses the same act again, forever
        sys.commit(NewLockNote(note))
        assertEquals(2, sys.bareEditRefusals().size)                    // THE DEFECT: one refusal per lock, same act
    }

    // ── the handled-once spelling ────────────────────────────────────────────

    @Test
    fun `handled-once partition - the same story behaves`() {
        val sys = newSystem()
        sys.commit(NewCreateNote("minutes", "v1"))
        val note = sys.notes().single()

        // the editor's edit, applied while open — and anchored by its evidence
        sys.commit(NewSafeEdit(note, "agenda-by-editor"))
        assertEquals("agenda-by-editor", sys.read(note).title)
        assertEquals(1, sys.editApplications().size)

        // the lock re-partitions nothing: the edit is handled, not refusable
        sys.commit(NewLockNote(note))
        assertEquals(0, sys.editRefusals().size)

        // an edit attempted *during* the lock is refused, once, with a reason
        sys.commit(NewSafeEdit(note, "vandalism"))
        assertEquals(1, sys.editRefusals().size)
        assertEquals("note is locked", sys.read(sys.editRefusals().single()).reason)
        assertEquals("agenda-by-editor", sys.read(note).title)

        // unlocking resurrects nothing: the applied edit is done, the refused
        // edit is done — neither re-enters the partition
        sys.commit(NewUnlockNote(note))
        assertEquals("agenda-by-editor", sys.read(note).title)
        assertEquals(1, sys.editApplications().size)
        assertEquals(1, sys.editRefusals().size)

        // and future locks refuse nothing retroactively
        sys.commit(NewLockNote(note))
        assertEquals(1, sys.editRefusals().size)

        with(sys) {
            assertTrue(sys.safeEdits().none { sys.read(it).isUnhandledSafeEdit }, "every act handled exactly once")
        }
    }

    // ── the transient spelling ───────────────────────────────────────────────

    @Test
    fun `transient partition - the same story, with no acts left to drift`() {
        val sys = newSystem()
        sys.commit(NewCreateNote("minutes", "v1"))
        val note = sys.notes().single()

        // the editor's edit, applied while open — and then the act is gone:
        // an input to the state, not a member of it
        sys.commit(NewTransientEdit(note, "agenda-by-editor"))
        assertEquals("agenda-by-editor", sys.read(note).title)

        // the lock re-partitions nothing — there is no act in the state to
        // re-partition, so no refusal can be minted for old edits, ever
        sys.commit(NewLockNote(note))
        assertEquals(0, sys.transientEditRefusals().size)

        // an edit attempted *during* the lock is refused, once, with the
        // payload copied into the refusal (the act itself can't be referenced)
        sys.commit(NewTransientEdit(note, "vandalism"))
        assertEquals(1, sys.transientEditRefusals().size)
        assertEquals("vandalism", sys.read(sys.transientEditRefusals().single()).requestedTitle)
        assertEquals("agenda-by-editor", sys.read(note).title)

        // unlocking resurrects nothing — same reason: nothing is there
        sys.commit(NewUnlockNote(note))
        assertEquals("agenda-by-editor", sys.read(note).title)
        assertEquals(1, sys.transientEditRefusals().size)

        sys.commit(NewLockNote(note))
        assertEquals(1, sys.transientEditRefusals().size) // no per-lock refusals, ever
    }
}

