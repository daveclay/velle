# Worked example: invoice/payment

Testing whether "output feeds input" + refinements (conditions-as-shapes) are enough to express order-dependent, multi-step behavior without a separate sequencing or state-machine construct.

This section is the consolidated model — every mechanism from every stress test below, wired into one coherent example. The stress-test sections that follow are the derivation history: how each piece was found and why it looks the way it does.

One synthesis choice made to unify the two flows that were developed somewhat separately below (`Invoice`/`Payment` and `Order`/`ChargeAttempt`): a successful card charge is treated as *one way* a `Payment` gets created against an `Invoice` — the same target the original `ApplyPayment` shape feeds. That wasn't explicitly discussed; flag it if that's not the intended relationship between the two flows.

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
    balance: Money = amount - sum(payments, amount)   // derived, not stored
}

shape Payment {
    invoice: one Invoice
    amount: Money
    receivedOn: Date
}

shape Order {
    customer: one Customer
    invoice: one Invoice
    amount: Money
}

shape ChargeAttempt {
    order: one Order
    requestedOn: Date
    response: ChargeResponse?
}

shape ChargeResponse {
    outcome: text
    processedOn: Date
}
```

## Refinements (conditions as shapes)

```
shape OverdueInvoice       = Invoice where balance > 0 and due < today
shape PartiallyPaidInvoice = Invoice where balance > 0 and balance < amount
shape SettledInvoice       = Invoice where balance <= 0

shape PendingChargeAttempt   = ChargeAttempt where response is none
shape CompletedChargeAttempt = ChargeAttempt where response is some
shape SuccessfulCharge       = CompletedChargeAttempt where response.outcome = "approved"
shape FailedCharge           = CompletedChargeAttempt where response.outcome = "declined"

shape FlaggedCustomer = Customer where count(invoices where OverdueInvoice) >= 3
```

`invoices` above is the inferred inverse of `Invoice.customer` (per the many-to-one shorthand from the early notes) — not separately declared on `Customer`.

## Evidence and effect shapes

Shapes produced by rules. Each one is both the record of what happened and the guard against it happening twice (stress test #2) — there's no separate "evidence" category, these are ordinary shapes doing double duty.

```
shape AuditLogEntry {
    order: one Order
    loggedOn: Date
}

shape Receipt {
    invoice: one Invoice
    sentOn: Date
}

shape InventoryRelease {
    order: one Order
    releasedOn: Date
}

shape AccountFlag {
    customer: one Customer
    flaggedOn: Date
}

shape FlagNotification {
    accountFlag: one AccountFlag
    sentOn: Date
}

shape DailyReview via schedule every 1 day {
    ranOn: Date
}
```

## Process and rules

```
shape ApplyPayment {
    invoice: one Invoice
    payment: one Payment
    output: invoice with payments += payment
}

rule InitiateCharge on Order produces ChargeAttempt {
    AuditLogEntry for order loggedOn: now
    then
    ChargeAttempt for order requestedOn: now
}

rule RecordPayment on SuccessfulCharge produces Payment {
    Payment for order.invoice amount: order.amount receivedOn: now
}

rule ReleaseInventory on FailedCharge produces InventoryRelease {
    InventoryRelease for order releasedOn: now
}

rule SendReceipt on SettledInvoice produces Receipt {
    Receipt for invoice sentOn: now
}

rule FlagOverdueAccounts on DailyReview {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
}

