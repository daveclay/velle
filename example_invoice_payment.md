# Worked example: invoice/payment

Testing whether refinements (conditions-as-shapes) plus rules reacting to commits are enough to express order-dependent, multi-step behavior without a separate sequencing or state-machine construct.

This section is the consolidated model — every mechanism from every stress test below, wired into one coherent example. The stress-test sections that follow are the derivation history: how each piece was found and why it looks the way it does. (The whole file is kept current with the language as it settles — `when`/`on` headers, `from`-mappings, guards as hand-written named refinements, `timestamp` fields — so the resolutions below are restated in today's terms even where the question was originally answered with since-retired constructs.)

One synthesis choice made to unify the two flows that were developed somewhat separately below (`Invoice`/`Payment` and `Order`/`ChargeAttempt`): a successful card charge is treated as *one way* a `Payment` gets created against an `Invoice`. That wasn't explicitly discussed; flag it if that's not the intended relationship between the two flows.

## Shapes

```
shape Customer {
    name: text
    email: text
}

shape Invoice {
    customer: one Customer
    amount: Money
    due: Date
    payments: many Payment
    balance: Money = amount - sum(payments, amount)   -- derived, not stored
}

shape Payment {
    invoice: one Invoice
    amount: Money
    receivedOn: timestamp on create
}

shape Order {
    customer: one Customer
    invoice: one Invoice
    amount: Money
}

shape ChargeAttempt {
    order: one Order
    requestedOn: timestamp on create
    response: ChargeResponse?
}

shape ChargeResponse {
    outcome: text
    processedOn: Date        -- the processor's own date — business data, not commit metadata
}
```

`ChargeAttempt` is the intent-before-effect pattern (README §11) in miniature: the attempt is committed as data *before* the processor interaction, and the outcome lands later as its resolution. How `response` gets filled — an HTTP callback, another process's write — is an external-input concern (`open_questions.md`, OQ5); Velle describes only that the field exists, isn't determined yet, and that refinement membership changes when it is.

## Refinements (conditions as shapes)

```
shape OverdueInvoice       = Invoice where balance > 0 and due < today
shape PartiallyPaidInvoice = Invoice where balance > 0 and balance < amount
shape SettledInvoice       = Invoice where balance <= 0

shape PendingChargeAttempt   = ChargeAttempt where response is none
shape CompletedChargeAttempt = ChargeAttempt where response is some
shape SuccessfulCharge       = CompletedChargeAttempt where response.outcome == "approved"
shape FailedCharge           = CompletedChargeAttempt where response.outcome == "declined"

shape FlaggedCustomer = Customer where count(invoices where OverdueInvoice) >= 3
```

`invoices` above is the inferred inverse of `Invoice.customer` (README §6) — not separately declared on `Customer`. `SuccessfulCharge` can read `response.outcome` because `CompletedChargeAttempt`'s own predicate (`response is some`) narrows the optional (README §10).

## Evidence and effect shapes

Shapes produced by rules. Each one is both the record of what happened and — where a guard is needed at all — the witness that disarms it (stress test #2); there's no separate "evidence" category, these are ordinary shapes doing double duty. Their timestamps are `timestamp on create` (README §5): the moment of record *is* the creation commit, so no field needs populating by hand.

```
shape AuditLogEntry {
    order: one Order
    loggedOn: timestamp on create
}

shape Receipt {
    invoice: one Invoice
    sentOn: timestamp on create
}

shape InventoryRelease {
    order: one Order
    releasedOn: timestamp on create
}

shape AccountFlag {
    customer: one Customer
    flaggedOn: timestamp on create
}

shape FlagNotification {
    accountFlag: one AccountFlag
    sentOn: timestamp on create
}
```

Schedule definition itself (what `Daily` below actually means — cadence, timezone, etc.) is a separate, not-yet-designed concern, assumed to be provided by some schedule construct that can fire arbitrarily, cron-like. Only the rule-side trigger syntax is settled (README §17).

## Process and rules

There is no `ApplyPayment` act-with-effect construct: committing a `Payment` *is* persisting it (README §12, "No act-level sugar"), and `balance` being derived means the invoice's refinement membership updates with no rule at all.

```
rule InitiateCharge when Order {
    AuditLogEntry from { order: this }
    then
    ChargeAttempt from { order: this }
}

rule RecordPayment when SuccessfulCharge {
    Payment from { invoice: order.invoice, amount: order.amount }
}

rule ReleaseInventory when FailedCharge {
    InventoryRelease from { order: this.order }
}

rule SendReceipt when SettledInvoice {
    Receipt from { invoice: this }
}

rule FlagOverdueAccounts
    when (FlaggedCustomer where not exists AccountFlag for this)
    on Daily {
    AccountFlag from { customer: this }
}

rule NotifyCustomerOfFlag when AccountFlag {
    FlagNotification from { accountFlag: this }
}
```

No explicit "then send receipt" step is written for the payment/settlement path. A `Payment` landing recomputes `balance` (derived), which changes which refinement the invoice belongs to, and `SendReceipt` — a rule whose condition is `SettledInvoice` — fires at that commit.

What each rule demonstrates:

- **`InitiateCharge`** — ordering without data dependency, via `then` (stress test below), and the intent committed before the external interaction (README §11).
- **`RecordPayment` → `SendReceipt`** — sequencing as data dependency, branching as refinement dispatch, no state-machine construct needed.
- **`ReleaseInventory when FailedCharge`** — errors are a refinement, not a control-flow mechanism (stress test #1).
- **Plain rules carrying no guards** — commit-triggered rules fire once per entering commit, inside the act's transaction; a guard would be dead machinery (stress test #2, README §18).
- **`FlagOverdueAccounts ... on Daily`** — a sweep: the schedule source fires once per current member, each firing its own transaction (README §16), reacting to a cross-shape aggregate refinement (stress test #4) that only the tick re-checks (stress test #3); the `not exists AccountFlag for this` guard is the cross-tick memory the tick law requires.
- **`NotifyCustomerOfFlag when AccountFlag`** — chaining through a produced fact: the flag's commit matches this rule's condition the same way an external act's would.

Stress test #5 (reversal) is expressible today — both policies below — but no single canonical pattern has been adopted (README §21); the consolidated `FlagOverdueAccounts` uses the simple once-per-customer guard, which #5's Option A upgrades.

## Stress test: order with no data dependency

Suppose compliance requires **"write an audit log entry before charging the customer's card,"** even though the audit log doesn't consume the charge's output and the charge doesn't consume the log's output. There's no data dependency between them at all — just a business/legal requirement on wall-clock order. Data-flow-implied ordering gives nothing here.

### Resolution: `then` as a lightweight ordering operator (settled — README §15)

```
rule InitiateCharge when Order {
    AuditLogEntry from { order: this }
    then
    ChargeAttempt from { order: this }
}
```

`then` is opt-in ordering inside a rule body — effects listed without `then` are unordered (the compiled implementation is free to run them in any order or in parallel); effects joined by `then` are forced into that order. A rule's body is exactly one commit, so `then` is a compilation ordering, never an observable intermediate state (README §15).

## More stress tests

Each of these pokes a different part of the design. Riffing through them to find where shapes/rules/refinements break down.

### 1. Failure / rollback

`ChargeCard` fails after inventory was already reserved — something needs to release the reservation. Does Velle need an explicit "compensating rule," or does failure just produce a different output shape that other rules react to via refinement, the same way `SettledInvoice` triggered `SendReceipt`?

> Resolved — error handling is ordinary refinement dispatch, no new mechanism needed.

#### Errors are not a control-flow mechanism — they're a refinement

The traditional "return an error object vs. throw an exception" debate is a symptom of the function-call model. An outcome is just another shape, and success vs. failure is just which refinement it satisfies:

```
shape PendingChargeAttempt   = ChargeAttempt where response is none
shape SuccessfulCharge       = CompletedChargeAttempt where response.outcome == "approved"
shape FailedCharge           = CompletedChargeAttempt where response.outcome == "declined"

rule ReleaseInventory when FailedCharge {
    InventoryRelease from { order: this.order }
}

rule SendReceipt when SuccessfulCharge {
    Receipt from { invoice: order.invoice }
}
```

Nothing is returned or thrown — there is no stack to throw through (README's "Validation rejection is data" stance, `open_questions.md` appendix). The attempt exists, the response lands, and whichever refinement the attempt then satisfies is what other rules react to — the same mechanism as `SettledInvoice` triggering `SendReceipt`. Reacting to failure uses the exact same machinery as reacting to success (README §18) — no `return`/`throw` distinction.

#### There's no internal/external distinction — the interaction is just a shape

An earlier draft of this tried to distinguish "pure derivation" (like `Invoice.balance`) from "exogenous" fields filled in by something outside the system (like a payment processor's response). That distinction isn't needed. The fix: model the *interaction itself* — the attempt, before it's resolved — as its own shape, the same as everything else. A `ChargeAttempt` exists the moment the system decides to try. Whether `response` eventually gets filled in by a payment processor's HTTP callback, a DB write from another process, or a pure calculation is irrelevant to the description — that's a compiling concern (marking shapes as external input is OQ5). Velle only describes: this shape exists, it has a field that isn't determined yet, and once it is, the shape's refinement changes and rules react at that commit. `PendingChargeAttempt` vs. `CompletedChargeAttempt` is the same mechanism as `OverdueInvoice` vs. `SettledInvoice`.

### 2. Rule-firing semantics (edge vs. level)

`rule SendReceipt when SettledInvoice` — if the invoice is re-evaluated later and it's *still* settled, does the rule fire again? Sending a duplicate receipt email is a real bug. Nothing so far distinguishes "just became a member of this refinement" from "currently a member of this refinement."

> Resolved — but "edge vs. level" was the wrong frame: it implies hidden runtime bookkeeping (a flag that says "already fired"). The commit model answers it with no bookkeeping at all (README §11).

#### Resolution: firing is per source, and memory is data

A commit-triggered rule fires once per commit at which its condition *becomes* true — pre-state false, post-state true, both well-defined at the commit. "Still settled" is not an event; nothing happens without a commit, so there is no re-evaluation pass to re-fire the rule. Plain rules therefore need no guard at all — inside the act's transaction there is no gap for a firing to be lost in, and a guard on a plain rule is dead machinery the compiler flags (README §18).

Exactly two situations need more, and both get it as *data*, never a runtime flag: **durability** across a declared transaction boundary (`after commit`), and **cross-tick memory** — a schedule source fires per *current member* at the tick, so "already handled" must be something the data contains (the tick law, README §17). The guard is a hand-written named refinement — there is no sugar layer (README §18) — and the disarm proof checks that the rule's body provably exits its own trigger state:

```
shape UnacknowledgedSettledInvoice = SettledInvoice where not exists Receipt for this
```

Once the `Receipt` exists, membership ends — the evidence of the action is the guard, as data. A rule that forgot the `Receipt` line wouldn't compile ("this rule never leaves its trigger state"). And note what entry semantics gives free: if the invoice is re-settled after a refund (#5), that's a *new* entering commit — the rule fires again, once per episode, with the accumulated `Receipt`s as history.

### 3. Time-based effects

"If the receipt isn't opened within 3 days, send a reminder." No triggering shape exists until a clock passes with nothing else happening — tests whether time itself needs to be a shape/relationship, or some other primitive.

> Resolved — nothing in Velle executes purely on the passage of time by default. A scheduled tick is a commit whose changed datum is `today` (README §17), referenced by name in the rule header's `on` clause; schedule *definition* (cadence, timezone) remains a separate, undesigned construct.

#### Resolution: the schedule is a trigger source in the header

```
rule FlagOverdueAccounts
    when (FlaggedCustomer where not exists AccountFlag for this)
    on Daily {
    AccountFlag from { customer: this }
}
```

`Daily` is a placeholder name, not a built-in. The header separates the condition (`when`) from the trigger source (`on`): omitted, the source defaults to `on commit`; a schedule source fires once per *current member* of the condition at the tick, each firing its own transaction (README §16, §17). The guard (`not exists AccountFlag for this`) is what the tick law demands — cross-tick memory as data — and its granularity is the predicate's content (#4).

This also sharpens something that was implicit until now: **a refinement is a pure predicate, not a trigger.** `OverdueInvoice` (`due < today`) doesn't "become true" and notify anyone — it's always evaluable against current data. Only rules react, and every reaction traces to a commit. For data-driven conditions, the acts' commits are the trigger set (derived by the compiler, README §11); for a purely time-dependent condition, only a tick's commit re-checks it — which is why a rule watching `FlaggedCustomer` with no schedule in its `on` clause under-fires, and the compiler says exactly that (the unfireable-rule diagnostic, README §11).

### 4. Cross-shape aggregate conditions

"Flag the customer's account if they have 3+ overdue invoices." A refinement on `Customer` defined by a condition over `many Invoice` — tests whether refinements compose across relationships, not just within one shape.

```
shape FlaggedCustomer = Customer where count(invoices where OverdueInvoice) >= 3
```

`invoices where OverdueInvoice` reuses `where` both to define a refinement and to filter a collection by one — no new mechanism, refinements compose across a relationship the same way they compose within one shape. The flagging rule is `FlagOverdueAccounts` above (#3): the aggregate drifts with payments, new invoices, and aging, and the Daily tick is what re-checks the aging component.

#### Extending it: notify the customer, not just flag the account

```
rule NotifyCustomerOfFlag when AccountFlag {
    FlagNotification from { accountFlag: this }
}
```

This chains through a produced fact: `AccountFlag` is simultaneously the witness disarming `FlagOverdueAccounts`' guard and the condition `NotifyCustomerOfFlag` reacts to — a rule firing's effects are a new commit that matches further conditions exactly as an external act's commit would (README §11).

Worth noting why `FlagNotification` references `accountFlag: one AccountFlag` rather than `customer: one Customer` directly: the reference target sets the granularity of everything downstream. If notification ever needs a durability guard (`AccountFlag where not exists FlagNotification for this`), that guard reads "once per *flag*" — so a legitimate future re-flagging (#5) is a distinct trigger with its own notification. Referencing the customer instead would make any such guard mean "has this *customer* ever been notified" — silently suppressing notification forever after the first. Granularity is predicate content, and the reference is what the predicate has to work with (README §18).

### 5. Reversal

A payment gets refunded; the invoice goes from `SettledInvoice` back to `PartiallyPaidInvoice`. Do rules run symmetrically on the way out of a refinement (e.g. revoke the receipt?) or only on the way in?

```
shape Refund {
    payment: one Payment
    amount: Money
    refundedOn: timestamp on create
}
```

Extend `Payment` and `Invoice.balance` to account for it:

```
shape Payment {
    invoice: one Invoice
    amount: Money
    receivedOn: timestamp on create
    refunds: many Refund
}

shape Invoice {
    customer: one Customer
    amount: Money
    due: Date
    payments: many Payment
    balance: Money = amount - sum(payments, amount) + sum(payments.refunds, amount)
}
```

Walk the sequence: an invoice is fully paid, `balance` drops to `0`, the entering commit fires `SendReceipt`, a `Receipt` lands. Later a `Refund` is committed, `balance` recomputes above `0`, and the invoice leaves `SettledInvoice`. The `Receipt` still exists — a produced fact records something that happened in the world, and evidence outliving its premise needs nothing declared (README §13). What *is* worth examining is what the business wants next.

#### Reframing the question

"Is a refinement transition symmetric or one-directional" assumed Velle itself needs an opinion about what reversal means. It doesn't — reversal-handling is a business decision, not a language design decision (README §2, flexible not restrictive). The human's decision gets encoded as *which* shapes they declare and *which* conditions they wire into the rules, not as a new primitive. Even detecting the reversal is just a refinement comparing current state against past evidence:

```
shape ReopenedInvoice = Invoice where exists Receipt for this and not this is SettledInvoice
```

(Re-settlement itself needs nothing: entry semantics fires `SendReceipt` once per settling episode — #2.)

**Option A — re-flag and re-notify.** The customer should go through the exact same treatment as a first-time flag. The added shape is a *resolution* — evidence that the old flag no longer applies — and the flagging guard is re-keyed to *active* flags:

```
shape AccountFlagResolved {
    accountFlag: one AccountFlag
    resolvedOn: timestamp on create
}

shape ActiveAccountFlag = AccountFlag where not exists AccountFlagResolved for this

rule ResolveFlagIfCleared
    when (Customer where exists ActiveAccountFlag for this and not this is FlaggedCustomer)
    on Daily {
    AccountFlagResolved from { accountFlag: (ActiveAccountFlag for this) }
}

rule FlagOverdueAccounts
    when (FlaggedCustomer where not exists ActiveAccountFlag for this)
    on Daily {
    AccountFlag from { customer: this }
}
```

The singular reference `(ActiveAccountFlag for this)` is provably at-most-one *because of `FlagOverdueAccounts`' own guard* — the whole-spec singularity proof (README §10, §20). Once resolved, a later `FlaggedCustomer` match produces a *new* `AccountFlag`, and because `NotifyCustomerOfFlag` chains off the flag instance (#4), the customer is automatically re-notified with no separate declaration. This is the flag/resolution episode pattern of README §20, arrived at independently.

**Option B — grace period.** A genuinely different policy, not just an undo: a reopened invoice shouldn't immediately re-trigger flagging, but should suspend it for a window:

```
shape GracePeriod {
    customer: one Customer
    startedOn: timestamp on create
    endsOn: Date
}

shape InGracePeriod = Customer where
    exists GracePeriod for this and today <= latest(GracePeriod for this).endsOn

rule StartGracePeriod when ReopenedInvoice {
    GracePeriod from { customer: this.customer, endsOn: today + 14 days }
}

rule FlagOverdueAccounts
    when (FlaggedCustomer where not exists ActiveAccountFlag for this and not this is InGracePeriod)
    on Daily {
    AccountFlag from { customer: this }
}
```

`latest(...)` orders by `startedOn`, the shape's declared creation timestamp (README §10) — repeated grace periods over a customer's lifetime make the singular `(GracePeriod for this)` unprovable, so the selector is required, and the `exists` conjunct narrows it to non-empty.

Both options reuse the same machinery — shapes, refinements, guarded sweeps, `from`-mappings — and the difference is entirely which artifact shapes the human chose to declare and what conditions they wired into `FlagOverdueAccounts`. This was never a gap where the language needed a construct for "reversal semantics": the existing vocabulary encodes either business decision explicitly, and Velle staying silent about which one is correct is the right behavior, not an omission. (Which pattern is *idiomatic* for the common case remains the open reversal item in README §21.)
