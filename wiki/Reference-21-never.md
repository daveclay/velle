# 21. `never`

Every business has sentences of the form "this must not happen": no account is suspended while in good standing, no customer refers themselves, nobody but a licensed pharmacist verifies a fill. In Velle such a sentence is a *prohibition*: a configuration of records that the system will not allow to exist. You write a prohibition once, as a `never` or a `requires` declaration, and the compiler decides how to enforce it from where you wrote it. Written over a request, it turns the request away. Written over a stored record, it keeps the books clean against every act and every scheduled rule. Written over something only rules can produce, it is proved at compile time and never checked at all.

This page shows how to write a prohibition in each of its spellings, how to choose what to write it over, and what the compiler does with it. The code comes from `examples/pharmacy/pharmacy.velle`, `examples/shipping/shipping.velle`, `examples/billing/billing.velle`, `examples/loyalty/loyalty.velle`, and `examples/peptides/peptides.velle`.

## Writing a prohibition

A `never` is a top-level declaration naming a [refinement](Reference-08-Refinements) that must always be empty. It takes an inline predicate in the ordinary [predicate grammar](Reference-10-Predicate-expressions), or the name of a refinement declared elsewhere:

```
never (Account where balance >= 0 and suspended)    -- "an account in good standing is never suspended"
never (Customer where referrer == this)             -- "no customer refers themselves"
never SuspendedInGoodStanding                       -- the same statement over a named refinement
```

A `requires` states the same thing from the other side. It names a shape or refinement and a predicate that every one of its records must satisfy:

```
VerifyFill requires actor to be LicensedPharmacist                         -- "only a licensed pharmacist verifies" (or `by LicensedPharmacist` in the header)
FillPrescription requires filledBy to be Technician or filledBy to be Pharmacist   -- the actor, bound `as filledBy`
Fill requires fillNumber == count(prescription.fills where fillNumber <= this.fillNumber)
Staff requires role == "pharmacist" or role == "technician" or role == "admin"
```

Line by line:

- `VerifyFill requires actor to be LicensedPharmacist` means `never (VerifyFill where actor is not LicensedPharmacist)`. The compiler turns every requirement into the matching `never` by negating the predicate: comparisons flip, `and` and `or` swap, `is` and `is not` swap.
- `to be` is a second spelling of refinement membership, allowed only inside a `requires`. It reads as the sentence an owner says. It takes a refinement name and nothing else. Optionality, emptiness, and non-membership keep their one spelling: `is some`, `is not empty`, `is not`. Outside a requirement, `to be` is an error.
- `actor` and `filledBy` are the person who committed the act, bound by the act's `by` clause ([Authorization](Authorization)).
- The third line checks a field against a count. The fourth restricts a field to a list of values.

Choose `never` or `requires` by which sentence the owner said. "A price change is never negative" is `never (PriceChange where unitPrice < 0)`. "A fill number is its position in the series" is a `requires`. Both are the same construct underneath.

A requirement that only restricts who may commit an act has a third spelling, in the act's header. `transient act VerifyFill by LicensedPharmacist { ... }` means `by Staff` plus `VerifyFill requires actor to be LicensedPharmacist`. The body line stays legal beside the header. Write it in the body when the condition is more than a bare role ([Authorization, "Role gates"](Authorization#role-gates)).

When a prohibition rejects something, the rejection quotes the declaration as you wrote it: a requirement is reported as unmet, a `never` as reached.

## When a prohibition is checked

A prohibition holds over the *settled* state at the end of each transaction. It is not checked after every commit inside a transaction. Suppose `never (Account where balance >= 0 and suspended)` is declared and a $50 deposit arrives for a suspended account at −$20. At the deposit's commit the account briefly has a non-negative balance and is still suspended. The rule that restores service fires as a consequence inside the same transaction and clears the flag. The transaction ends clean, so the deposit is valid. A rule that watches a transition may observe the forbidden configuration mid-transaction. The prohibition promises only what the state settles to.

## Three kinds of prohibition, by what it is written over

A prohibition is one of three things, depending on who could violate it ([GLOSSARY](GLOSSARY), "prohibition"). You choose which by choosing what you write it over.

