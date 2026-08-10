# Investigation: `transient` acts, and what "delete" should mean

Context: the partition-drift exhibit (`examples/partition-drift/`) demonstrated that a bare act partition is drift-exposed — and the discussion that followed surfaced a deeper observation: the natural mental model for an act arriving through an API is *a request, consumed at its commit*, while Velle's model is *a fact, persisted forever*. This file works out what to do with that gap. Tracked in `working-docs/TODO.md`.

## Why delete-after-commit cannot be the mechanism

If acts were literally deleted once handled, most of the language's load-bearing idioms die, because *acts being read later is how everything works*:

- **Guards** read past acts: `UnappliedDeposit = Deposit where not applied` — the `Deposit` is an act.
- **The ledger** is acts: `EmailChange`, read forever by `latest(...)` — the act *is* the history.
- **Episodes** are acts: `DelinquencyFlag`/`DunningFlag`, counted and measured later (`ChronicDelinquent`).
- **Outcome records reference their act**: `BareEditRefusal.edit` would dangle the moment the edit was reaped.
- **Captures and `why`** walk reified acts by design — "every captured value traces to data" (README §8).
- **Audit is free** precisely because refused/handled acts persist (`count(RefusedVoid)`).

Many acts are simultaneously requests *and* durable business records (`Payment`, `Deposit`, `EmailChange`). A `transient`-as-deletion marker would bifurcate the data model and break audit and provenance for whichever shapes carried it.

## What the instinct is actually detecting

An act has two aspects the language currently expresses in one shape:

- the **occurrence** — "this request happened," a timeless fact, rightly permanent;
- the **pending-ness** — "this request awaits handling," consumed exactly once, at its handling commit.

Velle's current answer is that pending-ness is data the author writes by hand: the handled-once idiom (`UnhandledSafeEdit = SafeEdit where not exists EditApplication for this and not exists EditRefusal for this`), with each handling rule producing the evidence that disarms its own side. The drift exhibit shows what happens when the author forgets: the partition silently means "an act whose note is locked *right now*," not "an act that arrived while locked."

The A4 advisory (checks.md; implemented) catches the omission after the fact. The `transient` idea is the author declaring the *intent* up front.

## The sketch: `transient` as declared intent, not storage behavior

> `transient expose shape BareEdit { ... } using MockHarness` — "this act must be fully handled at its own commit."

Nothing is deleted and nothing changes at runtime. The marker is a static contract, upgrading A4 from advisory to **error** for this shape:

- every rule triggered by a refinement of the shape must either provably fire only at the act's creation commit, or carry a handled-anchor its body disarms;
- a drift-exposed partition over the shape is a compile error naming the rule and the mutable atom, with the handled-once rewrite as the fix-it;
- (possibly) reading the act from tick-cadence rules without an anchor gets the same treatment.

This is the same declare-intent relationship `tolerates` has with the fold analysis (README §19): the analysis runs for everyone; the declaration turns its finding from guidance into a signed contract. The machinery is already built — A4's conjunct classification and the disarm proof.

## Resolved sub-questions

### Scope: boundary-only

The marker is coherent on a produced shape ("this shape's consequence graph settles within its creating transaction") but would be a false statement on every produced shape worth having — pending-ness across time is exactly what produced work items exist to carry (`ChargeAttempt` is *deliberately* unresolved across the world gap; guard-witnessed items span declared boundaries; episode flags reify pending-ness as the business object). The one produced shape for which it would hold — a pure relay, minted only to trigger same-transaction consequences and never read again — is a call graph wearing a shape costume, which idiomatic Velle says shouldn't exist (A2 dead-machinery territory, not a transience contract).

The principled asymmetry: transience is a statement about the *trust boundary*, where the world commits on its own schedule and arrival-time handling can only be declared; inside the spec, the compiler statically sees every producer and consumer, and the equivalent guarantees are the ordinary checks.

### Placement

Follows from scope: a modifier on `expose` (`expose transient shape BareEdit { ... } using MockHarness`), since the contract is only meaningful where an external committer exists.

## Still open

### Spelling

Whether the word is `transient` at all, given nothing is actually transient in storage (candidates: `handled once`, `consumed`, `settled at commit`).

### Sugar: derive the apparatus, or only demand it?

Worked against the exhibit's safe family. The baseline, hand-written today:

```
expose shape SafeEdit {
    note: one Note
    newTitle: text
} using MockHarness

shape EditApplication {
    edit: one SafeEdit
    appliedOn: DateTime
}

shape EditRefusal {
    edit: one SafeEdit
    reason: text
    refusedOn: DateTime
}

-- the hand-written anchor: the part sugar would derive
shape UnhandledSafeEdit = SafeEdit where
    not exists EditApplication for this and not exists EditRefusal for this

shape ApplicableSafeEdit = UnhandledSafeEdit where not note is LockedNote
shape RefusedSafeEdit    = UnhandledSafeEdit where note is LockedNote

rule ApplySafeEdit when ApplicableSafeEdit {
    note.title = newTitle
    EditApplication from { edit: this, appliedOn: now }
}

rule RefuseSafeEdit when RefusedSafeEdit {
    EditRefusal from { edit: this, reason: "note is locked", refusedOn: now }
}
```

**Design 1 — inferred outcomes: rejected.** No new syntax: the compiler treats as "outcomes" every shape that rules triggered off `SafeEdit` produce with a `SafeEdit`-typed field, and derives the anchor from their absence. It works — until someone adds an unrelated rule, months later, in another file:

