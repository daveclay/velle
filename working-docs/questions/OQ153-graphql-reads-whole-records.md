# OQ153 — GraphQL computes a whole record for every reference, whatever the query selects

**Status:** opened 2026-11-19 from Dave's report that the console's table of the pharmacy's `Interaction` records took 1.25 seconds. Causes C1 and C2 fixed the same day. D1 decided and built on 2026-11-20, not committed. Two work items remain (§4).

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

Roster of labels in this file: C1 to C4 are the four causes below. D1 is the one decision in §3.

- **C1, fixed.** GraphQL evaluated every member of a record whatever the query selected. Now each record fetcher in `VelleGraphQL.kt` hands the API the fields the query selects (`selectedMembers`), and `Wire.record` evaluates only those, with the record's shape and key. `VelleApi.query`, `read`, and `readEach` take the same optional `selected`. REST passes none and answers whole records as before. Pinned by `PharmacyStatementsTest`, "the console's table of interactions reads no drug's own members to show its key", which failed before the change on statements over `StockLot`, `Fill`, `PriceChange`, `PurchaseOrder`, and `Prescription`, and by `VelleApiTest` (`billingFlow`, the lines on `selected`).
- **C2, fixed.** Reading one record for a viewer was three read entries: `currentShapeOf`, `exists`, `visibleCurrent`. Until D1 the runtime dropped the rows it had read at each entry, so the record's row was fetched three times. `VelleSystem.existsFor` is the three checks as one entry, used by `VelleApi.read` and `readEach` and by the generated viewer read (`Binding.kt`, `shown`). Pinned by `WorkingSetTest`, "a viewer's fetch by identity is one read entry, so the row is fetched once".
- **C3, fixed by D1.** Each reference GraphQL followed was a read of its own with a snapshot of its own: per reference, the actor's row, the drug's row, and the drug's ingredients again, though the list had just read the same drug. Now every read of one `scope { }` is answered from one snapshot (`VelleSystem.openReadSnapshot`), and one GraphQL request runs in one scope (`VelleGraphQL.configure`, which `apis/spring` calls). Pinned by `WorkingSetTest`, "a scope's reads are one snapshot, so the next read in the scope does not fetch the row again", which failed before the change, and by `PharmacyStatementsTest`, "the console's table of interactions reads no drug's own members to show its key", which bounds the table at two statements plus two for each distinct drug the interactions name. I did not run that second test with the runtime change and without the GraphQL one, so it is not shown to fail on the GraphQL half alone.
- **C4, open, work items in §4.** A whole record is evaluated one related record at a time, a statement each. `PharmacyStatementsTest`, "a list of whole drugs sends a statement for every related record it reads", pins it: more than five statements per drug, and no more than 20 distinct statements among them. Two things add up there. A member such as `onHand` reads each stock lot, and each lot reads each of its fills, one statement per record. And reading a reference member fetches the record it points at to see that it still exists (`VelleSystem.normalizeRead`), so a list of 20 interactions fetches every drug they name though it shows only their keys.

What this does to Dave's table has not been timed against PostgreSQL, because his running application is the build from before the change. By statement count each reference followed falls from a whole `Drug` read, which a scratch run on 2026-11-19 counted at 76 statements with ninety days of seeded data, to none when the list already read that drug.

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

## 4. Work items

- [ ] Time the console's `Interaction` table against the pharmacy application over PostgreSQL once it runs this build, and put the number beside the 1.25 seconds in §1.
- [ ] Design how the runtime reads many related records in one statement (cause C4): what the store contract (`StateResolver`) would need for a fetch of several keys, or of the records referencing several owners, at once, and whether reading a reference member has to fetch the record it points at. Write the decisions here before building.
