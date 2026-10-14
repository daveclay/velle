package velle.generated.partitiondrift

import velle.generated.PartitionDriftSystem
import velle.generated.*

/** The human-owned scenarios the generated partition-drift specs demand. */
class Givens(private val sys: PartitionDriftSystem) : RequiredGivens {

    override fun createNote() {
        sys.commit(NewCreateNote("minutes", "v1"))
    }

    private fun note(): Note {
        sys.commit(NewCreateNote("minutes", "v1"))
        return sys.notes().last()
    }

    private fun lockedNote(): Note {
        val n = note()
        sys.commit(NewLockNote(n))
        return n
    }

    override fun lockNote(): LockNote {
        sys.commit(NewLockNote(note()))
        return sys.lockNotes().last()
    }

    override fun unlockNote(): UnlockNote {
        sys.commit(NewUnlockNote(lockedNote()))
        return sys.unlockNotes().last()
    }

    override fun renameText(): RenameText {
        sys.commit(NewRenameText(note(), "v2"))
        return sys.renameTexts().last()
    }

    override fun applicableBareEdit(): BareEdit {
        sys.commit(NewBareEdit(note(), "v2"))
        return sys.bareEdits().last()
    }

    override fun refusedBareEdit(): BareEdit {
        sys.commit(NewBareEdit(lockedNote(), "v2"))
        return sys.bareEdits().last()
    }

    override fun applicableSafeEdit(): SafeEdit {
        sys.commit(NewSafeEdit(note(), "agenda"))
        return sys.safeEdits().last()
    }

    override fun refusedSafeEdit(): SafeEdit {
        sys.commit(NewSafeEdit(lockedNote(), "agenda"))
        return sys.safeEdits().last()
    }

    override fun applicableTransientEdit() {
        sys.commit(NewTransientEdit(note(), "agenda"))
    }

    override fun refusedTransientEdit() {
        sys.commit(NewTransientEdit(lockedNote(), "agenda"))
    }
}
