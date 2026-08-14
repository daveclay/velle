# Investigation: delete — can removing an instance be a described mutation?

**Status:** open (2026-08-14) — framed and developed from discussion. Rulings so far: **`undeletable` accepted** (2026-08-14) — the state-scoped deletion gate, a `frozen`-sibling refinement-body clause; crux resolved at the leaning level (2026-08-14) — **absorbing references plus per-field copies**, with **`one <Shape> unless deleted` accepted as the working syntax** (2026-08-14); identity-surviving variant rejected — deletion is pure removal, no deletion-memory; the built-in tombstone set aside as sugar at most — a deletion record is an explicitly modeled outcome shape, never minted for free; guard re-arming **ruled not `tolerates`-signable** (2026-08-14) — intentional re-triggering is reversal-as-data, cleanup is provable disjointness or OQ27 retention. Everything else undecided.
**Question tag:** [OQ37](questions/OQ37-delete.md) — the index entry; this doc holds the discussion
**See:** README §4 (no delete primitive; transient acts) · §12 (assignment) · §8 "Frozen fields" · §13 (the last-reader rule) · §18 (run-once guards) · `evaluation.md` "Transient acts" (the step-7 removal) · [OQ27](questions/OQ27-erasure.md) (erasure/retention — the storage-policy face of what may be the same primitive)

---

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

Two riders on this check: guard re-arming is **not `tolerates`-signable** (ruled 2026-08-14 — its section, below), and prover coarseness remains the open thread (below).

## Semantics of the deleting commit — nothing new, by construction

The runtime already removes instances: a transient act is fully present within its transaction and gone at close (`evaluation.md`, step 7), and capture retraction already defines re-timed last reads (§13). Deletion composes the two:

> **Deletion is an ordinary instance becoming transient at its final commit.**

- **Within the deleting transaction**, the instance is fully present: rules fired by the delete commit read its fields normally, as its **last readers** — the capture contract's re-timing, generalized. The idiom for "keep what matters" is the transient-acts copy idiom: an outcome record copies the fields that are durable business data (`DeletionRecord from { listingTitle: draft.title, discardedBy: ..., discardedOn: now }`) — which is also the author declaring, field by field, which slice of the deleted instance was history all along.
- **At transaction close**, the instance is removed. Nothing after the boundary may read it — a capture-style stranding error, the mirror of V17: a rule that reads the deleted instance can never be `after commit` and can never carry a schedule backstop ("this rule reads `draft`, which does not survive the transaction boundary it declares"). `after commit` reactions to a deletion hang off the durable record the deleting transaction produced — intent-before-effect, unchanged.
- **"Before" needs no hook vocabulary.** Rules reacting to the deleting commit share its transaction by default and see the instance; that *is* "before delete." "After delete" is `after commit` off the produced record. The before/after pair the question asked for is already the on/after preposition (§11), with the last-reader rule supplying what "before" may see.

## Refinements recalculate — yes, and for free

A delete is a commit; drift is commit-mediated (§11); every predicate reading the deleted instance's existence or aggregating over it flips at that commit — memberships change, entry *and* exit rules fire under ordinary commit-local transition semantics, and the derived trigger set extends: deleters of `Payment` join the trigger set of everything reading `payments`, automatically, with no edit to any rule. That this needs *no new design* is the strongest evidence the delete-is-a-commit framing is right. Two edges to note:

- **Exit rules fire for the deleted instance itself** (it leaves every refinement it was a member of) — coherent under the last-reader semantics above, since exit rules were already the one construct with a sanctioned re-timed read. Whether firing *every* exit rule at deletion is wanted, or deletion should be distinguishable from predicate-exit (`when leaving R` vs. a hypothetical `when deleted`), is open — the §13 argument that *became-compliant* and *was-always-compliant* are indistinguishable has a sibling here: *left because the predicate flipped* and *left because it ceased to exist* are different business events. The distinguishability half is likely answered by the crux's tombstone ruling (below): a business that must react to "this was deleted" models the deletion record explicitly and hangs rules off it — durable, `after commit`-able — so no `when deleted` trigger; the *should-every-exit-rule-fire* half remains genuinely open.
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

