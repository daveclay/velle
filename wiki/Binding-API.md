# Binding API

The binding API is the Kotlin the compiler generates from a spec for an engineer's own code to call. This page describes the part that commits an act: the input class for each act, the nested values for its parts, the reference types a `one` field takes, the commit function, and what the boundary refuses before the runtime runs. After reading it an engineer can build an act's input, including records that arrive with it, and commit it.

An engineer's transport code, whatever it is, maps a request onto one input object and calls one commit function. Velle owns the shape of that object and the function. Everything before the call, such as authentication or rate limits, is the engineer's code ([§22](Reference-22-Open-unresolved), "External input").

## The input class

Every act gets a class named `New` followed by the act's name, with one constructor parameter per stored field the committer supplies, required fields first, then optional ones defaulting to `null`. A field typed by another shape takes a handle, `Ref<T>`, which any loaded model object is. For `act PeptideBatch` in `examples/batches/batches.velle`, the generated class in `examples/batches/output/src/main/kotlin/velle/generated/Batches.kt` begins:

```kotlin
data class NewPeptideBatch(
    val peptide: Ref<Peptide>,
    val quantityMg: BigDecimal,
    val origin: OriginReference,
)
```

The class carries no `id`, no timestamp fields, no derived properties, and no actor: those are Velle's to fill ([§5](Reference-05-Scalars), [§22](Reference-22-Open-unresolved)).

## Parts

An act declared with inline parts ([§6](Reference-06-Relationships), "Inline part creation") takes one list per part collection, each element a `New` class for the part shape without its back-reference. A part with parts of its own nests the same way. From `examples/orders/orders.velle`:

```kotlin
NewOrder(
    customer = customer,
    placedOn = LocalDate.of(2026, 8, 21),
    orderLines = listOf(NewOrderLine(product = product, quantity = 2)),
)
```

## References to new records

A `one` field that may take a new record is typed by a reference type named after its target, with two cases:

```kotlin
sealed interface OriginReference {
    data class Existing(val ref: Ref<Origin>) : OriginReference
    data class New(val value: NewOrigin) : OriginReference
}
```

- `Existing` holds a handle to a record already on the books.
- `New` holds a value built for this act. The case names say what the engineer has in hand, and the Kotlin compiler refuses the other one.

Which fields get this type, and what `New` means on each, is fixed by the spec:

| the field in Velle | `New(value)` means |
|---|---|
| a `one` field named in the act's `with` ([§6](Reference-06-Relationships), "A reference supplied by value") | the value is created here, nested at this field |
| a `one` field typed by a shape that arrives elsewhere in the act's closure ([§6](Reference-06-Relationships), "A reference to a record arriving elsewhere in the same commit") | the value is one of the act's own new values, placed in a part list or at a `with`-named field |
| a `many` field typed by a shape that arrives elsewhere in the act's closure | each element is `Existing` or `New`; the list is a set |
| any other `one` or `many` field | the field takes `Ref<T>`, or a list of them, and has no reference type |

A field never has both meanings. When the target is a static union, or more than one input class creates it, the `New` case takes an interface the creating classes implement, named `New` followed by the target's name. From the batches example:

```kotlin
val batch = NewPeptideBatch(
    peptide = peptide,
    quantityMg = BigDecimal("100"),
    origin = OriginReference.New(NewFermentation(strain, run)),
)
```

A `many` field that may hold new values is typed `List<ConditionReference>` in the same way, and each element is `Existing` or `New`.

A `uuid` field is a `java.util.UUID`, a `duration` field a `java.time.Duration`, a `time` field a `java.time.LocalTime`, and a `bytes` field a `ByteArray` on the input class and the model object ([§5](Reference-05-Scalars)); a `uuid` with `initially randomUUID` is not an input parameter. In a composed query, a text member offers `lowercase`, `uppercase`, `trim`, and `length` (`where { title.length gt 10 }`), `matches` with a regular expression, and `isEmpty()` / `isNotEmpty()` for an absent or blank text; a `uuid` member equality only; and a `duration`, `time`, or `bytes` member the ordered comparisons. Every comparable member offers `oneOf(a, b)` for `in` and `between(lo, hi)` for `between` ([§10](Reference-10-Predicate-expressions)). A `Date` member offers `year`, `month`, `day`, and `dayOfWeek`, a `time` member `hour` and `minute`, and a `DateTime` member all six, each compared as a whole number: `where { due.dayOfWeek gte 6 }`. The `examples/billing/output` query tests use each of these over SQLite.

