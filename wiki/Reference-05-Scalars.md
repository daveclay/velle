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

Each type behaves as its Kotlin counterpart unless the Reference says otherwise. A `DateTime` has no time zone; when the business needs one, store it as data beside the value. A `uuid` field compared to a quoted text reads the text as a uuid. A `duration` is ordered, adds to and subtracts from another `duration`, moves a `Date` or `DateTime` ([§10](Reference-10-Predicate-expressions)), and is the difference of two dates or two moments: `elapsed: duration = finished - started`. A `Date` moved by a `duration` that is not whole days is an error when the rule runs, as it is for the literal `today + 3 hours`. A `time` compared to a quoted text reads the text as a time, `"09:00"`; it moves by a `duration` as a clock does, wrapping past midnight, and two times subtract to a `duration`. `bytes` is for a document, an image, or a key another system produced: a spec stores it, reads it back, and tests `is some`; two values are equal when their contents are, and they order byte by byte, but no rule should need to say more about one.

`now` is the current `DateTime`. `today` is the current `Date` in the system's one time zone, set at deployment, not in the spec.

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

An extension type is one value, never a business concept with parts. An amount and its currency are two fields of a shape, where the product owner reads the rules about them. A path never reaches into a value of the type: `price.currency` is a field of a shape or nothing. A field may hold several of the type's values with `many` (below), compared by the type's own equality; `one` takes a shape only. Duration arithmetic stays on `Date` and `DateTime`. Writing a type, and the piece that lets a store hold it, is the [Extension types](Extension-types) guide.

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
- Count it with `count(tags)`, test it with `tags is empty` or `tags contains "honors"`, and run over it with `for each tag in tags` ([§14](Reference-14-for)), in the kept order when there is one. `shape Honors = Student where tags contains "honors"` is in `examples/enrollment/enrollment.velle`.
- Read it as a derived property of another record: `tagsSeen: many text = student.tags`.

A collection of values has no members to reach into, so it cannot be filtered with `where`, summed, or ordered with `by`; those belong to collections of records ([§10](Reference-10-Predicate-expressions)).

## The built-in functions

The list is fixed. A spec may call only these:

- `count`, `sum`, `latest`, `first`, for collections ([§10](Reference-10-Predicate-expressions)). `sum` applies to a number and to an extension type that declares how its values add.
- `contains`, `starts with`, `ends with`, `like`, for text ([§10](Reference-10-Predicate-expressions)). `contains` also tests whether a `many` of values holds a value (below, "Collections of values").
- `lowercase`, `uppercase`, `trim`, `length`, each taking one text. `length` is a whole number.
- `max`, `min`. They apply to numbers and to any extension type that declares an ordering.
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

- **Rejects** a call to a function outside the list above, a text function with anything but one text argument, an operator or comparison on mismatched types, an ordered comparison on a `uuid` or a `boolean`, and duration arithmetic on anything but a `Date`, a `DateTime`, or a `duration` ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** a name in type position that is neither a builtin, nor a shape after `one` or `many`, nor an extension type in scope; a name two registered types share with no `uses` line picking one; and a shape named like an extension type in scope, naming the `uses` line to write ([checks.md, F1](checks#f1--name-resolution-and-casing), [E1](checks#e1--registered-type-names)).
- **Rejects** an operator, comparison, `sum`, `max`, `min`, or selector `by` on an extension type that does not declare the capability, naming the Kotlin class ([checks.md, E3](checks#e3--a-capability-the-type-does-not-declare)).
- **Rejects** a `where`, `sum`, or `by` over a collection of values ([checks.md, F2](checks#f2--type-checking)).
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
