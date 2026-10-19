# 13. Exit triggers (`when leaving`)

A rule normally reacts to a record *entering* a condition: an invoice becoming overdue, a member becoming delinquent. Some business reactions belong to the opposite moment, when a record *stops* satisfying a condition: restore service when a member is no longer delinquent, notify someone when an invoice is taken out of the archive. Velle writes that with `when leaving`. A record can also stop satisfying a condition by being deleted, and Velle treats that as a separate event with its own trigger, `when deleted`.

After reading this page you can write a rule that fires when a record leaves a refinement, know what its body may read, choose between an exit rule and the compensation pattern, and say which of the exit forms to use when the record might be deleted.

## Reacting to a record leaving a refinement

`when leaving R` fires at the commit where a record that was a member of `R` stops being one. It is the mirror of `when R` ([§11](Reference-11-rule-when-on)), the same transition in the other direction. The example below is from `examples/membership/membership.velle`, which writes the same rule as `CloseDelinquencyEpisode`.

```
rule RestoreService when leaving Delinquent {
    ServiceRestoration from {
        account: this
        restoredOn: now
    }
}
```

You cannot write this as entering the opposite refinement. A newly created account that was never delinquent also enters `Compliant`, and "became compliant" and "was always compliant" look the same in current data. Only a member can leave, so `when leaving` needs no such reconstruction.

The trigger source is independent of the direction: `when leaving Delinquent on Nightly` checks at the tick ([§17](Reference-17-Schedule-triggers)). A [run-once guard](Reference-18-Run-once-guards) applies to an exit rule exactly as to an entry rule, and the guard's condition decides what a second exit means.

## What an exit rule may read

The body reads the record's current values, after the commit, as any rule body does. It may read one thing more: the **captured properties** of the refinement being left ([§8, "Properties of a refinement"](Reference-08-Refinements#properties-of-a-refinement)). A capture is a value stored when the record entered the refinement, and it is removed at the close of the exit commit. The exit rule is its last reader. The usual use is to copy what matters into a durable record before it goes. A version of this example with a different capture is in `examples/billing/billing.velle` as `NoteUnarchival`.

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

Line by line:

- `ArchivedInvoice` is an invoice with an archive request and no later unarchive request.
- `captured archivedBy` stores who asked for the archive, evaluated once at entry.
- `NotifyArchiver` fires when the invoice leaves the archived state. `archivedBy` is still readable here, and nowhere later.

Three limits follow:

- **No reading of a field's old value.** There is no operator for a base field's value before the commit. If the business needs the old value, such as "the balance it recovered from," declare it as a capture at entry: `captured balanceWhenDelinquent: Money = balance`. The old value an exit body may read is the one the author said they would need.
- **The left refinement's derived properties are not readable.** They are live computations that depended on a condition that is now false.
- **A rule that reads a capture must run inside the exit commit's transaction.** The capture does not outlive that transaction, so such a rule may not say `after commit` and may not carry a schedule. The compiler rejects it, naming the capture that would not survive the boundary ([checks.md, V7](checks#v7--capture-transaction-boundedness)). A reaction that must be durable reads the stored act instead: `notify: latest(ArchiveRequest where invoice == this by requestedOn).requestedBy`. It must say `latest(...)` rather than `(ArchiveRequest for this)`, because once an invoice can be re-archived nothing proves there is only one request ([§10](Reference-10-Predicate-expressions)).

## Records a rule created before the exit

Suppose an entry rule created a `ServiceSuspension`, and then the member left `Delinquent`. Nothing happens to the suspension by itself. Each business answer uses machinery you already have:

