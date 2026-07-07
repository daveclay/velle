# Worked example: invoice/payment

Testing whether "output feeds input" + refinements (conditions-as-shapes) are enough to express order-dependent, multi-step behavior without a separate sequencing or state-machine construct.

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
```

## Refinements (conditions as shapes)

```
shape OverdueInvoice       = Invoice where balance > 0 and due < today
shape PartiallyPaidInvoice = Invoice where balance > 0 and balance < amount
shape SettledInvoice       = Invoice where balance <= 0
```

## Process: apply a payment, notify if settled

```
shape ApplyPayment {
    invoice: one Invoice
    payment: one Payment
    output: invoice with payments += payment
}

shape Receipt {
    invoice: one Invoice
    sentOn: Date
}

rule SendReceipt on SettledInvoice produces Receipt {
    Receipt for invoice sentOn: now
}
```

No explicit "then send receipt" step is written anywhere. `ApplyPayment` recomputes `balance` (derived), which changes which refinement the invoice belongs to, which means `SendReceipt` — a rule scoped to `SettledInvoice` — fires automatically. `produces Receipt` also guards against firing twice — see stress test #2.

- **Sequencing** here is just data dependency: you can't know the invoice is settled until after the payment is applied, so ordering falls out of the input/output graph.
- **Branching** is just refinement dispatch, not an explicit "then."

This supports the idea that state-machine-like behavior is an artifact of how inputs/outputs and refinements are wired, with no separate state-machine mechanism needed.

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

>

### 4. Cross-shape aggregate conditions

"Flag the customer's account if they have 3+ overdue invoices." A refinement on `Customer` defined by a condition over `many Invoice` — tests whether refinements compose across relationships, not just within one shape.

>

### 5. Reversal

A payment gets refunded; the invoice goes from `SettledInvoice` back to `PartiallyPaidInvoice`. Do rules run symmetrically on the way out of a refinement (e.g. revoke the receipt?) or only on the way in?

>
