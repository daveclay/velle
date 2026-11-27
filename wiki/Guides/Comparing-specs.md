# Comparing specs

`velle compare` reads two versions of a spec and says what changed between them. It writes in the names the spec gives things and in everyday words, not as changed lines of text. Each sentence points at the line of the spec it comes from. A product owner can read the report to see which business rules changed. When they decide against a change, they can tell the engineer exactly where in the spec to undo it.

## The situation

A spec changes in a pull request. The changed lines of the `.velle` file say what was typed, not what it does to the business. Deleting one `never` can let a line item clear an overdue invoice, and nobody edited the invoice. Moving a section to the end of the file changes many lines and nothing the system does. A reviewer needs to hear the first and not the second.

## Running it

```
velle compare before/billing.velle after/billing.velle
```

The same comparison runs from the compiler's classes as `velle.CompareKt`:

```
java -cp <the compiler and the spec's libraries> velle.CompareKt before/billing.velle after/billing.velle
```

Both specs must validate. The classpath holds the compiler and every library the spec uses, as it does for `velle.GenerateKt`. The command prints Markdown, ready to paste into a pull request.

## What it prints

The edit below changes one refinement in `examples/billing/billing.velle`. Overdue invoices stop being reminded when they are issued, instead of when they are archived:

```velle
shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice   -- before
shape ActionableOverdue = OverdueInvoice and not IssuedInvoice     -- after
```

The report has three parts. The first says what was edited:

```
## What was edited

- An Invoice is now an ActionableOverdue when it is an OverdueInvoice and it is not an IssuedInvoice.
  Before: when it is an OverdueInvoice and it is not an ArchivedInvoice.
  In the spec: after/billing.velle, line 151, `shape ActionableOverdue = OverdueInvoice and not IssuedInvoice`
```

The second says what the edit changes beyond what it says on its own, such as a rule nobody edited that an act can no longer cause:

```
## What that changes

### UnarchiveRequest

- A new UnarchiveRequest can no longer cause RemindOverdue to run, later rather than in the same commit.
  In the spec: after/billing.velle, line 151, `shape ActionableOverdue = OverdueInvoice and not IssuedInvoice`
```

The third says what the edit does to a record like the ones the system keeps:

```
## What happens to a record like this

### An Invoice like this

- An Invoice becomes an ActionableOverdue by this change alone when it is an OverdueInvoice,
  it is not an IssuedInvoice, and it is an ArchivedInvoice.
- At the next Weekly tick, RemindOverdue runs for it if it is an ActionableOverdue and there is no
  Reminder whose invoice is it and whose sentOn is later than 7 days ago.
- An Invoice stops being an ActionableOverdue by this change alone when it is an OverdueInvoice,
  it is not an ArchivedInvoice, and it is an IssuedInvoice.
- At the next Weekly tick, RemindOverdue no longer runs for it.
```

`examples/billing/changes/` holds a history of six changes to `examples/billing/billing.velle`, each version a whole spec, with the report for each change checked in beside it. Its README says what each report shows.

When nothing the spec says has changed, the report says `No change.` Reordering the file, editing a comment, and rewriting a condition in other words that mean the same are all no change.

## What was edited

Each declaration of one version is matched to the declaration of the same kind and name in the other. A shape, an act, a refinement, a rule, and a list of words are matched by name, and a `never` by what it says. A declaration with no match was added or removed. A matched declaration whose meaning differs was changed, and the report says it now and before.

A declaration that disappears under one name and appears under another, with nothing else different, is reported as renamed. A rename of a shape carries a warning. Records already stored under the old name are not carried over by the change, because a store names what it keeps after the shape.

The pointer after each sentence names the file, the line, and the declaration's first line as written. For a removed declaration it is the line in the older version.

A construct the report has no words for yet is printed as Velle, with a line saying so. The report never drops an edit.

## What that changes

Each sentence names only what the spec names: a shape, an act, a refinement, a rule. The compiler works out facts from a spec to draw its [diagrams](diagrams) and write its [generated cases](testgen). The report compares those facts between the two versions:

