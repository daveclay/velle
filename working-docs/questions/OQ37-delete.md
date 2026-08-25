# OQ37 — Deleting a record: spelling the rules that react to a deletion

**Status:** open — rulings R1–R16 live in `../investigate-delete.md`. R1–R10 are **implemented in v0** (2026-08-22: `delete`, `undeletable`, `? initially required` — grammar.md, checks V23–V28, `examples/moderation/`). R11–R16 (2026-08-24/25) settle the exit-versus-deletion semantics, the write-plus-delete refusal, and the trigger family's spelling (`when leaving` / `when deleted` / `when leaving or deleted`) — R11–R14 and R16 **not yet implemented**; the implementation lands together with the remaining spellings. Open inputs: the "transition-only" marker's spelling (**§1**), conditions on the trigger's named state (**§2**), the bare-shape edge (**§3**); plus the stress-test pass over the rulings (a `TODO.md` item).
**In plain terms:** Velle can delete records, and it is settled that a rule reacting to a record changing out of a state never also runs when the record is deleted — reacting to a deletion is its own kind of rule, able to read exactly the same things, spelled `when deleted` (with `when leaving or deleted` covering either cause). What remains: how a rule says "I deliberately ignore deletion," whether the deletion trigger can carry a condition, and what to do about a form that provably can never fire.
**Opened by:** design discussion (2026-08-14); distinct from [OQ27](OQ27-erasure.md) (erasure/retention — the storage-policy face; this is the description face)
**See:** `../investigate-delete.md` (discussion and the rulings table) · README §4, §8 "Frozen fields", §12, §13 (the last-reader rule), §18 · `evaluation.md` "Transient acts" · `compiler/src/test/kotlin/velle/LeavingRuleDeletionProbeTest.kt` (the checked-in exhibits)

---

## What is settled

**Deletion as ordinary description (R1–R10, implemented).** The original question — can removing a record be an ordinary described mutation without breaking what the spec proves — is answered yes: `delete` is a rule effect (literal static path target, one deleter per instance per commit), `undeletable` is the state-scoped deletion gate, `? initially required` is the reference that absorbs its target's deletion (with per-field copies for what must survive), cascade is per-hop referential completeness, and existence-dependency is checked fail-closed. The worked fixture is `examples/moderation/moderation.velle`; the checks are checks.md V23–V28.

**Which cause a rule reacts to (R11–R14, settled 2026-08-24/25, not yet implemented).** A record leaves a state for two different business reasons — a field changed and the state's predicate flipped false, or the record was deleted — and:

