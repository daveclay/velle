# Spec Index — PartitionDrift

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## CreateNoteSpec.kt

- MaterializeNote - a new CreateNote produces a Note
- ApplyBareEdit - a new ApplicableBareEdit sets note.body
- RefuseBareEdit - a new RefusedBareEdit produces a BareEditRefusal
- ApplySafeEdit - a new ApplicableSafeEdit produces an EditApplication and sets note.title
- RefuseSafeEdit - a new RefusedSafeEdit produces an EditRefusal
- ApplyTransientEdit - a new ApplicableTransientEdit sets note.title
- RefuseTransientEdit - a new RefusedTransientEdit produces a TransientEditRefusal

## LockNoteSpec.kt

- ApplyLock - a new LockNote sets note.locked

## UnlockNoteSpec.kt

- ApplyUnlock - a new UnlockNote sets note.locked

## RenameTextSpec.kt

- ApplyRename - a new RenameText sets note.body
