# Prohibitions, refusals, and who may act

Open this file when deciding how a "must not" is enforced, when a request may be refused and the refusal must be kept, when a transient request is refused for not being answered, and for role gates and visibility. The basics of `never`, `requires`, `unique`, `by`, and `visible to` are in [language.md](language.md), §13 and §14. Every example compiles on its own.

## Contents

1. [Three kinds of prohibition](#1-three-kinds-of-prohibition)
2. [When a prohibition is checked](#2-when-a-prohibition-is-checked)
3. [`never`, `requires`, and `to be`](#3-never-requires-and-to-be)
4. [Keys: `unique`](#4-keys-unique)
5. [What a constraint may not do](#5-what-a-constraint-may-not-do)
6. [A refusal kept as a record](#6-a-refusal-kept-as-a-record)
7. [Answering every transient request](#7-answering-every-transient-request)
8. [Who may commit an act](#8-who-may-commit-an-act)
9. [Who may see a record](#9-who-may-see-a-record)

## 1. Three kinds of prohibition

A prohibition is one of three things, decided by what it is written over. The author chooses by choosing where to write it.

- A **refusal** binds the caller. It is written over a transient act. It is checked once, when the request arrives, and the request is turned away if it fails. Nothing of the request is kept, and the record a rule creates from an accepted request carries no trace of the check.
- A **constraint** binds the records. It is written over a kept shape or a kept act. It holds at the end of every transaction for as long as the record exists, and any act or scheduled rule that would leave the records in the forbidden state is rejected.
- A **guarantee** binds the spec's own rules. It is written over state only rules produce. Nobody is refused; the compiler must prove the rules never reach it.

The same sentence changes meaning with its placement. "Nobody submits an invoice due in the past":

```velle
shape Customer { name: text }

transient act BillCustomer { customer: one Customer, due: Date }

never (BillCustomer where due < today)

shape Invoice { customer: one Customer, due: Date }

rule OpenInvoice when BillCustomer {
    Invoice from { customer: customer, due: due }
}

shape OverdueInvoice = Invoice where due < today
```

- Over the request, the clock is read once, when the request arrives. The invoice may then age past its due date freely, which is the ordinary state `OverdueInvoice` names.
- Over the record, `never (Invoice where due < today)` would forbid what every invoice becomes in time. It is refused (§5).

A constraint names the record the sentence is about, whichever side arrives last. `never (Shipment where order is CancelledOrder)` rejects a shipment for a cancelled order and a cancellation of a shipped order alike. To turn away only a request, write the prohibition over a transient act.

A rejection reaches the caller as data: the act, the records that would be in the forbidden state, and the prohibition as written. A requirement is reported as unmet, a `never` as reached.

## 2. When a prohibition is checked

A prohibition holds over the state when the transaction settles, never at a step inside it. A rule inside the transaction may briefly see the forbidden state.

- **At the door**, before any rule runs, when the condition reads nothing a rule writes, creates, or deletes. The verdict cannot change, so it is checked once. `never (LineItem where quantity <= 0)` is checked at the door.
- **At the end of the transaction**, when the condition reads something the rules move.

A sentence about the records that the request's own handling makes true belongs on the record the handling produces, not on the request. "A fill's number is its position among the prescription's fills" is a constraint on `Fill`. Written over the fill request against a count the handling grows, it would refuse every correctly numbered request.

## 3. `never`, `requires`, and `to be`

```velle
shape Staff { name: text, role: text }

Staff requires role == "pharmacist" or role == "technician"

shape Pharmacist = Staff where role == "pharmacist"

act Drug { name: text }
act Lot { drug: one Drug, onHand: integer, expiresOn: Date }
act Prescription { drug: one Drug, quantity: integer }

transient act FillPrescription {
    prescription: one Prescription
    lot: one Lot
    quantity: integer
    checkedBy: one Staff
}

shape Fill { prescription: one Prescription, lot: one Lot, quantity: integer }

rule RecordFill when FillPrescription {
    Fill from { prescription: prescription, lot: lot, quantity: quantity }
}

FillPrescription requires lot.drug == prescription.drug
FillPrescription requires checkedBy to be Pharmacist
never (FillPrescription where quantity <= 0 or quantity > prescription.quantity)
```

- `Subject requires P` means `never (Subject where not P)`. The compiler negates the condition: comparisons flip, `and` and `or` swap, `is` and `is not` swap.
- Choose by the sentence the owner said. "A fill is never more than prescribed" is a `never`. "A fill must come from a lot of the prescribed drug" is a `requires`.
- `checkedBy to be Pharmacist` is membership, spelt as the owner says it. It is allowed only inside `requires` and takes a refinement name only. Absence, emptiness, and non-membership keep `is some`, `is not empty`, `is not`.
- A field of a few fixed words is usually a type, not a requirement: `Role is one of "pharmacist", "technician"` and `role: Role` refuse any other word at the door.
- A `never` that only says an optional field is never absent is the required field said the long way round. `never (Request where key is none)` beside `key: text?` draws an advisory to declare `key: text` instead.

## 4. Keys: `unique`

```velle
act Kind {
    name: text unique
    code: text? unique
}

act Assay { name: text }

act AssayReading {
    assay: one Assay
    hoursElapsed: integer
    inhibitionPercent: decimal
    unique (assay, hoursElapsed)
}
```

- `name: text unique` says no two kinds share a name. A second kind with a name already on record is refused at the door, quoting the key.
- `unique (assay, hoursElapsed)` is one key over two fields: one reading per assay per hour mark. Two separate `unique` words would be two keys, and would allow one reading per assay across all hours.
- An optional key field, `code: text? unique`, lets any number of records have no value, while no two present values are equal.
- A key field is a stored value of the shape: a scalar, a reference, or the actor. A derived property, a timestamp, or a collection is refused.
- A key says at most one, never at least one. An act that names a record by its key also needs `never (Submission where not exists (Entry where key == this.entryKey))`.
- A filter naming every field of a key is one record: `(Kind where name == this.kindName)` ([expressions.md](expressions.md), §6).
- If a rule assigns a key field, the key is refused like any prohibition over a field a rule assigns: `[V10] a 'never' reads 'Kind.name', which rule 'ApplyRename' assigns, so it is a guarantee that the rules keep it true, and the compiler cannot yet prove one over every assignment; read a field no rule assigns, or derive 'name' from the records the rule writes`.

## 5. What a constraint may not do

- **Read the clock.** A constraint over a kept shape that reads `today` or `now` is refused, because the records could age into it with no act to refuse. Message: `[V30] never #1 (Invoice where due < today) over Invoice reads the clock — the books can age into the forbidden state with no act to refuse, and the clock has no caller: state it on a transient request, where the clock is read once at the moment of the act; record the decision as a field the prohibition reads instead of the clock; or retract it`. "An invoice is never unpaid past its due date" is not a prohibition at all: it is the `OverdueInvoice` refinement and a scheduled rule.
- **Be broken by a rule nobody asked for.** A scheduled rule, or one behind `after commit`, has no caller to receive a refusal. If it creates what a constraint consults, it is refused: `[V29] rule 'CancelUnpaid' creates a 'Cancellation' on Nightly with nobody asking, and the 'never' (Shipment where order is CancelledOrder) reads 'Cancellation', so the firing could be refused at every tick with no caller to tell; condition 'CancelUnpaid' so it cannot create a 'Cancellation' the prohibition forbids, put the books right within the firing, or retract the prohibition`. Deleting what a `never` reads is checked the same way.
- **Read a field a rule assigns.** That makes it a guarantee, which the compiler cannot yet prove over assignments: `[V10] a 'never' reads 'Account.suspended', which rule 'ApplySuspend' assigns, so it is a guarantee that the rules keep it true, and the compiler cannot yet prove one over every assignment; read a field no rule assigns, or derive 'suspended' from the records the rule writes`. Derive the value from the records the rule creates, or prohibit on fields set at creation.
- A record that arrived without an act, such as one the engineer loaded, is still checked at the next act that reaches it.

## 6. A refusal kept as a record

A `never` turns a request away and keeps nothing. Sometimes the caller must read the refusal back, with a reason, as data. Split the transient request into the cases that apply and those that are refused, and let each side's rule record its outcome.

```velle
act Invoice { due: Date }

act Issuance { invoice: one Invoice }

shape IssuedInvoice = Invoice where exists Issuance for this {
    frozen due
}

transient act ChangeDueDate { invoice: one Invoice, newDue: Date, requestKey: text }

shape DueChangeRefusal { invoice: one Invoice, requestedDue: Date, reason: text, requestKey: text }

shape ApplicableDueChange = ChangeDueDate where invoice is not IssuedInvoice
shape RefusedDueChange    = ChangeDueDate where invoice is IssuedInvoice

rule ApplyDueChange when ApplicableDueChange {
    invoice.due = newDue
}

rule RecordDueChangeRefusal when RefusedDueChange {
    DueChangeRefusal from { invoice: invoice, requestedDue: newDue, reason: "invoice is issued", requestKey: requestKey }
}
```

- The two refinements are opposites, so every request is answered (§7).
- `ApplyDueChange` writes `due` only on an invoice that is not issued, so the compiler can prove it never writes the frozen field.
- The request is transient, so it is sorted once, at its own commit, and cannot drift to the other side as the invoice changes. The same split over a kept act, on state that rules change, draws an advisory: a kept request is re-sorted whenever that state changes, and may fire again on the other side.
- `requestKey` is a value the caller mints per request and the outcome copies, so the caller can find what became of its request. To refuse a resubmission, guard with `not exists (DueChangeRefusal where requestKey == this.requestKey)`. Velle does not check the caller's keys are unique.

Three answers to a request, which are not interchangeable:

| The sentence is about | Write |
|---|---|
| data that is wrong on arrival and stays wrong | a refusal: `never` or `requires` over the transient request |
| the situation, which the caller must read back with a reason | a refusal kept as a record, as above |
| the records the request's handling produces | a constraint on the produced record (§2) |

## 7. Answering every transient request

A transient act that nothing answers in some situation would vanish with no trace that it arrived, so the compiler requires an answer for every one. An answer is a rule whose condition covers the request, or a `never` over it. The compiler sees every request answered when:

- a rule or a `never` is written over the bare act;
- two answers have opposite conditions, `P` and `not P`, as `ApplicableDueChange` and `RefusedDueChange` do;
- the act carries a field of fixed words, and the answers, each testing it with `==` or `in`, name every word between them.

```velle
Verdict is one of "approved", "declined", "error"

act Charge { amount: decimal }

transient act ProcessorVerdict { charge: one Charge, outcome: Verdict }

shape Approval { charge: one Charge }
shape Decline { charge: one Charge, outcome: Verdict }

rule RecordApproval when (ProcessorVerdict where outcome == "approved") {
    Approval from { charge: charge }
}

rule RecordDecline when (ProcessorVerdict where outcome in ["declined", "error"]) {
    Decline from { charge: charge, outcome: outcome }
}
```

Anything else is refused. A word no answer names is refused by name: `[V18] a 'Review' whose 'outcome' is "error" can arrive and no rule or 'never' answers it, and it is transient, so it would be ignored with no record that it arrived; the answers name "approved" and "declined" of the three words of 'Outcome'. Answer "error" in a rule, or refuse it with 'never (Review where outcome == "error")'`. To refuse for several reasons, write one rule on the opposite condition whose reason field is an `if`.

## 8. Who may commit an act

The person who commits an act is its **actor**. The actor is a record of a shape the spec declares; nothing is built in. Roles are refinements of that shape, and their membership may depend on data and the calendar.

```velle
visible to Staff

shape Staff { name: text, role: text }

shape Administrator = Staff where role == "admin"
shape Pharmacist = Staff where role == "pharmacist"

act Renewal by Administrator as admin { pharmacist: one Staff, expiresOn: Date }

Renewal requires pharmacist to be Pharmacist
never (Renewal where pharmacist == admin)

shape LicensedPharmacist = Pharmacist where exists (Renewal where pharmacist == this and expiresOn >= today)

shape Lot { number: text }

act Recall by Staff { lot: one Lot, reference: text }
act ExpireLot { lot: one Lot }

shape Quarantine { lot: one Lot, reason: text, quarantinedBy: one Staff? }

rule QuarantineRecalled when Recall {
    Quarantine from { lot: lot, reason: "recalled", quarantinedBy: actor }
}

rule QuarantineExpired when ExpireLot {
    Quarantine from { lot: lot, reason: "expired", quarantinedBy: none }
}

shape QuarantineLog { quarantine: one Quarantine, actorName: text }

rule LogQuarantine when Quarantine by Staff? {
    QuarantineLog from { quarantine: this, actorName: if actor is some then actor.name else "system" }
}
```

- `by Administrator` is a role gate in the header, the same as `Renewal requires actor to be Administrator`. `as admin` names the actor; without `as` it is `actor`.
- `never (Renewal where pharmacist == admin)` is separation of duty: nobody renews their own licence.
- `LicensedPharmacist` is a role no permission table could hold: it lapses by itself when the renewal expires.
- A gate may read any data. A staff member changes a price only for an assigned vendor: `ChangePrice requires exists (VendorAssignment where staff == this.actor and vendor == this.product.vendor)`. Inside the nested `where`, the act's own fields are reached through `this`.
- The actor is a fact of the commit: never in the committer's input, never assigned. A kept act stores it, so `renewal.admin` reads it later. A transient act's rules copy it into a field, as `quarantinedBy: actor` does.
- `actor` is in scope in the act's own prohibitions, refinements, derived properties, and rules over the act. A rule over a record the transaction produced declares `by Staff` in its header. When some path to the rule starts at an act without `by`, a tick, or `after commit`, the actor may be absent and the clause is `by Staff?`. `ExpireLot` has no `by`, so `LogQuarantine` says `by Staff?`.
- A rule behind `after commit` or on a schedule may not declare `by`; nobody is asking. It reads the stored actor, `this.actor` or `this.request.actor`.
- There is no system identity. A job that commits a gated act is a record of the actor shape with a role of its own, provided by the engineer. "The user, or system" is `if actor is some then actor.name else "system"`.
- The first administrator, where hiring needs one, is created by the engineer outside the spec.
- A role gate draws a contention advisory today. It does not block the spec.

**Refused.** An optional actor on an act, `act Recall by Staff? { ... }`, does not parse. A field named like the bound actor is refused until `as` names the actor. `act Refund by Manager` with `act Staff` gives ``[F2] 'Refund by Manager' — the actor's shape is a `shape`, never an act: an act is an input, and the person who commits one is a record; declare 'Staff' as a shape and create its records with a rule``.

## 9. Who may see a record

```velle
visible to Staff

shape Staff { name: text, role: text }

shape Administrator = Staff where role == "admin"

shape Vendor {
    name: text
    visible to anyone
}

shape VendorAssignment { staff: one Staff, vendor: one Vendor }

shape Product {
    vendor: one Vendor
    name: text
    price: decimal
    cost: decimal visible to Administrator
    margin: decimal = price - cost visible to Administrator
    visible to Staff where exists (VendorAssignment where staff == actor and vendor == this.vendor)
    visible to Administrator
}
```

- A **record-level** clause stands on its own line in the body, or after a comma. Several add up: a product is seen by assigned staff and by every administrator.
- A **field-level** clause follows the field's type on the same line and narrows within the record's clauses: only administrators see the cost.
- The viewer is bound as `actor`, or under `as`, and reaches into a nested `where` bare. The product's own fields need `this`.
- `visible to anyone` makes a record or field public.
- `visible to Staff` at the top is the default for every shape with no clause of its own. A shape's own clauses replace the default.
- A derived property is calculated from every input, whoever asks. `margin` is computed from a cost the viewer may not see, which is why it carries its own clause.
- The clause goes on a base shape. A refinement is seen by whoever sees its base. A transient act is never read and needs none.
- Visibility applies only when data is read: a query returns what the viewer may see, a fetch of a hidden record returns nothing, hidden fields come back absent. No rule, derived property, or prohibition depends on who is reading; a `never` may read a field the actor cannot see.

**Refused.** Once a spec has any `by` or `visible to`, a readable shape with no clause and no default gives ``[V32] 'Refund' declares no visibility, and this spec has authorization — say who may see it: a `visible to <Shape> [where ...]` clause in its body, `visible to anyone` for a public one, or a spec-wide `visible to <Shape>` at the top of the spec``.
