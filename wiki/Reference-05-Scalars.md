# 5. Scalars

A scalar is a single value a property holds: text, a number, true or false, a date, a moment in time, or a value of a type an engineer has added. This page lists the scalar types, how an added type is named, how a property holds several scalar values, the built-in functions, and three things that go with a property declaration: a starting value, a timestamp the system keeps, and the identity every record carries. After it you can declare the properties of a [shape](Reference-03-shape) and know which values the system fills in.

## The types

| Type | Holds |
|---|---|
| `text` | characters |
| `integer` | a whole number (Kotlin `Int`) |
| `long` | a larger whole number (Kotlin `Long`) |
| `decimal` | an exact decimal, for money and quantities (Kotlin `BigDecimal`) |
| `double` | a floating-point number (Kotlin `Double`) |
| `boolean` | true or false |
| `Date` | a calendar date (`java.time.LocalDate`) |
| `DateTime` | a moment on the global timeline (`java.time.Instant`) |
| `uuid` | an identifier the outside world can use (`java.util.UUID`); `==` and `!=` only, never ordered |
| `duration` | a span of time, written as a duration literal such as `3 days` (`java.time.Duration`) |
| `time` | a time of day with no date (`java.time.LocalTime`) |
| `bytes` | binary data the system stores and hands back unchanged (`ByteArray`) |
| `Regex` | a regular expression, in the Java dialect, that compiles (`velle.ext.builtin.RegexValue`); `==` and `!=` only; `matches` takes one on its right ([§10](Reference-10-Predicate-expressions)) |

Each type behaves as its Kotlin counterpart unless the Reference says otherwise. A `DateTime` has no time zone; when the business needs one, store it as data beside the value. A comparison's two sides are one type: a text beside a number or a date, or a `Date` beside a `DateTime`, is an error, and a comparison never reads a text as another type. Where a value is held as text because the business says what it is, `number`, `date`, and `datetime` read it (below, "The built-in functions"). The one reading a comparison makes is of a quoted text beside a `uuid` or a `time`, since neither has a literal of its own: `code == "123e4567-e89b-12d3-a456-426614174000"` and `opensAt < "09:00"` read the text when the spec is compiled, and a text the type cannot read is an error there. A `duration` is ordered, adds to and subtracts from another `duration`, moves a `Date` or `DateTime` ([§10](Reference-10-Predicate-expressions)), and is the difference of two dates or two moments: `elapsed: duration = finished - started`. A `Date` moved by a `duration` that is not whole days is an error when the rule runs, as it is for the literal `today + 3 hours`. A `time` moves by a `duration` as a clock does, wrapping past midnight, and two times subtract to a `duration`. `bytes` is for a document, an image, or a key another system produced: a spec stores it, reads it back, and tests `is some`; two values are equal when their contents are, and they order byte by byte, but no rule should need to say more about one.

`now` is the current `DateTime`. `today` is the current `Date` in the system's one time zone, set at deployment, not in the spec.

## Text made from other values

A business text is sometimes made from other values: an invoice reference that is a prefix and a number, a notice that names the drug it is about. A text literal may hold pieces, each written `${...}` around any value expression, and the text is the literal's characters with each piece's value in its place.

```
shape Invoice {
    customer: one Customer
    number: integer unique
    due: Date
    reference: text = "INV-${number}"
    label: text = "Invoice ${reference} for ${customer.name}, due ${due}"
}

rule NoticeReady when ReadyForPickup {
    Notice from { recipient: prescription.order.patient, subject: "${prescription.drug.name} ready for pickup", sentOn: now }
}
```

- `"INV-${number}"` is the characters `INV-` followed by the number's text, `INV-42`. A piece that is not a text renders as its type's one text form: a number as its plain digits with no trailing zeros (`1.5`, never `1.50`), a `Date` as `2026-11-11`, a `DateTime` as `2026-11-11T09:30:00Z`, a `boolean` as `true` or `false`, a `duration` as `PT168H`. `number("${n}")` reads back `n`. A business that wants a format, `7 days` or `$1.50`, writes a function for it.
- A piece may be any value expression: a path, a function call, `count(lineItems)`, an `if`. A piece that is a record or a collection is an error; write the field meant, `customer.name`.
- A piece whose value is absent makes the whole text absent, so a literal with an optional piece is declared `text?`, and narrows as any optional does. For an absent piece that should read as nothing, write it out: `if poNumber is some then "PO ${poNumber}" else ""`.
- `\$` writes a literal `${`: `"Pay \${amount} now"` holds the characters `${amount}`. A `$` not followed by `{` needs no escape, so `"$${amount}"` is a dollar sign and the amount.
- A literal with pieces is accepted wherever a text literal is: a derived property, a creation's `from` block, a predicate's operand, and a `like` or `matches` pattern ([§10](Reference-10-Predicate-expressions)). `+` between two texts is an error; the literal with pieces is the spelling.
- The rule is `NoticeReady` in `examples/pharmacy/pharmacy.velle`. The invoice is not from an example.