The gate's polarity for positive-exhaustive sentences ("deletable only if draft") is an open thread — top-level section, below.

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

The problem, focused: how a reference field declares *required on create/update, but none after the target's deletion*. A declared marker on the referencing field carries exactly that contract:

```
shape Listing {
    title: text
    seller: one Seller unless deleted     -- accepted working syntax (2026-08-14)
    isDraft: boolean initially true
}
```

Required at creation and at every assignment — never committable or assignable as absent — but reads are optional-shaped, using the existing machinery wholesale: `is none`, `is some` narrowing, `?.` propagation. No new atom is needed, because the contract itself disambiguates: never-set is impossible (creation required presence), so **`is none` unambiguously means the target was deleted**:

```
-- traversal is gated: narrowing or ?., existing machinery
sellerName: text? = seller?.name
```

**The read discipline, and its idiom (2026-08-14).** The marker is deliberately load-bearing at every read site: traversal over an `unless deleted` reference without acknowledgment of absence is the same compile error as unnarrowed `.` on any optional. The spec-wide `?` tax is answered by the existing narrowing idiom, not new machinery — one named refinement asserts presence, and everything downstream references it with plain `.` licensed by its predicate:

```
shape ListingWithSeller = Listing where seller is some
shape OrphanedListing   = Listing where seller is none

-- the predicate narrows: plain `.` licensed, no ?. anywhere
shape PremiumListing = ListingWithSeller where seller.tier == "premium"

rule ReassignOrphan when OrphanedListing { ... }
```

This converts scattered absence-handling into a named partition of the shape — and the orphan side is the point, not a residue: "what does the business do with a listing whose seller is deleted?" is a real product question the refinement pair makes visible, where `?.` everywhere would have silently propagated it away. The seller's deletion is the commit that flips membership, so `OrphanedListing` entry is ordinary drift and rules react to it — or repair it, the reference being assignable (to a present seller only) as ever. Note also that the marker is what makes `is some`/`is none` *meaningful* on the field: on a plain `one` the check would be trivially true — dead-machinery territory — so presence-testing a reference is itself a signal the reference should be (or is) marked.

