# OQ37 — Deleting a record: the syntax for rules that react to a deletion, and may the deleting commit also change the record?

**Status:** open — discussion and rulings (R1–R13) live in `../investigate-delete.md`; R1–R10 are **implemented in v0** (2026-08-22: `delete`, `undeletable`, `? initially required` — grammar.md, checks V23–V28, `examples/moderation/`); R11–R13 (2026-08-24/25) settle the semantics — bare `when leaving` fires only when the predicate flips, never at deletion; deletion-reaction is its own per-rule trigger; a deleter reaching a `when leaving` rule's state is a hard error until addressed — **not yet implemented**, landing together with the trigger design; still open: the "transition-only" marker's spelling (**§1a**), the trigger spelling (**§1b**), the both-causes form (**§1c**) — with a leaning (2026-08-25) toward the family `when leaving X` / `when deleted X` / `when leaving or deleted X` — the write-plus-delete decision (**§2**), plus the stress-test pass over the rulings (a `TODO.md` item)
**In plain terms:** Velle can now delete records (the original question here — can deletion be an ordinary rule effect without breaking what the spec proves — is answered and implemented); it is settled that a rule reacting to a record leaving a state fires only when the record's values change out of the state, never when the record is deleted — reacting to a deletion will be its own kind of rule, and a spec that deletes records a leaving-rule watches without saying what that rule means for them is an error, not a warning. What remains: the spellings (the deletion rule's trigger word, the "this rule deliberately ignores deletion" marker, a possible both-causes form), and whether a single commit may both change a record's field and delete that record (refused today).
**Opened by:** design discussion (2026-08-14); distinct from [OQ27](OQ27-erasure.md) (erasure/retention — the storage-policy face; this is the description face)
**See:** `../investigate-delete.md` (the discussion) · README §4, §8 "Frozen fields", §12, §13, §18 · `evaluation.md` "Transient acts"

---

The frame under investigation: delete is instance-granularity mutation (assignment is field-granularity) — the statement declarative, storage realization left to compilation, legality *derived from what the spec proves using the instance's existence* (guard witnesses, singularity proofs, spent `never`s, monotone predicates) rather than banned by category.

Threads, developed in the investigation doc: the `delete` statement (literal static path, one-deleter); the existence-dependency check (fail-closed, connected diagnostics; guard re-arming **ruled not-signable 2026-08-14** — intentional re-triggering is reversal-as-data, cleanup is provable disjointness or OQ27 retention; coarseness **ruled conservative 2026-08-14** — the check reuses the shared refinement-overlap disjointness prover, nothing finer; disjointness must be written into predicates, restructure is the discharge, sharpening is a backward-compatible relaxation riding OQ16; catalog C1–C11 in the investigation doc is the normative exhibit set); deleting-commit semantics (an instance becomes transient at its final commit — last readers, copy idiom, stranding mirror of V17); refinement recalculation (free, by delete-is-a-commit; deletion-vs-predicate-exit distinguishability **settled 2026-08-24/25, R11/R12** — `when leaving` is predicate-exit only, deletion-reaction is its own trigger; remaining design in §1a–§1c below); validation (state-scoped `undeletable` gate — **accepted 2026-08-14** — + partition idiom; gate polarity **ruled negative 2026-08-14** — positive-exhaustive sentences like "deletable only if draft" ride the state partition declaration); cascade as referential completeness (per-hop, spelled, never transitive). **The crux:** evidence that references deleted state — resolved (2026-08-14/15): absorbing references composited with per-field copies for what must survive; syntax is **R10's decomposition** (`one <Shape>? initially required` — read-side optionality and creation-side requiredness orthogonal; absence causes are ordinary spec: target deletion, or a rule assigning `none`, which is also how a field is cleared — OQ27's erasure rides this; supersedes the earlier `unless deleted` marker, R4); identity-surviving references and `is deleted` rejected (imply stored records of deleted records); built-in tombstone not a resolution, sugar at most — a deletion record is an explicitly modeled outcome shape, never minted for free.

---

## 1. Settled: `when leaving` fires only when the predicate flips — never at deletion

**Ruled 2026-08-24 (R11) and 2026-08-25 (R12) in `../investigate-delete.md`. The remaining design inputs are §1a–§1c below.**

A record leaves a state for two different business reasons: a field changed and the state's predicate flipped false, or the record was deleted. The settled semantics:

