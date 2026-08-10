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

**Open sub-questions:**

- *Placement and spelling* — a modifier on `expose` (transience is about arrival) vs. on the shape declaration; whether the word is `transient` at all, given nothing is actually transient in storage (candidates: `handled once`, `consumed`, `settled at commit`).
- *Sugar* — should `transient` also *derive* the apparatus (`Unhandled<X>` plus the two-sided anchor) instead of just demanding it? That must clear §18's no-sugar bar; the counterargument is that "what counts as handled" is per-side business judgment, same as guard granularity. Current lean: demand, don't derive.
- *Scope* — does the contract extend to produced (unexposed) shapes serving as work queues, or is it strictly about the external boundary?

## Deletion proper: a storage concern, tracked separately

Velle's "delete has no primitive" stance (README §4) is about *description*: facts don't un-happen, and a spec that erases records lies. But real systems owe **erasure** — retention windows, right-to-be-forgotten — and that obligation is about *storage*, not description: "an edit occurred" can remain true in the model while its payload ceases to be physically retrievable. That places retention/erasure with compilation (the same layer as "which database"), plausibly as declared policy the transpiler enforces (annotations on shapes/fields; crypto-shredding or hard deletion as mechanism). What the language owes is at most the policy vocabulary — and a check that no derivation or guard depends on data the policy allows to vanish. Not designed; tracked as its own TODO item.
