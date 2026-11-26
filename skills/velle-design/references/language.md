# The Velle language

Velle is a language for writing down how a business works: what it keeps, what arrives from outside, what follows when things change, and what is never allowed. A spec is one `.velle` file. The compiler reads the whole spec as one unit, refuses anything whose meaning is undefined, and proves what it can about the rest. This file teaches each construct in the order an author meets it.

Every example compiles on its own. Compiler messages are quoted as printed, minus the pointer into the full documentation that some end with. Besides refusals the compiler can print advisories: findings about a spec that compiles, which never block it. The most frequent, a contention advisory marked `[A5]`, appears on many ordinary specs, role gates among them ([time-and-repetition.md](time-and-repetition.md), §6).

## Contents

1. [A whole spec at a glance](#1-a-whole-spec-at-a-glance)
2. [Reading a spec](#2-reading-a-spec)
3. [`shape`, `act`, and `transient act`](#3-shape-act-and-transient-act)
4. [Field types](#4-field-types)
5. [Values the system fills in](#5-values-the-system-fills-in)
6. [Relationships: `one` and `many`](#6-relationships-one-and-many)
7. [Derived properties: `=`](#7-derived-properties-)
8. [Refinements: a named condition](#8-refinements-a-named-condition)
9. [Conditions](#9-conditions)
10. [Rules and what their bodies do](#10-rules-and-what-their-bodies-do)
11. [When a rule fires](#11-when-a-rule-fires)
12. [Leaving a state, and deletion](#12-leaving-a-state-and-deletion)
13. [Prohibitions: `never`, `requires`, `unique`](#13-prohibitions-never-requires-unique)
14. [Who may commit, who may see: `by` and `visible to`](#14-who-may-commit-who-may-see-by-and-visible-to)
15. [From what a product owner says to what to write](#15-from-what-a-product-owner-says-to-what-to-write)

Further files in this folder:

- [expressions.md](expressions.md): open when a condition or formula goes beyond a plain comparison: every operator, `is`, `exists`, `latest` and `first`, finding a record by a key, `as`, absent values and `?.`, dates and durations, the built-in functions.
- [relationships.md](relationships.md): open for parts that arrive with an act (`with`), a field several shapes share (`is`), a field holding either of two shapes (`or`), walks along a chain, changing a collection, and deleting a record others point at.
- [time-and-repetition.md](time-and-repetition.md): open for anything on a schedule or behind `after commit`: sweeps, run-once guards, running totals and `tolerates`, commits that must take turns, and choosing between a computed status, a stored flag, and a record per event.
- [prohibitions.md](prohibitions.md): open when deciding how a "must not" is enforced: refusal, constraint, or guarantee, when each is checked, a refusal kept as a record, answering every transient request, and role gates and visibility in full.

## 1. A whole spec at a glance

A small invoicing system. Each later section explains what is used here.

```velle
act Customer { name: text, email: text }

transient act BillCustomer { customer: one Customer, due: Date }

shape Invoice {
    customer: one Customer
    due: Date
    total: decimal = sum(lineItems, amount)
    balance: decimal = total - sum(payments, amount)
}

rule OpenInvoice when BillCustomer {
    Invoice from { customer: customer, due: due }
}

act LineItem {
    invoice: one Invoice
    price: decimal
    quantity: integer
    amount: decimal = price * quantity
}

never (LineItem where quantity <= 0)
transient act SubmitPayment { invoice: one Invoice, amount: decimal }
never (SubmitPayment where amount <= 0)
shape Payment { invoice: one Invoice, amount: decimal, receivedOn: timestamp on create }

rule RecordPayment when SubmitPayment {
    Payment from { invoice: invoice, amount: amount }
}

shape OverdueInvoice = Invoice where balance > 0 and due < today
shape Reminder { invoice: one Invoice, sentOn: Date }

rule RemindOverdue
    when (OverdueInvoice where not exists (Reminder where invoice == this and sentOn > today - 7 days))
    on Weekly {
    Reminder from { invoice: this, sentOn: today }
}
```

- `Customer` and `LineItem` are acts: submitted from outside and kept. `BillCustomer` and `SubmitPayment` are transient acts: requests that are not kept, whose rules create the `Invoice` and the `Payment`.
- `customer: one Customer` links an invoice to its customer and gives every customer an `invoices` collection with no declaration. Invoices likewise get `lineItems` and `payments`. `total` and `balance` are formulas, recomputed at every read.
- The `never` lines turn away a line item with no quantity and a payment of zero or less. `OverdueInvoice` names a condition, and `RemindOverdue` runs at each tick of the `Weekly` schedule, once per overdue invoice with no reminder in the last seven days.

## 2. Reading a spec

**Declarations** come in any order. A name may be used before it is declared.

| Starts with | Declares |
|---|---|
| `shape Name { ... }` | a kind of record the system keeps (§3) |
| `act Name { ... }`, `transient act Name { ... }` | a request from outside (§3) |
| `Name is one of "a", "b"` | a type whose values are a few fixed words (§4) |
| `shape Name = Other where ...` | a refinement: a named condition (§8) |
| `rule Name when ... { ... }` | what follows when a condition becomes true (§10) |
| `never ...`, `Name requires ...` | a prohibition (§13) |
| `visible to Name` | the spec-wide default for who may see records (§14) |
| `uses a.b.Name` | picks or renames an engineer's type or function (§4) |

**Casing carries meaning.** Shape, refinement, type, rule, and schedule names start uppercase. Field names start lowercase. In `invoices where OverdueInvoice` the capital tells the compiler `OverdueInvoice` is a refinement, not a field.

**Lines.** A newline ends a statement; there are no semicolons. Inside braces a comma works as a newline, so `{ name: name, email: email }` is the one-line form. Inside parentheses newlines do not matter, so a long condition may wrap. Comments start with `--`.

**`=` and `==`.** `==` is the only equality test. `=` defines (`total: decimal = ...`, `shape X = Y where ...`) or, in a rule, assigns.

**Reserved words** include `shape act rule never requires transient uses when leaving deleted on after commit where and or not is exists for from then if else as this none some empty one many initially captured frozen tolerates delete undeletable timestamp create update true false now today` and the type names. `on: Date` does not parse; write `sentOn: Date`. A field may not be named `id` (§5).

## 3. `shape`, `act`, and `transient act`

A `shape` is one kind of record the business keeps: a customer, an invoice, a reminder. Its records are created only by rules. An `act` is a request from outside: a payment, a correction, a sign-up. The acts are everything the outside world can do, and committing one stores it as a record. A `transient act` is a request that is not kept: it is read at its own commit, its rules record what the business keeps, and then it is gone.

```velle
shape Customer {
    name: text
    email: text
    phone: text?
}

transient act SignUp { name: text, email: text }

rule AdmitCustomer when SignUp {
    Customer from { name: name, email: email, phone: none }
}

act CorrectEmail { customer: one Customer, corrected: text }

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}

act Order {
    customer: one Customer
    transient cardNumber: text
}

never (Order where length(cardNumber) < 12)
```

- Each body line is a field: a name, a colon, a type. `phone: text?` may be absent; without `?` a field is required.
- `AdmitCustomer` creates the customer. A `from` block gives every field, `none` for an absent one.
- `CorrectEmail` is kept, so there is always a record that the correction happened. Choose `act` when the request is itself a business fact, `transient act` when only its outcome matters.
- `transient cardNumber: text` on a kept act is supplied by the committer, read at the commit by a `never` over the act or a rule on the bare act, and never stored.

**Every transient request gets an answer.** A transient act that nothing answers in some situation would vanish without trace, so the compiler requires an answer for every arrival. A rule or `never` over the bare act answers all. Two answers with opposite conditions, `P` and `not P`, answer all. Answers that test a field of fixed words (§4) with `==` or `in` answer all when they name every word.

**Refused.** A transient act answered only in part, `rule RecordPayment when (SubmitPayment where amount > 0) { ... }` and nothing else:

> [V18] a 'SubmitPayment' can arrive that no rule or 'never' answers, and it is transient, so an unanswered one would be ignored with no record that it arrived. The compiler sees every 'SubmitPayment' answered when a rule or a 'never' is written over the bare 'SubmitPayment', or when two answers have conditions that are each other's opposite, 'P' and 'not P'; add a rule over 'SubmitPayment' with no condition, refuse the rest with 'never (SubmitPayment where not P)', or write one rule on the opposite condition whose reason is a conditional value

Accepted: `never (SubmitPayment where amount <= 0)` and `rule RecordPayment when SubmitPayment`, as in §1. Keeping the refusal as a record instead is in [prohibitions.md](prohibitions.md).

**Also refused.**

- A rule over a shape nothing creates. With `shape Invoice { due: Date }` and no rule creating invoices, `rule Remind when Invoice { ... }` gives `[V3] rule 'Remind' can never fire — no act declaration, rule effect, or tick in the spec can affect its condition`. Make it an `act`, or write the rule that creates it.
- An assignment in a shape body, `customer.email = corrected`, does not parse: `expected COLON, found DOT('.')`. Assignments belong in rules.

## 4. Field types

| Type | Holds |
|---|---|
| `text` | characters |
| `integer`, `long` | whole numbers |
| `decimal` | an exact decimal, for money and quantities |
| `double` | a floating-point number |
| `boolean` | true or false |
| `Date`, `DateTime`, `time` | a calendar date; a moment, with no time zone; a time of day |
| `duration` | a span of time, written `3 days`, `2 hours` |
| `uuid` | an identifier for the outside world; `==` and `!=` only |
| `bytes` | binary data stored and handed back unchanged |
| `Regex` | a regular expression that compiles |

`now` is the current `DateTime`, `today` the current `Date`.

```velle
Status is one of "open", "paid", "void"

act Invoice {
    number: integer
    status: Status
    tags: many text
    notes: many text tolerates duplication
    poNumber: text?
    reference: text = "INV-${number}"
    poLabel: text? = "PO ${poNumber}"
}
```

- `Status is one of ...` declares a type whose values are exactly those words. A committed invoice with another word is refused at the door. Words compare with `==`, `!=`, and `in`, and have no order.
- `tags: many text` holds texts, each once, in no order. `many` never takes `?`; the empty collection means none.
- `notes: many text tolerates duplication` keeps every value in the order given, repeats included.
- `"INV-${number}"` is a text built from other values. An absent piece makes the whole text absent, so `poLabel` is `text?`.

**Refused.**

- `full: text = first + " " + last` gives `[F2] 'first + " "' — '+' is not defined on a text; a text made from other values is a text literal with pieces, "${first} "`. Write `"${first} ${last}"`.
- `status == "payed"` gives `[F2] '"payed"' — 'Status' rejects this constant: "payed" is not one of "open", "paid", "void"`.
- `priority >= "normal"` gives `[F2] 'priority >= "normal"' — the words of 'Priority' have no order, so '>=' is not defined on them; compare with '==', '!=', or 'in'`. Write `priority in ["normal", "high"]`, or derive a number with `if`.
- `customer: Customer` gives ``[F1] 'Invoice.customer': 'Customer' is a shape; a field holding a record is `one Customer` or `many Customer` ``.

**Types an engineer adds.** A type such as `Money`, or a function, may come from a library the system is built with. The spec writes it like any other type, `price: Money`, with no declaration, and may do with it only what the library declares. When two libraries register one name, or the spec wants another name, a line at the top picks or renames it: `uses velle.finance.Money`, `uses velle.finance.Money as Cash`, `uses velle.finance.roundToCents as cents`. An added type is one value, never a concept with parts: an amount and its currency are two fields of a shape. A spec that uses one compiles only where its library is present.

## 5. Values the system fills in

```velle
shape Customer { name: text }
transient act BillCustomer { customer: one Customer, due: Date }

shape Invoice {
    customer: one Customer
    due: Date
    reference: text initially randomUUID
    issued: boolean initially false
    issuedOn: timestamp on create
    changedOn: timestamp on update
}

rule OpenInvoice when BillCustomer {
    Invoice from { customer: customer, due: due }
}
```

- `initially value` sets a stored field once, when the record is created. A rule may change it later, and the creating `from` block may leave it out.
- `randomUUID` mints a unique value. It is the only generator; there is no sequence.
- `timestamp on create` is set at the creating commit. `timestamp on update` moves at every commit that writes a stored field. Neither is supplied or assigned.
- Every record has an `id` without declaring it. It supports `==` only and has no order. A business number is a field of its own.
- `issued: boolean = false` would be a formula that is always false (§7). A stored starting value is `initially`.

**Refused.** `profile.updatedAt = now` gives `[F3] rule 'ApplyTouch' assigns Profile.updatedAt — a timestamp field (commit metadata) is never assignable`. A field named `id` gives `[F3] 'Customer.id' — 'id' is reserved`.

## 6. Relationships: `one` and `many`

A relationship links records: an invoice has a customer, a student takes courses. Declare it once, on the side that stores the link. The other side appears by itself.

```velle
act Customer {
    name: text
    outgoing: many Transfer = (Transfer where source == this)
    incoming: many Transfer = (Transfer where target == this)
}

act Invoice { customer: one Customer, due: Date }
act Transfer { source: one Customer, target: one Customer, amount: decimal }
act Advisor { name: text }
act Course { title: text }

act Student {
    name: text
    advisor: one Advisor?
    courses: many Course
}
```

- `customer: one Customer` is the stored side of a one-to-many. Each customer gets `invoices`: the declaring shape's name, lowercased, plus `s`.
- `Transfer` points at `Customer` twice, so no name is inferred. `outgoing` and `incoming` are views declared by hand, `(Transfer where source == this)`. A view is never assigned.
- `advisor: one Advisor?` is optional. `courses: many Course` is a many-to-many with no join shape, and `Course` gets `students`.
- A link with data of its own, such as a grade, is a shape with a `one` field to each end.
- A required `one` link **holds** its target: the target cannot be deleted while the link exists. `seller: one Seller? initially required` must be given at creation and goes absent if the seller is deleted.

**Refused.**

- Both sides, `invoices: many Invoice` on `Customer` beside `customer: one Customer` on `Invoice`, give ``[V19] 'Customer.invoices: many Invoice' and 'Invoice.customer: one Customer' declare both sides — two owners of one edge set, or two distinct relationships; drop one side (the inverse is inferred), or make the collection a derived view (`invoices: many Invoice = (...)`)``.
- A link to a refinement, `invoice: one OpenInvoice`, gives `[F2] 'Reminder.invoice' — relationships target base shapes, not refinements: a field type is fixed at the write, a membership drifts after it. State the condition on the act that creates 'Reminder': 'Reminder requires invoice to be OpenInvoice', or a gate refinement`.

## 7. Derived properties: `=`

A derived property is a value nobody enters: a balance, a total, whether a payment was on time. It is a formula, recomputed at every read, never stored, never assigned.

```velle
act Customer {
    name: text
    nickname: text?
    greeting: text = if nickname is some then "Hi ${nickname}" else "Hi ${name}"
}

act Invoice {
    customer: one Customer
    total: decimal
    dueBy: Date
    balance: decimal = total - sum(payments, amount)
    settled = balance <= 0
}

act Payment {
    invoice: one Invoice
    amount: decimal
    paidOn: Date
    onTime: boolean = paidOn <= invoice.dueBy
}
```

- `= expression` makes the property derived. The type may be left out when the expression says it; `settled` is a `boolean`.
- A boolean property is written as its condition, not as `if ... then true else false`.
- `if p then a else b` always has `else`. Chain with `else if`.
- A value up a chain of records, "everyone above me", is a walk: `upline: many Member = walk referrer` ([relationships.md](relationships.md)).

**Refused.**

- `invoice.balance = 0` in a rule gives `[F3] rule 'ApplyWriteOff' assigns Invoice.balance — a derived property is never assignable`. Commit the record the formula reads, such as a write-off payment.
- `label: text = if balance > 0 then "owing"` gives `expected 'else'`.
- `label: text = customer.nickname` with `nickname: text?` gives `[F2] 'Invoice.label' is declared a text and may be absent; declare it 'label: text?', or test the value with 'is some' first`.

## 8. Refinements: a named condition

A refinement names the records of a shape that satisfy a condition: overdue invoices, issued invoices, licensed pharmacists. A record is a member whenever the condition is true of it. Rules fire when records enter or leave refinements, prohibitions name them, and the data and locks that belong to one state are declared on it.

```velle
act Invoice { due: Date, balance: decimal }
act Issuance { invoice: one Invoice }
act ArchiveRequest { invoice: one Invoice }
act UnarchiveRequest { invoice: one Invoice }
shape OverdueInvoice = Invoice where balance > 0 and due < today

shape IssuedInvoice = Invoice where exists Issuance for this {
    frozen due
}

shape ArchivedInvoice = Invoice where exists ArchiveRequest for this and not exists UnarchiveRequest for this {
    captured archivedOn: Date = today
    undeletable
}

shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice
```

- `Invoice where ...` names the condition. Nothing is stored and nothing fires by itself.
- `exists Issuance for this` asks whether any `Issuance` points at the invoice.
- `captured archivedOn: Date = today` is computed once, when the invoice enters, and kept while it stays. A derived property in the body, `name: Type = expr`, is live.
- `frozen due` locks the field while the invoice is a member; `frozen` alone locks every stored field. `undeletable` forbids deleting a member.
- `and`, `or`, `not` compose refinements. `not` binds tightest, then `and`, then `or`.
- A refinement's own properties are read only where the record is known to be a member: after `x is ArchivedInvoice` earlier in the same `and` chain or `if`, or in a rule over the refinement.

**Refused.**

- `Invoice where paid = true` gives `expected a declaration (shape/act/rule/never/requires/visible/uses), found ASSIGN('=')`. Write `Invoice where paid`.
- A rule writing a frozen field while the record may be a member, `rule ApplyDueChange when ChangeDueDate { invoice.due = newDue }`, gives `[V5] rule 'ApplyDueChange' writes Invoice.due, frozen by 'IssuedInvoice' — the trigger does not provably exclude membership; partition the act`. Split the request into the changes that may apply and those that are refused, as in [prohibitions.md](prohibitions.md), "A refusal kept as a record".

## 9. Conditions

A condition is a yes-or-no question about one record, written after `where`, inside `never (...)`, after `if`, or as a boolean value.

| Write | Means |
|---|---|
| `==`, `!=`, `<`, `<=`, `>`, `>=` | compare; two records compare by identity |
| `qty between 2 and 5` | both bounds included |
| `status in ["open", "void"]`, `not in` | one of the listed values |
| `contains`, `starts with`, `ends with`, `like`, `matches` | text tests, case-sensitive |
| `applied`, `not applied` | a boolean field on its own |
| `assignee is none`, `is some` | an optional value is absent, present |
| `payments is empty`, `is not empty` | a collection has no members |
| `invoice is IssuedInvoice`, `is not` | the record is in the refinement now |
| `exists Receipt for this` | some `Receipt` points at this record |
| `exists (Reminder where invoice == this and sentOn > today - 7 days)` | some record matches |
| `count(invoices where OverdueInvoice) >= 3` | how many |
| `sum(payments, amount)` | one field added up over a collection |
| `latest(payments by receivedOn).amount` | one record picked; `by` is required |

- `this` is the record the condition is about. A bare name inside a nested `where` is a field of the collection being filtered; reach the outer record with `this.field`.
- `.` through an optional value needs a test that shows it present. `?.` reads absent instead.
- `today - 7 days` and `now + 2 hours` move a date or moment.

**Refused.**

- `latest(payments).amount` gives `latest(...) requires 'by <property>' — the ordering datum is the author's statement, never implicit`.
- `customer.name` with `customer: one Customer?` gives `[V11] 'customer.name' reads through 'customer', which may be absent; test 'customer is some' first, or write 'customer?.name' to read absent when it is`.
- `(Customer for customerEmail)` gives `[F2] '(Customer for customerEmail)' — the subject after 'for' is a record, and 'customerEmail' is a text; to find the record by a value, select it: first(Customer where <field> == customerEmail by <field>)`.

## 10. Rules and what their bodies do

A rule says "when this becomes true, do that". After `when` comes a shape, an act, a refinement, or an inline refinement in parentheses. The rule fires once for each record that newly satisfies it, and `this` is that record. Rules never call each other; one rule's effects are a commit that may fire others. A body holds creations, assignments, deletions, and `for each` blocks, and is one commit: every effect lands, or none does.

```velle
act Customer { name: text, tier: text }
act Invoice { customer: one Customer, total: decimal }
shape Receipt { invoice: one Invoice, sentOn: DateTime }
shape AuditEntry { receipt: one Receipt, note: text }
shape PaidInvoice = Invoice where total > 0

rule SendReceipt when PaidInvoice {
    Receipt from { invoice: this, sentOn: now } as receipt
    AuditEntry from { receipt: receipt, note: "receipt sent to ${customer.name}" }
    customer.tier = "paying"
}

transient act CloseCustomer { customer: one Customer }
never (CloseCustomer where customer is held)

rule ApplyClose when CloseCustomer {
    delete customer
}

act PrintCopies { invoice: one Invoice, copies: integer }
shape Copy { invoice: one Invoice, number: integer }

rule MakeCopies when PrintCopies {
    for each number from 1 to copies {
        Copy from { invoice: invoice, number: number }
    }
}
```

- `Receipt from { ... }` creates a record and gives every field except those the system fills (§5). `as receipt` names it for later statements, as a reference only: `receipt.sentOn` is not allowed.
- `Receipt for this sentOn: now` is the short form. `for` fills the one field whose type matches `this`.
- `customer.tier = "paying"` assigns. The left side is a fixed path to a stored field.
- `delete customer` removes one record named by a fixed path. The invoices hold their customer, so the `never` refuses the request while one does.
- `for each number from 1 to copies { ... }` runs once per number; `for each line in lines { ... }` once per element. A block holds creations and blocks only.
- Statements run in any order unless one reads another's result. `then` on its own line between two statements forces an order.

**Refused.**

- `Reminder from { invoice: this }` gives `[F4] rule 'Remind' — 'Reminder.sentOn' has no value (from-mapping is totality-checked)`.
- Two rules on `CorrectEmail` both assigning `customer.email` give `[V1] rules 'ApplyEmailCorrection' and 'NormalizeEmail' both assign Customer.email and their triggers are not provably disjoint`. Write one assignment with the whole intent, `customer.email = lowercase(corrected)`.
- `delete invoice.lineItems` gives `[V23] rule 'ApplyWipe' deletes 'invoice.lineItems', which goes through a collection; a delete removes one record, so write a rule whose subject is each record to delete, and it fires once per record`.
- Without the `never`, `delete customer` gives `[V24] rule 'ApplyClose' deletes a 'Customer' ('delete customer'), and 'Invoice.customer: one Customer' holds the record it points at, so a 'Customer' cannot be deleted while a 'Invoice' points at it: refuse the request with 'never (CloseCustomer where customer is held)', condition the rule on 'customer is not held', delete the 'Invoice' in the same rule, or declare the reference 'customer: one Customer? initially required' so it goes absent when the 'Customer' is deleted`.

## 11. When a rule fires

By default a rule fires `on commit`: at the commit where a record enters its condition, inside that commit's transaction. If anything in the transaction fails, all of it is undone, the act included.

```velle
act Invoice { due: Date, balance: decimal }
shape OverdueInvoice = Invoice where balance > 0 and due < today
shape Reminder { invoice: one Invoice, sentOn: Date }

rule RemindOverdue
    when (OverdueInvoice where not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }
}

shape PaidInvoice = Invoice where balance <= 0
shape ReceiptEmail { invoice: one Invoice, queuedOn: DateTime }
shape UnemailedInvoice = PaidInvoice where not exists ReceiptEmail for this

rule EmailReceipt when UnemailedInvoice after commit, Hourly {
    ReceiptEmail from { invoice: this, queuedOn: now }
}
```

- `on Daily` runs the rule at each tick of a schedule named `Daily`, once per record in the condition at that moment, each firing its own transaction. Schedules are not declared in the spec; the engineer decides when each name ticks.
- Only a schedule notices time passing. A condition that reads `today` or `now` needs a schedule in `on`.
- A scheduled rule remembers nothing between ticks. The `Reminder` it creates is its memory, and stops it firing again for three days.
- `on commit, Daily` fires at the commit and again at each tick.
- `after commit, Hourly` runs the effects in a transaction of their own once the triggering one is saved, with an hourly backup. Use it for work that must not hold up or undo the request, such as sending email. It needs a guard, a condition its body makes false: here `not exists ReceiptEmail for this`.

**Refused.**

- `rule RemindOverdue when OverdueInvoice { ... }` with no schedule gives `[V3] rule 'RemindOverdue' — its condition depends on the passage of time, which no act commit changes: entry by aging is unobserved. Add a schedule to 'on', or this rule under-fires`.
- `rule SendReceipt when Invoice after commit { ... }` gives `[V4] rule 'SendReceipt' fires 'after commit', in a transaction of its own, so a firing can be lost between the commit and the rule, and nothing would find it; trigger the rule on a guard, a condition its body makes false by creating a record or setting a field, and add a backup schedule beside 'after commit'`.

## 12. Leaving a state, and deletion

`when leaving R` fires at the commit where a record stops being a member of `R` because its condition became false. `when deleted R` fires at the commit that deletes a member. `when leaving or deleted R` fires for either.

```velle
act Invoice { due: Date }
act ArchiveRequest { invoice: one Invoice }
act UnarchiveRequest { invoice: one Invoice }

shape ArchivedInvoice = Invoice where exists ArchiveRequest for this and not exists UnarchiveRequest for this {
    captured archivedOn: Date = today
}

shape UnarchiveNotice { invoice: one Invoice, wasArchivedOn: Date, noticedOn: DateTime }

rule NoteUnarchival when leaving ArchivedInvoice {
    UnarchiveNotice from { invoice: this, wasArchivedOn: archivedOn, noticedOn: now }
}
```

- The body reads the record after the commit, plus the captured values of the refinement it left, for the last time. There is no way to read a field's value from before the commit; capture at entry what the exit will need.
- A rule that reads a capture, and any `when deleted` rule, runs in the triggering transaction: no `after commit`, no schedule.
- A `when deleted` body may not create a record pointing at the deleted one. Copy the fields it needs.
- "No longer delinquent" is `when leaving Delinquent`, not `when Compliant`: a brand-new record enters `Compliant` too.

**Refused.** `rule Gone when leaving Invoice { ... }` over a bare shape gives `[V3] rule 'Gone' can never fire — 'Invoice' is a bare shape with no predicate to flip, and 'when leaving' never fires at deletion; a record leaves a bare shape only by being deleted. Did you mean 'when deleted Invoice'?`

## 13. Prohibitions: `never`, `requires`, `unique`

A prohibition says what must never be: a zero quantity, a customer referring themselves, two customers with one email. Write it once. The compiler decides how to enforce it from what it is written over.

```velle
shape Staff { name: text, role: text }
shape Manager = Staff where role == "manager"

act Customer {
    email: text unique
    referrer: one Customer?
}

act Invoice {
    customer: one Customer
    number: integer
    year: integer
    approver: one Staff
    unique (number, year)
}

never (Customer where referrer == this)
Invoice requires approver to be Manager
```

- `never (Shape where condition)`: no record may satisfy it. `never Name` takes a named refinement.
- `Subject requires condition`: every record must satisfy it, the same as `never (Subject where not condition)`. Inside a `requires`, and only there, membership may be written `approver to be Manager`.
- `email: text unique`: no two customers share an email. `unique (number, year)` is one key over two fields together.
- Over a transient act a prohibition is a **refusal**: the request is turned away and nothing is kept, as `never (SubmitPayment where amount <= 0)` in §1. Over a kept shape or act it is a **constraint**: any act or scheduled rule that would leave the records in that state is rejected. Over state only rules produce it is a **guarantee**, which the compiler must prove the rules never break.
- It holds at the end of each transaction, not at every step inside one.

**Refused.**

- `never (Invoice where due < today)` gives `[V30] never #1 (Invoice where due < today) over Invoice reads the clock — the books can age into the forbidden state with no act to refuse, and the clock has no caller: state it on a transient request, where the clock is read once at the moment of the act; record the decision as a field the prohibition reads instead of the clock; or retract it`. Write it over the request, `never (BillCustomer where due < today)`.
- `never (Account where balance >= 0 and suspended)` beside a rule that sets `suspended` gives `[V10] a 'never' reads 'Account.suspended', which rule 'ApplySuspend' assigns, so it is a guarantee that the rules keep it true, and the compiler cannot yet prove one over every assignment; read a field no rule assigns, or derive 'suspended' from the records the rule writes`.
- `never (Approve where actor to be Manager)` gives `'to be' spells membership only in the predicate of a 'requires' declaration; write 'is' here`.

## 14. Who may commit, who may see: `by` and `visible to`

An act a person commits names the person's shape after `by`. Roles are refinements of that shape. `visible to` says which readers see a record or a field.

```velle
visible to Staff

shape Staff { name: text, role: text }
shape Manager = Staff where role == "manager"
transient act HireStaff by Manager { name: text, role: text }

rule ApplyHire when HireStaff {
    Staff from { name: name, role: role }
}

shape Product {
    name: text
    cost: decimal visible to Manager
    visible to anyone
}
```

- `by Manager` is a role gate: a committer who is not a `Manager` is refused. A gate with a condition is `by Staff` in the header and a `requires` over `actor`, such as `Refund requires actor to be Manager or amount < 100`.
- The committer is `actor` in the act's scope, or the name given by `by Staff as filledBy`. Nobody supplies or assigns it. A kept act stores it.
- The shape after `by` is a `shape` or a refinement of one, never an act.
- `visible to Staff` at the top is the default. `visible to anyone` in a body makes records public. `cost: decimal visible to Manager` hides one field from everyone else.
- Once a spec has any `by` or `visible to`, every shape and kept act must say who sees it, itself or through the default. Visibility applies only to reads; no rule or prohibition depends on the reader.
- The first manager, where hiring needs one, is created by the engineer outside the spec.

**Refused.** With `act Staff`, `act Refund by Manager` gives ``[F2] 'Refund by Manager' — the actor's shape is a `shape`, never an act: an act is an input, and the person who commits one is a record; declare 'Staff' as a shape and create its records with a rule``. With no visibility anywhere, ``[V32] 'Refund' declares no visibility, and this spec has authorization — say who may see it: a `visible to <Shape> [where ...]` clause in its body, `visible to anyone` for a public one, or a spec-wide `visible to <Shape>` at the top of the spec``.

Rules that read the actor, and gates that read the data, are in [prohibitions.md](prohibitions.md).

## 15. From what a product owner says to what to write

| The product owner says | Write |
|---|---|
| "A customer can send a correction, and we keep it." | `act CorrectEmail { ... }` and a rule that assigns |
| "They ask us to change the due date; we do it or say no." | `transient act ChangeDueDate { ... }`, a rule per answer |
| "An order is overdue when it's unpaid past its due date." | `shape OverdueOrder = Order where balance > 0 and due < today` |
| "The balance is the total less what's been paid." | `balance: decimal = total - sum(payments, amount)` |
| "When an invoice is paid, send a receipt." | `rule SendReceipt when PaidInvoice { Receipt from { ... } }` |
| "Every night, suspend members who are behind." | `rule ... when (Delinquent where not suspended) on Nightly { ... }` |
| "Remind them, but at most once a week." | a scheduled rule on `not exists (Reminder where invoice == this and sentOn > today - 7 days)` |
| "A payment is never zero or negative." | `never (SubmitPayment where amount <= 0)` over the request |
| "No two customers share an email." | `email: text unique` |
| "Only a manager may approve a refund." | `act ApproveRefund by Manager { ... }` |
| "A fill must come from a lot of the same drug." | `FillPrescription requires lot.drug == prescription.drug` |
| "Once issued, the due date can't change." | `frozen due` on `IssuedInvoice`, and the change request split |
| "When a member is no longer delinquent, restore service." | `rule RestoreService when leaving Delinquent { ... }` |
| "We need to know every time it happened." | a rule creating a record per occurrence, read with `count` or `latest(... by ...)` |
| "Only managers see the cost." | `cost: decimal visible to Manager` |
