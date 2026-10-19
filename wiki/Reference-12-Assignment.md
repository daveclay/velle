# 12. Assignment (mutation in place)

A rule changes a stored value by assigning to it. The new value replaces the old one in the record itself, at the commit that fired the rule. The old value is gone unless you chose to keep a history. This page shows how to write an assignment, which values can be assigned, what happens when two rules could write the same value, how to keep a history instead, and how a rule deletes a record.

## Writing an assignment

A customer sends in a corrected email address, and the business wants the customer record to show the new address from then on. From `examples/billing/billing.velle`, where `CorrectEmail` is declared as an [act](Reference-04-State-and-commits):

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
}

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}
```

- `CorrectEmail` is the request. It names the customer and carries the new address.
- `ApplyEmailCorrection` fires when a `CorrectEmail` is committed ([§11](Reference-11-rule-when-on)).
- `customer.email = corrected` writes the new address onto the customer. The write lands in the same commit.

Three things about the statement:

- **`=` inside a rule means "becomes, now."** Inside a shape body the same symbol means "is always defined as," which is a [derived property](Reference-07-Derived-properties). The two never meet: a rule body holds no definitions and a shape body holds no effects.
- **The left side is a fixed path written out in full.** `customer.email` names one field of one record reachable from the rule's subject. There is no way to compute which field to write. Because every write names its field, the compiler can tell you every refinement and derived property a write may change.
- **The left side is a stored field.** A derived property is a formula, and a formula cannot be assigned. `invoice.balance = 0` is a compile error when `balance` is derived. Instead, commit the data the formula reads, such as a `Payment` record.

Across commits, the last write wins. Within one commit there is no "last," which is why the compiler insists on one writer per field, below.

If the right side reads the field being assigned, as in `account.balance = account.balance + amount`, the value is a running total. Running totals need extra care, covered in [§19](Reference-19-Self-referential-folds-and-tolerates).

To assign the same field on every member of a collection, see "Committing and assigning collections" in [§6](Reference-06-Relationships#committing-and-assigning-collections).

## One writer per field, per commit

Two rules may both assign the same field, as long as no single commit can fire both. If one commit could fire both, the compiler rejects the pair, because the field would have no defined value.

```
shape CorrectEmail  { customer: one Customer, corrected: text }
shape OverrideEmail { customer: one Customer, admin: one Admin, overridden: text }

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}

rule ApplyEmailOverride when OverrideEmail {
    customer.email = overridden          -- legal: an OverrideEmail is never a CorrectEmail,
                                         -- so these always fire from different commits
}

rule NormalizeEmail when CorrectEmail {
    customer.email = lowercase(corrected)     -- compile error: same trigger shape as
                                              -- ApplyEmailCorrection — one commit fires both
}
```

- `ApplyEmailCorrection` and `ApplyEmailOverride` are fine together. A commit is either a `CorrectEmail` or an `OverrideEmail`, never both, so whichever is committed later wins. That matches the business: the admin's override stands until the customer corrects it again.
- `NormalizeEmail` is rejected. It has the same trigger as `ApplyEmailCorrection`, so one `CorrectEmail` commit would fire both and write `email` twice.

The compiler decides "could one commit fire both?" from the declarations alone:

- Same trigger shape: yes.
- A [refinement](Reference-08-Refinements) and its base shape, or two refinements that can overlap: yes.
- Two unrelated shapes: no.
- Anything the compiler cannot settle counts as yes, and the pair is rejected.

The check covers the whole spec. A second rule writing `customer.email`, added months later in another file, is reported in one diagnostic naming both rules. `then` ([§15](Reference-15-then)) cannot order two writes to one field within a rule. Write one assignment whose right side states the whole intent.

## A shape body cannot assign

An assignment belongs in a rule, never in the shape that triggers it:

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    customer.email = corrected      -- rejected
}
```

Write the rule instead, as in the first example. Committing a record already stores it, so a rule is needed only when the commit has a further consequence, and the rule's name says what that is. A [transient act](Reference-04-State-and-commits#transient-acts-transient-act) is not stored, so it does nothing without a rule.

## The ledger alternative

If the business needs the history of a value, do not overwrite it. Record each change as a new record, and define the current value as the latest one:

```
shape Customer {
    signupEmail: text
    email: text = if exists EmailCorrection for this
                  then latest(EmailCorrection for this by correctedOn).corrected
                  else signupEmail
}

shape EmailCorrection {
    customer: one Customer
    corrected: text
    correctedOn: timestamp on create
}
```