rule NotifyCustomerOfFlag on AccountFlag produces FlagNotification {
    FlagNotification for this sentOn: now
}
```

No explicit "then send receipt" step is written for the payment/settlement path. `ApplyPayment` (or `RecordPayment`, chained from a successful charge) recomputes `balance` (derived), which changes which refinement the invoice belongs to, which means `SendReceipt` — a rule scoped to `SettledInvoice` — fires automatically.

What each rule demonstrates:

- **`InitiateCharge`** — ordering without data dependency, via `then` (stress test: order with no data dependency, below).
- **`RecordPayment` → `SendReceipt`** — sequencing as data dependency, branching as refinement dispatch, no state-machine construct needed.
- **`ReleaseInventory on FailedCharge`** — errors are a refinement, not a control-flow mechanism (stress test #1).
- **`SendReceipt`, `ReleaseInventory`, `RecordPayment` all using `produces`** — firing-once via evidence shapes, not runtime bookkeeping (stress test #2).
- **`FlagOverdueAccounts on DailyReview`** — explicit scheduled ticks as the only trigger for purely time-dependent refinements (stress test #3), reacting to a cross-shape aggregate refinement (stress test #4).
- **`NotifyCustomerOfFlag on AccountFlag`** — chaining through a produced shape, and guard granularity scoped to the specific triggering instance, not the broader customer.

Stress test #5 (reversal) is not reflected here — still open.

## Stress test: order with no data dependency

Suppose compliance requires **"write an audit log entry before charging the customer's card,"** even though the audit log doesn't consume the charge's output and the charge doesn't consume the log's output. There's no data dependency between them at all — just a business/legal requirement on wall-clock order. Data-flow-implied ordering gives nothing here; it would need the explicit "sequence of referenced shapes" idea from the hard-problems discussion to say `AuditLog then ChargeCard`, even though nothing about their inputs/outputs forces it.

**Open question:** is genuinely dependency-free-but-order-required behavior common enough in real systems to deserve first-class syntax? Or is it rare enough that it's better to force a fake data dependency (e.g., `ChargeCard` takes the audit log's receipt as an unused input) to keep the language simpler?

### Proposed resolution: `then` as a lightweight ordering operator

```
rule OnChargeRequest on Order {
    AuditLog(order) then ChargeCard(order)
}
```

`then` is opt-in ordering inside a rule body — effects listed without `then` are unordered (transpiler/AI free to run them any order or in parallel); effects joined by `then` are forced in that order. This is the minimal version of "explicit sequence of referenced shapes," without a separate `process`/`sequence` block.

>

## More stress tests

Each of these pokes a different part of the design. Riffing through them to find where shapes/rules/refinements break down.

### 1. Failure / rollback

`ChargeCard` fails after inventory was already reserved — something needs to release the reservation. Does Velle need an explicit "compensating rule," or does failure just produce a different output shape (e.g. `FailedCharge`) that other rules react to via refinement, the same way `SettledInvoice` triggered `SendReceipt`?

> Resolved below — error handling turns out to be ordinary refinement dispatch, no new mechanism needed.

#### Errors are not a control-flow mechanism — they're a refinement

The traditional "return an error object vs. throw an exception" debate is a symptom of the function-call model. An outcome is just another shape, and success vs. failure is just which refinement it satisfies:

```
shape Charge {
    order: one Order
    amount: Money
    processedOn: Date?
    outcome: text?
}

shape PendingCharge    = Charge where processedOn is none
shape SuccessfulCharge = Charge where outcome = "approved"
shape FailedCharge     = Charge where outcome = "declined"

shape InventoryRelease {
    order: one Order
    releasedOn: Date
}

shape Receipt {
    order: one Order
    sentOn: Date
}

rule ReleaseInventory on FailedCharge produces InventoryRelease {
    InventoryRelease for order releasedOn: now
}

rule SendReceipt on SuccessfulCharge produces Receipt {
    Receipt for order sentOn: now
}
```

Nothing is returned or thrown. Attempting the charge causes a `Charge` shape to come into existence, and whichever refinement it ends up satisfying is what other rules react to — same mechanism as `SettledInvoice` triggering `SendReceipt`. Both rules now carry `produces`, guarding against firing twice — see stress test #2.

#### There's no internal/external distinction — the interaction is just a shape

An earlier draft of this tried to distinguish "pure derivation" (like `Invoice.balance`) from "exogenous" fields filled in by something outside the system (like a payment processor's response). That distinction isn't needed. The fix: model the *interaction itself* — the attempt, before it's resolved — as its own shape, the same as everything else:

```
shape ChargeAttempt {
    order: one Order
    requestedOn: Date
    response: ChargeResponse?
}

