package velle.generated.partitiondrift

import velle.generated.PartitionDriftSystem

/** The human-owned scenarios the generated partition-drift specs demand. */
class Givens(private val sys: PartitionDriftSystem) : RequiredGivens {

    private fun note(): PartitionDriftSystem.NoteView {
        sys.commitNote("minutes", "v1")
        return sys.notes().last()
    }

    private fun lockedNote(): PartitionDriftSystem.NoteView {
        val n = note()
        sys.commitLockNote(n)
        return n
    }

    override fun lockNote(): PartitionDriftSystem.LockNoteView {
        sys.commitLockNote(note())
        return sys.lockNotes().last()
    }

    override fun unlockNote(): PartitionDriftSystem.UnlockNoteView {
        sys.commitUnlockNote(lockedNote())
        return sys.unlockNotes().last()
    }

    override fun renameText(): PartitionDriftSystem.RenameTextView {
        sys.commitRenameText(note(), "v2")
        return sys.renameTexts().last()
    }

    override fun applicableBareEdit(): PartitionDriftSystem.BareEditView {
        sys.commitBareEdit(note(), "v2")
        return sys.bareEdits().last()
    }

    override fun refusedBareEdit(): PartitionDriftSystem.BareEditView {
        sys.commitBareEdit(lockedNote(), "v2")
        return sys.bareEdits().last()
    }

    override fun applicableSafeEdit(): PartitionDriftSystem.SafeEditView {
        sys.commitSafeEdit(note(), "agenda")
        return sys.safeEdits().last()
    }

    override fun refusedSafeEdit(): PartitionDriftSystem.SafeEditView {
        sys.commitSafeEdit(lockedNote(), "agenda")
        return sys.safeEdits().last()
    }

    override fun applicableTransientEdit() {
        sys.commitTransientEdit(note(), "agenda")
    }

    override fun refusedTransientEdit() {
        sys.commitTransientEdit(lockedNote(), "agenda")
    }
}
