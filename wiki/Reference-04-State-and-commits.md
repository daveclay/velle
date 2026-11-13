# 4. State and commits

Velle has no database vocabulary. There is no save, load, insert, or query call to write. Instead, the system has a *state*, the set of every record and its stored values, and the only way that state ever changes is a *commit*: one change entering the state at one moment. This page covers where commits come from, how create, update, read, and delete are written, which changes land together as one transaction, and how to declare a request that is answered but not kept.

## What the state is

The state is every instance of every [shape](Reference-03-shape), with its stored properties. How it is physically kept is decided when the spec is compiled, not in the spec ([§1](Reference-01-Principles)).

## Where commits come from

Nothing happens on its own. Every change traces to one of three kinds of commit:

- **An act arrives.** Something outside the system submits a new instance of a shape declared `act`. Committing the instance is what stores it; there is no separate save step ([§12, "A shape body cannot assign"](Reference-12-Assignment#a-shape-body-cannot-assign)).
- **A schedule ticks.** A [scheduled rule](Reference-17-Schedule-triggers) runs because the date changed. The tick is a commit whose changed value is `today`.
- **A rule fires.** A [rule](Reference-11-rule-when-on) whose condition holds at a commit runs its body. The body is exactly one commit.

A rule's effects are a new commit, which can match other rules' conditions in turn. Rules never call each other; they react to what a commit changed.

## Create, update, read, delete

- **Create** is committing a new instance, from an act or from a rule body.
- **Update** is an [assignment](Reference-12-Assignment) in a rule body.
- **Read** is any predicate or [derived property](Reference-07-Derived-properties) evaluated against the current state.
- **Delete** is the rule effect `delete <path>` ([§12, "Deletion"](Reference-12-Assignment#deletion-delete)). A record of something that happened in the world is not deletable, because removing it would make the spec's account of the past false ([§13](Reference-13-Exit-triggers)). A record of current state is deletable wherever nothing the spec proves depends on it.

When the business needs to reverse something rather than remove it, write the reversal as a record of its own ([§22](Reference-22-Open-unresolved), "Reversal").

## One commit, one transaction

A commit happens at one moment, so the state before it and after it are both well defined. That is what lets a rule fire when a record *enters* or *leaves* a [refinement](Reference-08-Refinements) ([§11](Reference-11-rule-when-on)).

By default, an act's commit and every commit its rules produce stand or fall together. If any of them fails, none of them lands. A rule opts out with `after commit`, which runs its body in a transaction of its own ([§11, "Transactions and `after commit`"](Reference-11-rule-when-on#transactions-and-after-commit)).

An act's commit carries one instance, or the act plus the parts its declaration says arrive with it ([§6, "Inline part creation"](Reference-06-Relationships#inline-part-creation-with)). Which parts belong is fixed by the declaration, never by the data. When several things arrive together, make the thing that contains them the act and the parts related shapes.

## Transient acts (`transient act`)

Committing an act normally stores it: a `Payment` or an `EmailChange` is both a request and a durable business fact. Some requests should not be kept. Their meaning is decided when they arrive, and the durable record should be what the system did about them, not the request itself. Declare such an act `transient`. From `examples/billing/billing.velle`:

```
transient act ChangeDueDate {
    invoice: one Invoice
    newDue: Date
}
```

A transient act's instance exists only inside its own commit's transaction. Its refinements are evaluated once, at that commit. Its rules fire, and what they create or change is stored. When the transaction closes, the instance is gone ([`evaluation.md`, "Transient acts"](evaluation#transient-acts-transient-act)).

**Why a request would be transient.** An ordinary act stays in the state, so a refinement over it such as "refused" is re-evaluated whenever the data it reads changes. A request applied long ago can drift into the refused side when its invoice is later issued, and a rule on that side would fire again. The compiler warns about that ([checks.md, A4](checks#a4--drift-exposed-act-partition); worked example: `examples/partition-drift/partition_drift.velle`). A transient act is evaluated once and is then gone, so it cannot drift.

**What a transient act may not do.** Nothing durable or later may depend on it:

- No shape may hold a relationship to it. A record that needs some of the request's values copies them into its own fields. Choosing which fields to copy is choosing which parts of the request are durable.
- Its name may not appear in any expression.
- Its rules fire only `on commit`: no `after commit`, no schedule, no `when leaving`. Work that must happen later hangs off a durable record a rule creates from the request ([§11, "Transactions and `after commit`"](Reference-11-rule-when-on#transactions-and-after-commit)).

A transient act may arrive with parts ([§6, "A request that is not kept, with its parts"](Reference-06-Relationships#a-request-that-is-not-kept-with-its-parts)). They are values in its commit, read by its rules there, and gone with it. Everything above holds for a part as it holds for the act. The one relationship allowed is inside the request: a part points at the request it arrived with, and the request reads its parts through the collection that pointer gives it. `PriceRequest` in `examples/orders/orders.velle` arrives with its `requestedItems`; the rule copies each item's product, quantity, and current price into a `QuoteLine`, and the request is gone.

**Every request gets a response.** A transient act that no rule answers in some situation would be ignored, with no record that it arrived. The compiler therefore requires that every instance is answered in every reachable state. Otherwise the spec is rejected with a message naming the act and the unanswered situation.

An answer is a rule whose condition covers the instance, or a [`never`](Reference-21-never) over the act. A `never` refuses the request at the door and keeps nothing. A rule can instead create a refusal record, making the refusal a business fact. Which to use is a product decision, made per act. `AssignTicket` in `examples/membership/membership.velle` is refused by a `never`; `ChangeDueDate` in `examples/billing/billing.velle` creates a `DueChangeRefusal`.

The compiler proves coverage when one answer covers the bare act, or when two answers have complementary conditions, `P` and `not P`. Anything else is rejected. To refuse for several reasons, write one rule on the complement whose reason field is a conditional value.

**Matching a response to its request.** The instance is gone when the transaction closes, so there is no system-assigned id a caller can refer to afterwards. When a caller must find a request's outcome, or may submit it twice, give the act a caller-supplied field such as `requestKey: text`, and copy it into every outcome record. The caller mints one key per request, so a resubmission carries the same key, and a rule can refuse the duplicate with a guard such as `not exists (BidRecord where requestKey == this.requestKey)`. Velle does not check that keys are unique; that is the caller's job. Two copies arriving at once run as two transactions, one after the other, and the second meets the guard. `RequestGreenhouseTrial` in `examples/peptides/peptides.velle` copies the key into its approval and refusal records.

## Transient fields of a kept act (`transient`)

Sometimes a request is a business fact worth keeping, but one value it carries must not be. An order is kept with its lines. The card it is paid with is read when the order arrives, and must never be stored. Mark the field `transient`. From `examples/orders/orders.velle`:

```
act Order {
    customer: one Customer
    placedOn: Date
    transient cardNumber: text
    transient cardholder: text
    total: integer = sum(orderLines, quantity)
} with {
    orderLines with { customizations }
}

shape Charge {
    order: one Order
    cardholder: text
}

never (Order where length(cardNumber) < 12)

rule TakePayment when Order {
    Charge from { order: this, cardholder: cardholder }
}
```

- `transient cardNumber: text` says the number is part of the order's input and not of its record. The committer supplies it like any other field. The record the order becomes has no such field.
- `never (Order where length(cardNumber) < 12)` reads the number at the door. A short number refuses the whole order, lines and all, and nothing lands.
- `rule TakePayment when Order` fires once, at the commit, and reads the card there. It records the cardholder's name on a `Charge` and never the number. What the rules record is what lasts.
- After the commit nothing can read the card. It is not in the order's record, not in storage, and not in the generated model object ([Act API](Act-API)).

A transient field is read by what runs at the act's commit and by nothing else: a `never` over the act, and a rule on the bare act. A refinement of the act is re-evaluated whenever its data changes, and a derived property is read later, so neither may read a transient field. A rule over another record reaches the order through a reference and finds no such field.

The members of an act, by what the committer supplies and what the record keeps:

| Member | In the input | In the record | Read |
|---|---|---|---|
| a stored field | yes | yes | at any time |
| a derived property ([§7](Reference-07-Derived-properties)) | no | computed, never stored | at any time, from current data |
| an `initially` field ([§5](Reference-05-Scalars)) | no | yes, calculated once | at any time |
| a `transient` field | yes | no | at the act's commit only |

A value with several parts that must not be kept, such as a card's number and holder, is written as several transient fields. A request that is not kept at all, parts included, is a transient act with parts (above). A kept act has no parts that are not kept: what must not be stored arrives on the act's fields, or on a transient act of its own that names the kept record.

## What the compiler checks

- **Rejects** anything that would depend on a transient act after its commit, as listed above, and the same for a part that arrives with it ([checks.md, V17](checks#v17--transient-isolation)).
- **Rejects** a transient act that is not provably answered in every state ([checks.md, V18](checks#v18--transient-totality-every-request-gets-a-response-coarse)).
- **Rejects** `transient` on a field of a `shape`, a read of a transient field anywhere but a `never` over the act or a rule on the bare act, and an assignment to it ([checks.md, V37](checks#v37--transient-fields-of-a-kept-act)).
- **Warns** about a rule on a refinement of an ordinary act that could fire again when the data drifts ([checks.md, A4](checks#a4--drift-exposed-act-partition)).

## Related pages

- [§11 rule](Reference-11-rule-when-on), for when rules fire and `after commit`.
- [§12 Assignment](Reference-12-Assignment), for updates and deletion.