shape ChargeResponse {
    outcome: text
    processedOn: Date
}

shape PendingChargeAttempt   = ChargeAttempt where response is none
shape CompletedChargeAttempt = ChargeAttempt where response is some
shape SuccessfulCharge       = CompletedChargeAttempt where response.outcome = "approved"
shape FailedCharge           = CompletedChargeAttempt where response.outcome = "declined"
```

A `ChargeAttempt` exists the moment the system decides to try. Whether `response` eventually gets filled in by a payment processor's HTTP callback, a DB write from another process, or a pure calculation is irrelevant to Velle — that's a transpilation/implementation detail. Velle only describes: this shape exists, it has a field that isn't determined yet, and once it is, the shape's refinement changes, and rules react to that. `PendingChargeAttempt` vs. `CompletedChargeAttempt` is the same mechanism as `OverdueInvoice` vs. `SettledInvoice`. One mechanism — shapes, refinements, rules — regardless of where a field's value ultimately comes from.

This still leans directly on stress test #2 below: does `SendReceipt` fire the instant something newly satisfies `SuccessfulCharge`, or every time it's re-evaluated while still `SuccessfulCharge`?

>

### 2. Rule-firing semantics (edge vs. level)

`rule SendReceipt on SettledInvoice` — if the invoice is re-evaluated later and it's *still* settled, does the rule fire again? Sending a duplicate receipt email is a real bug. Nothing so far distinguishes "just became a member of this refinement" (edge-triggered, fire once) from "currently a member of this refinement" (level-triggered, re-fires every re-evaluation). This is foundational — everything else in this doc assumes edge-triggered semantics without having said so.

> "Fire only once" is the right instinct, but edge-vs-level is the wrong frame — it implies hidden runtime bookkeeping (a flag somewhere that says "already fired"). There is no re-evaluation loop or clock anywhere in this model. The fix is to make the rule's own effect into evidence, the same way `ChargeAttempt` turns an in-flight interaction into a shape.

#### Resolution: a rule's effect is itself a shape, and that shape is the guard

```
shape Receipt {
    invoice: one Invoice
    sentOn: Date
}

rule SendReceipt on SettledInvoice produces Receipt {
    Receipt for invoice sentOn: now
}
```

`produces Receipt` is sugar: the compiler derives an implicit guard so this rule only applies to `SettledInvoice` members that don't yet have an associated `Receipt`. Once the rule fires, the `Receipt` exists, so the guard is no longer satisfied — not because a runtime tracked "already fired," but because the evidence of the action now exists as data. Spelled out without the sugar, it's just an ordinary refinement:

```
shape UnacknowledgedSettledInvoice = SettledInvoice where not exists Receipt for this
```

This answers "when" directly: **a rule triggers when a shape newly satisfies its refinement, full stop.** Never a clock, a poll, or a re-evaluation pass — only refinement membership, checked whenever the data it depends on changes. The sugar matters because if every effectful rule required hand-writing its own `where not exists X for this` guard, someone would eventually forget it and get duplicate effects — exactly the class of bug Velle is supposed to make structurally impossible.

Every rule with a side effect earlier in this doc was underspecified without this and has been retrofitted with `produces` above: `SendReceipt on SettledInvoice`, `ReleaseInventory on FailedCharge`, `SendReceipt on SuccessfulCharge`.

>

### 3. Time-based effects

"If the receipt isn't opened within 3 days, send a reminder." No triggering shape exists until a clock passes with nothing else happening — tests whether time itself needs to be a shape/relationship, or some other primitive.

> In software today, nothing executes purely on the passage of time by default — a developer explicitly writes a timer/interval and registers a function to run on it. Velle shouldn't pretend otherwise: assume a scheduling framework is provided by the runtime, and make the registration explicit, the same way `via API` explicitly registers a REST endpoint in the earlier notes.

#### Resolution: a schedule is an explicit registration that produces tick shapes

```
shape DailyReview via schedule every 1 day {
    ranOn: Date
}