- **Bare `when leaving` means the predicate flipped — nothing else** (R12). `rule X when leaving Draft` fires only when the record stays in the state space and stops satisfying `Draft`'s condition; deleting the record never fires it. A deletion therefore cannot counterfeit a transition: no rule can be made to record a transition that never happened.
- **Reacting to a deletion is its own per-rule trigger** (R11; the spelling is open, §1b). The earlier leaning — "model a deletion record and hang rules off it" — required keeping a record around solely to have something to trigger on, an unnecessary burden. A deletion-reaction rule fires inside the deleting commit as one of the deleted record's last readers, reading its final values; anything that must outlive the record is copied into records the rule creates (a kept required reference to the deleted record would be the ordinary referential-completeness error, checks.md V24). A durable deletion *record* remains an explicitly modeled choice for businesses that want deletion *memory* (rule `ApplyDiscard` in `examples/moderation/moderation.velle` creating a `DiscardRecord`) — it is just not required for a deletion *reaction*.

The v0 compiler still implements the rejected default (every `when leaving` rule fires at deletion). The implementation change lands together with §1b's trigger — dropping the default before a deletion trigger exists would leave no way to react to a deletion at all.

**The example that decided it.** Under the rejected default, this spec validates clean and a discard produces a publication notice for a listing that was never published; under the settled semantics the discard fires nothing:

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

The counterfeit is not hypothetical: `compiler/src/test/kotlin/velle/LeavingRuleDeletionProbeTest.kt` runs this exact spec (plus the publish machinery) through the runtime and shows the notice landing.

**The corpus sweep (2026-08-24) — the settled semantics costs nothing today.** Every `when leaving` rule in the checked-in examples was classified by which cause it actually means. There are four, and all four mean a specific transition — none means "no longer in this state, for any reason":

- rule `NoteSettlementReversal when leaving SettledOrder` in `examples/payments/payments.velle` — means "the order's settlement was reversed" (a refund or forced fresh charge re-opens the settlement episode). A deleted order is not a reversal.
- rule `NoteUnarchival when leaving ArchivedInvoice` in `examples/billing/billing.velle` — means "the invoice was unarchived" (an unarchive request arrived). A deleted invoice is not an unarchival.
- rule `CloseDelinquencyEpisode when leaving Delinquent` in `examples/membership/membership.velle` — means "the member is no longer delinquent, resolve the open episode." A deleted member did not resolve anything; recording a `DelinquencyResolution` for them would state a resolution that never happened.
- rule `NoticeReopen when leaving ClosedTicket` in `examples/membership/membership.velle` — means "the ticket was reopened, tell whoever closed it." A deleted ticket was not reopened.

The rejected default's trap was also structural, not just semantic: each of these rules produces a record holding a required `one` reference to the leaving record itself (`SettlementReversal.order`, `UnarchiveNotice.invoice`, `ReopenNotice.ticket`; `DelinquencyResolution` references the member's open flag, which the member's deletion would itself have to remove). Any deleter of the base shape errors immediately under referential completeness — settled semantics or not, checks.md V24 refuses those deleters regardless of what exit rules mean (exhibited by the first probe in `compiler/src/test/kotlin/velle/LeavingRuleDeletionProbeTest.kt`). Nothing errors today only because the corpus keeps the two features apart: the four rules live in specs with no deleters, and the one spec with deleters (`examples/moderation/moderation.velle`) has no `when leaving` rules.

### 1a. Settled: a deleter reaching a `when leaving` rule's state is a hard error until the author addresses it — the "transition-only" marker needs a spelling

**Ruled 2026-08-25 (R13 in `../investigate-delete.md`): the spec is ambiguous, and Velle errors and tells the author rather than compiling an ambiguity.** The settled `when leaving` semantics cannot fabricate a false record; what it can do is *omit* a reaction: an author writes `when leaving` meaning "for any reason, deletion included," a deleter is added later, and the deletion slips past the rule — nothing fires. The worked case (this spec validates clean today; under R13 it is refused, with an error naming `NoteFollowUpStop` and `ApplyPurge`; it is also the third probe family in `compiler/src/test/kotlin/velle/LeavingRuleDeletionProbeTest.kt`):

```velle
expose shape Lead {
    company: text
    won: boolean initially false
}

shape OpenLead = Lead where not won

shape FollowUpStop {
    company: text
}

-- Written to mean "stop chasing this lead, for any reason": the outreach
-- team clears its queue when a FollowUpStop lands. But `when leaving` fires
-- only when `won` flips — so only the winning path is covered.
rule NoteFollowUpStop when leaving OpenLead {
    FollowUpStop from { company: company }
}

expose transient shape MarkWon {
    lead: one Lead
}

rule ApplyWon when MarkWon {
    lead.won = true
}

expose transient shape PurgeLead {
    lead: one Lead
}

-- Added later: stale leads are purged. The purge deletes an OpenLead — and
-- under R13 the spec now refuses to compile until the author says what
-- NoteFollowUpStop means for a purged lead.
rule ApplyPurge when PurgeLead {
    delete lead
}
```