**A refusal** binds the caller. It is a prohibition over a [transient act](Reference-04-State-and-commits#transient-acts-transient-act): a request that is not itself stored. It is checked once, when the request arrives, and the request is turned away if it fails. Whatever record a rule creates from an accepted request carries no trace of the check. `never (SubmitPayment where amount <= 0)` in `examples/billing/billing.velle` is a refusal.

**A constraint** binds the books. It is a prohibition over a stored shape. It holds at the end of every transaction for as long as the record exists, and any act or scheduled rule that would leave the books in the forbidden configuration is rejected. `never (Member where referrer == this)` in `examples/loyalty/loyalty.velle` is a constraint. A prohibition over a stored (non-transient) act is also a constraint, because committing a stored act is the record.

**A guarantee** binds the spec's own rules. It is a prohibition over state that only rules can produce, such as `never (Account where balance >= 0 and suspended)` where only rules write `balance` and `suspended`. Nobody is ever refused. The compiler proves at compile time that the rules cannot reach the configuration, and a violation at run time would be a bug.

The same business sentence changes meaning with its placement. Take "nobody submits an invoice with a due date in the past":

- Written over the request, `never (SubmitInvoice where dueOn < today)` is a refusal. The clock is read once, at the moment of the request. An invoice may age past its due date freely afterwards, which is an ordinary state the business acts on (the `OverdueInvoice` refinement and `RemindOverdue` rule in `examples/billing/billing.velle`).
- Written over the record, `never (Invoice where dueOn < today)` is a constraint that no invoice could keep satisfying once its due date passes. The compiler rejects the declaration ([checks.md, V30](checks#v30--a-constraint-that-reads-the-clock-coarse)).

So a refusal needs the request and the record to be two shapes: a transient act, and a rule that creates the record from it. That is the ordinary transient pattern of [§4](Reference-04-State-and-commits); billing's `BillCustomer` act and its `OpenInvoice` rule are the worked case. Both placements of the invoice sentence sit side by side in `compiler/src/test/kotlin/velle/ValidatorTest.kt`.

A constraint names the record the sentence is about, whichever side arrives last. `never (Shipment where order is CancelledOrder)` in `examples/shipping/shipping.velle` rejects a shipment arriving for a cancelled order and a cancellation arriving for a shipped order alike, and names the shipment either way. An author who means only to turn away a request writes the prohibition over a transient act.

A rejection is data, not just a message. A refusal carries the act, the records that would be in the forbidden state, the record the act was about, and the evaluation that found it, and a readable message is rendered from that data. A scheduled rule's rejection carries the rule and the record it ran for, and reaches the code that called the tick as an exception ([evaluation.md, "Errors, refusals, and retry"](evaluation#errors-refusals-and-retry)).

## What the compiler checks

**A guarantee is proved, not checked at run time.** The compiler examines every rule that writes a field the prohibition reads and proves none can end a transaction in the forbidden configuration. When it cannot, the error names both sides and the three ways out:

> `SuspendManually` can end a transaction violating `never (Account where balance >= 0 and suspended)`: condition the act, restore within the transaction, or retract the prohibition.

**A refusal or a constraint is compiled into the generated code.** No compile-time proof can stop tomorrow's request, so the generated system evaluates the prohibition against incoming data and rejects a violating act. The check runs at one of two moments:

- *At the door*, meaning at the act's own commit before any rule runs, when the predicate reads nothing any rule writes, creates, or deletes. The rules cannot change the verdict, so it is evaluated once over the state as it would stand with the act landed, and not again at the end. `never (AssayReading where count(assay.assayReadings where hoursElapsed == this.hoursElapsed) > 1)` in `examples/peptides/peptides.velle` counts the arriving reading among its siblings at the door, so no rule ever runs against a duplicate and the caller gets the refusal the author wrote.
- *At the end of the transaction*, when the predicate reads something the rules move. A fill request numbered against a count that its own handling grows is checked after the handling. A sentence about the books that the request's handling makes true belongs on the record the handling produces, as a constraint ([patterns.md, "Three answers to one request"](patterns#three-answers-to-one-request)).

Nothing is checked twice.

**A rule that fires with nobody asking cannot create what a constraint forbids.** A scheduled rule, or one behind an `after commit` boundary, has no caller to receive a refusal. If such a rule creates a record of a shape a constraint consults, the compiler reports an error, because the firing would be refused at every tick. The ways out are the same three: a condition the prohibition cannot reach, restoring the books within the firing, or retracting the prohibition ([checks.md, V29](checks#v29--a-firing-with-no-caller-joins-never-induction-coarse); deletions join the same proof under [V26](checks#v26--deleters-join-never-induction-coarse)).

**A constraint may not read the clock.** Time advancing is a tick with nobody asking, so a constraint over a stored shape whose predicate reads `today` or `now`, directly or through a refinement it names, is an error ([checks.md, V30](checks#v30--a-constraint-that-reads-the-clock-coarse)). The books could age into the forbidden state with no act to refuse. Three ways out:

- State it on a transient request, where the clock is read once at the moment of the act. `never (PlantTrial where trial is not PermittedTrial)` in `examples/peptides/peptides.velle` is written over the planting request, not the planting record.
- Record the decision as a field the prohibition reads instead of the clock.
- Retract it, when the sentence is really a state the business acts on. "An invoice is never unpaid past its due date" is an `OverdueInvoice` refinement and a scheduled rule, not a prohibition.

**A record that arrived without an act is still checked.** A seeded row that already violates a constraint, or has aged into one, is rejected at the next act that reaches it, or thrown at the next tick.

**A proved prohibition is used by other proofs.** `never (Customer where referrer == this)` is what lets the compiler prove that writes to `customer.tier` and `customer.referrer.tier` from one commit can never land on the same record, which the one-writer check needs ([§12](Reference-12-Assignment); [checks.md, V1](checks#v1--one-writer-disjointness)). A prohibition is available to other proofs only once the compiler has finished enforcing it ([checks.md, V10](checks#v10--never-obligations-and-spend-tracking)).

## Related pages

- [Authorization](Authorization), for prohibitions on who may commit an act.
- [§4 State and commits](Reference-04-State-and-commits), for transient acts and the request-then-record pattern.
- [§8 Refinements](Reference-08-Refinements), for the `frozen` declaration, which follows the same declare-once model.
- [§11 rule](Reference-11-rule-when-on), for transactions and `after commit`.
- [patterns.md, "Three answers to one request"](patterns#three-answers-to-one-request), for choosing between refusing a request, recording its refusal, and constraining its result.