rule FlagOverdueAccounts on DailyReview {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
}
```

`via schedule every 1 day` tells the runtime to generate a scheduler that creates a `DailyReview` instance once a day — that instance is the actual trigger, the same category of thing as a `Payment` arriving or a `ChargeResponse` coming back. There's still no ambient "time passing" primitive; the developer explicitly registered the equivalent of a timer via this declaration.

This also sharpens something that was implicit until now: **a refinement is a pure predicate, not a trigger.** `OverdueInvoice` (`due < today`) doesn't "become true" and notify anyone — it's just always evaluable against current data, same as `balance <= 0`. Only *rules* react to something actually happening, and "something happening" always means a new shape got created. For rules driven by data changes (a `Payment` arriving), that's naturally satisfied. For a rule that depends purely on elapsed time with no other data change, nothing would ever re-check it without `DailyReview` existing to be the shape that happened. `FlagOverdueAccounts on DailyReview` is what actually walks the `FlaggedCustomer` refinement (#4) and notices invoices that silently crossed into `OverdueInvoice` since the clock advanced.

`each FlaggedCustomer produces AccountFlag` also composes two existing mechanisms rather than adding a new one: `each` is the loop construct from the original "Typical Language Constructs" section, and `produces` is the firing guard from #2 — now applied to a filtered set instead of a single shape.

>

### 4. Cross-shape aggregate conditions

"Flag the customer's account if they have 3+ overdue invoices." A refinement on `Customer` defined by a condition over `many Invoice` — tests whether refinements compose across relationships, not just within one shape.

```
shape FlaggedCustomer = Customer where count(invoices where OverdueInvoice) >= 3

shape AccountFlag {
    customer: one Customer
    flaggedOn: Date
}

rule FlagAccountForReview on FlaggedCustomer produces AccountFlag {
    AccountFlag for customer flaggedOn: now
}
```

`invoices where OverdueInvoice` reuses `where` both to define a refinement and to filter a collection by one — no new mechanism, refinements just compose across a relationship the same way they compose within one shape.

#### Extending it: notify the customer, not just flag the account

```
shape FlagNotification {
    accountFlag: one AccountFlag
    sentOn: Date
}

