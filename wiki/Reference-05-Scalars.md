# 5. Scalars

A scalar is a single value a property holds: text, a number, true or false, a date, or a moment in time. This page lists the scalar types, the built-in functions, and three things that go with a property declaration: a starting value, a timestamp the system keeps, and the identity every record carries. After it you can declare the properties of a [shape](Reference-03-shape) and know which values the system fills in.

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

Each type behaves as its Kotlin counterpart unless the Reference says otherwise. A `DateTime` has no time zone; when the business needs one, store it as data beside the value.

`now` is the current `DateTime`. `today` is the current `Date` in the system's one time zone, set at deployment, not in the spec.

The Reference also uses `Money`. It is not built in; it stands for a type you will be able to declare later ([§22](Reference-22-Open-unresolved)).

## The built-in functions

The list is fixed. A spec may call only these:

- `count`, `sum`, `latest`, `first`, for collections ([§10](Reference-10-Predicate-expressions)).
- `contains`, `starts with`, `ends with`, `like`, for text ([§10](Reference-10-Predicate-expressions)).
- `lowercase`, `max`, `min`.
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

`initially` also accepts a generator. `reference: text initially randomUUID` mints a unique value when the record is created, which is how a record gets a key the outside world can use (`examples/billing/billing.velle`). The generator runs once at the creation commit; if that transaction is rolled back and retried, it runs again. `randomUUID` is the only generator. There is no sequence generator; ordering comes from timestamps or declared fields.

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

- **Rejects** a call to a function outside the list above, an operator or comparison on mismatched types, and duration arithmetic on anything but a `Date` or `DateTime` ([checks.md, F2](checks#f2--type-checking)).
- **Rejects** `initially` on anything but a stored property, and any assignment to a `timestamp` field or `id` ([checks.md, F3](checks#f3--position-rules)).
- **Rejects** `initially` or `timestamp` on an act, naming the field and where it should move ([checks.md, V21](checks#v21--act-field-forms)).
- **Rejects** a declared field named `id` ([checks.md, F1](checks#f1--name-resolution-and-casing)).

## Related pages

- [§7 Derived properties](Reference-07-Derived-properties), for values computed instead of stored.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for the functions in use.