A `many` of values ([§5, "Collections of values"](Reference-05-Scalars#collections-of-values-many)) is a `List` of the element's Kotlin type on the input class and on the model object, `List<String>` for `many text`. A commit with the same value twice is refused unless the property tolerates duplication, in which case the list reads back in the order it was committed.

A reference to a sibling is the sibling's own object. The engineer builds the value once, places it where it is created, and passes the same object wherever another value points at it. From the plate-layout spec in `compiler/src/test/kotlin/velle/ClosureTest.kt`:

```kotlin
val low  = NewCondition(block = 1, value = BigDecimal("0.1"))
val high = NewCondition(block = 2, value = BigDecimal("1.0"))

NewExperimentDesign(
    containerType = plateType,
    containerCount = 1,
    replicates = 1,
    conditions = listOf(low, high),
    containers = listOf(
        NewContainer(replicate = 1, position = 1, wells = listOf(
            NewWell(position = 1, condition = ConditionReference.New(low)),
            NewWell(position = 2, condition = ConditionReference.New(high)),
        )),
    ),
)
```

Identity is what matters, not equality. Two separately built `NewCondition` objects with equal fields are two records, and `copy()` on one makes a second record.

## The commit function

Each act gets one overload of `commit`, taking the input and, for an act declared `by`, the actor ([Authorization](Authorization)). It returns a `Commit` value carrying the input: `Accepted` with a reference to the record the act became, or `Refused` with a reason, and with the `never` that refused it as data when there was one ([§21](Reference-21-never)). For `act PeptideBatch` the overload is declared as `commit(input: NewPeptideBatch): Commit<NewPeptideBatch, Ref<PeptideBatch>>`.

```kotlin
when (val r = sys.commit(batch)) {
    is Commit.Accepted -> r.ref          // a Ref<PeptideBatch>: keep it, or sys.read(r.ref)
    is Commit.Refused -> r.reason
}
```

A transient act ([§4](Reference-04-State-and-commits), "Transient acts") leaves no record, so its overload returns `Commit<NewSignUp, Nothing?>` and `r.ref` is null. What the act's rules made is found through the queries, as `sys.customers().last()` in `examples/billing/output/src/main/kotlin/velle/app/BillingApp.kt`.

Before the runtime is entered, the function walks the input once. Every value in a creating position, a part or a `with`-named field's `New`, gets a token by its identity, and every reference to a sibling is resolved to that token. Three things are refused here, as a `Refused` whose reason begins with `type:` and carries no `never`:

- a reference to a value that is in no creating position;
- a value in two creating positions, which would be one value creating two records;
- two new records pointing at each other, since no record could then be created first.

The runtime then creates every record in an order where each reference points at a record already created, as one transaction, and the door check and the rules see all of them at once ([evaluation](evaluation), "Closure commits").

## Reading a record

A reference is the durable name of one record: its shape and the key your database gave the row. Every `Ref<T>` carries both as `shape` and `key`, and so does every model object, since a model object is a reference to its own record. The pair is what you store, log, or put on the wire. Nothing else the generated code hands you survives a restart.

The engineer's transport code receives "show me order 42" and has to turn the number into an `Order`. Later it receives a comment's `target`, which is a `Ref<Content>`, and has to find out whether that is a post or a photo. From `examples/orders/orders.velle` and `examples/gallery/gallery.velle`:

```kotlin
val order: Order = sys.read.order(42)          // by the store's key, when the shape is known
val again: Order = sys.read(order.customer)    // by a reference held from an earlier read

val comment: Comment = sys.read.comment(7)
comment.target.shape                            // "Post" or "Photo", with no read
when (val c = sys.read(comment.target)) {       // the model object of the concrete shape
    is Post -> c.body
    is Photo -> c.caption
}
```

- `read.order(key)` exists once per shape and takes the key as your database holds it, either a `StoreKey` or a `Long`. It throws when no such row exists.
- `read(ref)` takes a `Ref<T>` the system handed you, such as a model object or one of its fields, and returns the `T`. It throws when the record has been deleted since the reference was taken. A reference built by hand from a shape name and a key does not compile here; a key goes through `read.order(key)`.
- A reference typed by a union ([§9](Reference-09-Composing-refinements)) always names one operand in `shape`, never the union. Reading it returns the operand's model object, so a `when` over the operands' classes covers every case.
- Two references are equal when their shape and key are equal, whichever class carries them. Whether two reads of one record returned the same fields is a comparison you make yourself.
- A field of an [extension type](Extension-types) is that type's own class on every surface: `price: Money` in the spec is `price: Money` on the input class, the model object, and the typed store's row, and a query compares it with `Money.of("10.00")`.

A generated system also holds a numeric handle for every record it has touched. The handle is private to one running system and never reaches the engineer: it is not the store's key, two running copies of the same system give one row different handles, and a restart forgets them all. If a `Long` shows up in the generated code, it is one of these, and nothing on the surface takes or returns it.

## The raw surface

The runtime's own `commit(shape, fields, parts)` takes maps instead of classes, and the generated specs use it. The generated functions that build model objects from handles are private to the generated class; `read` is the only way to get a model object. A generated system extends `GeneratedSystem`, and its readers extend `ReadSurface` and `ViewerReadSurface`; those hold what every spec's surface does the same way, and nothing on them beyond what this page names is for the engineer to call. A nested value is a map carrying `"@shape"` when the field's type is a union; a value others point at carries `"@new"` with a number, and the pointing field holds `NewRef(number)`. The generated classes produce these maps, so hand-written code rarely needs them.

## Related pages

- [§6 Relationships](Reference-06-Relationships), for the Velle side of parts and references.
- [§22 Open / unresolved](Reference-22-Open-unresolved), "External input", for what the committer supplies and what Velle fills.
- [Authorization](Authorization), for the actor the commit function takes.
- [Store API](Store-API), for where a commit lands and where a read is answered from.