rule NotifyCustomerOfFlag on AccountFlag produces FlagNotification {
    FlagNotification for this sentOn: now
}
```

This chains through a produced shape, the same pattern as `ApplyPayment → SettledInvoice → SendReceipt` earlier in the doc: `AccountFlag` is simultaneously the evidence guarding `FlagAccountForReview` against re-firing, and the trigger for `NotifyCustomerOfFlag`.

Worth noting why `FlagNotification` references `accountFlag: one AccountFlag` rather than `customer: one Customer` directly: if it referenced the customer, the `produces` guard (from #2) would mean "has this *customer* ever been notified" — which would silently suppress notification on a legitimate future re-flagging once #5 (reversal) allows a customer to be un-flagged and re-flagged later. Scoping to the specific `AccountFlag` instance instead means a second, later flag is a distinct trigger with its own guard. The `for` target in `produces` isn't a style choice — it sets the guard's granularity.

This example exposed two problems rather than resolving cleanly on its own:

- **Time (#3):** `OverdueInvoice` is `due < today` — nothing "happens" to make an invoice cross into it, no shape is created, time just passes. So `FlaggedCustomer` can become true with zero new data, and nothing in the model so far would ever notice. Resolved under #3 below.
- **Reversal (#5) — still open:** if a customer pays down one overdue invoice and drops back to 2, does `AccountFlag`'s `produces` guard mean the flag persists forever, or does the account get un-flagged symmetrically? Unresolved — see #5.

>

### 5. Reversal

A payment gets refunded; the invoice goes from `SettledInvoice` back to `PartiallyPaidInvoice`. Do rules run symmetrically on the way out of a refinement (e.g. revoke the receipt?) or only on the way in?

```
shape Refund {
    payment: one Payment
    amount: Money
    refundedOn: Date
}
```

Extend `Payment` and `Invoice.balance` to account for it:

```
shape Payment {
    invoice: one Invoice
    amount: Money
    receivedOn: Date
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

Walk the sequence: an invoice is fully paid, `balance` drops to `0`, it satisfies `SettledInvoice`, `SendReceipt` fires and `produces Receipt`. Later, the payment is refunded — a `Refund` shape is created, `balance` recomputes above `0`, and the invoice no longer satisfies `SettledInvoice`; it's back to `PartiallyPaidInvoice` or `OverdueInvoice` depending on `due`.

Nothing reacts to that transition. `Receipt` still exists, referencing an invoice that is, in the system's current state, not actually settled. Two concrete things this breaks:

- **The `Receipt` guard, if the invoice is later paid off again.** `SendReceipt on SettledInvoice produces Receipt` guards on "does a `Receipt` exist for this invoice" — full stop, permanently, the same guard-granularity issue as `FlagNotification` in #4. If the customer legitimately pays the invoice again after the refund, `SendReceipt` can never fire a second time, because a `Receipt` from the *first* settlement still exists. The guard doesn't distinguish "settled once, forever" from "settled right now."
- **`AccountFlag` from #4**, cited above: if the refund also drops the customer's overdue-invoice count back under 3, does `FlaggedCustomer` no longer applying mean anything? Nothing currently un-flags the account or notifies the customer they're clear.

#### Reframing the question

"Is a refinement transition symmetric or one-directional" assumed Velle itself needs an opinion about what reversal means. It doesn't — reversal-handling is a business decision, not a language design decision. Velle's job is only to give the human vocabulary to express whichever decision they make. The mechanism for that is exactly what's already built: artifact shapes control "when," so the human's decision gets encoded as *which* artifact shapes they choose to declare, not as a new language primitive.

Detecting that a reversal happened at all doesn't need new machinery either — it's a refinement comparing current state against past evidence:

```
shape ReopenedInvoice = Invoice where exists Receipt for this and not (this is SettledInvoice)
```

**Option A — re-flag and re-notify.** The human decides the customer should go through the exact same treatment as a first-time flag. The artifact shape they add is a *resolution* — evidence that the old flag no longer applies, so the guard can be satisfied again:

```
shape AccountFlagResolved {
    accountFlag: one AccountFlag
    resolvedOn: Date
}

shape ActiveAccountFlag = AccountFlag where not exists AccountFlagResolved for this

rule ResolveFlagIfCleared on DailyReview {
    each Customer where exists ActiveAccountFlag for this and not (this is FlaggedCustomer) produces AccountFlagResolved {
        AccountFlagResolved for (ActiveAccountFlag for this) resolvedOn: now
    }
}

rule FlagOverdueAccounts on DailyReview {
    each FlaggedCustomer where not exists ActiveAccountFlag for this produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
}
```

Once resolved, a later `FlaggedCustomer` match produces a *new* `AccountFlag`, which — because `NotifyCustomerOfFlag` is already scoped to the specific `AccountFlag` instance, not the customer (the fix from #4) — automatically re-notifies too, with no separate declaration needed.

**Option B — grace period.** A genuinely different policy, not just an undo: the human decides a reopened invoice shouldn't immediately re-trigger flagging at all, but should suspend it for a window:

```
shape GracePeriod {
    customer: one Customer
    startedOn: Date
    endsOn: Date
}

shape InGracePeriod = Customer where exists GracePeriod for this and today <= (GracePeriod for this).endsOn

rule StartGracePeriod on ReopenedInvoice produces GracePeriod {
    GracePeriod for invoice.customer startedOn: now endsOn: now + 14 days
}

rule FlagOverdueAccounts on DailyReview {
    each FlaggedCustomer where not exists ActiveAccountFlag for this and not (this is InGracePeriod) produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
}
```

Both options reuse the same four things — `shape`, `where`, `produces`, `for` — the difference is entirely which artifact shapes the human chose to declare and what conditions they wired into `FlagOverdueAccounts`. This was never a gap where the language needed a new construct for "reversal semantics." It's evidence the existing vocabulary is expressive enough for a human to encode either business decision explicitly, and Velle staying silent about which one is correct is the right behavior, not an omission.

>


>
