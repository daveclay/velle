# Store API

The store API is the contract between a running Velle system and the database that holds its records. Velle keeps no records of its own. Every record lives in a store the engineer owns, the runtime reads records from it when a commit or a read needs them, and the runtime hands each commit's writes back to it. After this page you can connect a system to a store, write a store of your own, and tell apart the part of the contract a store must get right from the part it may use to make reads faster.

The contract has two halves. The read half is a small set of questions the runtime asks: one row by its key, all rows of a shape, the rows that reference one record, and the values remembered for a refinement membership. The write half is one call per commit carrying everything the commit changed. Every decision about what a record means is made inside Velle, in memory, by the evaluator. A store never decides whether a predicate holds. It only hands rows over and writes rows back.

## The situation

The billing team's invoices live in a database the operations team runs, with backups, reporting, and migrations the operations team already knows. The spec in `examples/billing/billing.velle` says nothing about tables or SQL and never will. The engineers want the generated system to read and write that database, and they want the nightly reminder sweep to fetch only the overdue invoices rather than every invoice ever issued.

## Connecting a system to a store

Velle ships one generic store over SQLite in `stores/sqlite/`. It implements the contract once for every spec by reading the spec's model, so nothing is written per system. From `examples/billing/output/src/test/kotlin/velle/GeneratedApiOverSqliteTest.kt`:

```kotlin
val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
val sys = BillingSystem().also { SqliteStore.attach(it.system, conn) }
```

- `BillingSystem()` is the generated system. Without a store it keeps every record in memory, which is how the generated tests run.
- `SqliteStore.attach` creates one table per shape in the connection, then connects the system to the store for both halves of the contract.

From then on every commit through the [Binding API](Binding-API) lands in the database, and every read through the query API is answered from it. The same system runs in memory for tests and over the store in production, and the spec does not change.

## What the runtime asks and what it hands back

A store implements two Kotlin interfaces from `compiler/src/main/kotlin/velle/Hydration.kt`. The read half:

```kotlin
interface StateResolver {
    fun fetchByKey(shape: String, key: StoreKey): Row?
    fun fetchAll(shape: String): List<Row>
    fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row>
    fun fetchCandidates(shape: String, filter: QF): List<Row> = fetchAll(shape)
    fun fetchCaptures(instance: Ref.Persisted, refinement: String): Map<String, Any?>?
}
```

- `fetchByKey` returns one row by the key the store gave it, or nothing when no such row exists. The runtime calls it to follow a `one` field ([§6](Reference-06-Relationships)) and to answer `read.invoice(key)`.
- `fetchAll` returns every row of a shape. The runtime calls it to find the members of a refinement ([§8](Reference-08-Refinements)), to run a sweep ([§16](Reference-16-Sweeps)), and to check a `never` ([§21](Reference-21-never)).
- `fetchReferencing` returns the rows of a shape whose `one` field points at a given record. The runtime calls it to read an inferred inverse, such as a customer's invoices.
- `fetchCandidates` returns the rows of a shape that may satisfy a filter. It has a default, which returns every row, and the next section is about it.
- `fetchCaptures` returns the values a refinement remembered for one record when the record entered it ([§13](Reference-13-Exit-triggers)), or nothing when the record is not a member now.

The write half is one function:

```kotlin
fun interface CommitCallback {
    fun onCommit(commit: CommitSet): List<StoreKey>
}
```

`onCommit` receives everything one commit did: the rows it created in creation order, the fields it assigned on rows the store already holds, the refinement values it captured, the memberships it ended, and the rows it deleted. It writes all of that and returns one key per created row, in the same order. A reference inside a created row to another row created in the same commit points at an earlier entry in the list, so a store that inserts in order always has the key it needs.

