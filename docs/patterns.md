# Worked patterns

Worked spellings the normative documents cite and the README does not carry: each section states a settled idiom with one complete example and the reasoning that makes it the served spelling. Cited from README §8, `evaluation.md`, `checks.md`, and the example specs.

## A cascade, concretely

*The transaction shape README §11 defines: one act, the commits its rules fire, all-or-nothing. This is the shape the sibling-firing checks (`checks.md` V15, V16) reason about.*

```
shape Account {
    balance: Money
}

shape Deposit {
    account: one Account
    amount: Money
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied
shape Delinquent = Account where balance < 0

rule ApplyDeposit when UnappliedDeposit {
    account.balance = account.balance + amount
    this.applied = true
}

rule RestoreService when leaving Delinquent {
    ServiceRestoration from { account: this, restoredOn: now }
}

rule NotifyRestored when ServiceRestoration {
    RestorationEmail from { account: account, queuedOn: now }
}
```

A $50 `Deposit` against a −$20 account is **one transaction containing four commits**: the `Deposit` act; `ApplyDeposit`'s effects (at this commit the transition out of `Delinquent` occurs); `RestoreService`'s `ServiceRestoration`; `NotifyRestored`'s `RestorationEmail`. An unexpected error at any of them rolls back the whole envelope — the deposit never happened, and retrying the act re-produces the same sequence of commits intact.

Because each firing is its own commit, transitions inside a transaction are ordinary commit-local transitions — nothing new to define. A mid-transaction blip is real: if a later commit in the same transaction (a reinstatement fee, say) drops the balance negative again, the account genuinely left and re-entered `Delinquent`, and rules watching either transition fire — the same stance README §17 takes ("commit-triggered rules observe every membership the commit stream produces").

## Conditioned acceptance is a definition

*The first of two patterns showing why most "reject the commit" sentences need no rejection machinery.*

"Don't accept the order unless the payment authorizes" is not a transaction statement — it is the definition of *accepted*, owned by the errors-are-refinements pattern:

```
shape Order {
    placedOn: Date initially now
}

shape AuthResponse {
    order: one Order
    approved: boolean
    respondedOn: Date
}

shape PendingOrder  = Order where not exists AuthResponse for this
shape AcceptedOrder = Order where exists (AuthResponse where order == this and approved)
shape DeclinedOrder = Order where exists (AuthResponse where order == this and not approved)
```

Fulfillment rules hang off `AcceptedOrder`, never `Order`. The order landing as a fact ("placed, authorization pending") is honest; nothing downstream can act until membership says so. Exhaustiveness checking (README §8) proves the partition covers every order.

## Validation rejection is data

*The second pattern. Also referenced from README §8 ("Frozen fields") and `evaluation.md`.*

There is nothing to throw and nowhere to throw it — Velle has no stack and no functions, so exception propagation doesn't exist; a validation failure is a shape or a refinement membership, never a control-flow event. A validate-and-reject flow needs no transaction machinery:

```
transient act VoidPayment {
    payment: one Payment
}

shape ReceiptedInvoice = Invoice where exists Receipt for this

shape ApplicableVoid = VoidPayment where payment.invoice is not ReceiptedInvoice
shape RefusedVoid    = VoidPayment where payment.invoice is ReceiptedInvoice

rule ApplyVoid when ApplicableVoid {
    payment.voided = true
}

rule RecordRefusal when RefusedVoid {
    VoidRefusal from { payment: payment, reason: "invoice is settled and receipted", refusedOn: now }
}
```