## Extension types

An extension type is a scalar type an engineer adds: a Kotlin class, published as a library and registered with Velle, that a spec names in type position like `Date`. `Money` is one, from `examples/billing/billing.velle`:

```
act LineItem {
    invoice: one Invoice
    description: text
    price: Money
    quantity: integer
    amount: Money = price * quantity
}

never (LineItem where price < 0)
```

- `price: Money` is a field of the type. The name is in scope because the library is on the classpath of the module that compiles the spec; the spec declares nothing.
- `price * quantity` and `price < 0` are legal because the type's author declared an operator line for `Money * decimal`, an ordering, and a reader for number constants. A spec may do with a value of the type exactly what its author declared, and the compiler names the Kotlin class to extend when the spec asks for more.

When two libraries register one name, or a shape in the spec wants the name, a `uses` line at the top of the spec picks or renames the type:

```
uses velle.finance.Money              -- this library's Money, when two libraries register one
uses velle.finance.Money as Cash      -- the same type, written Cash in this spec
```

An extension type is one value, never a business concept with parts. An amount and its currency are two fields of a shape, where the product owner reads the rules about them. A path never reaches into a value of the type: `price.currency` is a field of a shape or nothing. A field may hold several of the type's values with `many` (below), compared by the type's own equality; `one` takes a shape only. Duration arithmetic stays on `Date` and `DateTime`. Writing a type, and the piece that lets a store hold it, is the [Extension types](Extension-types) guide. A `uses` line picks or renames a registered function the same way, with a lowercase name: `uses velle.finance.roundToCents as cents` ([Extension functions](Extension-functions)).

## Collections of values (`many`)

A property can hold several values of one scalar type instead of one, written `many` before the type. Each value is held once, in no order, as a `many` of records holds each record once ([§6](Reference-06-Relationships)). When the business needs the same value more than once, or in the order it arrived, the property adds `tolerates duplication`, and then the collection keeps exactly what it is given, in that order. The element type is any type in the table above or an extension type in scope.

A student record carries tags an administrator attaches, where attaching the same tag twice means nothing, and notes added over time, where the same note on two days is two notes. From `examples/enrollment/enrollment.velle`:

```
act Student {
    name: text
    advisor: one Advisor?
    tags: many text
    notes: many text tolerates duplication
    courses: many Course
}

transient act Note {
    student: one Student
    note: text
}

rule ApplyNote when Note {
    this.student.notes = this.student.notes + this.note
}
```

- `tags: many text` holds text values, each once. The committer supplies it, possibly empty. A committed collection holding the same value twice is refused.
- `notes: many text tolerates duplication` holds text values in the order given, duplicates included. The committer supplies it, possibly empty.
- `courses: many Course` holds records, not values, so a link cannot arrive twice and the property cannot tolerate duplication.
- `this.student.notes + this.note` is the collection with the note added at the end. On a property that tolerates duplication, `+` appends and `-` removes every occurrence of the value. On a plain `many`, `+` adds the value when it is absent and `-` removes it when it is present, and otherwise nothing changes. The right-hand side is one value or a collection.

`tolerates duplication` says the same thing it says on a number ([§19](Reference-19-Self-referential-folds-and-tolerates)): the same thing arriving twice is accepted, and the value shows it. On a plain `many` nothing would show, which is why a plain `many` never needs the clause. A rule that appends to such a property on a schedule is also an order-dependent fold, and the compiler asks for `tolerates reordering` as it would for any other.

What a rule or predicate can do with either form:

- Assign it whole: `this.student.tags = this.tags` replaces every value.
- Start it empty: `tags: many text initially empty`. A `many` never takes `?`, because the empty collection already means "none".
- Count it with `count(tags)`, test it with `tags is empty`, `tags contains "honors"`, or `"honors" in tags`, and run over it with `for each tag in tags` ([§14](Reference-14-for)), in the kept order when there is one. `shape Honors = Student where tags contains "honors"` is in `examples/enrollment/enrollment.velle`.
- Add it up or pick from it: `sum(scores)` over a `many` of numbers or durations, `max(scores)` and `min(scores)` over a `many` of any ordered type. The sum of an empty collection is zero, and `max` and `min` of one are absent.
- Read it as a derived property of another record: `tagsSeen: many text = student.tags`.