| Fact | A sentence it gives |
|---|---|
| Which commit can move a record into or out of a refinement | "A new LineItem can now make an Invoice stop being an OverdueInvoice." |
| Which commit can cause which rule to run | "A new UnarchiveRequest can no longer cause RemindOverdue to run." |
| Which prohibitions are checked when the act arrives, before any rule runs | "Whether a LineItem is refused when its quantity is at most 0 is now checked when it arrives, before any rule runs." |
| What an act's work waits its turn behind | "A new SubmitPayment now waits its turn behind other work on the same SubmitPayment.invoice." |
| The compiler's advisories | "The compiler now advises: ..." |
| The generated cases | "The compiler no longer writes 2 of the example tests it writes from the spec. They checked that a LineItem whose price is below 0.00 is refused, and that a LineItem whose price is 0.00 is accepted." |

Every one of these is a "can" or a "can no longer". The compiler draws a possibility whenever it cannot prove it impossible, so the report never says what will happen. A consequence it cannot prove impossible may still be one the business never sees.

The proofs are the ones the [state-flow diagrams](diagrams#state-flow-diagrams) use to draw an arrow in one direction only. A rule whose own write can only take a record out of its condition, such as a rule that creates the notice its condition says is missing, is never reported as able to cause itself to run.

Each consequence points at the edit that causes it: the one edit when there is one, or the edits that name something the consequence mentions.

## What happens to a record like this

When a refinement's condition changes, a record that meets the new condition and not the old one becomes a member by the change alone, with no act and no tick. A record that meets the old condition and not the new one stops being a member. The report writes both conditions down.

A rule on a schedule runs at its next tick for every record that is a member then ([§16](Reference-16-Sweeps)). So the report says what that rule does with such a record. Any other rule over the refinement could run for it, and the report says "could". It also says that nothing happened to the record itself, since the change to the spec is what moved it:

```
- SendReceipt could then run for it, though nothing happened to the Invoice itself: the change to the spec is what made it a PaidInvoice.
```

That sentence is from `examples/billing/changes/05-zero-total-receipts.md`, where `PaidInvoice` stops requiring a total above 0. When a `never` is added over a shape, or over an act that is kept as a record, a record stored before the change could break it, and its next change could be refused. The report says so only for a shape or act the earlier version already declared, since nothing of a new one, or of a `transient act` ([§4](Reference-04-State-and-commits)), was stored before.

This part describes one record of a kind. It never says which records a running system holds, or how many.

## When a rewording means the same

Two conditions mean the same when they are written the same way after the report has put each in one agreed spelling. It reasons only from facts:

- The sides of `and` and of `or` may be swapped, and a condition repeated beside itself counts once.
- `not (not P)` is `P`, and `not (P and Q)` is `not P or not Q`.
- `total > 0` is `0 < total`, for any ordered type, and `a == b` is `b == a`.
- An operator states whether its sides may be swapped and whether grouping matters ([Extension types](Extension-types)). The numbers' `+` and `*` state both.
- A function states whether its arguments may be given in any order and grouped any way ([Extension functions](Extension-functions)). `max` and `min` state both.

`not (due < today)` and `due >= today` are reported as changed. With `due` absent the first holds and the second does not ([§10](Reference-10-Predicate-expressions)). Nothing is concluded from a value either: `balance > 0` and `balance >= 0.01` are reported as changed.

When "means the same" rests on a fact an extension library stated, the report says so and names the library:

```
- LineItem is written differently and means the same, if `velle.finance` is right that the sides may be swapped for `decimal * Money`.
```

## Related pages

- [diagrams](diagrams), the pictures drawn from the same facts.
- [testgen](testgen), the generated cases the report counts.
- [Extension types](Extension-types) and [Extension functions](Extension-functions), the facts a library states.
- [§16 Sweeps](Reference-16-Sweeps), what a rule on a schedule does at its tick.
