# OQ37 — Deleting a record: do its `when leaving` rules fire, and may the same commit also change it?

**Status:** open — discussion and rulings (R1–R10) live in `../investigate-delete.md`; the rulings are **implemented in v0** (2026-08-22: `delete`, `undeletable`, `? initially required` — grammar.md, checks V23–V28, `examples/moderation/`); still open: the two decisions in **§1 and §2 below**, plus the stress-test pass over R1–R10 (a `TODO.md` item)
**In plain terms:** Velle can now delete records (the original question here — can deletion be an ordinary rule effect without breaking what the spec proves — is answered and implemented); what remains to decide is two shipped-with-a-default behaviors: should the rules that react to a record leaving a state also run when the record is deleted, and may a single commit both change a record's field and delete that record (refused today)?
**Opened by:** design discussion (2026-08-14); distinct from [OQ27](OQ27-erasure.md) (erasure/retention — the storage-policy face; this is the description face)
**See:** `../investigate-delete.md` (the discussion) · README §4, §8 "Frozen fields", §12, §13, §18 · `evaluation.md` "Transient acts"

---

The frame under investigation: delete is instance-granularity mutation (assignment is field-granularity) — the statement declarative, storage realization left to compilation, legality *derived from what the spec proves using the instance's existence* (guard witnesses, singularity proofs, spent `never`s, monotone predicates) rather than banned by category.

Threads, developed in the investigation doc: the `delete` statement (literal static path, one-deleter); the existence-dependency check (fail-closed, connected diagnostics; guard re-arming **ruled not-signable 2026-08-14** — intentional re-triggering is reversal-as-data, cleanup is provable disjointness or OQ27 retention; coarseness **ruled conservative 2026-08-14** — the check reuses the shared refinement-overlap disjointness prover, nothing finer; disjointness must be written into predicates, restructure is the discharge, sharpening is a backward-compatible relaxation riding OQ16; catalog C1–C11 in the investigation doc is the normative exhibit set); deleting-commit semantics (an instance becomes transient at its final commit — last readers, copy idiom, stranding mirror of V17); refinement recalculation (free, by delete-is-a-commit; deletion-vs-predicate-exit distinguishability open); validation (state-scoped `undeletable` gate — **accepted 2026-08-14** — + partition idiom; gate polarity **ruled negative 2026-08-14** — positive-exhaustive sentences like "deletable only if draft" ride the state partition declaration); cascade as referential completeness (per-hop, spelled, never transitive). **The crux:** evidence that references deleted state — resolved (2026-08-14/15): absorbing references composited with per-field copies for what must survive; syntax is **R10's decomposition** (`one <Shape>? initially required` — read-side optionality and creation-side requiredness orthogonal; absence causes are ordinary spec: target deletion, or a rule assigning `none`, which is also how a field is cleared — OQ27's erasure rides this; supersedes the earlier `unless deleted` marker, R4); identity-surviving references and `is deleted` rejected (imply stored records of deleted records); built-in tombstone not a resolution, sugar at most — a deletion record is an explicitly modeled outcome shape, never minted for free.

---

## 1. When a delete removes an instance, should every one of its exit rules fire?

**Needs your ruling.** The v0 compiler ships a default; whether the default is the right semantics is undecided.

An exit rule — `rule X when leaving R { ... }` — fires when an instance stops being a member of the refinement `R`. Ordinarily that happens because a field changed and the refinement's predicate flipped from true to false. Deletion makes it happen a second way: at the deleting commit, the deleted instance leaves every refinement it was a member of, because it ceases to exist. The two causes are different business events — *left because the predicate flipped* and *left because the record was removed* — but to an exit rule they look identical.

**The implemented default:** every exit rule fires for the deleted instance at the deleting commit. The rule body reads the instance's final values, which is coherent because exit rules were already the one construct allowed to read their subject's values from just before the change that removed it (checks.md V28 covers the boundary cases where the subject would not survive to a delayed firing).

**Why the default might be wrong** — a self-contained example:

