# OQ153 — GraphQL computes a whole record for every reference, whatever the query selects

**Status:** opened 2026-11-19 from Dave's report that the console's table of the pharmacy's `Interaction` records took 1.25 seconds. Causes C1 and C2 fixed the same day. D1 decided and built on 2026-11-20 (commit `2e16657`). The design for cause C4 was written the same day: D2 and D3 decided and built 2026-11-20, not committed. D4, D5, D6, and D7 decided 2026-11-20, not built; the build in §4 awaits Dave's word, and §4 holds the work items.

**In plain terms:** when a page asks GraphQL for a list of records and only the key of each record they point at, GraphQL works out everything about each pointed-at record anyway, and throws most of it away. On a shape whose records are costly to work out, a table of twenty rows takes over a second.

**See:** `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`, `referencedRecord` and `referencedRecords` (a reference is answered by reading its record) · `apis/json/src/main/kotlin/velle/api/Wire.kt`, `record` (every member of a record is evaluated) · `examples/pharmacy/pharmacy.velle`, acts `Interaction` and `Drug` · `console/src/graphql/documents.ts`, `pageDocument` (the query the console's table sends) · `examples/pharmacy/app/src/test/kotlin/velle/pharmacy/PharmacyStatementsTest.kt` (the SQL statements a read sends, counted) · `compiler/src/test/kotlin/velle/WorkingSetTest.kt` (how long the runtime keeps a row it read) · `wiki/Normative/evaluation.md`, "A read is a read-only snapshot" · OQ152 in `working-docs/QUESTIONS.md`, whose finding G2 is the same fault seen at a collection.

---

## 1. What ran when this was opened

The query the console's table sends for the pharmacy's `Interaction` shape, built by `pageDocument` in `console/src/graphql/documents.ts`:

```
query Page($where: String, $orderBy: [String!]!, $first: Int, $after: String) {
  page: interactions(where: $where, orderBy: $orderBy, first: $first, after: $after) {
    items { shape key drugA { shape key } drugB { shape key } severity }
    next
  }
}
```

- A list answers each item through `Wire.record`, which evaluates every member of the shape and every refinement flag, whatever the query selects.
- A reference field, `drugA` here, is answered by `referencedRecord`, which calls `api.read` on the target and so `Wire.record` on it. That happens even when the query selects only `shape` and `key`, which the reference already holds.
- A collection of references is answered the same way by `referencedRecords`, one whole record per reference.
- A `Drug` is costly to evaluate. In `examples/pharmacy/pharmacy.velle` it carries `onHand` (a sum over its stock lots), `unitPrice` (the latest price change), `interactions` (a filter over every `Interaction`), and the refinement flags `Controlled`, `ScheduleTwo`, and `LowStock`.
- Over REST a reference is only its shape and key, so the same list reads no `Drug` at all.

Timings taken on 2026-11-19 against Dave's running pharmacy application, acting as `Staff:1`, 20 `Interaction` records. They are session measurements and no test pins them. What is pinned is the count of SQL statements, in §2.

| What the query selects for each interaction | Time |
|---|---|
| `key severity` | 0.04 seconds |
| the same and `drugA { key }` | 0.6 seconds |
| the same and `drugB { key }` as well | 1.2 seconds |
| the same list over REST | 0.03 seconds |
| one `Drug` read by key | about 0.035 seconds |

So the table pays for 40 whole `Drug` records to show 40 keys. The console read REST before 2026-11-19 and reads GraphQL since, so this table became slower with that change.

## 2. What was found and built

The time is round trips to the store. Over in-memory SQLite one `Drug` is read in under a millisecond; over Dave's PostgreSQL each SQL statement is a round trip, and the read sends many. `PharmacyStatementsTest` counts the statements at the DataSource.

Roster of labels in this file: C1 to C4 are the four causes below. D1 to D7 are the decisions in §3. P1 to P4 are the four statement patterns behind C4, listed in §3 under "What cause C4 is made of".

- **C1, fixed.** GraphQL evaluated every member of a record whatever the query selected. Now each record fetcher in `VelleGraphQL.kt` hands the API the fields the query selects (`selectedMembers`), and `Wire.record` evaluates only those, with the record's shape and key. `VelleApi.query`, `read`, and `readEach` take the same optional `selected`. REST passes none and answers whole records as before. Pinned by `PharmacyStatementsTest`, "the console's table of interactions reads no drug's own members to show its key", which failed before the change on statements over `StockLot`, `Fill`, `PriceChange`, `PurchaseOrder`, and `Prescription`, and by `VelleApiTest` (`billingFlow`, the lines on `selected`).
- **C2, fixed.** Reading one record for a viewer was three read entries: `currentShapeOf`, `exists`, `visibleCurrent`. Until D1 the runtime dropped the rows it had read at each entry, so the record's row was fetched three times. `VelleSystem.existsFor` is the three checks as one entry, used by `VelleApi.read` and `readEach` and by the generated viewer read (`Binding.kt`, `shown`). Pinned by `WorkingSetTest`, "a viewer's fetch by identity is one read entry, so the row is fetched once".
- **C3, fixed by D1.** Each reference GraphQL followed was a read of its own with a snapshot of its own: per reference, the actor's row, the drug's row, and the drug's ingredients again, though the list had just read the same drug. Now every read of one `scope { }` is answered from one snapshot (`VelleSystem.openReadSnapshot`), and one GraphQL request runs in one scope (`VelleGraphQL.configure`, which `apis/spring` calls). Pinned by `WorkingSetTest`, "a scope's reads are one snapshot, so the next read in the scope does not fetch the row again", which failed before the change, and by `PharmacyStatementsTest`, "the console's table of interactions reads no drug's own members to show its key", which bounds the table at two statements plus two for each distinct drug the interactions name. I did not run that second test with the runtime change and without the GraphQL one, so it is not shown to fail on the GraphQL half alone.
- **C4, open, work items in §4.** A whole record is evaluated one related record at a time, a statement each. `PharmacyStatementsTest`, "a list of whole drugs sends a statement for every related record it reads", pins it: more than five statements per drug, and no more than 20 distinct statements among them. Two things add up there. A member such as `onHand` reads each stock lot, and each lot reads each of its fills, one statement per record. And reading a reference member fetches the record it points at to see that it still exists (`VelleSystem.normalizeRead`), so a list of 20 interactions fetches every drug they name though it shows only their keys.

Dave reloaded the table after D1 and said on 2026-11-20 that it is much better and needs no timing. By statement count each reference followed fell from a whole `Drug` read, which a scratch run on 2026-11-19 counted at 76 statements with ninety days of seeded data, to none when the list already read that drug.

## 3. What to decide

### D1. One request is answered from one snapshot · Decided 2026-11-20

Should every read made inside one `scope { }` be answered from one snapshot, each row fetched once, until a commit made in that scope?

Recommendation: yes, and GraphQL runs one request in one scope. Then following a reference reuses the row the list already read, and the table above costs what the list alone costs.

What runs today: a scope is already one unit of work on the store, and `wiki/Normative/evaluation.md` ("A read is a read-only snapshot") says the SQL store makes that unit one read-only snapshot transaction. So on a SQL store the second fetch inside a scope returns the same row it returned the first time. The runtime fetches it again all the same, because each read entry empties what it holds (`WorkingSetTest`, header and "within one read entry a row is fetched once, and the next entry fetches it again").

Costs:

- A read no longer means "as the store is this instant" inside a scope. On a store whose unit of work is not a snapshot, a row changed beside the runtime between two reads of one scope is seen today and would not be seen until the scope ends. `WorkingSetTest` pins today's behaviour on purpose, so this reverses a choice somebody made. (Found while building, 2026-11-20: the in-memory store is not such a store. Its read unit holds a lock, so nothing can write beside an open scope there.)
- Nothing changes in what the compiler proves or what a spec can say. A commit still starts from the store, never from what a scope had read (`WorkingSetTest`, "a commit drops what the scope had read, and a read after it starts over").
- A scope held open for a long time, as a test does, would hold every row it read. A request's scope is short.
- Tried on 2026-11-19 and reverted: with `openReadSnapshot` emptying the rows only at a scope's outermost read, the whole suite failed in one place beyond its baseline, the `WorkingSetTest` test named above. That is a session result, not a checked-in one.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`, `openReadSnapshot`: inside a scope the rows already read are kept; a bare read outside any scope stays a snapshot of its own.
- `compiler/src/test/kotlin/velle/WorkingSetTest.kt`: "the next entry fetches it again" becomes "the next read in the scope does not fetch it again", and the header says a scope is the lifetime.
- `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`: one request's fetchers run inside one `api.scope { }`.
- `examples/pharmacy/app/src/test/kotlin/velle/pharmacy/PharmacyStatementsTest.kt`: the bound for the table drops to the list's own statements.
- `wiki/Normative/evaluation.md`, "A read is a read-only snapshot": a scope's reads are one snapshot in the runtime as well as in the store.

**Decided:** yes (Dave, 2026-11-20). Built the same day as proposed, and `wiki/Guides/Read-API.md` ("A read goes to the store") and `wiki/Guides/API-layer.md` (`configure`) say it as well.

### What cause C4 is made of

The decisions D2 to D4 are about cause C4. These are the statements two reads sent on 2026-11-20, taken from a scratch run of `PharmacyStatementsTest` (36 seed days) with a line added to write them out. They are session measurements. They were taken before D2 and D3 were built; the counts after are under D3.

The list of 35 whole drugs over REST, 571 statements:

| Statements | What each one reads | Pattern |
|---|---|---|
| 84 | the `Verification` records of one fill | P1 |
| 83 | the `ReturnToStock` records of one fill | P1 |
| 43 | the `Fill` records of one stock lot | P1 |
| 43 | the `StockAdjustment` records of one stock lot | P1 |
| 35 each, seven kinds | the `StockLot`, `PriceChange`, `PriceWithdrawal`, `PurchaseOrder`, `Prescription`, `ControlledLogEntry`, and `ControlledInventorySnapshot` records of one drug | P1 |
| 35 | one `Ingredient` by its key | P2 |
| 35 | the `ingredients` of one drug, from the table that holds that `many` field | P3 |
| 3 | the list of drugs, the list of interactions, the actor | |

The console's table of 20 interactions naming 24 distinct drugs, 50 statements:

| Statements | What each one reads | Pattern |
|---|---|---|
| 24 | one `Drug` by its key, only to see that it still exists | P4, P2 |
| 24 | the `ingredients` of that drug | P3 |
| 2 | the list of interactions, the actor | |

The four patterns:

- **P1, the records pointing at one record.** A member such as `onHand` on `Drug` in `examples/pharmacy/pharmacy.velle` reads the drug's stock lots, and the runtime asks the store for them one drug at a time (`StateResolver.fetchReferencing`, called from `VelleSystem.ensureReferencing`). The contract has no way to ask for the lots of 35 drugs at once.
- **P2, one record by key.** Following a reference fetches its record alone (`StateResolver.fetchByKey`, called from `VelleSystem.instance`). The contract has no way to ask for several keys at once.
- **P3, a `many` field of one row.** The SQL store reads the table behind a `many` field once per row, even for rows that came back from one statement (`stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `readRow`). This is inside the store and needs no change to the contract. It is a work item in §4, not a decision.
- **P4, checking that a referenced record still exists.** Reading a reference member fetches the record it points at, because a reference to a deleted record reads as absent (`VelleSystem.normalizeRead`). The table above reads 24 drugs for this alone.

None of D2 to D4 changes what a spec means or what any read answers. The runtime may always hold more rows than it was asked for: every condition is checked again in memory on what the store returned (`wiki/Guides/Store-API.md`, "What a store can do: narrowing a read"). So these decisions cost nothing in what the compiler can prove or a product owner can say. Their costs are in the store contract and in memory.

### D2. The store contract gains two fetches that take several records at once · Decided 2026-11-20

Should `StateResolver` gain a fetch of several keys and a fetch of the records pointing at any of several records, each with a default that calls today's one-record fetch in a loop?

Recommendation: yes.

```kotlin
interface StateResolver {
    // today: one row by key
    fun fetchByKey(shape: String, key: StoreKey): Row?
    // new: the rows of [shape] with these keys, in any order; a key with no row is left out
    fun fetchByKeys(shape: String, keys: List<StoreKey>): List<Row> =
        keys.mapNotNull { key -> fetchByKey(shape, key) }

    // today: the rows of [shape] whose [field] points at [target]
    fun fetchReferencing(shape: String, field: String, target: Ref.Persisted): List<Row>
    // new: the rows of [shape] whose [field] points at any of [targets]
    fun fetchReferencingAny(shape: String, field: String, targets: List<Ref.Persisted>): List<Row> =
        targets.flatMap { target -> fetchReferencing(shape, field, target) }
}
```

Costs:

- The contract grows by two calls. Both are exact reads, under the same rule as the calls they extend: a missing row is a record the spec cannot see (`wiki/Guides/Store-API.md`, "What a store must do").
- No store has to change. A store that leaves the defaults is as correct and as slow as today. This keeps the principle that the store API says nothing about how records are stored: a store over a REST call may have no batched form, and a SQL store answers with `WHERE id IN (...)`.
- A store written by hand that overrides one of the two can get it wrong in a way the one-record call did not, by returning rows for some of the keys only.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Hydration.kt`, `StateResolver`: the two calls above with their defaults.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: both answered by one statement each.
- `compiler/src/main/kotlin/velle/MemoryStore.kt`: both answered under one hold of the read lock.
- `wiki/Guides/Store-API.md`, "What the runtime asks and what it hands back" and "What a store must do": the two calls and their rule.

**Decided:** yes (Dave, 2026-11-20). Built the same day, not committed: the two calls in `StateResolver`, answered in one statement by the SQL store and under one hold of the lock by the in-memory store. Pinned over SQLite and PostgreSQL by `SqlStoreConformance` (`stores/sql/src/testFixtures/kotlin/velle/sql/SqlStoreConformance.kt`), "several keys are read in one statement, and each collection field of the rows in one more" and "the records pointing at any of several records are read in one statement, by key and shape for a union". Nothing calls them until D3 is built. A store generated from a spec is a separate matter, D5.

### D3. The runtime fetches for the records that arrived together, not for one record at a time · Decided 2026-11-20

When the runtime needs the related records of one record, should it fetch them at the same time for every record that came back from the store in the same fetch?

An example from the table above. The list of drugs comes back from one fetch, 35 rows. Evaluating `onHand` for the first drug needs that drug's stock lots. Under this decision the runtime fetches the stock lots of all 35 drugs in one call of `fetchReferencingAny`. Those lots came back together, so the first lot that needs its fills fetches the fills of every lot. The 571 statements become about one per kind of statement, 16 by the table above. That figure is an estimate from the table, not a measurement.

The same holds for following a reference: the references that one fetch's rows hold in the same field are fetched together with `fetchByKeys`.

Recommendation: yes, with a limit on how many records one such fetch asks for (I propose 500), so a fetch for one record out of a very large list does not ask for the whole list's related records.

What happens at the limit (Dave asked, 2026-11-20): the fetch asks for the record that was needed and up to 499 others from the same group, taken in the order they arrived. The rest of the group is left unfetched. When one of those is needed, the next fetch takes it and the next 499 not yet fetched. A list of 1,200 drugs whose members are all evaluated sends three statements for its stock lots, not one and not 1,200. The limit never changes an answer and never leaves a record out; it only sets how much one fetch can bring in that nobody asked for.

The alternative is to work out ahead of time, from the expressions of the members a read selects, which related records it will need, and fetch them before evaluating. It never fetches a record that is not needed. It needs a new analysis in the compiler that must stay in step with the evaluator, and it helps only the reads that go through it.

Costs of the recommendation:

- The runtime can fetch related records that nobody then reads. A list that evaluates `onHand` for its first drug only still fetches the lots of the others, up to the limit. That is time and memory spent, never a wrong answer.
- A scope holds more rows in memory, with D1 keeping them for the scope's life.
- It applies everywhere the evaluator reads, with no analysis: a rule, a `never` check, and a tick's sweep over many records get the same saving as a read does.
- A limit is a number somebody chose. It needs a home where the owner of a system can change it.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`: the runtime remembers which rows one fetch returned; `ensureReferencing` and `instance` ask for the whole group through the two calls of D2, up to the limit.
- `compiler/src/test/kotlin/velle/WorkingSetTest.kt`: tests that the related records of a list are fetched in one call per relation, and that the limit holds.
- `examples/pharmacy/app/src/test/kotlin/velle/pharmacy/PharmacyStatementsTest.kt`: the bound for a list of whole drugs drops from "more than five per drug" to a fixed number that does not grow with the number of drugs.
- `wiki/Guides/Read-API.md`, "A read goes to the store", and `wiki/Guides/Store-API.md`: what the runtime fetches together.

**Decided:** yes, with the limit, and the limit is configurable, probably per shape (Dave, 2026-11-20). Built the same day, not committed. The limit is `VelleSystem.fetchTogether`, a `FetchTogether(limit, limitByShape)` keyed by the shape whose records are being fetched, and `velle.store.fetch-limit` and `velle.store.fetch-limit-by-shape` under `apis/spring`; where it is set is D6. Pinned by four tests in `WorkingSetTest` ("the records pointing at the rows of one fetch are fetched together, for all of them at once" and the three after it) and by `PharmacyStatementsTest`, which now bounds the list of whole drugs at 20 statements and the table of interactions at 6. A scratch run on the day counted 16 and 6, against 571 and 50 before. `wiki/Guides/Store-API.md`, "What the runtime fetches together", says it.

### D4. A reference to a record that no rule can delete is read without fetching that record · Decided 2026-11-20

Should reading a reference member skip the fetch of the record it points at when no rule in the spec deletes records of that shape?

Recommendation: yes. The fetch exists only to make a reference to a deleted record read as absent. The model already knows which shapes any rule deletes (`Model.deletedShapes` in `compiler/src/main/kotlin/velle/Model.kt`). In `examples/pharmacy/pharmacy.velle` only `PriceChange` is deleted, by the `delete priceChange` statement, so a reference to a `Drug` cannot point at a deleted record. With D2 and D3 built, the console's table of interactions sends 6 statements, a session measurement that `PharmacyStatementsTest` pins only as 6 or fewer. Without this fetch it would send about 2.

Costs:

- This leans on the store contract's rule that nothing writes to the store but Velle's commits (`wiki/Normative/evaluation.md`, clause U5). A row removed by hand from a table whose shape no rule deletes is read today as an absent reference. Under this decision the reference is still shown, and reading a member through it fails as a missing record. The contract already calls that removal a breach, so nothing Velle promises changes. What Velle promises and reports after such a removal is OQ154 (`working-docs/QUESTIONS.md`), whose D5 is this decision seen from that side.
- It follows the principle that a design in which a wrong state cannot be represented beats one in which it is checked: for these shapes the dangling reference cannot arise, so the check is dropped, not made faster.
- A reference to a shape that some rule does delete is still fetched, with D2 and D3 making that one call for many references.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`, `normalizeRead`: no fetch for a reference whose shape, and every shape it may be, is outside `Model.deletedShapes`.
- A test in `compiler/src/test/kotlin/velle/WorkingSetTest.kt`: reading a reference to an undeleted shape fetches nothing, and a reference to a deleted record of a deleted shape still reads as absent.
- `examples/pharmacy/app/src/test/kotlin/velle/pharmacy/PharmacyStatementsTest.kt`: the table of interactions sends the list and the actor and nothing else.
- `wiki/Normative/evaluation.md`: where it says a reference to a deleted record reads as absent, that the check is made only for shapes a rule deletes.

**Decided:** yes, 2026-11-20. Dave: "velle works like the db: if you didn't make it a foreign key, the db won't tell you the value no longer points to a real record." He expects an engineer who wants the protection to declare the foreign key in the database. What the runtime reports when something reads through such a reference is OQ154 D2 and D5.

### D5. The store generated from a spec gains the same two questions · Decided 2026-11-20

Should the typed store interface the compiler generates for a spec gain a several-records form of each of its by-key and by-reference questions, each defaulting to its one-record question asked in a loop?

What runs today: the compiler generates a typed interface per spec, such as `BillingStore` in `examples/billing/output/src/main/kotlin/velle/generated/BillingStore.kt`, with `invoice(key)` and `invoicesByCustomer(target)`, so an engineer can answer one question with hand-written SQL (`wiki/Guides/Store-API.md`, "Tuning one question with hand-written SQL"). A system connected through it gets D2's defaults: every answer is right, and nothing is fetched together. A system served by `apis/spring` connects the SQL store directly and is not affected.

Recommendation, revised 2026-11-20 after Dave said the hazard below "is a very real concern to someone implementing their own store": yes, and each question has one method an engineer can override, the several-records one. `invoices(keys)` replaces `invoice(key)` on the interface, and `invoicesByCustomers(targets)` replaces `invoicesByCustomer(target)`. The one-record form stays as a Kotlin extension function, which calls the several-records method with a list of one and which no class can override. There is then no second method to disagree with the first.

The alternative, which keeps both methods: the generated class answers the several-records method by calling the one-record method in a loop. An override of `invoice(key)` is always honoured, and a system using the generated store gains D3's saving only for a question whose several-records method the engineer also writes. The engineer then holds two methods for one question, and keeping them in agreement is theirs.

Costs:

- Without this, D3 saves nothing for a system that uses a generated typed store.
- The hazard: an engineer who has overridden `invoice(key)` with their own SQL must not find the several-records form answering from the generic store behind their back. That would be two answers to one question, and nothing in Velle would detect it. The recommendation removes it by the principle that a wrong state which cannot be represented beats one that is checked.
- Under the recommendation, an engineer who has overridden `invoice(key)` gets a compile error after regenerating and rewrites the SQL from `WHERE id = ?` to `WHERE id IN (...)`. No such override exists in this repo; `BillingStoreOverGeneric` is subclassed nowhere under `examples/`.
- The same hazard is already in the store contract as D2 built it. That is D7.
- Every example's generated store changes, so every example is regenerated.

#### Proposed changes

- `compiler/src/main/kotlin/velle/StoreGen.kt`: on the generated interface, the generic class, and the resolver, each by-key and by-reference method takes several records; the one-record forms are generated as extension functions.
- Every `examples/*/output` regenerated.
- `wiki/Guides/Store-API.md`, "Tuning one question with hand-written SQL": the several-records questions and how an override of the one-record question is honoured.

**Decided:** yes, the revised recommendation: one method per question, the several-records one (Dave, 2026-11-20: "yes for both D5 and D7 for those reasons"). His reason, given for D7 the same day: "an engineer implementing a custom store needs to clearly recognize and understand the what velle is expecting".

### D6. Where the fetch limit is set, and which shape it is set for · Decided 2026-11-20

Is the limit of D3 set on the running system, keyed by the shape whose records are being fetched?

What was built with D3, ahead of this answer:

```kotlin
// on the system, by an application's own code
system.fetchTogether = FetchTogether(limit = 500, limitByShape = mapOf("StockLot" to 2000))
```

```
# under apis/spring, in application.properties
velle.store.fetch-limit=500
velle.store.fetch-limit-by-shape.StockLot=2000
```

- The shape named is the shape of the records being fetched. A limit on `StockLot` caps how many drugs one fetch of stock lots asks for, and how many stock lots one fetch by key asks for.
- A shape name the spec does not have is refused when the limit is set. A limit of 1 fetches one record at a time.

Recommendation: yes, keep it as built.

Costs:

- The limit is a setting of a deployment, outside the spec. That fits: it changes no answer, so it is nothing a product owner and an engineer would discuss.
- The other reading of "per shape" is the shape the records belong to: a limit on `Drug` would cap every fetch made for a group of drugs, whatever is fetched. It is one number per list, where the built form is one number per kind of related record. Changing to it is a small edit.
- No test checks that a value in a properties file reaches the system. The limit itself is pinned by `WorkingSetTest`.

#### Proposed changes

- None if yes, beyond a test in the `apis/spring` tests that `velle.store.fetch-limit` reaches `VelleSystem.fetchTogether`.

**Decided:** yes, keep it as built (Dave, 2026-11-20: "D6: is fine").

### D7. The store contract has one method per question too · Decided 2026-11-20

Should the store contract in `compiler/src/main/kotlin/velle/Hydration.kt` keep only `fetchByKeys` and `fetchReferencingAny` as methods a store implements, with `fetchByKey` and `fetchReferencing` becoming extension functions that call them with a list of one?

What runs today, as D2 built it: the contract has both forms, and the several-records form defaults to the one-record form in a loop.

- A store written from scratch against the contract is safe. It implements `fetchByKey`, and the default `fetchByKeys` calls it.
- A store that extends `SqlStore` (`stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, an `open class`) and overrides only `fetchByKey` is not. `SqlStore.fetchByKeys` answers with its own SQL and never calls the override. This is D5's hazard, one level down, and it is in the tree now.
- A store that implements both forms and has them disagree is wrong, and only a conformance test would say so.

Recommendation: yes, for the same reason as D5.

Costs:

- Every store changes: `MemoryStore`, `SqlStore`, every generated resolver such as `BillingStoreResolver`, and any store an engineer has written. Theirs fails to compile until `fetchByKey` becomes `fetchByKeys`, which is the loud failure wanted here.
- A store's simplest read now takes a list. A store with no way to fetch several at once writes the loop itself.
- `wiki/Guides/Store-API.md` describes the two one-record reads as the contract's own; that text is rewritten.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Hydration.kt`: `fetchByKey` and `fetchReferencing` leave the interface and become extension functions.
- `compiler/src/main/kotlin/velle/MemoryStore.kt`, `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `compiler/src/main/kotlin/velle/StoreGen.kt`: the one-record overrides are removed.
- `SqlStoreConformance` and `WorkingSetTest`: tests that override the one-record form are rewritten.
- `wiki/Guides/Store-API.md`: the contract's reads.

**Decided:** yes, 2026-11-20. Dave: "the store should for the same reasoning: an engineer implementing a custom store needs to clearly recognize and understand the what velle is expecting". He confirmed the same day that the answer covers D5 as well.

## 4. The build

D1, D2, and D3 are built. I built D2 and D3 as each was answered, before D4, D5, and D6 were, and without showing this section first. What remains, in order:

1. D4: `VelleSystem.normalizeRead`, its test in `WorkingSetTest`, the bound in `PharmacyStatementsTest`, `wiki/Normative/evaluation.md`.
2. D7: `Hydration.kt`, `MemoryStore.kt`, `SqlStore.kt`, their tests.
3. D5: `compiler/src/main/kotlin/velle/StoreGen.kt`, every `examples/*/output` regenerated, `wiki/Guides/Store-API.md`.
4. D6: the `apis/spring` test.
4. The full suite, and the settled row in `working-docs/QUESTIONS.md` in the commit that deletes this file.

## 5. Work items

- [ ] Build D4, D5, D6, and D7 on Dave's word (§4).
