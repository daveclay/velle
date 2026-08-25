# Investigation: delete — can removing an instance be a described mutation?

**Status:** open (2026-08-25) — rulings R1–R13 below (R4 superseded by R10); still open: the deletion trigger's spelling, the both-causes form, and the "transition-only" marker's spelling (all OQ37 §1a–§1c), and the delete statement's write-plus-delete edge case ("The statement", OQ37 §2). Follow-up actions (remaining opens, the stress-test pass) tracked in `TODO.md`.
**Implementation (2026-08-22):** the v0 compiler implements the rulings — `delete`, `undeletable`, and `? initially required` (grammar.md; checks V23–V28 plus the deleter-aware extensions of V2/V10/V12/V14/V16 and the A2 dead-machinery members; `examples/moderation/` is the worked fixture). Two behaviors ship with defaults: every exit rule fires for the deleted instance at the deleting commit (last-reader semantics) — **now rejected by R11 (2026-08-24)**, and the implementation changes when its syntax design lands — and write-plus-delete of one instance in one commit is refused fail-closed (still pending resolution). Anything the stress-test pass reopens changes the implementation with it.
**Question tag:** [OQ37](questions/OQ37-delete.md) — the index entry; this doc holds the discussion
**See:** README §4 (no delete primitive; transient acts) · §12 (assignment) · §8 "Frozen fields" · §13 (the last-reader rule) · §18 (run-once guards) · `evaluation.md` "Transient acts" (the step-7 removal) · [OQ27](questions/OQ27-erasure.md) (erasure/retention — the storage-policy face of what may be the same primitive)

---

## Rulings

The decisions this investigation has made; cite by tag (`R5` here, `OQ37-R5` from other docs). The stress-test pass over these lives in `TODO.md`, not here.

| tag | date | ruling |
|---|---|---|
| R1 | 2026-08-14 | `undeletable`: state-scoped deletion gate, a `frozen`-sibling refinement-body clause |
| R2 | 2026-08-14 | deletion is not a "write": bare `frozen` does not imply undeletable — separate clauses, separate business sentences |
| R3 | 2026-08-14 | the crux: absorbing references plus per-field copies — reference serves the living target, copied fields survive it |
| R4 | 2026-08-14 | marker syntax: `one <Shape> unless deleted`; required on create/update, reads optional-shaped via existing `is none`/`?.` machinery — *superseded by R10* |
| R5 | 2026-08-14 | identity-surviving references and `is deleted` rejected: deletion is pure removal — no deletion-memory anywhere |
| R6 | 2026-08-14 | built-in tombstone rejected as sugar at most: deletion records are explicitly modeled outcome shapes, never minted for free |
| R7 | 2026-08-14 | guard re-arming is not `tolerates`-signable: intentional re-triggering is reversal-as-data; false positives are never signed |
| R8 | 2026-08-14 | existence-dependency coarseness is conservative: the shared refinement-overlap prover, nothing finer; disjointness written into predicates; sharpening is a backward-compatible relaxation (rides OQ16) |
| R9 | 2026-08-14 | deletion gate polarity is negative — no second polarity; positive-exhaustive sentences ride the state partition declaration |
| R10 | 2026-08-15 | `? initially required` supersedes cause-specific markers: read-side optionality and creation-side requiredness are orthogonal declarations; absence causes are ordinary spec (a deleter of the target; a rule assigning `none` — which is how a field is cleared, OQ27's erasure case) — no `clear` statement, no `unless deleted`/`unless cleared`; a field nothing can make absent is dead-optionality advisory |
| R11 | 2026-08-24 | which cause a leaving-rule reacts to — the predicate flipped, or the record was deleted — is the rule author's explicit per-rule choice. Firing every `when leaving` rule at deletion (the v0 default) is rejected: a deletion can counterfeit the transition the rule was written for. And reacting to a deletion is directly expressible per rule — requiring a modeled deletion record just to have a trigger is an unnecessary burden. R5/R6 stand: the reaction fires inside the deleting commit as a last reader and copies what must survive; a deletion *record* remains an explicitly modeled choice for durable memory only |
| R12 | 2026-08-25 | bare `when leaving` fires only when the predicate flips — never at deletion. No counterfeit is possible; the residual hazard is omission (a rule meant as "for any reason" silently stops at deletion — worked gap example, OQ37 §1a). Implementation lands together with the trigger — dropping the default alone would leave no way to react to a deletion |
| R13 | 2026-08-25 | a `delete` statement whose scope can reach a `when leaving` rule's state (not provably disjoint — the shared prover, R8's posture) is a hard error, not an advisory: the spec is ambiguous about what the rule means for a deleted member, and Velle errors and tells the author rather than compiling an ambiguity. Discharges: a deletion-reaction rule (OQ37 §1b), restructure to provable disjointness (the V25/V27 pattern), or an explicit "transition-only" per-rule marker — whose spelling is open, designed as one vocabulary with the trigger word |