**Not `?`.** The tempting spelling `seller: one Seller?` cannot carry this: optional means "may be absent, at creation included," where the contract is required-on-create — plain `?` structurally can't say it, which is the argument that this is a genuine reference kind rather than sugar over optionality. And unlike SQL's `ON DELETE SET NULL`, no write occurs anywhere: the field's read surface changes because the target is gone, and whether storage physically nulls the cell is compilation's business — no silent write, no hidden writer, and no never-set/deleted conflation (never-set can't happen).

**Rejected variant — the identity-surviving reference (2026-08-14).** An earlier sketch had the reference permanently holding the target's `id`, with a distinct narrowing atom (`listing is deleted`) gating traversal while `==` correlation stayed sound forever. Rejected: a surface form that *names* deletion implies the system remembers deletions — records of deleted records, unscalable and against the point of deleting — and the atom is redundant anyway: with creation-required presence there are only two states, and `is none` already carries the meaning. Deletion must be pure removal; the runtime needs to know nothing.

**The honest casualty — and the copy half.** Post-deletion identity correlation dies: `==` against an absent reference has nothing to compare, so same-subject grouping and reference-keyed guards degrade at the target's deletion. That is not a flaw to engineer around but the second half of the resolution: **the reference serves the living target; the fields (or keys) that must outlive it are copied, per field** — the transient-acts copy discipline applied field-wise instead of shape-wise, with the author declaring, field by field, which slice of the target is durable business data:

```
shape ListingReport {
    listing: one Listing unless deleted   -- correlation and traversal while the listing lives
    listingTitle: text                    -- copied at creation: the slice that survives it
    reason: text
    reportedOn: timestamp on create
}
```

A guard that must hold across the subject's deletion was always keyed on a business datum, not an identity.

**The marker is referential completeness's third discharge.** A deleter of `Seller` plus a plain `one Seller` reference is the completeness error (above), and the fixes become exactly three: delete the referrer in the same commit (cascade, spelled), restructure to copies (the copy half, above), or mark the reference — "this reference absorbs its target's deletion by going absent." The converse diagnostic completes it, dead-tolerance-style: the marker on a reference whose target no rule deletes is dead machinery — "no deleter of `Seller`; did you mean `one Seller`?"

*Preserves:* referential completeness discharged declaratively, with nothing required of the deleting commit; no new evaluation semantics and no new predicate forms — the read surface is the existing optional/narrowing machinery. *Breaks:* traversal after deletion (by design) and post-deletion identity correlation (answered by per-field copies). *Costs:* splits `one` into two kinds — the visible, declared fact that this reference may outlive its target — plus the per-field copy discipline wherever survival is needed.

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

The fixture's deleter is ambiguous on its face — it reads as an author *intentionally* setting up re-application of the deposit, and the language cannot tell that intent from a mistake. It never needs to: the check fails closed and demands a stated policy, the fold-obligation stance (§19) — legit and mistaken get the same diagnostic; only the legit author has an answer to give. What settles the leaning is that both legitimate intents decompose into spellings that need no signature:

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
- *Cleanup deletion* (pruning old records) is either provably harmless — the coarseness rider's territory, below — or retention policy, which is OQ27's compiled-erasure face, not a business delete.

With both constituencies served elsewhere, `tolerates re-arming` signs nothing legitimate, and the diagnostic's fix-it list is complete without it: condition the deleter, restructure the guard, or — for intentional re-triggering — model the reversal as data. Ruled: no escape.

**The conservative false positive does not reopen this (2026-08-14).** The coarseness thread's prune case errors under a conservative prover despite having no actual hazard — and that is a second, independent reason tolerance stays unavailable, not a crack in the ruling: `tolerates` signs *real* hazards the business accepts (the streak really breaks under replay; the ping really can be lost), never prover limitations. A signature on a rule that cannot re-arm would be a false statement whose falseness the signature outlives — sign the prune today at 90-vs-3 days, widen the rate-limit window to 120 days next year, and the prune now *genuinely* re-arms with the hazard already signed away: no diagnostic, the effects-at-a-distance failure manufactured by hand. The fold precedent ("legit-but-unprovable gets `tolerates`") doesn't apply — there the hazard was real and only the safety unprovable; here the hazard is absent. A false positive's discharges are restructure (the field-witness fix, available today) or a sharper prover (the open thread) — the same line one-writer already holds: fail-closed, no signature, restructure until provable.

## Open thread — coarseness of the existence-dependency check

**Leaning: conservative (2026-08-14, unratified)** — the error stands even for provably-harmless deletes, with the field-witness restructure as the discharge; the worked catalog (below) is the study set for ratifying where exactly the line falls. Conservative: *any* deleter of a shape *any* guard reads is the error. Path-sensitive: only deleters whose trigger can coincide with the guarded instance's active lifetime fail. One case each way, both over the fixture family above. First, a cleanup sweep a path-sensitive prover clears outright — the disjointness is in the predicates:

The context that trips the check is the rate limit's guard itself — the §17 spelling, whose cross-tick memory is the `Reminder` **evidence**: the guard's `exists` reads `Reminder`, so `Reminder` records are load-bearing, and any deleter of them is in the check's sights:

```
rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }   -- the evidence IS the guard's memory
}

rule PruneOldReminders when (Reminder where sentOn < today - 90 days) on Monthly {
    delete this    -- conservative: error — a deleter of Reminder exists, and RemindOverdue's
                   -- guard reads Reminder's existence.
                   -- path-sensitive: legal — a reminder with sentOn < today - 90 days provably
                   -- never satisfies the guard's sentOn > today - 3 days window; nothing re-arms.
}
```

Under the conservative slice, the author still achieves the prune — via the diagnostic's "restructure the guard" fix. **The prune rule doesn't change at all**; the fix is to the *guard*: move the rate limit's memory off the prunable evidence onto a **field witness** (§18's witness-grain choice, date grain), so no guard reads `Reminder`'s existence:

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

