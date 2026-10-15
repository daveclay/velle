# 13. Exit triggers (`when leaving`)

`when R` reacts to an instance *entering* a refinement — becoming a member. `when leaving R` is its mirror: a reaction to an instance that was a member of R and stopped being one — the same commit-local transition reversed (`## rule`).

```
rule RestoreService when leaving Delinquent {
    ServiceRestoration from {
        account: this
        restoredOn: now
    }
}
```

**Not expressible as a complement.** Reacting to entering `Compliant` is not the same thing: a newly created, never-delinquent account also "enters" `Compliant` — *became compliant* and *was always compliant* are indistinguishable from current data alone. `leaving` needs no such reconstruction: only a member can leave, so the trigger is inherently transitional. This completes the condition vocabulary — `when` for entry, `when leaving` for exit; the trigger source (`on commit` / `on <schedule>`, see `## Schedule triggers`, §17) is orthogonal to both. A run-once guard applies identically when durability demands one, and guard granularity — the guard predicate's content (`## Run-once guards`) — decides what a *repeated* exit means, exactly as it does for repeated entries.

**What an exit rule may read — the last-reader rule.** At the exit commit the body reads current (post-state) data as any body does, plus exactly one thing more: the leaving refinement's **captured properties**. A capture is stored per-membership memory the runtime demonstrably holds for the membership's duration, and its retraction lands at the *close* of the exit commit, not at the moment the rule fires — so the exit rule is the capture's **last reader**, the one place per-membership memory can be read on its way out, typically to convert what matters into a durable fact before it's gone:

```
shape ArchivedInvoice = Invoice where
    exists ArchiveRequest for this and not exists UnarchiveRequest for this {
    captured archivedBy: one User = (ArchiveRequest for this).requestedBy
}

rule NotifyArchiver when leaving ArchivedInvoice {
    UnarchiveNotice from {
        invoice: this
        notify: archivedBy        -- the capture's last read, at the exit commit
        noticedOn: now
    }
}
```

This is a re-timing of retraction, not a second evaluation context: base fields read post-state everywhere, a capture has no post-state value to collide with, and no temporal operator exists. Full pre-state access — base fields as they were before the commit, a `before(...)`-style operator — is rejected: reads would be ambiguous for any field the exit-causing commit itself wrote, and the operator would grow into the delta-inspection vocabulary the language excludes (rules react to conditions, never diffs). The leaving refinement's *derived* properties stay unreadable — live computations licensed by a predicate that is now false; there is nothing to re-time. Evaluability is the mirror of the entry-side guardrail (`## Refinements`): beyond ordinary reads, an exit body may reference exactly the captures of the refinement it is leaving. Where the business needs a base field's pre-state ("the balance it recovered from"), the spelling is a capture declared at entry — `captured balanceWhenDelinquent: Money = balance` — the pre-state an exit body may read is the pre-state the author declared they would need; memory is visible data, never ambient context.

The residue is transaction-boundedness: a capture does not survive the exit commit's transaction, so a capture-reading exit rule can never be `after commit` and can never carry a schedule backstop — a compile error in the stranding family ("this rule reads `archivedBy`, which does not survive the transaction boundary it declares"). Evidence remains the only thing that survives *beyond the transaction*: a reaction that must be durable reads the reified act instead of the capture — `notify: latest(ArchiveRequest where invoice == this by requestedOn).requestedBy` (and must say `latest(...)`, not `(ArchiveRequest for this)`: the singular form was licensed by a predicate this instance no longer satisfies, and with re-archival in the model nothing proves at-most-one — `## Predicate expressions`) — or hangs off the evidence itself, the compensation pattern below.

**Evidence outlives its premise.** A rule fired, its evidence escaped, and then the instance left the refinement: what happens to evidence whose premise has been falsified? Nothing needs declaring — each case is expressed by machinery the language already has:

- **Evidence that is simply history** ("the quote is the quote; prices drift") needs nothing declared — silence is the behavior, and a spec that records evidence and reacts no further is complete and correct as written (the ledger stance, `## Assignment (mutation in place)`).
- **Immutability** ("you can't edit line items on an issued invoice") was never about the exit at all — it's a write-gate on a state, declared as `frozen` in the refinement's body (`## Refinements`, "Frozen fields"). The evidence-held lien is a freeze on an evidence-entered refinement, and lifting the lien is a non-monotone predicate.
- **Compensation** ("invoices are never edited — voided and reissued") is a pattern over the same machinery — next subsection.

Deleting evidence is never on the menu — a produced fact records something that happened in the world (the email was sent), and deleting the record makes the description lie.

## The compensation pattern

Compensation — the exit produces a compensating fact — is fully expressed by ordinary machinery once the rule's subject is the *evidence* rather than the instance that left:

```
shape UncompensatedSuspension = ServiceSuspension where not exists ServiceRestoration for this
shape RestorableSuspension    = UncompensatedSuspension where account is not Delinquent

rule CompensateSuspension when RestorableSuspension {
    ServiceRestoration from { suspension: this, restoredOn: now }
}
```