```velle
expose shape Listing {
    title: text
    isDraft: boolean initially true
}

shape Draft = Listing where isDraft

shape PublicationNotice {
    title: text
}

-- Written to mean "the listing was published": short of deletion, the only
-- way a listing leaves Draft is a rule assigning isDraft = false.
rule AnnouncePublication when leaving Draft {
    PublicationNotice from { title: title }
}

expose transient shape DiscardDraft {
    listing: one Listing
}

rule ApplyDiscard when (DiscardDraft where listing is Draft) {
    delete listing
}

-- The complement pair below only satisfies transient-act totality (checks.md
-- V18 — every DiscardDraft must be answered); it is not part of the point.
shape DiscardRefusal {
    title: text
}

rule RefuseDiscard when (DiscardDraft where not (listing is Draft)) {
    DiscardRefusal from { title: listing.title }
}
```

Under the implemented default, discarding a draft fires `AnnouncePublication`, producing a publication notice for a listing that was never published. The author wrote the exit rule to mean one specific transition (publication) and deletion counterfeits it.

**What is already settled next to this:** the opposite need — reacting to a deletion *on purpose* — will not get a new trigger. There is no `when deleted`; a business that must react to "this was removed" models the record the deletion leaves behind explicitly, in the deleting rule (rule `ApplyDiscard` in `examples/moderation/moderation.velle` creates a `DiscardRecord` in the same commit that deletes the listing, and durable reactions hang off that record — the no-built-in-tombstone ruling). So the only open half is whether exit rules written for predicate transitions should *also* fire at deletion.

**The options:**

- **(a) Keep the default — every exit rule fires.** An author whose exit rule means only the predicate transition must make that explicit (for example, condition the rule on evidence the transition itself produced). Cheapest; the hazard is silent wrong firings like `AnnouncePublication` above.
- **(b) Exit rules do not fire at deletion.** Deletion is removal, not a transition; cleanup-at-deletion is written in the deleting rule or against the deletion record instead. The hazard is the mirror image: an exit rule written as an episode closer (for example, rule `NoteSettlementReversal when leaving SettledOrder` in `examples/payments/payments.velle`) silently does not run when its subject is deleted mid-episode.
- **(c) A per-rule distinction** — new syntax letting an exit rule say which causes it covers. This is new language surface and would need its own design pass.

## 2. May one commit both write a field of an instance and delete that same instance?

**Needs your ruling.** The v0 compiler refuses this case without choosing a meaning for it ("fail closed": rejected until a real use case argues for a semantics); whether that refusal is permanent is undecided.

The rest of the `delete` statement is ruled and implemented (checks.md V23): the target is a literal static path; one deleter per instance per commit (two rules deleting the same instance from one commit is the same coincidence error as two writers of one field). The one edge case left open is a commit that both changes a field of an instance and removes the instance — refused today on the argument that there is no business sentence "change it and also remove it, at once." A self-contained example of what is refused:

```velle
expose shape Account {
    holder: text
    status: text
}

expose transient shape CloseAccount {
    account: one Account
}

-- Refused (checks.md V23): this commit both writes account.status and
-- removes the account — the written value exists at no moment any
-- outside observer could see.
rule ApplyClose when CloseAccount {
    account.status = "closed"
    delete account
}
```

The refusal is per *commit*, not per rule body: the same error fires when one rule writes `account.status` and a different rule deletes the account and both are triggered by the same commit. What stays legal and is not in question: the deleting rule *reading* the instance (it is one of the last readers — rule `ApplyDiscard` in `examples/moderation/moderation.velle` reads `listing.title` in the commit that deletes the listing), creating other records in the deleting commit, and writing fields of *other* instances.

**The options:**

- **(a) Ratify the refusal as permanent.** "Change it and also remove it" stays a contradiction; the pending-a-use-case hedge is dropped and the check becomes a settled ruling.
- **(b) Admit a use case with a defined order.** The only candidate meaning is "the write happens first, so the last readers — including exit rules — observe the written value, then the instance is removed." Note this only matters if exit rules fire at deletion, so this option is entangled with §1: deciding §1 for option (b) there removes the main reason to want write-plus-delete here.