- **Bare `when leaving` means the predicate flipped, never deletion** (R11, R12). The v0 default (every `when leaving` rule also fires at deletion) is rejected: it lets a deletion counterfeit the transition the rule was written for — the checked-in probe shows a discard producing a publication notice for a listing that was never published (`LeavingRuleDeletionProbeTest.kt`, the counterfeit-firing probe). Under the settled semantics no such fabrication is possible.
- **Reacting to a deletion is its own per-rule trigger** (R11) — no modeled deletion record is required just to have something to trigger on. A durable deletion *record* remains the explicitly modeled choice for businesses that want deletion *memory* (rule `ApplyDiscard` in `examples/moderation/moderation.velle` creating a `DiscardRecord`); the trigger provides the *reaction*.
- **A `delete` statement whose scope can reach a `when leaving` rule's state is a hard error until the author addresses it** (R13). The spec is ambiguous about what that rule means for a deleted member, and Velle errors and tells the author rather than compiling an ambiguity. The error's trigger is narrow and precise — deleters are statically known (checks.md V23's literal-target requirement), so it fires only when the spec actually contains a reaching `delete` (scope not provably disjoint from the state, by the shared prover). The three discharges: add a `when deleted` rule (R16), restructure the deleter's predicate to provable disjointness (the checks.md V25/V27 pattern; true-but-unprovable disjointness blocks until restructured, never signed — the R8 posture), or mark the rule "transition-only, deliberately" (§1).
- **The trigger family is read-symmetric** (R14). Whichever form a rule uses, the body reads the subject's properties as of the triggering commit, plus the named state's `captured` properties as their last reader — README §13's last-reader rule extends to the deletion side unchanged (deletion ends the membership; the capture's retraction lands at that commit's close). An author learns one reading rule; there is no "`when leaving` works this way, `when deleted` works this other different way." The two differences the record's non-existence forces may surface only as named compile errors, never as silent behavioral divergence: keeping a reference to the subject (legal on the predicate-exit side; refused on the deletion side by checks.md V24 — copy the field instead), and delayed firing (`after commit` / tick backstop: the deletion side is transaction-bound, since a record kept to read later is the deletion-memory R5 forbids; the leaving side may delay only where no deleter can reach the subject in the gap, checks.md V28).

**Write-plus-delete is refused, permanently (R15).** One commit never both writes a field of an instance and deletes that instance — there is no business sentence "change it and also remove it, at once" (checks.md V23, implemented; same-body at path granularity, cross-rule at shape granularity). Reading the instance in the deleting commit, creating other records there, and writing other instances all stay legal.

**The trigger family's spelling (R16).** `when leaving X` (the predicate flipped) / `when deleted X` (the record was deleted) / `when leaving or deleted X` (either cause) — one grammar slot: `"when" ("leaving" ("or" "deleted")? | "deleted")? condition`. The participle mix is deliberate: both forms elide the same subject-and-copula frame — "when [the record is] leaving," "when [the record is] deleted" — so `deleted` reads as an eventive passive, not a past tense, and the voice difference encodes real agency: a record leaves a state by its own data changing, and is deleted by something else. (`when deleting` was rejected as reading like the rule performs the deletion; `when being deleted` as paying words for what the ellipsis already handles.) A `when deleted` rule's subject is the deleted record, read as a last reader inside the deleting commit; what must survive gets copied, and a combined-form body is checked against the same deletion-side constraints (R14) — where a transition-side body keeps references, the author writes two rules with differing bodies instead. The worked case — tell the seller when their draft is discarded, with no deletion record kept:

```velle
expose shape Seller {
    name: text
}

expose shape Listing {
    title: text
    seller: one Seller
    isDraft: boolean initially true
}

shape Draft = Listing where isDraft

shape SellerNotice {
    seller: one Seller
    listingTitle: text
}

-- SETTLED SYNTAX (R16), not yet implemented: fires at the commit that
-- deletes a Draft; `this` is the deleted listing, readable one last time.
-- The title is copied out; the seller reference is legal because the
-- seller lives on.
rule NoteDiscard when deleted Draft {
    SellerNotice from { seller: seller, listingTitle: title }
}
```

The v0 compiler still implements the rejected default; when the remaining spellings below land, the counterfeit-firing probe in `LeavingRuleDeletionProbeTest.kt` flips to asserting no notice, and its gap probes become a validation-error assertion (R13) — that spec's own fix is now `when leaving or deleted OpenLead`.

## 1. How does a rule say "transition-only, deliberately"?

An author whose `when leaving` rule really does mean only the transition, in a spec that really does delete members of that state, has an R13 error and neither of the first two discharges fits: they do not want a deletion reaction, and the deleter cannot be restructured to disjointness because deleting members of that state is the feature. The worked case — the spec that motivated R11 (checked in as the counterfeit-firing probes in `compiler/src/test/kotlin/velle/LeavingRuleDeletionProbeTest.kt`):

```velle
expose shape Listing {
    title: text
    isDraft: boolean initially true
}

shape Draft = Listing where isDraft

shape PublicationNotice {
    title: text
}

-- Means "the listing was published" and nothing else: short of deletion,
-- the only way a listing leaves Draft is a rule assigning isDraft = false.
rule AnnouncePublication when leaving Draft {
    PublicationNotice from { title: title }
}

expose transient shape DiscardDraft {
    listing: one Listing
}

-- Deleting drafts is the feature — this deleter cannot be made disjoint
-- from Draft. Under R13 the spec is refused until AnnouncePublication
-- states what it means for a discarded draft; "publication only" is the
-- honest answer, and it needs a spelling.
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

The marker is one vocabulary with R16's trigger family — it says "not at deletion" in whatever way the trigger says "at deletion." Natural candidates under the settled family: a fourth trigger form such as `when only leaving Draft` or `when leaving not deleted Draft`, or another spelling entirely. No candidate is endorsed yet.

## 2. May the trigger's named state carry a condition?

`when leaving` takes a full condition (`when leaving (Draft where seller is some)`). Whether `when deleted` and `when leaving or deleted` take one identically — which would fall out of reusing the existing condition grammar — or are restricted to a bare state name is undecided. Symmetry (R14's one-rule-learned-once goal) argues for identical; anything narrower needs a reason.

## 3. The bare-shape edge: a trigger half that can never fire

On a bare shape there is no predicate to flip, so a record only leaves it by deletion — the predicate-exit half of a bare-shape trigger is inert. Today this validates clean with no advisory:

```velle
expose shape Listing {
    title: text
}

shape GoneNotice {
    title: text
}

-- Listing is a bare shape: no predicate, so under the settled semantics
-- this rule can never fire. (`when leaving or deleted Listing` would be
-- effectively `when deleted Listing`.)
rule NoteGone when leaving Listing {
    GoneNotice from { title: title }
}
```

Whether Velle treats the inert half as an error, an advisory (the dead-machinery family, checks.md A2), or simply allows it is undecided.