The central question. Businesses delete records — not as retention compliance (that's OQ27) but as ordinary domain behavior: remove the draft, discard the duplicate, take down the listing. `expose transient` is not this — transience is a statement about the trust boundary (a request that was never a member of the state), not a mechanism for removing something that *was*. The frame under investigation: **delete is a special kind of general mutation** — instance-granularity where assignment is field-granularity — with the statement declarative ("this instance ceases to be part of the state") and the storage realization (hard delete, tombstone, crypto-shred) left to compilation, the same way GraphQL abstracts the mutation's mechanics to the implementor. Velle's difference from GraphQL is the usual one: the rules *about* the delete — who may, when, what must happen around it — are describable and checked.

## Reconciling with §4 — what "no delete primitive" actually protects

README §4's argument ("a produced fact records something that happened, and deleting the record would make the description lie") is an argument about **occurrence facts** — evidence, reified acts, history. It was never an argument that stored truth is immortal: in-place assignment is last-in-wins, and the overwritten value is simply gone unless the author chose the ledger — a per-field, per-use-case choice the language explicitly leaves open (flexible, not restrictive). Delete at instance granularity is the same choice one level up: overwriting `customer.email` destroys the old email; deleting the `Customer` destroys the whole record. If the first needs no ceremony, the second is not philosophically different.

So the honest revision is not "delete has no primitive" → "delete is fine." It is:

> **Delete is a mutation whose legality is derived from what the spec proves *using* the instance's existence.**

The stance's real content survives as derived obligations, not a category ban. This also fits §18's "no separate evidence category" — an evidence shape is an ordinary shape that happens to serve as a guard witness; likewise, what makes an instance undeletable is not membership in a blessed category but the concrete proofs its existence is spent in (below). Deletability is emergent from the whole spec, exactly like trigger sets.

## The statement — sketch

A delete is an ordinary rule-body effect, so everything already known about commits applies to it unchanged:

```
expose shape DiscardDraft {
    draft: one DraftListing
}

rule ApplyDiscard when DiscardDraft {
    delete draft
}
```

- **The target is a literal static path** — the same hard requirement as assignment (§12), for the same reason: the whole-spec compiler must know every deleter of every shape, so the existence-dependency check (below), referential completeness (below), and impact analysis stay derivable. No computed or reflective target, ever.
- **One deleter per instance, per commit** — the one-writer check aimed at existence: two rules deleting the same instance from one commit is the coincidence error; a commit that both writes a field of an instance and deletes it is presumptively the same incoherence (there is no business sentence "change it and also remove it, at once") — fail closed, pending a use case.
- **No special exposure.** External delete requests are ordinary acts (`DiscardDraft` above — plausibly `expose transient`, since the request's durable trace is usually what was done, not the asking); the delete itself is always a rule's effect. Nothing new at the boundary.
- **A delete is a commit** — one mutation entering the state at a discrete moment, pre- and post-state both well-defined. Everything below falls out of taking that seriously.

## The genuinely new check: existence is spent in proofs

This is what makes delete different from assignment, and it is the investigation's center of gravity. The machinery leans on facts persisting in at least four places:

1. **Guard witnesses.** `UnappliedDeposit = Deposit where not exists DepositApplication for this` — delete the `DepositApplication` and the guard *re-arms*: the deposit re-enters the trigger state and the `Hourly` backstop applies it again. The disarm law's soundness (§18) silently assumes witnesses are immortal.
2. **Singularity proofs.** `(OpenDelinquencyFlag for this)` is legal because a guard elsewhere proves at-most-one (§10's `for`-query rule); deleting the flag mid-episode breaks the *at-least-one* the exit rule's reference relies on.
3. **Spent `never`s.** An established invariant is a proof input (§21) — the one-writer prover consumes it. Deleters are a new class of state change its inductive proof must range over, both as potential violators and as potential restorers.
4. **Monotone predicates.** `exists ArchiveRequest for this` is monotone today — the whole "exit from act-entered refinements" item (§22) exists because nothing can leave. A deleter of `ArchiveRequest` makes the refinement non-monotone: a feature (un-archival becomes expressible as removal) and a hazard (every act-entered refinement in the spec becomes exitable whether or not its author considered exit) at once.

The good news: this has the same shape as every existing check. Static paths mean the compiler knows every predicate that reads a shape's existence (`exists`, `count`, `sum`, selectors, `for`-queries), so "who depends on this instance existing" is derivable exactly as trigger sets are (§11, "Rules ground in commits"). The check writes itself, fail-closed, as one connected diagnostic naming both sides:

> "`PurgeApplications` deletes `DepositApplication`, which re-arms `ApplyDeposit`'s guard — the fold double-applies. Restructure the guard, condition the deleter, or [sign the hazard]."

Both riders on this check are ruled (2026-08-14, sections below): guard re-arming is **not `tolerates`-signable**, and coarseness is **conservative** — the check reuses the shared refinement-overlap disjointness prover, nothing finer.

## Semantics of the deleting commit — nothing new, by construction

The runtime already removes instances: a transient act is fully present within its transaction and gone at close (`evaluation.md`, step 7), and capture retraction already defines re-timed last reads (§13). Deletion composes the two:

> **Deletion is an ordinary instance becoming transient at its final commit.**

- **Within the deleting transaction**, the instance is fully present: rules fired by the delete commit read its fields normally, as its **last readers** — the capture contract's re-timing, generalized. The idiom for "keep what matters" is the transient-acts copy idiom: an outcome record copies the fields that are durable business data (`DeletionRecord from { listingTitle: draft.title, discardedBy: ..., discardedOn: now }`) — which is also the author declaring, field by field, which slice of the deleted instance was history all along.
- **At transaction close**, the instance is removed. Nothing after the boundary may read it — a capture-style stranding error, the mirror of V17: a rule that reads the deleted instance can never be `after commit` and can never carry a schedule backstop ("this rule reads `draft`, which does not survive the transaction boundary it declares"). `after commit` reactions to a deletion hang off the durable record the deleting transaction produced — intent-before-effect, unchanged.
- **"Before" needs no hook vocabulary.** Rules reacting to the deleting commit share its transaction by default and see the instance; that *is* "before delete." "After delete" is `after commit` off the produced record. The before/after pair the question asked for is already the on/after preposition (§11), with the last-reader rule supplying what "before" may see.

## Refinements recalculate — yes, and for free

A delete is a commit; drift is commit-mediated (§11); every predicate reading the deleted instance's existence or aggregating over it flips at that commit — memberships change, entry *and* exit rules fire under ordinary commit-local transition semantics, and the derived trigger set extends: deleters of `Payment` join the trigger set of everything reading `payments`, automatically, with no edit to any rule. That this needs *no new design* is the strongest evidence the delete-is-a-commit framing is right. Two edges to note:

- **Exit rules at deletion — ruled, R11 (2026-08-24).** The deleted instance leaves every refinement it was a member of, which is coherent under the last-reader semantics above — but *left because the predicate flipped* and *left because it ceased to exist* are different business events (the sibling of §13's became-compliant/was-always-compliant argument), and which of them a rule reacts to is now the rule author's explicit per-rule choice. The v0 default — every `when leaving` rule fires at deletion — is rejected: a deletion counterfeits the transition the rule was written for (the worked trap: an "announce publication" rule over `when leaving Draft` firing when the draft is *discarded* — OQ37 §1). The earlier leaning that deletion-reaction needs no trigger because the business models a deletion record and hangs rules off it is also rejected: keeping a record around solely to have something to trigger on is an unnecessary burden, so reacting to a deletion is directly expressible per rule. R5/R6 are untouched — the reaction fires inside the deleting commit as one of the record's last readers, copies what must survive into records it creates, and a durable deletion *record* stays an explicitly modeled choice, never minted for free. The meaning of bare `when leaving` is settled by R12 (2026-08-25): predicate-exit only, never deletion — and by R13 (2026-08-25) a deleter that can reach a `when leaving` rule's state is a hard error until the author addresses it. The trigger syntax, the both-causes form, and the "transition-only" marker's spelling are the open design pass (OQ37 §1a–§1c).
- **Aggregates silently change.** `balance = amount - sum(payments, amount)` jumps when a `Payment` is deleted. Correct by construction — but a derivation-consumer that assumed monotone history (a fold twin, a snapshot) inherits the non-monotonicity. Probably just a facet of the existence-dependency check; flagged.

## Validation — two existing idioms already cover it

"Can't delete while X" is the freeze question aimed at existence instead of fields, and both spellings are in the vocabulary today:

- **The gate, declared — accepted (2026-08-14):** `undeletable`, a `frozen`-sibling clause in a refinement body — deletion permission scoped to a state, exactly as write permission is:

  ```
  shape IssuedInvoice = Invoice where exists Issuance for this {
      frozen lineItems, billingAddress
      undeletable
  }
  ```

  Same static check (can this deleter's trigger coincide with membership?), same fail-closed disjointness proof, same connected diagnostic. Deletion is deliberately **not** a "write" for bare `frozen`'s purposes — "you can't edit an issued invoice" and "you can't delete one" are different business sentences, and bundling them would hide the second inside the first.
- **The fix-it idiom:** the partition — `DeletableListing = DraftListing where ...`, hang the deleting rule off the deletable subset, refusal lands as data (`patterns.md`, "Validation rejection is data"). The diagnostic for a gate violation points here, mirroring how `frozen` and rejection-as-data already pair.

The gate's polarity is ruled: negative, no second polarity — positive-exhaustive sentences ride the state partition declaration ("Ruled — the gate stays negative," below).

## Cascade — resist SQL's shape; it's a completeness check

SQL's `ON DELETE CASCADE` is transitive magic declared at the schema edge. Velle's precedent points the other way — freeze depth is declared, not inferred; each hop is its own visible business decision (§8). The Velle-shaped version is **referential completeness**: deleting an instance that required `one` references point at is illegal unless the same commit resolves every referrer —

- deleted too (the cascade, spelled as visible statements or per-hop declarations, never inferred transitively),
- or restructured out of the problem (the reference declared optional; or the referrer copies rather than references, the transient-acts idiom).

The compiler derives the obligation from the relationship graph it already holds — every `one DraftListing` field in the spec is known — and reports unresolved referrers as one connected diagnostic: "deleting `DraftListing` strands `ListingPhoto.listing`; delete the photos in this commit, or make the reference optional." Cascade stops being a *mode* and becomes the exhaustiveness check for a delete's blast radius. A fan-out spelling ("delete every `ListingPhoto` of this listing") is the same per-member-effect ergonomics gap the Mapping item already owns (§22) — sugar territory, not new transaction machinery.

## The crux — evidence that references deleted state

The unresolved center, and the sharpest tension with §4. Durable occurrence facts reference their subjects: delete the subject and referential completeness demands the evidence be resolved — but cascading *into evidence* deletes history in order to delete state, which is exactly what §4 rightly forbids, and gating deletion on "no evidence references it" makes almost everything undeletable in any spec that records anything. The running example for all three resolutions:

```
shape Listing {
    title: text
    seller: one Seller
    isDraft: boolean initially true
}

shape ListingReport {                 -- occurrence fact: someone reported this listing
    listing: one Listing
    reason: text
    reportedOn: timestamp on create
}

shape ReportedListing = Listing where exists ListingReport for this
```

A moderation takedown deletes the listing; the report is audit evidence that must survive. Note the tension is at its worst exactly where delete is most wanted: the listings moderation removes are precisely the ones evidence references.

**The resolution (leaning, 2026-08-14): absorbing references plus per-field copies** — the reference serves the living target; the slice that must survive its deletion is copied, field by field. The built-in tombstone is not a resolution at all — at most sugar over an explicitly modeled deletion record — and is separated out below.

### The resolution — references that absorb their target's deletion, plus per-field copies

The problem, focused: how a reference field declares *required on creation, but possibly absent later*. The adopted spelling (R10, 2026-08-15, superseding R4's `unless deleted` marker) decomposes it into two orthogonal declarations — `?` for the read side, `initially required` for the creation side:

```
shape Listing {
    title: text
    seller: one Seller? initially required   -- adopted syntax (2026-08-15, R10)
    isDraft: boolean initially true
}
```

`initially` already marks creation-moment-only semantics (`initially false`, `initially now`, `initially randomUUID`) — those forms *supply* a creation value; `initially required` *demands* one, from the committer or from a creating rule's totality-checked mapping. Reads are optional-shaped using the existing machinery wholesale: `is none`, `is some` narrowing, `?.` propagation. No new atom is needed, because the contract disambiguates: never-set is impossible (creation required presence), so **`is none` means "was present, now gone"** — and the possible causes (the target's deletion; a rule assigning `none`) are derivable, since every deleter and every writer is a static path:

```
-- traversal is gated: narrowing or ?., existing machinery
sellerName: text? = seller?.name
```

**The read discipline, and its idiom (2026-08-14).** The optional type is deliberately load-bearing at every read site: traversal over an `? initially required` reference without acknowledgment of absence is the same compile error as unnarrowed `.` on any optional. The spec-wide `?` tax is answered by the existing narrowing idiom, not new machinery — one named refinement asserts presence, and everything downstream references it with plain `.` licensed by its predicate:

```
shape ListingWithSeller = Listing where seller is some
shape OrphanedListing   = Listing where seller is none

-- the predicate narrows: plain `.` licensed, no ?. anywhere
shape PremiumListing = ListingWithSeller where seller.tier == "premium"

rule ReassignOrphan when OrphanedListing { ... }
```

This converts scattered absence-handling into a named partition of the shape — and the orphan side is the point, not a residue: "what does the business do with a listing whose seller is deleted?" is a real product question the refinement pair makes visible, where `?.` everywhere would have silently propagated it away. The seller's deletion is the commit that flips membership, so `OrphanedListing` entry is ordinary drift and rules react to it — or repair it, the reference being assignable as ever: to a new seller, or, being optional-typed, to `none` — which is how a field is *deliberately* cleared, the erasure case OQ27 now rides (an ordinary sweep rule assigning `none`; no `clear` statement exists). Note also that presence-testing is *meaningful* only on an optional-typed field: on a plain `one` the check would be trivially true — dead-machinery territory — so `is some` on a reference is itself a signal it should be (or is) `? initially required`.

**Why the decomposition, not a reference kind (2026-08-15, R10).** Bare `?` cannot carry the contract — optional means "may be absent, at creation included," where the business requires presence at commit. R4's first answer was a cause-specific marker (`unless deleted`), but the clearing case (OQ27) immediately demanded a second (`unless cleared`), and the pair revealed the real primitive: **read-side optionality and creation-side requiredness are orthogonal**, and absence *causes* are ordinary spec — a deleter of the target, a rule assigning `none` — visible and derivable, never marker vocabulary. One modifier replaces every cause-specific marker and generalizes beyond references to any field. The SQL comparison stands as before: unlike `ON DELETE SET NULL`, the target's deletion writes nothing — the read surface degrades because the target is gone, storage mechanics are compilation's — no silent write, no hidden writer, no never-set conflation (never-set can't happen).

**Rejected variant — the identity-surviving reference (2026-08-14).** An earlier sketch had the reference permanently holding the target's `id`, with a distinct narrowing atom (`listing is deleted`) gating traversal while `==` correlation stayed sound forever. Rejected: a surface form that *names* deletion implies the system remembers deletions — records of deleted records, unscalable and against the point of deleting — and the atom is redundant anyway: with creation-required presence there are only two states, and `is none` already carries the meaning. Deletion must be pure removal; the runtime needs to know nothing.

**The honest casualty — and the copy half.** Post-deletion identity correlation dies: `==` against an absent reference has nothing to compare, so same-subject grouping and reference-keyed guards degrade at the target's deletion. That is not a flaw to engineer around but the second half of the resolution: **the reference serves the living target; the fields (or keys) that must outlive it are copied, per field** — the transient-acts copy discipline applied field-wise instead of shape-wise, with the author declaring, field by field, which slice of the target is durable business data:

```
shape ListingReport {
    listing: one Listing? initially required   -- correlation and traversal while the listing lives
    listingTitle: text                    -- copied at creation: the slice that survives it
    reason: text
    reportedOn: timestamp on create
}
```

A guard that must hold across the subject's deletion was always keyed on a business datum, not an identity.

**The optional-typed reference is referential completeness's third discharge.** A deleter of `Seller` plus a plain `one Seller` reference is the completeness error (above), and the fixes become exactly three: delete the referrer in the same commit (cascade, spelled), restructure to copies (the copy half, above), or make the reference `? initially required` — "this reference absorbs its target's deletion by going absent." The converse diagnostic completes it, dead-tolerance-style: an `? initially required` field that nothing can ever make absent — no deleter of its target, no rule assigning `none` — is dead optionality, advisory: "did you mean `one Seller`?"

*Preserves:* referential completeness discharged declaratively, with nothing required of the deleting commit; no new evaluation semantics and no new predicate forms — the read surface is the existing optional/narrowing machinery. *Breaks:* traversal after deletion (by design) and post-deletion identity correlation (answered by per-field copies). *Costs:* one new modifier (`initially required`) in `initially`'s existing position, plus the per-field copy discipline wherever survival is needed; the declaration no longer names the absence cause — the compiler derives it, and `why`/impact analysis reports it.

### Separated: the built-in tombstone — sugar at most, not a resolution

**Not a solution to the crux (2026-08-14).** Everything a built-in tombstone would provide, an explicitly modeled deletion record already expresses — an ordinary outcome shape the deleting rule produces, copying what the author declares durable (the `DeletionRecord` idiom, "Semantics of the deleting commit" above). So the construct could only ever be *syntax sugar* over that modeling, and it is set aside on the no-sugar stance: what a deletion record retains, who reads it, and how long it lives differ per use case — exactly the kind of visible product decision the spec keeps in the author's hands (the same call as Reversal, README §22: policy expressed via which artifact shapes a human declares). Velle mints nothing for free. The distinguishability point transfers intact to the explicit form — reactions to "this was deleted" hang off the explicitly modeled record, durable and `after commit`-able, so no `when deleted` trigger is needed. The built-in sketch is retained below as the documented road not taken:

```
shape Listing {
    title: text
    seller: one Seller
    isDraft: boolean initially true
} deleted as DeletedListing { title }     -- sketch: the retained slice, declared once
```

A reference to a possibly-deleted `Listing` then reads as a sum — `Listing or DeletedListing`, OQ29's territory — narrowed by `is`:

```
shape ReportOfRemoved = ListingReport where listing is DeletedListing

rule NotifyReporter when ReportOfRemoved {
    ReporterNotice from {
        report: this
        listingTitle: listing.title       -- retained: readable forever
        -- listing.seller: compile error — outside the retained slice, shredded
        noticedOn: now
    }
}
```

*Preserves:* references never dangle, predicates stay total, identity and declared fields survive. *A genuine point in its favor:* the tombstone reifies the deletion as an ordinary durable fact, which answers the deletion-vs-predicate-exit distinguishability question (refinement-recalculation section, above) with existing machinery — a reaction to "my subject was deleted" hangs off `listing is DeletedListing`, durable and `after commit`-able, where any `when deleted` transition trigger would be transaction-bound by the transition law. *Suspicions:* it is the resolution wearing a shape costume — a tombstone is a surviving identity plus copied fields, i.e., the reference-plus-copies composite bundled behind sugar (the observation that settled its status, above); and it recurses — a tombstone is itself an instance, so either tombstones are undeletable by construction or OQ27's retention policy applies to *them*, which is suspiciously exactly OQ27's "the fact remains; the payload ceases to be retrievable" — the natural meeting point of the two faces, or a sign the construct belongs to OQ27's compilation layer rather than the language. *Costs:* sum-typed reads wait on OQ29, and the retained-slice declaration is a new shape-level construct.

### Or: the split is itself the answer *(retired by the leaning above)*

References to *current-state* shapes and references to *occurrence* shapes might have behaved differently by derived role — the compiler classifying, rung-recognition-style (§20). Retired: the resolution already makes the choice **per field** (reference vs. copy, marker vs. cascade), which is finer-grained than any per-shape-role derivation and keeps each decision visible where §18's no-sugar stance wants it.

## Ruled — guard re-arming is not signable (2026-08-14)

The existence-dependency check ("The genuinely new check," above) admits no `tolerates` escape. The fixture is the canonical guard with a deleter aimed at its witness:

```
shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}

expose transient shape PurgeApplication { application: one DepositApplication }

rule ApplyPurge when PurgeApplication {
    delete application    -- error: deleting the witness re-arms ApplyDeposit's guard —
                          -- the deposit re-enters UnappliedDeposit, and the Hourly
                          -- backstop folds it into balance a second time
}
```

The fixture's deleter is ambiguous on its face — it reads as an author *intentionally* setting up re-application of the deposit, and the language cannot tell that intent from a mistake. It never needs to: the check fails closed and demands a stated policy, the fold-obligation stance (§19) — legit and mistaken get the same diagnostic; only the legit author has an answer to give. What settles the ruling is that both legitimate intents decompose into spellings that need no signature:

- *Intentional re-application* is **reversal, and reversal is data** (README §22; the Receipt/ReceiptVoid pairing). Pair the witness with a reversing occurrence and let the guard read the pair — the application record persists as history, and re-application fires by ordinary drift entry when the reversal lands. No delete anywhere:

  ```
  shape StandingApplication = DepositApplication where not exists ApplicationReversal for this
  shape UnappliedDeposit    = Deposit where not exists StandingApplication for this

  expose shape ReverseApplication { application: one DepositApplication, reason: text }

  rule RecordReversal when ReverseApplication {
      ApplicationReversal from { application: application, reason: reason, reversedOn: now }
  }
  ```

  Deleting-to-retrigger is a call graph wearing a delete costume — the intent "apply it again" is a business event, and events are committed, not simulated by erasing witnesses.
- *Cleanup deletion* (pruning old records) is either dischargeable under the coarseness ruling — disjointness written into the predicates, or the guard restructured (below) — or retention policy, which is OQ27's compiled-erasure face, not a business delete.

With both constituencies served elsewhere, `tolerates re-arming` signs nothing legitimate, and the diagnostic's fix-it list is complete without it: condition the deleter, restructure the guard, or — for intentional re-triggering — model the reversal as data. Ruled: no escape.

**The conservative false positive does not reopen this (2026-08-14).** The coarseness thread's prune case errors under a conservative prover despite having no actual hazard — and that is a second, independent reason tolerance stays unavailable, not a crack in the ruling: `tolerates` signs *real* hazards the business accepts (the streak really breaks under replay; the ping really can be lost), never prover limitations. A signature on a rule that cannot re-arm would be a false statement whose falseness the signature outlives — sign the prune today at 90-vs-3 days, widen the rate-limit window to 120 days next year, and the prune now *genuinely* re-arms with the hazard already signed away: no diagnostic, the effects-at-a-distance failure manufactured by hand. The fold precedent ("legit-but-unprovable gets `tolerates`") doesn't apply — there the hazard was real and only the safety unprovable; here the hazard is absent. A false positive's discharges are restructure (the field-witness fix, available today) or a future prover sharpening (a backward-compatible relaxation — the coarseness ruling, below) — the same line one-writer already holds: fail-closed, no signature, restructure until provable.

## Ruled — coarseness: the check is conservative (2026-08-14)

The existence-dependency check reuses **the disjointness prover the language already has** — the refinement-overlap machinery behind one-writer, `frozen`, and `undeletable` — **and nothing finer**: no window arithmetic, no lifetime analysis, no bespoke reasoning for deletes. A deleter is legal exactly when that prover shows its trigger disjoint from every proof-bearing read's scope; anything unprovable errors, fail-closed, and the discharge is restructure — never a signature (ruled above), never hoping a finer prover finds the proof. The false positive this accepts is deliberate: a sharper prover is a **backward-compatible relaxation** — it only ever removes errors, never adds them — so path-sensitivity can ride OQ16's calibration later without blocking any spec written today. Two exhibits below: the accepted false positive with its restructure discharge, and a true positive the prover rightly refuses.

**Exhibit 1 — the accepted false positive.** The context that trips the check is the rate limit's guard itself — the §17 spelling, whose cross-tick memory is the `Reminder` **evidence**: the guard's `exists` reads `Reminder`, so `Reminder` records are load-bearing, and any deleter of them is in the check's sights:

```
rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }   -- the evidence IS the guard's memory
}

rule PruneOldReminders when (Reminder where sentOn < today - 90 days) on Monthly {
    delete this    -- error: RemindOverdue's guard reads Reminder's existence, and the
                   -- disjointness (90-day condition vs 3-day window) is interval arithmetic
                   -- over sentOn — beyond the refinement-overlap prover. A human can see
                   -- nothing re-arms; the ruling accepts the false positive.
}
```

The author still achieves the prune — via the diagnostic's "restructure the guard" fix. **The prune rule doesn't change at all**; the fix is to the *guard*: move the rate limit's memory off the prunable evidence onto a **field witness** (§18's witness-grain choice, date grain), so no guard reads `Reminder`'s existence:

```
shape Invoice {
    ...
    lastRemindedOn: Date?
}

rule RemindOverdue
    when (OverdueInvoice where lastRemindedOn is none or lastRemindedOn <= today - 3 days)
    on Daily {
    Reminder from { invoice: this, sentOn: today }     -- pure history now: no guard reads it
    this.lastRemindedOn = today                        -- the disarm, on the field witness
}

rule PruneOldReminders when (Reminder where sentOn < today - 90 days) on Monthly {
    delete this    -- legal under any coarseness: Reminder's existence is spent in no proof
}
```

The disarm proof still discharges (`= today` falsifies `<= today - 3 days`), one-writer holds, and `Reminder` becomes what this design arguably always meant it to be — optional history the business may keep, prune, or never write — rather than the rate limit's load-bearing memory. The general consequence is worth naming: **§18's witness-grain choice now has a delete-side face.** An evidence witness makes its records load-bearing — undeletable until the guard is restructured or the disjointness is written into the predicates; a field witness leaves the evidence free to prune. Choosing the guard's grain is also choosing the evidence's prunability, and the connected diagnostic is what makes that choice visible instead of latent.

**Exhibit 2 — a true positive.** A sweep the prover rightly refuses, because the guard's own predicate doesn't carry the disjointness:

```
shape ArchivableApplication = DepositApplication where deposit.account is ClosedAccount

rule PurgeClosedApplications when ArchivableApplication on Monthly {
    delete this
}
```

Error — and correctly: the hope "closed accounts' deposits are done; purging is safe" is not true as written, because `UnappliedDeposit`'s predicate never mentions account state — the re-armed deposit re-enters it regardless, and the Hourly tick re-folds. The purge becomes legal exactly when the guard's own predicates carry the disjointness in refinement-overlap terms — `UnappliedDeposit = Deposit where not exists ... and account is OpenAccount` makes deleter scope (`ClosedAccount`) and guard scope (`OpenAccount`) complementary, which the shared prover clears (the same complementary-predicate power that clears C8's conditioned deleter in the catalog, below). The ruling's demand is precisely this: disjointness must be *written into the predicates*, where a reader sees it — never inferred from arithmetic a reader would have to re-derive.

## When a delete errors — the worked catalog

**Ruled: conservative (2026-08-14) — the error stands wherever a proof-bearing read exists and the shared disjointness prover cannot clear the deleter; this catalog is the normative exhibit set.** The boundary principle: *a delete errors when it can break something the spec proves or declares — never merely because state changes.* Recalculation is not a hazard: memberships flipping and rules firing at the deleting commit is drift, the design working. Errors come from five sources; the cases below put one example on each side of each line.

### No error — recalculation and discharged references

**C1 — drift only.** Nothing proves anything about `DraftListing`'s persistence; deleting one flips aggregates and memberships at the commit, ordinary drift:

```
shape ActiveSeller = Seller where count(listings where PublishedListing) > 0

rule ApplyDiscard when DiscardDraft {
    delete draft        -- ActiveSeller membership may flip; exit rules fire. Fine.
}
```

**C2 — derivations recalculate, even startlingly.** Deleting a `Payment` raises the derived `balance`, and the invoice may re-enter `OverdueInvoice` and be reminded again — *correct*: it is overdue again. Aggregates and derivations are current-state computations, not proofs of once-ness; no error from this check (whether `Payment` — an occurrence fact — *should* be deletable is the author's call, spelled as `undeletable` or a conditioned deleter, not this check's business).

**C3 — completeness discharged by the optional-typed reference.** Deleting a `Seller` with `Listing.seller: one Seller? initially required` referrers: the reference absorbs by going absent; no error.

**C4 — completeness discharged by same-commit cascade.** `delete invoice` and `delete`s of its line items in one body: every referrer resolved at the commit; no error.

### Error — the five sources

**C5 — referential completeness.** `delete invoice` while `LineItem.invoice: one Invoice` (plain, required) referrers exist and the commit resolves none of them: error naming the stranded referrers; fixes are C3's marker, C4's cascade, or restructuring to copies.

**C6 — guard witness (existence-dependency).** The fixture: deleting `DepositApplication` re-arms `ApplyDeposit`; the backstop double-folds. Error naming deleter and guard; fixes are conditioning the deleter, restructuring the guard's witness grain, or — for intentional re-triggering — reversal-as-data (ruled not signable, above).

**C7 — guard witness, humanly-provable window.** `PruneOldReminders` (coarseness ruling, above): no actual hazard, but the window disjointness is interval arithmetic — beyond the shared prover — so the same error as C6. The accepted false positive; the discharge is the field-witness restructure, never a signature.

**C8 — singularity proof.** The episode pattern's exit rule reads `(OpenDelinquencyFlag for this)`, provably at-most-one *and present* because the entry rule's guard maintains exactly one open flag per delinquent account. A deleter of `DelinquencyFlag` can remove the open flag mid-episode, stranding the exit rule's singular reference: error naming deleter and reference. Fix: condition the deleter on the episode being closed (`DelinquencyFlag where exists DelinquencyResolution for this`) — complementary predicates, which the shared prover clears; this is what the coarseness ruling's conservative prover *can* do.

**C9 — `never` maintenance.** Deleters join writers in every invariant's inductive proof:

```
never (Order where count(lineItems) == 0)     -- "no empty orders"

rule RemoveItem when ItemRemoval {
    delete lineItem      -- error: can end a transaction violating the never —
                         -- deleting the last item empties the order
}
```

Fix: condition the act (`ItemRemoval where count(lineItem.order.lineItems) > 1`), or the same commit deletes the order too (C4's shape).

**C10 — the deletion gate.** A deleter whose trigger can coincide with `undeletable` membership (`IssuedInvoice`, accepted above): error; fix is the partition idiom — hang the deleter off the deletable subset, refusal lands as data.

**C11 — transaction stranding.** An `after commit` or schedule-triggered rule reading the deleted instance: error in the stranding family (the V17 mirror, "Semantics of the deleting commit" above) — nothing after the deleting transaction's close may read it; durable reactions read the outcome record the deleting commit produced.

The catalog's shape to validate against realistic specs: C1–C4 confirm that ordinary modeling stays untaxed; C5, C9, C10, C11 are structural and uncontroversial; C6–C8 are the existence-dependency family where the conservative ruling bites — C8 shows what the shared prover clears, C7 its accepted false positive. If realistic specs show C7-shaped restructures dominating, that is the evidence for the backward-compatible prover sharpening, not for reopening the ruling.

## Ruled — the gate stays negative (2026-08-14)

Raised 2026-08-14 via "deletable only if `isDraft`"; resolved same day: **the negative gate is the design — no second polarity.** The gate is negative and state-scoped: "deletable only while draft" is spelled as `undeletable` on the *complement*, exactly as "editable only while draft" is spelled as a freeze on the issued state — the permission-denying clause lives on the state where the permission is denied, and with a boolean the complement is exact, so the disjointness check covers the sentence fully:

```
shape Draft            = Listing where isDraft
shape PublishedListing = Listing where not isDraft {
    undeletable                        -- the exact complement: the sentence is fully covered
}
```

But the business sentence is naturally *positive-exhaustive* — "only if" claims that everywhere outside draft, deletion is forbidden — and with richer state the negative spelling becomes N declarations that silently under-cover the moment a state is added:

```
shape Draft     = Listing where status == "draft"
shape Submitted = Listing where status == "submitted" { undeletable }
shape Published = Listing where status == "published" { undeletable }

-- a year later, someone adds:
shape Archived  = Listing where status == "archived"
-- Archived listings are deletable — by omission, not decision. No proof fails,
-- no diagnostic fires: the effects-at-a-distance hazard, unreported.
```

Two candidate answers were weighed:

1. **The state partition declaration's business** (README §22, `states of`) — **adopted as the ruling's second half**: once a partition is declared, per-state deletion permission sits beside per-state write permission, "deletable only in Draft" is checkably exhaustive, and a new state is born undeletable unless the declaration grants:

   ```
   states of Listing = Draft | Submitted | Published | Archived   -- candidate construct, not designed
       deletable in Draft                                         -- positive, exhaustive, checkable:
                                                                  -- adding Archived changed nothing
   ```

2. **A positive `deletable` grant** with shape-level default-undeletable — **rejected**: a second polarity cutting against the `frozen` precedent, retained below as the road not taken:

   ```
   shape Listing {
       title: text
       status: text
   } undeletable                                     -- default: no state may delete

   shape Draft = Listing where status == "draft" {
       deletable                                     -- the one grant; Archived is born covered
   }
   ```

Ruled: the negative gate stands — sufficient for the boolean complement today — and the positive-exhaustive sentence rides with the state partition declaration (§22's `states of` item inherits it as a requirement: per-state deletion permission beside per-state write permission), rather than motivating a second polarity. Until that construct lands, richer-state "only if" sentences are spelled as N `undeletable` declarations, and the under-coverage hazard above is the documented, accepted gap.

## Relationship to OQ27

One primitive, two faces. This investigation is the *description* face: delete as a business behavior, commanded by rules, gated and checked. OQ27 is the *storage* face: erasure as declared policy (retention windows, right-to-be-forgotten) the transpiler enforces, where the model may retain the fact that something happened while its payload ceases to be physically retrievable. If this investigation lands a delete statement, OQ27's policy vocabulary plausibly compiles down to scheduled deletes plus a compilation-level payload-shredding choice — the tombstone candidate above is where the two faces would meet.

## Behaviors checklist (the framing questions, answered in place)

- **Cascade** → referential completeness, per-hop and spelled, never transitive magic (above).
- **Validation** → the state-scoped gate clause plus the partition idiom (above).
- **Rules before/after the delete** → on-commit rules share the deleting transaction and are the instance's last readers; `after commit` hangs off produced durable records; no hook vocabulary (above).
- **Refinement recalculation** → yes, free, by delete-is-a-commit; the open edges are deletion-vs-predicate-exit distinguishability and aggregate non-monotonicity (above).