The disarm proof still discharges (`= today` falsifies `<= today - 3 days`), one-writer holds, and `Reminder` becomes what this design arguably always meant it to be — optional history the business may keep, prune, or never write — rather than the rate limit's load-bearing memory. The general consequence is worth naming: **§18's witness-grain choice now has a delete-side face.** An evidence witness makes its records load-bearing — undeletable until path-sensitivity clears the window or the guard is restructured; a field witness leaves the evidence free to prune. Choosing the guard's grain is also choosing the evidence's prunability, and the connected diagnostic is what makes that choice visible instead of latent.

Second, a sweep the same prover rightly *cannot* clear, because the guard's own predicate doesn't carry the disjointness:

```
shape ArchivableApplication = DepositApplication where deposit.account is ClosedAccount

rule PurgeClosedApplications when ArchivableApplication on Monthly {
    delete this
}
```

Conservative verdict: error — a deleter of `DepositApplication` exists. The path-sensitive hope — "closed accounts' deposits are done; purging is safe" — is *not yet provable*, because `UnappliedDeposit`'s predicate never mentions account state: the re-armed deposit re-enters it regardless, and the Hourly tick re-folds. The purge becomes legal only when the guard itself excludes the purged scope (`UnappliedDeposit = Deposit where not exists ... and account is OpenAccount`) or a spent `never` proves the scopes disjoint — which is the right outcome, but how much of that proof burden the prover carries vs. demands rides with the OQ16 calibration, like every other coarseness question.

## When a delete errors — the worked catalog

**Leaning: conservative — the error stands wherever a proof-bearing read exists (2026-08-14, unratified; this catalog is the study set for ratifying exactly where the line falls.)** The boundary principle: *a delete errors when it can break something the spec proves or declares — never merely because state changes.* Recalculation is not a hazard: memberships flipping and rules firing at the deleting commit is drift, the design working. Errors come from five sources; the cases below put one example on each side of each line.

### No error — recalculation and discharged references

**C1 — drift only.** Nothing proves anything about `DraftListing`'s persistence; deleting one flips aggregates and memberships at the commit, ordinary drift:

```
shape ActiveSeller = Seller where count(listings where PublishedListing) > 0

rule ApplyDiscard when DiscardDraft {
    delete draft        -- ActiveSeller membership may flip; exit rules fire. Fine.
}
```

**C2 — derivations recalculate, even startlingly.** Deleting a `Payment` raises the derived `balance`, and the invoice may re-enter `OverdueInvoice` and be reminded again — *correct*: it is overdue again. Aggregates and derivations are current-state computations, not proofs of once-ness; no error from this check (whether `Payment` — an occurrence fact — *should* be deletable is the author's call, spelled as `undeletable` or a conditioned deleter, not this check's business).

**C3 — completeness discharged by the marker.** Deleting a `Seller` with `Listing.seller: one Seller unless deleted` referrers: the reference absorbs; no error.

**C4 — completeness discharged by same-commit cascade.** `delete invoice` and `delete`s of its line items in one body: every referrer resolved at the commit; no error.

### Error — the five sources

**C5 — referential completeness.** `delete invoice` while `LineItem.invoice: one Invoice` (plain, required) referrers exist and the commit resolves none of them: error naming the stranded referrers; fixes are C3's marker, C4's cascade, or restructuring to copies.