- `EmailCorrection` is one change. Nothing ever edits it.
- `correctedOn` is filled in when the record is created ([§5](Reference-05-Scalars)).
- `email` is a derived property. `latest(... by correctedOn)` picks the newest correction by that field. The ordering field is always named.
- The `exists` check comes first so that `.corrected` is read only when a correction exists.

The choice is made per field. "The email changed" is an assignment. "The email changed and we need to know what it was" is a ledger.

## Deletion (`delete`)

A rule body may contain three kinds of statement: an assignment, the creation of a record, and `delete <path>`. The delete removes one record from the state at the rule's commit.

A marketplace lets a seller discard a draft listing. The listing is removed, and the business keeps a note of what was discarded and why. From `examples/moderation/moderation.velle`:

```
act Seller {
    name: text
}

act Listing {
    title: text
    seller: one Seller? initially required    -- required at creation; absent once the seller is deleted
    isDraft: boolean initially true
}

shape Draft = Listing where isDraft

transient act DiscardDraft {
    listing: one Listing
    reason: text
}

shape DiscardRecord {
    listingTitle: text                        -- copied: the listing will not exist to reference
    reason: text
    discardedOn: timestamp on create
}

rule ApplyDiscard when (DiscardDraft where listing is Draft) {
    DiscardRecord from { listingTitle: listing.title, reason: reason }
    delete listing
}
```

- `seller: one Seller? initially required` means a listing must name a seller when created, but the field becomes absent if that seller is later deleted. Nothing writes the field; it goes absent on its own.
- `DiscardDraft` is a transient act: the request itself is not stored.
- `DiscardRecord` is what the business keeps. It copies the title, because the listing will not exist to point at.
- `ApplyDiscard` fires only for a draft. It creates the record from the listing's title, then deletes the listing, in one commit.

What you can and cannot write:

- The target is one record, named by a fixed path such as `delete this` or `delete listing`, never a collection. To delete several records, write a rule whose subject is each one; it fires once per record ([§16](Reference-16-Sweeps)).
- A rule may read the record it deletes, and create other records from what it reads, in the same commit.
- A commit may not both assign a field of a record and delete that record.
- A deletion leaves nothing behind: no tombstone, no `is deleted` test, no reference that outlives its target. To remember a deletion, create a record for it, as `DiscardRecord` does. To react to one, use `when deleted` ([§13](Reference-13-Exit-triggers#deletion-as-a-second-exit-cause-when-deleted)).
- A refinement may declare `undeletable`, which forbids deleting a record while it is a member. It is written where `frozen` is written ([§8](Reference-08-Refinements#frozen-fields-frozen)).

Delete current-state records such as a `Customer` or a `Listing`. Records of something that happened, such as a committed act or a `DiscardRecord`, are kept.

## What the compiler checks

- **Rejects** assigning a derived property, or any left side that is not a fixed path to a stored field.
- **Rejects** two assignments to the same field whose triggers could fire from one commit, and names both rules ([checks.md, V1](checks#v1--one-writer-disjointness)).
- **Rejects** an assignment in a shape body.
- **Rejects** a `delete` whose target is a collection or a transient act, two deletes of one record in one commit, and a commit that both assigns a field of a record and deletes it ([checks.md, V23](checks#v23--delete-statement-legality-coarse)).
- **Rejects** deleting a record that a required `one` reference still points at, unless the referrer is deleted in the same commit, declares the reference `? initially required`, or keeps copies of what it needs. Optional and `many` references need nothing. There is no cascade ([checks.md, V24](checks#v24--referential-completeness-coarse)).
- **Rejects** a delete when a `not exists` guard, a singular `(Shape for ...)` read, or a `never` depends on the deleted shape, unless the deleting rule's condition provably excludes those records ([checks.md, V25](checks#v25--existence-dependency-coarse), [V26](checks#v26--deleters-join-never-induction-coarse)).
- **Rejects** a delete that could reach a record in an `undeletable` refinement. The usual fix is to trigger the deleting rule on the deletable subset, as `ApplyDiscard` does with `Draft` ([checks.md, V27](checks#v27--the-deletion-gate)).
- **Rejects** a `when leaving` rule with `after commit` or a schedule that a deletion could hand a record that no longer exists ([checks.md, V28](checks#v28--deletion-stranding)).
- **Warns** when `undeletable` is declared where no rule could delete the record anyway ([checks.md, A2](checks#a2--dead-machinery)).

## Related pages

- [§7 Derived properties](Reference-07-Derived-properties), for values that are computed rather than assigned.
- [§11 rule](Reference-11-rule-when-on), for what fires a rule and which commit its writes land in.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), for running totals and other values updated from their own previous value.