The request is answered in its own transaction: the consequence never fires for the refused subset, and the refusal lands as a record the caller reads back (delivery is compilation's job), carrying what it needs to say from the request, since the request itself leaves nothing but its consequences (README §4). `ApplicableVoid`/`RefusedVoid` partition `VoidPayment`, exhaustiveness-checked — the forgotten `catch` block becomes a compile-time uncovered-subset error. Three levels of "invalid," only the first pre-commit: **(1)** can't inhabit the shape's type at all — rejected below the language, at the trust/input boundary, the `act` surface (README §22, "External input (`act`)"); **(2)** well-shaped, business-invalid — rejection-as-data, above; **(3)** well-shaped, valid, consequence forbidden — resolved as the `frozen` write-gate (README §8, "Frozen fields").

*`VoidPayment` is a `transient act` (README §4), so its partition is evaluated once, at its own commit, and cannot drift as the invoice's state moves. A bare state partition over a **persistent** act is drift-exposed (`checks.md` A4): the act persists, so its partition is re-evaluated at every later change to the state it reads. Where the request itself must be a durable record, the partition is scoped to unhandled requests and anchored by each side's outcome evidence (the handled-once act partition, `examples/payments/payments.velle`).*

## Three answers to one request

*Which of three mechanisms a sentence about a request belongs to (README §21). From the fill counter in `examples/pharmacy/pharmacy.velle`.*

A request can be answered three ways, and they are not interchangeable:

- A **refusal** — a `never` or `requires` over the transient request — for data that is wrong on arrival and stays wrong: the caller is told and nothing is kept. `FillPrescription requires lot.drug == prescription.drug`; `never (FillPrescription where quantity <= 0 or quantity > prescription.quantity)`.
- A **refusal recorded as data** — a partition of the request, with a rule recording the refusal (above, "Validation rejection is data") — for the patient's situation, which the counter must be able to read back with a reason: `ApplicableFill = FillPrescription where dispensable` / `RefusedFill = FillPrescription where not dispensable`, and a `FillRefusal` record from the refused side.
- A **constraint** on the record the request produces — for a sentence about the books that the request's own handling makes true: `never (Fill where fillNumber != count(prescription.fills where fillNumber <= this.fillNumber))` counts the arriving fill among its siblings and catches gaps and repeats in one sentence.

One question decides which: does the request's own handling change what the sentence reads? A refusal is evaluated at the door when its predicate reads nothing the handling moves, and at the settled transaction otherwise (`evaluation.md`, "An exposed call"). `never (FillPrescription where fillNumber != prescription.fillsRecorded + 1)`, where the handling records the fill and grows the count, is true after the handling for every correctly numbered request, and refuses them all. The sentence is about the fills on file, so it belongs on `Fill`.

## Materialize, then decide

*The transient-act idiom for asynchronous work and external effects (README §4, §11).*

A bid over $10,000 cannot be accepted until the fraud service answers, and a transient act must be fully handled in its own transaction. That is not a wall: the handler materializes the *undecided state* synchronously, and the decision machinery hangs off the durable intent:

```
transient act PlaceBid {
    auction: one Auction
    bidder: text
    amount: decimal
    requestKey: text            -- client-supplied correlation key, copied forward
}

shape BidReview {
    auction: one Auction
    bidder: text
    amount: decimal
    requestKey: text
}

rule HoldBigBid when (PlaceBid where amount > 10000) {
    BidReview from { auction: auction, bidder: bidder, amount: amount, requestKey: requestKey }
}

shape PendingReview = BidReview where not exists FraudVerdict for this

act FraudVerdict {                    -- the service's answer: a later, ordinary act
    review: one BidReview
    approved: boolean
}

rule ApplyReviewedBid when (BidReview where exists (FraudVerdict where review == this and approved)) {
    BidRecord from { auction: auction, bidder: bidder, amount: amount, requestKey: requestKey }
}
```

The transient act *was* fully handled at its commit: its handling produced the pending review. Everything asynchronous happens to the durable intent, which is intent-before-effect (README §11). The same move answers external effects (notify the winner: materialize a `SettlementIntent`, and the `after commit` plus backstop machinery hangs off it, since an `after commit` rule can never be triggered by the act itself, the act being gone). History acts, evidence acts, and idempotent ingestion each use the same idiom: materialize a durable record and copy the payload forward, so the durable/transient split is an explicit authoring decision.

## A wide domain: the two authors

*The consequence of the serialization-domain design (`evaluation.md` U3; `checks.md` A5) that decides why the width warning is an advisory.*

A **wide domain** (a *widening*) is the fail-closed answer the derivation gives when a read correlates to no key: rather than guess which rows the read depends on, the compiler declares that every commit touching this shape shares one queue. The A5 check warns, naming the read and the declaration carrying it, and lists the discharges: correlate the read (a model fact the model was missing), move the rule to a schedule, or declare `tolerates contention`. Two very different authors receive that warning, and advisory-versus-required is a decision about which of them to serve by default.

**The author who meant it: serialization is the feature.** Some computations are serial by nature, and no compiler setting can make them concurrent:

```
act Order {
    amount: decimal
}

shape OrderNumber {
    order: one Order
    n: int
}

rule AssignOrderNumber when Order {
    OrderNumber from { order: this, n: count(OrderNumber) + 1 }
}
```

Handing out sequential numbers cannot run two at a time; if it did, two orders would read the same count and take the same number. The `count(OrderNumber)` read must see every row, the domain widens to the whole `OrderNumber` shape, and the resulting global queue is not a defect: it is the requirement, stated in the only vocabulary the requirement has. The advisory tells this author what they already know, and `tolerates contention` turns the knowledge into record: the warning goes quiet, and the contention map shows the queue as deliberate rather than suspicious.

**The author who didn't: a global lock hiding in a guard.** The other author writes a read that looks nothing like a lock:

```
act Order {
    amount: decimal
}

act Promotion {
    startsOn: Date
    endsOn: Date
}

shape Discount {
    order: one Order
    rate: decimal
}

rule ApplyPromotion
    when (Order where exists (Promotion where startsOn <= today and endsOn >= today)) {
    Discount from { order: this, rate: 0.1 }
}
```

The intent is "orders placed while a promotion is live get a discount." But the promotion read correlates to no key (nothing links an order to a promotion, and date comparisons are not correlations), so every order commit widens to the whole `Promotion` shape, and since every order commit carries the same widening, the entire order intake shares one queue: a global lock nobody asked for, hiding inside a guard that reads like a harmless config check. Under the advisory ruling the compiler will not stop them from shipping it; the warning is the only line of defense. The natural discharge is the schedule move: a rule fired on a tick prices the wide read once per tick instead of inside every commit, and a width living only in a schedule-fired rule's own firing never warns (the cadence discharge).

**Why the dial stays at advisory** (ruled 2026-08-18): a wide domain's failure is throughput, not a wrong value, so failing open costs less here than anywhere else in the tolerance family. The *required* position remains arguable, with the accidental author as its whole case, and the ruling is held revisitable once calibration has run realistic specs against it. An author meets a width on three surfaces: the A5 warning at compile time, the ⚠-marked rows of the contention map (`diagrams.md`), and the queue-key contract on the generated commit function ("Queue key: [...]"), whose evaluation moment is part of the contract (`evaluation.md` U3: keys resolve at admission against pre-commit state plus the act's inputs, captures included).
