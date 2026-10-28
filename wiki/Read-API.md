# Read API

The read API is the Kotlin the compiler generates from a spec for reading records back: a reference that names one record for good, a read of that record by its reference or by your database's key, a typed query over each shape, and a rule for what a read keeps in memory and for how long. After reading this page an engineer can turn a key from a request into a record, follow a reference to the record it names, filter a shape's records, and say what a read returns after their own database changed a row. Committing an act is the [Act API](Act-API).

An endpoint has to answer a request for one record by the number the client holds, follow a reference a client was handed earlier without knowing in advance which shape it names, or list this week's invoices with their customers. Each is a read, and each goes to the store.

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

## Members in a query

A typed query is built from the shape's members. From `examples/billing/billing.velle`:

```kotlin
billing.read.invoices.where { due.dayOfWeek gte 6 }.orderBy { due.ascending }.list()
```

In a query, a text member offers `lowercase`, `uppercase`, `trim`, and `length` (`where { title.length gt 10 }`), `matches` with a regular expression, and `isEmpty()` / `isNotEmpty()` for an absent or blank text; a `uuid` member equality only; and a `duration`, `time`, or `bytes` member the ordered comparisons. Every comparable member offers `oneOf(a, b)` for `in` and `between(lo, hi)` for `between` ([§10](Reference-10-Predicate-expressions)). A `Date` member offers `year`, `month`, `day`, and `dayOfWeek`, a `time` member `hour` and `minute`, and a `DateTime` member all six, each compared as a whole number: `where { due.dayOfWeek gte 6 }`. The `examples/billing/output` query tests use each of these over SQLite.

## What a read remembers

A read goes to the store. The generated system keeps no copy of your records between calls: every `read(...)`, `read.<shape>(key)`, and query starts with nothing in hand, fetches the rows it needs from the store, and forgets them when the next read starts. Knowing this tells you what a read returns after your own code, or your database, has changed a row behind Velle's back.

A controller commits an act and then reads the record back to answer the request. The database has a trigger that fills in a column on that row when the commit lands. The engineer wants to know whether the read shows the trigger's value or the value Velle computed. From `examples/billing/billing.velle`, inside one `api.scope { }` ([Serving a system](Serving-a-system)):

```kotlin
val result = billing.commit(NewSignUp("Ada", "ada@x.com"))
val ref = (result as Commit.Accepted).ref
val customer = billing.read(ref)              // fetched from the store now, trigger's value included
val same = billing.read(ref)                  // fetched again: nothing was kept from the line above
val invoices = billing.read.invoices.orderBy { due.ascending }.list()   // one fetch per invoice, one per distinct customer they reach
```

- `billing.commit` runs the transaction and writes the commit to the store. What it built in memory is dropped as it returns.
- `billing.read(ref)` fetches the row from the store, so a column your trigger changed reads as the trigger left it, provided the spec declares that column as a stored field. A column the spec does not name is invisible to Velle.
- The second `read(ref)` fetches the row again. Two reads in a row cost two fetches; the generated system does not remember the first.
- Inside one read, rows are fetched once. A query over many invoices reads each customer those invoices reach one time, however many invoices share it. The memory lasts for that one read and no longer.
- Over a store that gives snapshots, every read inside one `api.scope { }` or one `scope { }` on the system sees the same state, even though each fetches afresh; a commit from elsewhere shows up on the next scope ([Store API](Store-API), "The unit of work").

A row your trigger changes is a write Velle did not make. If the spec reads that field, the rules and prohibitions of the commit evaluated the value before the trigger ran, and the next commit sees a change no act caused ([Store API](Store-API), "What a store must do"). Keep such columns out of the spec, or put the calculation in a derived property ([§7](Reference-07-Derived-properties)) where the spec can see it.

The compiler checks nothing here. The behavior is pinned by `compiler/src/test/kotlin/velle/WorkingSetTest.kt`, which runs the generic surface over an in-memory store that counts every fetch and lets the test change a row beside the runtime, as a trigger would.

## The raw surface

The runtime's own `commit(shape, fields, parts)` takes maps instead of classes, and the generated specs use it. A call on it made outside any `scope { }` is a read of its own: it fetches from the store, answers, and holds nothing afterwards, so a `get` right after a `commit` fetches from the store like a generated read does, and two such calls are two reads (`compiler/src/test/kotlin/velle/WorkingSetTest.kt`). Wrap several in `scope { }` to make them one. The generated functions that build model objects from handles are private to the generated class; `read` is the only way to get a model object. A generated system extends `GeneratedSystem`, and its readers extend `ReadSurface` and `ViewerReadSurface`; those hold what every spec's surface does the same way, and nothing on them beyond what this page names is for the engineer to call. A nested value is a map carrying `"@shape"` when the field's type is a union; a value others point at carries `"@new"` with a number, and the pointing field holds `NewRef(number)`. The generated classes produce these maps, so hand-written code rarely needs them.

## Related pages

- [Act API](Act-API), for committing the act whose record a read returns.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for the comparisons a query's `where` is built from.
- [Store API](Store-API), for where a read is answered from, and the unit of work a read runs in.
- [Concurrency](Concurrency), for what a read sees beside other requests.