A row crosses the boundary as a `Row`: the shape, the store's key, and a map from field name to a plain Kotlin value. Text is a `String`, every number a `BigDecimal`, a `Date` a `LocalDate`, a `DateTime` an `Instant`, a `boolean` a `Boolean`, a `uuid` a `UUID`, a `duration` a `Duration`, a `time` a `LocalTime`, `bytes` a `ByteArray`. A `one` field is a `Ref.Persisted` carrying the target's shape and key, and a `many` field is a list in membership order. A field of an [extension type](Extension-types) is that type's own class, and the store's binding for the type says how the column holds it. Derived properties ([§7](Reference-07-Derived-properties)) never appear in a row. They are recomputed from the stored fields on every read.

A system is connected with `system.connect(resolver, callback)`, which is what `SqliteStore.attach` calls after creating the schema. One object may implement both halves, as the SQLite store does, or two objects may split them.

## What a store must do

These are the parts of the contract where a mistake changes what the spec means. Nothing in Velle can detect a breach of them. The spec still validates, the generated tests still pass in memory, and the running system quietly stops doing what the spec proved it would do.

- **Keyed reads and join reads are exact.** `fetchByKey` returns the row or nothing. `fetchAll` returns every row of the shape. `fetchReferencing` returns every row whose field points at the target and no other. A missing row here is a record the spec cannot see, so a rule does not fire, a prohibition does not hold, and a run-once guard ([§18](Reference-18-Run-once-guards)) that reads "no such record yet" fires twice.
- **Capture reads are exact.** The values a refinement remembered are state the spec's exit rules depend on. A store facing a spec with captures decides how to keep them, as a table per refinement or as columns on the base table, and the generated typed store has one method per refinement that remembers values, returning a class with one property per value. There is no default for this read: a store for a spec with no captures returns nothing from it.
- **A commit lands whole or not at all.** Everything in one commit set becomes durable together. If the database throws, the callback throws, and the whole commit rolls back with nothing written. The callback runs inside the commit, so throwing from it is the rollback signal. The SQLite store in `stores/sqlite/src/main/kotlin/velle/sqlite/SqliteStore.kt` turns off auto-commit, writes every entry, commits, and rolls back on any exception.
- **Keys are the store's and are never reused.** The store assigns every key and reports it back from `onCommit`. It must return the same representation it accepts, since the runtime maps keys to records by equality. A deleted row's key must never be given to a later row. A surviving record may still hold a reference to a deleted one, and that reference reads as absent only while the dead key stays dead. The SQLite store declares its keys `INTEGER PRIMARY KEY AUTOINCREMENT` for this reason.
- **Every read inside one commit sees one moment.** All questions the runtime asks while processing one commit are answered from the same consistent state. The runtime never asks the same question twice within a commit, and it reads the commit's own in-progress writes from memory, so the store never serves mid-commit state. One database transaction opened at the start of the commit and used for every read delivers this.
- **Commits that touch the same records take turns.** Two commits over different customers may run in parallel. Two over the same account must run one after the other, and that includes the records each one only reads to decide what to do. Snapshot isolation alone does not deliver this. A `SERIALIZABLE` database transaction per commit does, and so does a lock on the record the commit revolves around. The generated commit function's documentation names that record.
- **An accepted commit stays.** Once the runtime reports a commit accepted, every later read reflects it. Velle's memory of what already happened is data in the store: the row that says a deposit was applied is the only thing that stops the next sweep from applying it again.
- **No writes around the side.** Every change to the tables behind a Velle shape enters as a commit through the generated surface. A hand-run `UPDATE`, a migration script, or another service writing the same table changes data with no rules fired and no prohibitions checked. Reading those tables with your own SQL is always safe. Your own tables beside them are none of the contract's business.
- **Every stored extension type has a binding.** A store holding a field of an [extension type](Extension-types) needs the piece that says how its column holds the value. The SQLite store refuses to start without one.

