package velle.generated.partitiondrift

import velle.generated.PartitionDriftSystem
import velle.generated.*

/** The human-owned scenarios the generated partition-drift specs demand. */
class Givens(private val sys: PartitionDriftSystem) : RequiredGivens {

    override fun createNote() {
        sys.commitCreateNote("minutes", "v1")
    }

    private fun note(): Note {
        sys.commitCreateNote("minutes", "v1")
        return sys.notes().last()
    }

    private fun lockedNote(): Note {
        val n = note()
        sys.commitLockNote(n)
        return n
    }

    override fun lockNote(): LockNote {
        sys.commitLockNote(note())
        return sys.lockNotes().last()
    }

    override fun unlockNote(): UnlockNote {
        sys.commitUnlockNote(lockedNote())
        return sys.unlockNotes().last()
    }

    override fun renameText(): RenameText {
        sys.commitRenameText(note(), "v2")
        return sys.renameTexts().last()
    }

    override fun applicableBareEdit(): BareEdit {
        sys.commitBareEdit(note(), "v2")
        return sys.bareEdits().last()
    }

    override fun refusedBareEdit(): BareEdit {
        sys.commitBareEdit(lockedNote(), "v2")
        return sys.bareEdits().last()
    }

    override fun applicableSafeEdit(): SafeEdit {
        sys.commitSafeEdit(note(), "agenda")
        return sys.safeEdits().last()
    }

    override fun refusedSafeEdit(): SafeEdit {
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
