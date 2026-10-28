# Act API

The act API is the Kotlin the compiler generates from a spec for committing an act: a class for each act's input, nested values for the records that arrive with it, the reference types a field takes, one commit function, and one function per schedule that runs its rules now. After reading this page an engineer can build an act's input, including records that arrive with it, commit it, read the outcome, and tick a schedule. Reading records back is the [Read API](Read-API).

An engineer's transport code, whatever it is, maps a request onto one input object and calls one commit function. Velle owns the shape of that object and the function. Everything before the call, such as authentication or rate limits, is the engineer's code ([§22](Reference-22-Open-unresolved), "External input"). An application that wants every act and shape served over HTTP without writing that mapping declares the system as a Spring Boot bean instead ([Serving a system](Serving-a-system)); the JSON an act takes there mirrors the input class this page describes.

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

A `uuid` field is a `java.util.UUID`, a `duration` field a `java.time.Duration`, a `time` field a `java.time.LocalTime`, and a `bytes` field a `ByteArray` on the input class and the model object ([§5](Reference-05-Scalars)); a `uuid` with `initially randomUUID` is not an input parameter.

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

Each act gets one overload of `commit`, taking the input and, for an act declared `by`, the actor ([Authorization](Authorization)). It returns a `Commit` value carrying the input: `Accepted` with a reference to the record the act became, or `Refused` with a reason, and with the `never` that refused it as data when there was one ([§21](Reference-21-never)). An `Accepted` also carries what the act's `after commit` rules came to ([§18](Reference-18-Run-once-guards)): `afterCommitRejections`, the firings a `never` refused, and `afterCommitFailures`, the firings that failed for any other reason, each naming the rule and the record it ran for. Both are usually empty; the act stands either way. For `act PeptideBatch` the overload is declared as `commit(input: NewPeptideBatch): Commit<NewPeptideBatch, Ref<PeptideBatch>>`.

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

## Ticking a schedule

Each schedule the spec names gets one function, `tickWeekly()` for `Weekly`, that runs every rule on the schedule now ([§17](Reference-17-Schedule-triggers)). It returns a `TickReport`: the schedule, the moment, how many firings of each rule landed, and the firings that failed for a reason other than a `never`, as `FiringFailure` values naming the rule, the record it ran for, and the cause. When a `never` refused a firing, the function throws `TickRejected` instead, carrying each refusal as data and the same report ([§21](Reference-21-never)). The other firings stood, and the next tick retries the refused and failed ones.

```kotlin
val report = sys.tickWeekly()
report.firings            // {"RemindOverdue": 3}
report.failures           // empty unless the store or the runtime failed a firing
```

To hear every tick and every act whose `after commit` firings were refused or failed without being the caller, register a `TickListener` on the system, `sys.system.listen(listener)`; it is told once the work is on record ([Serving a system, "Hearing a tick"](Serving-a-system#hearing-a-tick)).

## Related pages

- [Read API](Read-API), for reading back the record a commit made, and what a read remembers.
- [§6 Relationships](Reference-06-Relationships), for the Velle side of parts and references.
- [§22 Open / unresolved](Reference-22-Open-unresolved), "External input", for what the committer supplies and what Velle fills.
- [Authorization](Authorization), for the actor the commit function takes.
- [Store API](Store-API), for where a commit lands.