A collection of values has no members to reach into, so it cannot be filtered with `where`, summed by a field, or ordered with `by`; those belong to collections of records ([§10](Reference-10-Predicate-expressions)).

## The built-in functions

A spec may call these, and any function a library on the classpath registers ([Extension functions](Extension-functions)). The functions below that take parentheses are registered functions Velle ships, written the way an engineer writes one, so a registered type that implements the capability a function asks for is accepted by it.

- `count`, `sum`, `latest`, `first`, for collections ([§10](Reference-10-Predicate-expressions)). `sum` applies to a number, a duration, and to an extension type that declares how its values add.
- `contains`, `starts with`, `ends with`, `like`, `matches`, for text ([§10](Reference-10-Predicate-expressions)). `contains` also tests whether a `many` of values holds a value (above, "Collections of values"), as `in` does the other way round.
- `lowercase`, `uppercase`, `trim`, `length`, each taking one text. `length` is a whole number. A function called on an absent value is absent.
- `number`, `date`, `datetime`, `regex`, each taking one text and giving it read as a `decimal`, a `Date`, a `DateTime`, or a `Regex`, absent when the text is not one. Each reads what its Kotlin counterpart reads: `number(" 12.50 ")` is 12.5, `date("2026-01-15")` a date, `datetime("2026-01-15T09:00:00Z")` a moment, and `datetime("2026-01-15T09:00:00")` absent, because a time without its offset is not a moment, and `regex("(")` absent, because the pattern does not compile. `regex` around a text literal with pieces reads the literal as a pattern piece by piece ([§10](Reference-10-Predicate-expressions)). They are for a value the business holds as text, as when a form field's kind is data: `number(value) is none` refuses a text that is not a number, and `number(value) < field.min` compares one that is. A text is read nowhere else; a comparison never reads it.
- `max`, `min`. They apply to two or more values of one ordered type, numbers, dates, text, or any extension type that implements `Ordered`, and to a `many` of values of any ordered type, where the result is absent when the `many` is empty.
- `abs`, taking one number or one duration, gives it without its sign: `abs(due - today)` is how far a date is from today either way.
- `year`, `month`, `day`, `dayOfWeek`, each taking one `Date` or one `DateTime`, and `hour`, `minute`, each taking one `time` or one `DateTime`. Each is a whole number. `dayOfWeek` counts Monday as 1 and Sunday as 7, so `dayOfWeek(due) >= 6` is a weekend. The parts of a `DateTime` are read in the zone the system runs in, the same zone that gives `today` its date.
- `%`, the remainder after division, between two numbers: `n % 2 == 0` is even. The result keeps the sign of the left side.
- Duration literals such as `3 days`, in `seconds`, `minutes`, `hours`, `days`, or `weeks` ([§10](Reference-10-Predicate-expressions)).
- `randomUUID`, only after `initially`.

## Optional properties

A property is required unless its type ends in `?`: `processedOn: Date?` may be absent.

## Starting values (`initially`)

`initially` gives a stored property its value when the record is created:

```
applied: boolean initially false
```

It is allowed on any stored property of a `shape`, relationships included. The value is computed once, at the commit that creates the record. The property stays stored, and a rule may [assign](Reference-12-Assignment) it later. So `submittedOn: Date initially now` records the creation time as business data an administrator could later correct.

`initially` is not a derivation. `applied: boolean = false` in a shape body would declare a [derived property](Reference-07-Derived-properties) that is always false and never assignable. A derived property has no stored value, so it cannot take `initially`; neither can a `timestamp` field, whose value the commit supplies (below). The counterpart inside a [refinement](Reference-08-Refinements) is `captured`, which computes its value when the record enters the refinement.

`initially` is not allowed on an `act`, whose every stored field the committer supplies. A system-kept value belongs on the `shape` a rule creates from the act ([§22](Reference-22-Open-unresolved), "External input").

## Generated values

`initially` also accepts a generator. `code: uuid initially randomUUID` mints a unique value when the record is created, which is how a record gets a key the outside world can use (`examples/enrollment/enrollment.velle`). On a `text` field, as `reference: text initially randomUUID` in `examples/billing/billing.velle`, the field gets the value's canonical spelling. The generator runs once at the creation commit; if that transaction is rolled back and retried, it runs again. `randomUUID` is the only generator. There is no sequence generator; ordering comes from timestamps or declared fields.