**C6 — guard witness (existence-dependency).** The fixture: deleting `DepositApplication` re-arms `ApplyDeposit`; the backstop double-folds. Error naming deleter and guard; fixes are conditioning the deleter, restructuring the guard's witness grain, or — for intentional re-triggering — reversal-as-data (ruled not signable, above).

**C7 — guard witness, provably-disjoint window.** `PruneOldReminders` (coarseness thread, above): no actual hazard, but under the conservative lean, still the same error as C6 — the discharge is the field-witness restructure, never a signature. This is the case the conservative/path-sensitive calibration decides; the lean accepts the false positive to keep the check simple and the restructure honest.

**C8 — singularity proof.** The episode pattern's exit rule reads `(OpenDelinquencyFlag for this)`, provably at-most-one *and present* because the entry rule's guard maintains exactly one open flag per delinquent account. A deleter of `DelinquencyFlag` can remove the open flag mid-episode, stranding the exit rule's singular reference: error naming deleter and reference. Fix: condition the deleter on the episode being closed (`DelinquencyFlag where exists DelinquencyResolution for this`).

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

The catalog's shape to validate against realistic specs: C1–C4 confirm that ordinary modeling stays untaxed; C5, C9, C10, C11 are structural and uncontroversial; C6–C8 are the existence-dependency family where the conservative lean bites, and C7 is its calibration pivot.

## Open thread — polarity of the deletion gate

Raised 2026-08-14 via "deletable only if `isDraft`." The gate is negative and state-scoped: "deletable only while draft" is spelled as `undeletable` on the *complement*, exactly as "editable only while draft" is spelled as a freeze on the issued state — the permission-denying clause lives on the state where the permission is denied, and with a boolean the complement is exact, so the disjointness check covers the sentence fully:

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

Two candidate answers, neither adopted:

1. **The state partition declaration's business** (README §22, `states of`) — once a partition is declared, per-state deletion permission sits beside per-state write permission, "deletable only in Draft" is checkably exhaustive, and a new state is born undeletable unless the declaration grants:

   ```
   states of Listing = Draft | Submitted | Published | Archived   -- candidate construct, not designed
       deletable in Draft                                         -- positive, exhaustive, checkable:
                                                                  -- adding Archived changed nothing
   ```

2. **A positive `deletable` grant** with shape-level default-undeletable — a second polarity, cutting against the `frozen` precedent, justified only if positive-exhaustive sentences turn out to be the common case:

   ```
   shape Listing {
       title: text
       status: text
   } undeletable                                     -- default: no state may delete

   shape Draft = Listing where status == "draft" {
       deletable                                     -- the one grant; Archived is born covered
   }
   ```

Current lean: (1) — the negative gate stands as accepted, sufficient for the boolean complement today, and the exhaustive sentence rides with the partition declaration rather than motivating a second polarity. Unratified.

## Relationship to OQ27

One primitive, two faces. This investigation is the *description* face: delete as a business behavior, commanded by rules, gated and checked. OQ27 is the *storage* face: erasure as declared policy (retention windows, right-to-be-forgotten) the transpiler enforces, where the model may retain the fact that something happened while its payload ceases to be physically retrievable. If this investigation lands a delete statement, OQ27's policy vocabulary plausibly compiles down to scheduled deletes plus a compilation-level payload-shredding choice — the tombstone candidate above is where the two faces would meet.

## Behaviors checklist (the framing questions, answered in place)

- **Cascade** → referential completeness, per-hop and spelled, never transitive magic (above).
- **Validation** → the state-scoped gate clause plus the partition idiom (above).
- **Rules before/after the delete** → on-commit rules share the deleting transaction and are the instance's last readers; `after commit` hangs off produced durable records; no hook vocabulary (above).
- **Refinement recalculation** → yes, free, by delete-is-a-commit; the open edges are deletion-vs-predicate-exit distinguishability and aggregate non-monotonicity (above).