The error's trigger is precise and narrow: deleters are statically known (checks.md V23 — every `delete` target is a literal static path so the whole-spec compiler knows every deleter of every shape), so the error fires only when the spec actually contains a `delete` statement that can remove a member of the rule's state (the delete's scope not provably disjoint from the state — the same disjointness the other delete checks compute). A spec with no such deleter pays nothing; all four corpus specs compile untouched, and the collision cannot arise silently — writing the `delete` statement is what surfaces it.

The three discharges, fail-closed like the rest of the delete family:

1. **Cover deletion** — add a deletion-reaction rule (§1b's trigger; or the combined form, §1c, if one lands).
2. **Restructure the deleter to provable disjointness** — the deleter's predicate carries the complement of the state's predicate, the discharge pattern checks.md V25/V27 already use. Accepted consequence (the R8 posture): disjointness that is true but not provable blocks until restructured — never signed away.
3. **State "transition-only, deliberately"** — a per-rule marker whose spelling is **the remaining open input here**. It should be designed as one vocabulary with the trigger family — the marker says "not at deletion" in whatever way the trigger says "at deletion." Under the current leaning (`when leaving X` / `when deleted X` / `when leaving or deleted X`, §1b–§1c), natural candidates are a fourth trigger form such as `when only leaving Draft` or `when leaving not deleted Draft`. No marker candidate is endorsed yet — pick alongside the family.

### 1b. How should a rule spell "react when a record in this state is deleted"?

The rule names a state (or a bare shape) and means "a record that was a member of this state was deleted." Its subject is the deleted record, read as a last reader inside the deleting commit; what must survive gets copied. The worked case — tell the seller when their draft is discarded, with no deletion record kept:

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

-- PROPOSED SYNTAX (not implemented): fires at the commit that deletes a
-- Draft; `this` is the deleted listing, readable one last time. The title is
-- copied out; the seller reference is legal because the seller lives on.
rule NoteDiscard when deleted Draft {
    SellerNotice from { seller: seller, listingTitle: title }
}
```

Candidate spellings for the trigger word — pick one or supply another:

- **(a) `when deleted Draft`** — reads as "when [a] Draft [is] deleted"; `deleted` becomes a keyword alongside `leaving`. **Leaning (2026-08-25): this word**, as the deletion half of the `when leaving or deleted` family (§1c).
- **(b) `when deleting Draft`** — mirrors `leaving`'s grammatical form exactly (`"when" ("leaving" | "deleting")? condition`), at the cost of reading as if the rule does the deleting.
- **(c) Something else** — a different word, or a different position.

Also part of this input: may the named state carry a condition the way `when leaving` conditions do (for example `when deleted (Draft where seller is some)`), which would fall out of reusing the existing condition grammar?

### 1c. How does a rule that means "for any reason" cover both causes?

A rule could genuinely mean "this record is no longer in this state, whatever happened" — §1a's `NoteFollowUpStop` is exactly that shape, though §1's sweep found no checked-in example-corpus rule that means it: all four `when leaving` rules there mean a specific transition, and their bodies could not run against a deleted record anyway (each produces a record keeping a required reference a deletion strands — the per-rule detail is in §1). For a rule that does mean it, the options:

- **(a) Write two rules** — one `when leaving`, one with §1b's deletion trigger. Explicit, and the two bodies would necessarily differ anyway (the deletion side must copy fields where the transition side may keep references), which weakens the duplication objection.
- **(b) A combined trigger** — one rule stating both causes: `when leaving or deleted SettledOrder`. One body, both events; new grammar — and that one body is constrained to the deletion side's stricter rules (no kept references to the subject). **Leaning (2026-08-25): this, spelled exactly `when leaving or deleted`** — which also fixes §1b's word to `deleted` and gives the trigger family three forms: `when leaving X` (transition only), `when deleted X` (deletion only), `when leaving or deleted X` (either).
- **(c) No combined form yet** — start with §1a + §1b only, and let the stress-test pass over realistic specs show whether both-causes rules exist at all. The corpus sweep (§1) was early evidence for this option — none of the four `when leaving` rules in the checked-in examples means "for any reason," so no existing rule needs the combined form. The leaning above overrides that, on the argument that the vocabulary should be complete when it ships.

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
- **(b) Admit a use case with a defined order.** The only candidate meaning is "the write happens first, so the last readers observe the written value, then the instance is removed." Note the entanglement with §1: under the per-rule distinction ruled there (R11), deletion-reaction rules are last readers of the deleted record, so a write-then-delete ordering *would* be observable by them — the question is whether any business sentence actually wants "stamp a final value only for the deletion reactions to read," rather than passing that value along as ordinary copied data.