## Timestamps (`timestamp`)

A `timestamp` property is filled in by the commit itself. Neither the committer nor any rule supplies it:

```
shape Review {
    product: one Product
    stars: integer
    body: text
    createdAt: timestamp on create
    updatedAt: timestamp on update
}
```

- `createdAt: timestamp on create` is set once, at the commit that creates the review.
- `updatedAt: timestamp on update` moves forward at every commit that writes a stored field of the review. The creating commit counts, so the field is never absent.

The names are yours; `correctedOn: timestamp on create` is fine. A committer cannot supply the value, and `this.updatedAt = now` in a rule is a compile error.

Choose `initially now` when the moment is a business fact someone may later correct, and `timestamp on create` when it is a fact about the commit. A timestamp can be named in a selector's `by` clause like any field ([§10](Reference-10-Predicate-expressions)), but only by name.

## Identity (`id`)

Every record carries an `id` without declaring it. It is unique to the record, fixed at creation, and never assignable. It supports `==` and nothing else: not ordered, not arithmetic, not text. Whether it is a UUID, an auto-increment, or an existing table's primary key is decided at compilation ([§1](Reference-01-Principles)).

`id` is a reserved name. A business identifier such as an account number gets its own declared field: identifiers are data, `id` is identity. Comparing two records compares identity ([§10](Reference-10-Predicate-expressions)): `a == b` means `a.id == b.id`.

## The actor

An act declared `by` carries one more system-filled property: the actor who committed it, named `actor` or whatever the `as` clause says. It is never supplied or assigned, and on a durable act it is stored like a timestamp and readable through any reference to the act ([§22](Reference-22-Open-unresolved), "The actor").

## What the compiler checks

- **Rejects** a call to a name no builtin or registered function has, a call that none of the function's signatures accepts (a text function with anything but one text argument, `abs` on anything but a number or a duration, a date part on anything but a value with a calendar date, `hour` or `minute` on anything but a value with a time of day, `max` or `min` on values of two types), `%` on anything but numbers, a comparison between two kinds that differ (a text beside a number or a date, a `Date` beside a `DateTime`), naming `number`, `date`, or `datetime` when a text field is one side, a quoted text beside a `uuid` or a `time` that the type cannot read, `+` or any arithmetic on a text, an ordered comparison on a `uuid` or a `boolean`, and duration arithmetic on anything but a `Date`, a `DateTime`, or a `duration` ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a name in type position that is neither a builtin, nor a shape after `one` or `many`, nor an extension type in scope; a name two registered types share with no `uses` line picking one; and a shape named like an extension type in scope, naming the `uses` line to write ([checks.md, F1](checks#f1--name-resolution-and-casing), [E1](checks#e1--registered-type-and-function-names)).
- **Rejects** an operator, comparison, `sum`, `max`, `min`, selector `by`, or registered function on an extension type that does not implement the capability it asks for, naming the Kotlin class and the interface ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- **Rejects** a `where`, a `sum` by field, or a `by` over a collection of values; `sum` with no field over anything but a `many` of numbers or durations; and `max` or `min` over a `many` of values with no order ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a piece of a text literal that is a record or a collection, naming the piece; a property declared `text` whose literal holds an optional piece, naming the piece and `text?`; and `+` between two texts, naming the literal with pieces to write ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** `tolerates duplication` on a `many` of records ([checks.md, F3](checks#f3--position-rules)).
- **Rejects** a rule that appends to a collection tolerating duplication on a schedule, unless the property also tolerates reordering ([checks.md, V8](checks#v8--fold-obligations-and-tolerance-coverage)).
- **Refuses** at the boundary a committed `many` of values that holds the same value twice, unless the property tolerates duplication ([checks.md, V21](checks#v21--act-field-forms)).
- **Rejects** `initially` on anything but a stored property, and any assignment to a `timestamp` field or `id` ([checks.md, F3](checks#f3--position-rules)).
- **Rejects** `initially` or `timestamp` on an act, naming the field and where it should move ([checks.md, V21](checks#v21--act-field-forms)).
- **Rejects** a declared field named `id` ([checks.md, F1](checks#f1--name-resolution-and-casing)).

## Related pages

- [§7 Derived properties](Reference-07-Derived-properties), for values computed instead of stored.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for the functions in use.
- [Extension types](Extension-types), for writing a type and the piece a store needs to hold it.