```
-- an innocent audit requirement: log every edit request on arrival
shape EditAuditEntry {
    edit: one SafeEdit
    loggedOn: DateTime
}

rule AuditEdit when SafeEdit {
    EditAuditEntry from { edit: this, loggedOn: now }
}
```

Under inference, `EditAuditEntry` is indistinguishable from a real outcome: `AuditEdit` is a rule triggered off `SafeEdit` (criterion one), and `EditAuditEntry` carries a `SafeEdit`-typed field, `edit: one SafeEdit` (criterion two) — the same two facts that made `EditApplication` and `EditRefusal` count as outcomes. So the inferred anchor silently becomes *"no `EditApplication`, no `EditRefusal`, **and no `EditAuditEntry`** reference this act."* But `AuditEdit` fires at every edit's *creation* commit, minting an `EditAuditEntry` immediately: **every edit is born failing the third conjunct, `unhandled SafeEdit` is permanently empty, and `ApplySafeEdit`/`RefuseSafeEdit` silently never fire again.** Edits are accepted and ignored. No diagnostic anywhere — the audit rule is fine, the editing rules are untouched, and the spec still validates. This is exactly README §1's forbidden failure shape: a distant declaration silently re-resolving what an untouched construct means. Rejected.

**Design 2 — declared outcomes, derived anchor: the viable form.** The author names the outcome set; the compiler derives the anchor from *that list and nothing else*:

```
expose transient shape SafeEdit {
    note: one Note
    newTitle: text
} using MockHarness handled by EditApplication, EditRefusal

shape EditApplication {
    edit: one SafeEdit
    appliedOn: DateTime
}

shape EditRefusal {
    edit: one SafeEdit
    reason: text
    refusedOn: DateTime
}

-- `unhandled SafeEdit` is an operator (no compiler-minted name), desugaring to:
--   SafeEdit where not exists EditApplication for this
--                  and not exists EditRefusal for this
shape ApplicableSafeEdit = unhandled SafeEdit where not note is LockedNote
shape RefusedSafeEdit    = unhandled SafeEdit where note is LockedNote

rule ApplySafeEdit when ApplicableSafeEdit {
    note.title = newTitle
    EditApplication from { edit: this, appliedOn: now }
}

rule RefuseSafeEdit when RefusedSafeEdit {
    EditRefusal from { edit: this, reason: "note is locked", refusedOn: now }
}
```

Now replay the same distant addition. The audit rule is **harmless**: `EditAuditEntry` is not in the `handled by` list, so it contributes nothing to the anchor — "handled" means what the declaration says, and only editing the declaration can change it. (`AuditEdit` itself stays legal: its condition is the bare act, no mutable-state atom, so it fires once at creation and can never drift.)

What the contract *does* refuse is a rule that touches the partition without playing by its rules:

```
-- someone adds escalation: locked-out edits should open a ticket
rule EscalateEdit when (unhandled SafeEdit where note is LockedNote) {
    EscalationTicket from { edit: this, openedOn: now }
}
```

`EscalationTicket` is not a declared outcome, so this rule never disarms its trigger — a drift-exposed partition over a `transient` act, which the marker upgrades from A4-advisory to **error**: *"rule 'EscalateEdit' handles a transient act but records no outcome — produce one of `EditApplication`/`EditRefusal`, or add `EscalationTicket` to `SafeEdit`'s `handled by` list."* Both fixes are visible, declared decisions.

The derived obligations, in full — the real value of the sugar (the anchor derivation itself saves ~3 lines):

1. Every rule whose condition partitions the act on mutable state must hang off `unhandled X` — A4 upgraded to an error. (Rules on the bare act with no mutable atom, like `AuditEdit`, are untouched.)
2. Every rule hanging off `unhandled X` must produce exactly one declared outcome referencing the act — the disarm proof, specialized; `EscalateEdit` above is the error case.
3. Reachable-completeness — an unhandled act must always have some rule able to handle it, or it strands unhandled forever.
4. Only handling rules may produce a declared outcome shape — outcomes are the handlers' signatures; an out-of-band producer of `EditRefusal` is an error naming both sites.

Payloads, state atoms, and rule bodies stay author-written.

Against §18's no-sugar bar: the anchor's correlation is always the identity case — §18's own "purely mechanical residue" — and with `transient` already naming the intent, the anchor is the contract's consequence, not a hidden decision. Lean *(revised)*: if `transient` lands, derive — declaration without derivation would leave a checked-but-hand-copied conjunction, the worst of both.

### A4's exposure scoping

The drift hazard itself doesn't care about exposure: an unanchored partition of a *produced* shape over mutable state drifts identically. The advisory's current act-only scope is a conservatism worth revisiting — widen A4 to all shapes; keep the `transient` contract boundary-only.

## Deletion proper: a storage concern, tracked separately

Velle's "delete has no primitive" stance (README §4) is about *description*: facts don't un-happen, and a spec that erases records lies. But real systems owe **erasure** — retention windows, right-to-be-forgotten — and that obligation is about *storage*, not description: "an edit occurred" can remain true in the model while its payload ceases to be physically retrievable. That places retention/erasure with compilation (the same layer as "which database"), plausibly as declared policy the transpiler enforces (annotations on shapes/fields; crypto-shredding or hard deletion as mechanism). What the language owes is at most the policy vocabulary — and a check that no derivation or guard depends on data the policy allows to vanish. Not designed; tracked as its own TODO item.