The five guarantees about one moment, taking turns, landing whole, staying, and no side writes are stated one clause each in [`evaluation.md`, "The universal transaction"](evaluation#the-universal-transaction), with what each one protects. One `SERIALIZABLE` database transaction per commit delivers all five, and that is the right default until measurement says otherwise.

## What a store can do: narrowing a read

Everything above is about correctness. `fetchCandidates` is the one call that is about speed, and its rule is simple. The store may return any set of rows that includes every row the filter would select. Returning more is always correct. Returning fewer is a wrong answer.

The reason is where the decision is made. When a refinement's condition, a sweep, or a query needs the members of a shape, the compiler translates the condition into a filter and hands it to `fetchCandidates`. The store returns candidates. The evaluator then runs the real condition on every candidate, in memory, and keeps only the rows that satisfy it. The evaluator's answer is the only answer. A store that returns every row makes the evaluator do all the work, which is the default. A store that narrows the rows makes the evaluator do less. Neither changes what the spec means.

The filter is a tree of these forms from `compiler/src/main/kotlin/velle/Query.kt`:

- `True`, `False`, `And`, `Or`, `Not`.
- `Cmp`: one column of the subject row compared to a constant with `==`, `!=`, `<`, `<=`, `>`, `>=`, or a text operator from [§10](Reference-10-Predicate-expressions): `contains`, `starts with`, `ends with`, `like`, `matches`. The constant is always on the right.
- `NullCheck`: `is none` or `is some` on an optional column.
- `TextBlank`: `is empty` on a text column, which is absent or nothing but whitespace.
- `Exists`: some row of another shape references the subject row and satisfies an inner filter.
- `RelPred`: the subject row's `one` field points at a row satisfying an inner filter.

A constant is a number, a text, a boolean, a date, a date and time, a uuid, a duration, a time, bytes, a reference to a record the store holds, or a value of an extension type.

Two rules keep a translation correct:

- **A part the store cannot translate becomes "every row" for that part.** Under a `Not` the roles flip, so an untranslatable part becomes "no row" there, which after the negation is again every row. The SQLite store writes the constant `1` for such a part in positive position and `0` under an odd number of negations. A store that follows this rule can translate as much or as little of the tree as it likes and stay correct.
- **An absent column satisfies `!=` and fails `==` and every ordered comparison.** This is what the evaluator does with an absent value, and a translation to SQL must do the same: `col IS NULL OR col != ?` for `!=`, and plain `col = ?` for `==`.

Some translations are looser than the evaluator on purpose, and that is fine. SQLite's `LIKE` ignores ASCII case, so the SQLite store's translation of `contains` returns rows the case-sensitive evaluator then drops. A column that holds a decimal as text cannot be ordered by SQL, so the SQLite store translates `<` on such a column to "every row". The only mistake is a translation that is tighter than the evaluator: one that drops a row the condition accepts. The evaluator never sees that row, and the spec's answer is wrong.

A store may also give the database a function it lacks, as the SQLite store does for `matches`: SQLite has no regular expressions, so the store registers a `REGEXP` function on the connection when it is built. When the connection refuses the registration, the store translates `matches` to "every row" and the evaluator does the matching. The spec behaves identically either way.

## Tuning one question with hand-written SQL

The generic store translates whatever filter it is given. For a question that matters, an engineer may write the SQL by hand. The compiler generates, per spec, a typed store interface with one method per condition the spec can ask, with the condition's constants as typed parameters. From `examples/billing/output/src/main/kotlin/velle/generated/BillingStore.kt`:

```kotlin
interface BillingStore {
    fun invoice(key: StoreKey): InvoiceRow?
    fun allInvoices(): List<InvoiceRow>
    fun invoicesByCustomer(target: Ref.Persisted): List<InvoiceRow>
    /** Serves rule RemindOverdue — `ActionableOverdue where not (exists (Reminder where (invoice == this) and (sentOn > (today - 7 days))))`.
     *  Superset contract: any superset of the matching rows is correct. */
    fun remindOverdueCandidates(dueBefore: LocalDate, sentOnAfter: LocalDate): List<InvoiceRow>
    // ...
}
```

A generated class, `BillingStoreOverGeneric`, implements every method over any generic store, so the engineer overrides only the methods worth tuning. From `examples/billing/output/src/test/kotlin/velle/TypedStoreTest.kt`:

```kotlin
class TunedStore(backend: SqliteStore, model: Model, private val conn: Connection) :
    BillingStoreOverGeneric(backend, model) {

    override fun remindOverdueCandidates(dueBefore: LocalDate, sentOnAfter: LocalDate): List<InvoiceRow> {
        val sql = """
            SELECT i.* FROM "Invoice" i
            WHERE i."due" < ?
              AND NOT EXISTS (SELECT 1 FROM "Reminder" r WHERE r."invoice" = i.id AND r."sentOn" > ?)
        """
        // bind the two dates, run the query, map each result to an InvoiceRow
    }
}
```

- `BillingStoreOverGeneric(backend, model)` answers every question through the generic store.
- The override receives the condition's constants already folded: `today` and `today - 7 days` arrive as two dates. No filter tree is in sight.
- The method is under the same rule as `fetchCandidates`. Any superset of the matching invoices is correct, and the evaluator still runs the rule's condition on each one.

The engineer connects it with the generated bridge, `BillingStoreResolver`, which recognizes each filter the runtime sends as the named question and routes it:

```kotlin
val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
val store = TunedStore(backend, sys.system.model, conn)
sys.system.connect(BillingStoreResolver(store, sys.system.model), backend)
```

Each typed row class, such as `InvoiceRow`, has every stored field as a Kotlin property of the field's type, and `toInvoiceRow()` and `toRow()` convert between it and the generic `Row`.

## Writing a store for another database

A store for another database implements `StateResolver` and `CommitCallback` in that database's own vocabulary. In order of what has to be right:

1. **One table, document, or collection per shape, keyed by something the store assigns and never reuses.** A `one` field holds the target's key and, when the field's type is a union of shapes ([§9](Reference-09-Composing-refinements)), the target's shape beside it, since keys are unique only within one shape.
2. **The exact reads**: by key, all of a shape, by referencing field, and captures.
3. **The commit**: one database transaction per `onCommit`, every entry written, keys returned in creation order, rollback on any exception. Deletions apply after creations and assignments.
4. **The transaction guarantees**: one snapshot per commit, serializable commits over the same records, durable once accepted, no writes around the side.
5. **A binding per extension type** the spec stores, in the store's own vocabulary. A column type means something to a database and nothing to a wire protocol, so a store over a binary protocol would ask for an encoding instead.

Only then, and only where measurement asks for it:

6. **A translation of the filter** in `fetchCandidates`, as much of the tree as the database can answer, with every untranslatable part becoming "every row" by the rule above.
7. **Hand-tuned SQL** for named questions through the generated typed store.

The in-memory runtime satisfies every guarantee trivially, which is why a spec runs and its generated tests pass before any store exists.

## What the compiler checks

- **Refuses to start** a store that holds a field of an extension type it has no binding for, or whose binding does not read the type's own sample values back unchanged ([checks.md, E4](checks#e4--a-store-holds-every-type-it-stores)).
- **Nothing else.** The exact reads, the whole commit, the one snapshot, the taking of turns, the permanence, and the absence of side writes are promises the store makes across processes Velle never sees. The validator's proofs rest on them and cannot test them.

## Related pages

- [`evaluation.md`, "The universal transaction"](evaluation#the-universal-transaction), the five guarantees stated one clause each, and what each one protects.
- [Binding API](Binding-API), the generated Kotlin that commits through the store and reads from it.
- [Extension types](Extension-types), "Holding a type in a store", the binding a store needs per type.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), the conditions the filter is compiled from.
- [§13 Exit triggers](Reference-13-Exit-triggers), the captured values a store keeps per membership.
- [§22 Open / unresolved](Reference-22-Open-unresolved), the read API and the architecture the store sits in.