An ordinary guarded entry rule — no `when leaving` anywhere. (Note the `ServiceRestoration` here references its *suspension*, not just the account as the section-top `RestoreService` example's did — the evidence linkage is exactly what the pattern is about.) The account's exit from `Delinquent` is observed as the suspension's *entry* into `RestorableSuspension`, at the same balance-changing commit (drift is commit-mediated — `## rule`, "Rules ground in commits"). Everything compensation requires, the pattern provides: evidence scoping is by construction (no suspension → no subject → no firing — a membership too brief for the entry rule to observe compensates nothing); once-per-evidence is the canonical guard with the disarm proof (`## Run-once guards` — the body produces the `ServiceRestoration` that falsifies `UncompensatedSuspension`); there is no singular selection and no at-most-one proof, because `this` *is* the evidence; and durability composes on demand (`when RestorableSuspension after commit, Hourly`), which no transition-triggered rule can have — a transition is not data (`## rule`, "Transactions and `after commit`"). The correlation decisions — per-suspension? per-account-ever? time-windowed? — are visibly the refinement predicates, where granularity always lives (`## Run-once guards`, "No guard sugar"). The pattern is also *more* correct than any exit-scoped spelling: evidence that lands after the exit (a tick-cadence or `after commit` entry rule, the account recovering in the gap) is born a member of `RestorableSuspension` and compensates immediately, where an exit-scoped rule — its transition already past when the evidence landed — would strand it uncompensated forever.

Compensation therefore never needs `when leaving`: uncompensated evidence is durable memory that the membership happened, so exactly where there is something to compensate, the exit is reconstructible from current state. `when leaving` remains irreducible only for reactions that carry no evidence (`RestoreService` at the top of this section) — which is exactly where there is nothing to compensate.

## Deletion as a second exit cause (`when deleted`)

A record leaves a state for two different business reasons: a field changed and the state's predicate flipped false, or the record was deleted (`## Assignment (mutation in place)`, "Deletion"). *The listing was published* and *the draft was discarded* are different events, and a rule says which one it reacts to. Bare `when leaving X` means the predicate flipped, never deletion — if it also fired at deletion, a discard could counterfeit a publication, producing a notice for a listing that was never published. Reacting to a deletion is its own trigger, and the family is one grammar slot: `when leaving X` (the predicate flipped), `when deleted X` (the record was deleted), `when leaving or deleted X` (either cause), and `when leaving not deleted X` (the predicate flipped, stated — below). Both participles elide the same frame — "when [the record is] leaving," "when [the record is] deleted" — and the voice difference is real: a record leaves a state by its own data changing, and is deleted by something else. The condition is the ordinary one, a bare state name or an inline refinement (`when deleted (Draft where seller is some)`), exactly as for entry and predicate exit. The checked-in version of the case below, with the combined form beside the other two, is `examples/moderation/moderation.velle`:

```
act Seller {
    name: text
}

act Listing {
    title: text
    seller: one Seller
    isDraft: boolean initially true
}

shape Draft = Listing where isDraft

shape PublicationNotice {
    title: text
}

shape SellerNotice {
    seller: one Seller
    listingTitle: text
}

-- Means "the listing was published" and nothing else. Written as bare
-- `when leaving Draft` it would draw the advisory below, because ApplyDiscard
-- deletes drafts; `not deleted` states the intent.
rule AnnouncePublication when leaving not deleted Draft {
    PublicationNotice from { title: title }
}

-- Fires at the commit that deletes a Draft; `this` is the deleted listing,
-- read one last time. The title is copied out; the seller reference is legal
-- because the seller lives on.
rule NoteDiscard when deleted Draft {
    SellerNotice from { seller: seller, listingTitle: title }
}

transient act DiscardDraft {
    listing: one Listing
}

rule ApplyDiscard when (DiscardDraft where listing is Draft) {
    delete listing
}
```

**One reading rule.** Whichever form a rule uses, the body reads the subject's properties as of the triggering commit plus the named state's captured properties as their last reader — the last-reader rule above, extended unchanged: deletion ends the membership, and the capture's retraction lands at the close of the deleting commit. The record's non-existence after that commit forces two differences, and both surface only as compile errors, never as silently different behavior. A deletion-side body may not keep a reference to its subject — the product copies the fields it needs — where a predicate-exit body may. And a deletion-side rule is transaction-bound, with no `after commit` and no schedule backstop, because a record kept around to be read later would be the deletion memory the language excludes. The combined form is checked against the deletion-side constraints; where the two causes want different bodies, the author writes two rules.

**Where the two causes could be confused, the compiler says so.** A predicate-exit trigger over a bare shape can never fire — a bare shape has no predicate to flip, so a record leaves it only by deletion — and that is the ordinary unfireable-rule error (`## rule ... when ... on ...`), with the diagnostic offering `when deleted`. And a `delete` whose scope can reach a bare `when leaving` rule's state draws an advisory: the rule's meaning is defined, the predicate flipped, but an author who meant "for any reason" would get a rule that silently stops at deletion. The advisory names the rule and the deleter, and the author either states the cause — `not deleted`, `or deleted`, or a separate `when deleted` rule — or restructures the deleter to provable disjointness. `when leaving not deleted X` exists for exactly this: it means what the bare form means, said out loud, and where no deleter reaches the state its `not deleted` half is dead machinery, reported as such.
