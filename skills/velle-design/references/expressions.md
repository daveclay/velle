# Conditions and formulas

Open this file when a condition after `where`, inside `never (...)`, or a formula after `=` needs more than a plain comparison. A condition is a formula whose value is `true` or `false`, so everything here works in both places. Every example compiles on its own.

## Contents

1. [Comparing values](#1-comparing-values)
2. [Tests with `is`](#2-tests-with-is)
3. [Related records: `exists`](#3-related-records-exists)
4. [Counting, adding up, largest and smallest](#4-counting-adding-up-largest-and-smallest)
5. [Absent values, `?.`, and tests that show a value present](#5-absent-values--and-tests-that-show-a-value-present)
6. [Picking one record: `latest`, `first`, `(Shape for x)`, keys](#6-picking-one-record-latest-first-shape-for-x-keys)
7. [Naming a level with `as`](#7-naming-a-level-with-as)
8. [`if`, and conditions as values](#8-if-and-conditions-as-values)
9. [Dates, times, and durations](#9-dates-times-and-durations)
10. [Text built from other values](#10-text-built-from-other-values)
11. [The built-in functions](#11-the-built-in-functions)

## 1. Comparing values

```velle
Status is one of "open", "paid", "void"

act Customer { name: text, email: text, tags: many text }

act Invoice {
    customer: one Customer
    status: Status
    quantity: integer
    note: text?
}

shape Unusual = Invoice where quantity between 50 and 100 and status in ["open", "void"]
shape Corporate = Customer where email ends with "@example.com" and "vip" in tags
shape WellFormed = Customer where email matches "[^@ ]+@[^@ ]+\\.[a-z]+"
shape Initialled = Customer where name like "_. %"
shape Unexplained = Invoice where note is empty
```

- `==`, `!=`, `<`, `<=`, `>`, `>=` compare two values of one type. Two records compare by identity: `a == b` asks whether they are the same record, never field by field.
- `x between a and b` includes both bounds and works on any ordered value.
- `x in [a, b]` and `x not in [...]` test against listed values of `x`'s type. The right side may also be a `many` of values, `"vip" in tags`, or, when `x` is a record, a collection of records, `this in lineage`.
- `contains`, `starts with`, `ends with` test text and are case-sensitive. `contains` also asks whether a `many` of values holds a value.
- `like` takes a pattern: `%` any run of characters, `_` one character, `\` escapes either.
- `matches` takes a regular expression the whole text must match. A backslash in a text is written twice, so the expression `\.` is `"\\."`. A malformed expression is refused when the spec compiles.
- A comparison never reads a text as a number or date. `value > limit` with `value: text` and `limit: decimal` gives `[F2] 'value > limit' — compares the text 'value' with a decimal; a text is not compared as a decimal. Write number(value), which is absent when the text is not a number, or declare 'value' as a decimal on a shape of its own`. A `Date` beside a `DateTime` is refused the same way.
- Words declared with `is one of` compare with `==`, `!=`, `in` only.

**Refused.** A collection beside one value: `inheritedBy == this` gives `[F2] 'inheritedBy == this' — compares the collection 'inheritedBy' with a record; a collection is not compared with one value. Write 'this in inheritedBy' to ask whether it is one of them`, and `tags == name` gives the same message ending `Write 'name in tags'`.

## 2. Tests with `is`

`is` asks one of five things.

| Write | Asks |
|---|---|
| `assignee is none`, `assignee is some` | is the optional value absent, present |
| `payments is empty`, `is not empty` | does the collection have no members |
| `note is empty`, `is not empty` on a text | is the text absent or only spaces and line breaks |
| `invoice is IssuedInvoice`, `invoice is not IssuedInvoice` | is the record in the refinement now |
| `seller is held`, `seller is not held` | does a required `one` link point at the record |

- A membership test needs the record and the refinement to share a base shape, or the named shape to be part of the record's union ([relationships.md](relationships.md)). Otherwise it is refused, not quietly false.
- A membership test **narrows**: after `x is ArchivedInvoice` earlier in an `and` chain, or in the `then` branch of an `if`, the rest may read `ArchivedInvoice`'s own properties through `x`.
- `is held` takes one record of a kept shape. `seller.name is held` gives `[F2] 'seller.name is held' — 'is held' tests one record of a kept shape, and 'seller.name' is not one`.

## 3. Related records: `exists`

```velle
act Customer { name: text }
act Referral { referrer: one Customer, referee: one Customer, referredOn: Date }
act Invoice { customer: one Customer, total: decimal }
act Receipt { invoice: one Invoice }

shape Receipted = Invoice where exists Receipt for this
shape Unreceipted = Invoice where not exists Receipt for this
shape Referred = Customer where exists (Referral where referee == this)
shape BigReferrer = Customer where exists (Referral where referrer == this and referee.name != "") and count(invoices) > 3
```

- `exists Receipt for this` asks whether any `Receipt` points at this record. `for` picks the one field of `Receipt` whose type matches.
- When two fields match, as `referrer` and `referee` do, write the general form with `where` and name the field. `exists Referral for this` there gives `[V13] exists Referral for ... — 'for' matches 2 fields of 'Referral' (needs exactly one; name the field or use the where-filtered form)`.
- The general form also adds conditions on the related record.
- Inside the nested `where`, bare names are fields of `Referral`. `this` is still the customer.

## 4. Counting, adding up, largest and smallest

```velle
act Customer { name: text, scores: many integer }
act Invoice { customer: one Customer, total: decimal, paid: boolean }

shape Valued = Customer where count(invoices where not paid) >= 3 and sum(invoices, total) > 1000

act Summary {
    customer: one Customer
    owed: decimal = sum(customer.invoices, total)
    best: integer = if customer.scores is empty then 0 else max(customer.scores)
    biggest = max(owed, 500)
}
```

- `count(collection)` counts. The collection may be filtered: `invoices where not paid`, `invoices where OverdueInvoice`.
- `sum(collection, field)` adds one field. `sum(scores)` adds a `many` of numbers or durations directly. The sum of nothing is zero.
- `max(a, b, ...)` and `min(...)` take values of one ordered type. Over a `many` of values they are absent when it is empty, so a required field tests first.
- A shape name is a collection of every record of it: `count(Waitlist)`. Such a read makes commits on the shape take turns ([time-and-repetition.md](time-and-repetition.md)).

## 5. Absent values, `?.`, and tests that show a value present

A field declared with `?` may be absent. A plain `.` through it is allowed only where a test has shown it present.

```velle
act Region { name: text }
act Site { region: one Region? }

act Visit {
    site: one Site?
    regionName: text = if site?.region is some then site.region.name else "unplaced"
    regionOrNone: text? = site?.region?.name
}
```

- `site?.region?.name` reads absent when any step is absent, so its type is `text?`.
- `site?.region is some` shows every step present, so the `then` branch reads `site.region.name` with plain dots. The path under test is written with `?.` because it is read before it is known to be present.
- A test shows a value present earlier in the same `and` chain, in the matching branch of an `if`, in the `where` of the rule's own condition, and on the right of an `or` whose left side was the opposite test: `site is none or site.region is some`.
- An equality shows a value present only when the other side cannot be absent. `!=` and `not in` show nothing.
- An absent value equals no present value and is in nothing.

**Refused.** `customer.name` with `customer: one Customer?` gives `[V11] 'customer.name' reads through 'customer', which may be absent; test 'customer is some' first, or write 'customer?.name' to read absent when it is`.

## 6. Picking one record: `latest`, `first`, `(Shape for x)`, keys

```velle
act Customer { name: text }

act Invoice { customer: one Customer, total: decimal }

act Payment { invoice: one Invoice, amount: decimal, receivedOn: DateTime, sequence: integer }

shape PaidInvoice = Invoice where exists Payment for this {
    lastAmount: decimal = latest(Payment for this by receivedOn, sequence).amount
}

act Plot { key: text unique, label: text }

transient act SubmitReading { plotKey: text, value: decimal }

shape Reading { plot: one Plot, value: decimal }

never (SubmitReading where not exists (Plot where key == this.plotKey))

rule RecordReading when SubmitReading {
    Reading from { plot: (Plot where key == this.plotKey), value: value }
}

act Summary {
    invoice: one Invoice
    firstAmount: decimal? = first(invoice.payments by receivedOn).amount
    lastOrZero: decimal = if invoice.payments is empty then 0 else latest(invoice.payments by receivedOn).amount
}
```

- `latest(collection by field)` and `first(collection by field)` pick one record. `by` is required; there is no default order and `id` has none. Further fields after a comma break ties. If the fields still do not single out one record, the selection fails and the request comes back refused, naming the rule.
- A selection picks none when the collection is empty. Its value is then absent, so `firstAmount` is `decimal?`.
- A required value through a selection needs a test that the collection is not empty, spelt the same way as the selection: `invoice.payments is empty` guards `latest(invoice.payments by ...)`, and `exists Payment for this` guards `latest(Payment for this by ...)`.
- `(Shape for this)` is the one record pointing at this one, allowed only when the compiler can prove there is at most one. Otherwise use `latest` or `first`.
- A filter that names every field of a key, `(Plot where key == this.plotKey)`, is one record or absent, with no `by`. Here the `never` refuses a key no plot has, so the rule always finds one.
- Two records committed in one transaction have the same commit timestamp. To tell them apart, order by a field the business supplies, such as `sequence`.

**Refused.**

- `latest(payments).amount` gives `latest(...) requires 'by <property>' — the ordering datum is the author's statement, never implicit`.
- `(Customer for customerEmail)` with a text after `for` gives `[F2] '(Customer for customerEmail)' — the subject after 'for' is a record, and 'customerEmail' is a text; to find the record by a value, select it: first(Customer where <field> == customerEmail by <field>)`.
- `opened: Date initially first(patient.visits by seenOn).seenOn` gives `[F2] 'Chart.opened' is declared a Date and may start absent: 'first(patient.visits by seenOn)' may match nothing; declare it 'opened: Date?', or start it where a test shows the selection matches, such as 'exists (patient.visits)'`.
- `?.` directly after a selection is an advisory, not a refusal: the plain dot already reads absent.

## 7. Naming a level with `as`

Inside a condition, `this` is always the outer record and a bare name is a field of the innermost collection. `as` names a middle level so a deeper one can read it.

```velle
act Customer { name: text, options: many text }
act Invoice { customer: one Customer, total: decimal }
act Payment { invoice: one Invoice, amount: decimal }

shape Overpaid = Customer where exists (invoices as inv where count(inv.payments where amount > inv.total) >= 1)

act Report {
    customer: one Customer
    bOptions: integer = count(customer.options as option where option starts with "b")
}
```

- `invoices as inv` names each invoice, so the filter over payments can compare against `inv.total`.
- A `many` of values is filtered only by naming each value: `(options as option where ...)`. It has no fields for a bare name to mean.
- An `as` name is visible inside its own condition and anything nested in it.
- Two collections of the same record are joined with a comma and one `where`: `exists (tickets as tix, orders as ord where ord.product == tix.product)`.

## 8. `if`, and conditions as values

- `if p then a else b` always has `else`, and chains with `else if`. Each branch reaches to the end of the text: `if p then 1 else q == 2` is `if p then 1 else (q == 2)`.
- In the `then` branch, the tests in `p` narrow as they do in an `and` chain. Over a union of two shapes, the `else` branch of `x is A` is narrowed to the other shape.
- A condition is a `boolean` value wherever a value is written: `onTime: boolean = paidOn <= dueBy`, or `flagged: quantity > 100` in a `from` block.
- A comparison used as an operand is parenthesized: `ok: boolean = (a < b) == expected`. Without the parentheses it does not parse: `expected LIDENT, found EQ('==')`.
- `not` binds tightest, then `and`, then `or`.

## 9. Dates, times, and durations

```velle
act Shift {
    starts: DateTime
    ends: DateTime
    opens: time
    closes: time
    due: Date
    grace: duration
    length: duration = ends - starts
    lastOrder: time = closes - 30 minutes
    weekend: boolean = dayOfWeek(due) >= 6
    deadline: Date = due + 14 days
}

shape Late = Shift where due + grace < today
```

- A duration literal is a whole number and `seconds`, `minutes`, `hours`, `days`, or `weeks`.
- On a `Date`, `+ 1 days` is the next calendar date; moving a `Date` by part of a day fails when it runs. On a `DateTime` a duration is exact clock time. A `time` wraps past midnight.
- Two `DateTime`s, two `Date`s, or two `time`s subtract to a duration. Durations add and subtract.
- `year`, `month`, `day`, `dayOfWeek` read a `Date` or `DateTime`; `hour`, `minute` read a `time` or `DateTime`. `dayOfWeek` counts Monday as 1. `abs(due - today)` is the distance either way.
- `today` is the date in the system's one time zone, set at deployment.

## 10. Text built from other values

- `"INV-${number}"` puts each piece's value in its place. A piece may be any value: a path, `count(lineItems)`, an `if`. A record or a collection is refused; write the field meant.
- A number renders as plain digits with no trailing zeros, a `Date` as `2026-11-11`, a `DateTime` as `2026-11-11T09:30:00Z`, a duration as `PT168H`. A business format such as `$1.50` is an engineer's function.
- An absent piece makes the whole text absent, so the property is `text?`. For an absent piece that reads as nothing, write `if poNumber is some then "PO ${poNumber}" else ""`.
- `\$` writes a literal `${`.
- `+` between texts is refused; the text with pieces is the spelling.

## 11. The built-in functions

| Function | Takes and gives |
|---|---|
| `count`, `sum`, `latest`, `first` | collections (§4, §6) |
| `max`, `min` | two or more values of one ordered type, or a `many` of values (absent when empty) |
| `lowercase`, `uppercase`, `trim`, `length` | one text; `length` is a whole number |
| `number`, `date`, `datetime`, `regex` | one text read as a `decimal`, `Date`, `DateTime`, `Regex`; absent when it is not one |
| `abs` | one number or duration, without its sign |
| `year`, `month`, `day`, `dayOfWeek`, `hour`, `minute` | one part of a date, moment, or time, as a whole number |
| `%` | the remainder of two numbers, `n % 2 == 0` |
| `randomUUID` | only after `initially` |

A function called on an absent value is absent. A field of words declared `Role is one of ...` also gets a reading function named like the type with a lowercase first letter: `role(answer)` is the word the text spells, or absent. A spec may also call functions an engineer's library registers.
