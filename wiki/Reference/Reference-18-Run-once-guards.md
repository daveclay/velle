# 18. Run-once guards

Some rules must do their work exactly once per record, even if the system crashes midway: a deposit must land on the balance once, never twice and never zero times. In Velle that protection is not a keyword. It is a condition in the rule's `when` clause that becomes false once the body has run. That condition is a *guard*, and the record or field that makes it false is evidence the work was done. This page shows when a rule needs one, how to write it, and what the compiler checks.

## When a rule needs a guard

A rule that fires `on commit` runs inside the triggering commit's transaction ([§11, "Transactions and `after commit`"](Reference-11-rule-when-on#transactions-and-after-commit)). It fires exactly once and cannot be lost, so it needs no guard. A guard is needed in two cases:

- The rule has `after commit`. Its firing is a separate transaction, started after the triggering one finishes. It could be lost in the gap, so the unfinished work must be findable later.
- The rule runs on a [schedule](Reference-17-Schedule-triggers). It sees only current data, so "already handled" must be stored where it can read it.

## How to write one

"A receipt is emailed as soon as the payment lands, and must not be lost if the system dies right after." From `examples/billing/billing.velle`:

```
shape UnemailedReceipt = Receipt where not exists ReceiptEmail for this

shape ReceiptEmail {
    receipt: one Receipt
    queuedOn: DateTime
}

rule EmailReceipt when UnemailedReceipt after commit, Hourly {
    ReceiptEmail from { receipt: this, queuedOn: now }
}
```

- `UnemailedReceipt` is a named [refinement](Reference-08-Refinements): a receipt with no `ReceiptEmail` record pointing at it. This is the guard.
- `after commit` fires the rule right after the payment commits, in its own transaction.
- `Hourly` reruns it every hour. A receipt whose email was lost is still an `UnemailedReceipt`, so the rerun picks it up; an emailed receipt no longer matches, so the rerun is harmless.
- `ReceiptEmail from { ... }` creates the record that takes the receipt out of `UnemailedReceipt`. The effect and its evidence are one body, and a body is one commit, so they land together or not at all.

The header reads as "immediately, and self-healing hourly."

The evidence can be a stored field instead of a new record. "A deposit lands on the balance exactly once," from `examples/membership/membership.velle`:

```
shape Deposit {
    member: one Member
    amount: decimal
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    member.balance = member.balance + amount
    this.applied = true
}
```

Here the guard is `not applied`, and `this.applied = true` makes it false. The compiler checks a created record and a set flag the same way.

How much the evidence records is your choice. A boolean says *that* it happened. A field `appliedOn: Date?` says *when*: the guard becomes `appliedOn is none` and the body sets `this.appliedOn = now`. A record like `ReceiptEmail` can carry anything else worth keeping.

The evidence must be a record or field the spec declares; Velle keeps no hidden list of firings. An evidence record is an ordinary shape: other rules can trigger on it, and reacting to a failure record (`when FailedCharge`) works like reacting to a success.

## Once per what

The guard's condition decides what "once" means, and that is a business rule, written where a reader can see it:

```
-- identity: the witness correlates 1:1 with the trigger instance
Deposit where not exists DepositApplication for this

-- self: guard state lives on the trigger instance; no join at all
Deposit where not applied

-- chosen key: "once per customer"; the granularity is a product decision
AccountFlag where not exists (FlagNotification where customer == this.customer)

-- chosen key + time window: a rate limit; the guard expires
OverdueInvoice where
    not exists (Reminder where invoice == this and sentOn > today - 3 days)

-- paired apparatus: an episode's correlation is maintained by entry/exit rules
-- ("Counting and timing the episodes", Reference §20)
```

Once per deposit, once per customer ever, at most once every three days: each is a condition over data the spec already holds; there is no shorter spelling. For the last form, where one rule opens an episode and another closes it, see [§20, "Counting and timing the episodes"](Reference-20-State-change-patterns#counting-and-timing-the-episodes).

## What the compiler checks

- **It rejects** a guarded rule whose body does not make its own guard false. A version of `ApplyDeposit` that forgets `this.applied = true` fails with "this rule never leaves its trigger state", because it would apply the deposit again every hour ([checks.md, V2](checks#v2--disarm-proof)). Only the guard part of the condition must be made false; other `not` parts are ordinary conditions.
- **It rejects** `after commit` on a rule with no guard or no backup schedule, because a lost firing could never be found ([checks.md, V4](checks#v4--boundaryapparatus-both-ways)).
- **It rejects** a guard and backup schedule on a plain `on commit` rule, with "did you mean `after commit`?". Inside the commit's transaction there is no gap to protect against ([checks.md, V4](checks#v4--boundaryapparatus-both-ways)).
- **It checks** that a flag used as evidence has one writing rule, like any stored field ([checks.md, V1](checks#v1--one-writer-disjointness); [§12](Reference-12-Assignment)). A body like `member.balance = member.balance + amount` gets the checks in [§19](Reference-19-Self-referential-folds-and-tolerates).