- **It is history.** "The quote is the quote; prices drift." Nothing needs to be declared. A spec that records the fact and reacts no further is complete ([§12, "The ledger alternative"](Reference-12-Assignment#the-ledger-alternative)).
- **It must not be edited.** "You can't edit line items on an issued invoice" is a write restriction on a state, declared with `frozen` in the refinement's body ([§8, "Frozen fields"](Reference-08-Refinements#frozen-fields-frozen)).
- **It must be undone by a new record.** "Invoices are never edited; they are voided and reissued." That is the compensation pattern in the next section.

Do not delete the record. It records something that happened in the world, and deleting it makes the data lie.

## The compensation pattern

To react to an exit by producing a compensating record, write an ordinary entry rule whose subject is the earlier record, not the record that left:

```
shape UncompensatedSuspension = ServiceSuspension where not exists ServiceRestoration for this
shape RestorableSuspension    = UncompensatedSuspension where account is not Delinquent

rule CompensateSuspension when RestorableSuspension {
    ServiceRestoration from { suspension: this, restoredOn: now }
}
```

Line by line:

- `UncompensatedSuspension` is a suspension with no restoration yet.
- `RestorableSuspension` is one of those whose account is no longer delinquent.
- `CompensateSuspension` fires when a suspension enters that state, which happens at the same commit that took the account out of `Delinquent`. Its `ServiceRestoration` points at the suspension it compensates, which is the point of the pattern.

There is no `when leaving` here, and the pattern has advantages over one:

- A membership too brief for the entry rule to notice produced no suspension, so there is nothing to compensate and the rule does not fire.
- The `not exists ServiceRestoration` condition is a run-once guard, so each suspension is compensated once ([§18](Reference-18-Run-once-guards)).
- The rule may be made durable, as `when RestorableSuspension after commit, Hourly`, because its trigger is stored data. A `when leaving` rule cannot be, because a transition is not data.
- A suspension that lands *after* the account recovered, from a scheduled or `after commit` entry rule, is born restorable and is compensated at once. An exit rule would have missed it, because its moment had passed.

What counts as "the same suspension" is written in the refinement conditions, per suspension, per account, or within a time window, as with any guard ([§18, "Once per what"](Reference-18-Run-once-guards#once-per-what)).

Use `when leaving` only when the reaction has no earlier record to hang off, as in `RestoreService` at the top of this page.

## Deletion as a second exit cause (`when deleted`)

A record leaves a state for two different business reasons. Either a field changed and the state's condition became false, or the record was deleted ([§12, "Deletion"](Reference-12-Assignment#deletion-delete)). "The listing was published" and "the draft was discarded" are different events, and a rule says which one it reacts to.

- `when leaving X` fires when the condition became false, and never at deletion.
- `when deleted X` fires at the commit that deletes a member of `X`.
- `when leaving or deleted X` fires for either cause, with one body.
- `when leaving not deleted X` means the same as the bare form, stated out loud. See "What the compiler checks" below for when to write it.

In every form the condition is an ordinary one: a state name, or an inline refinement such as `when deleted (Draft where seller is some)`. A fuller version of the example below, with the combined form beside the other two, is in `examples/moderation/moderation.velle`.

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

Line by line:

- `Draft` is a listing whose `isDraft` flag is set. Publishing clears the flag, so the listing leaves `Draft` by its condition becoming false.
- `AnnouncePublication` reacts only to that. If it were written as bare `when leaving Draft` it would mean the same thing, but the compiler would warn, because `ApplyDiscard` below can also make a listing leave `Draft`.
- `NoteDiscard` reacts only to deletion. Its body reads the listing's `title` and `seller` one last time and copies them into the notice.
- `ApplyDiscard` is the rule that deletes. `DiscardDraft` is a transient act, a request that is not stored ([§4, "Transient acts"](Reference-04-State-and-commits#transient-acts-transient-act)).

**What a deletion-side body may read.** The same as any exit body: the record's values as of the triggering commit, plus the captures of the named state as their last reader. Deletion ends the membership, so the capture is removed at the close of the deleting commit. Two differences follow from the record not existing afterwards, and both are compile errors rather than quiet changes of behavior:

- The record a deletion-side rule creates may not hold a reference to the deleted record. Copy the fields you need, as `NoteDiscard` copies `title` ([checks.md, V24](checks#v24--referential-completeness-coarse)). A body under plain `when leaving` may keep a reference.
- A deletion-side rule may not say `after commit` and may not carry a schedule. A record kept around to be read later would be a deleted record still in the data ([checks.md, V28](checks#v28--deletion-stranding)). The same applies to a plain `when leaving` rule over a shape some rule deletes, unless the deleter provably cannot reach the state.

The combined form `when leaving or deleted X` is checked under the deletion-side rules. If the two causes need different bodies, write two rules.

## What the compiler checks

- **`when leaving` over a shape with no condition is rejected.** A bare shape has no condition to become false, so a record leaves it only by deletion, and the rule could never fire ([checks.md, V3](checks#v3--unfireable-rule)). The error offers `when deleted`.
- **A `delete` that can reach a bare `when leaving` draws a warning.** The rule's meaning is defined, the condition became false, but an author who meant "for any reason" would get a rule that silently does nothing at deletion. The warning names the rule and the deleting rule. Fix it by stating the cause, `not deleted`, `or deleted`, or a separate `when deleted` rule, or by restructuring the deleter so it provably cannot reach the state ([checks.md, A6](checks#a6--deleter-reaches-a-bare-when-leaving)).
- **`not deleted` where no deleter can reach the state is dead machinery**, and the compiler says so ([checks.md, A2](checks#a2--dead-machinery)).

## Related pages

- [§11 rule ... when ... on ...](Reference-11-rule-when-on), for entry triggers and transactions.
- [§8 Refinements](Reference-08-Refinements), for captured properties and `frozen`.
- [§12 Assignment](Reference-12-Assignment), for `delete` and the ledger alternative.
- [§18 Run-once guards](Reference-18-Run-once-guards), for guards on exit rules and in the compensation pattern.
