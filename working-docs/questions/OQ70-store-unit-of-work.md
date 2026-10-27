# OQ70 — A unit of work in the store contract: pooled connections, a transaction per read, and the queue keys taken where the database can see them

**Status:** opened 2026-10-27 at Dave's request, after the Spring Boot starter (built 2026-10-26) showed the cost of the current shape: `SqlStore` holds one `java.sql.Connection` for the system's life, and the API layer serializes every commit, tick, and read on one process-wide lock. Decisions D1–D6 below were ratified by Dave 2026-10-27 ("your recommendations sound right to me"). Sequencing, also Dave's: the store side lands first, and the second half of D1 (the working set per unit of work, then `VelleApi`'s one lock replaced by per-key admission) is tackled right after, not deferred. The store side is built 2026-10-27 (items 1–7 below, all tests green over SQLite and PostgreSQL): `StateResolver.begin(kind)` returns a `UnitOfWork` with `serialize(keys)` and `end(outcome)` (`WorkKind`, `Outcome`, `AdmissionKey` in `Hydration.kt`, defaults for a store with nothing to open); the runtime opens a read unit lazily at a query's first fetch and closes it at the outermost scope's exit or when an envelope opens, opens a write unit in `inTransaction` and `seed`, values the envelope's queue keys at admission (`actKeys`, `firingKeys`, hopping stored and captured members; `DomainAnalysis.firingDomains` now covers every rule so after-commit firings have a domain), hands them to `serialize`, drops the working set, and ends the unit with the outcome; `SqlStore` takes a `DataSource` and a connection per unit (`OneConnection` wraps a single connection for SQLite in memory), `SqlDialect` gained `prepare`/`beginRead`/`beginWrite`/`lock`/`serializesKeys`, PostgreSQL takes transaction-scoped advisory locks on `hashtext(key)` in sorted order under `lock_timeout`, SQLite begins deferred for a read and immediate for a write; the generated typed store forwards `begin` (`StoreGen`, all examples regenerated); the conformance suite runs SQLite over a temporary WAL file and PostgreSQL over an unpooled `DataSource` per schema and pins: one connection per unit returned at its end, a read's snapshot while another connection commits, a refusal writing nothing, two envelopes on one key taking turns and disjoint keys not waiting, a lock timeout failing the envelope. D1's second half is built 2026-10-27 through D10, on Dave's word ("understood, refactor out the MemoryStore"): `MemoryStore` (`compiler/src/main/kotlin/velle/MemoryStore.kt`) implements both halves of the contract with rows in maps, keys from one counter, a read-write lock for its rows and a lock per queue key; every `VelleSystem` is connected to one from birth and `connect` swaps in another; the runtime's working set, handle tables, transaction, and unit of work live in a per-thread `Session` created at first use and dropped at the outermost `scope`'s exit, so the nine `resolver == null` branches are gone and two calls on two threads share nothing but the store; `VelleApi.locked` is gone, `VelleApi.scope` sets the clock and opens the runtime's scope, and nothing in the API layer locks (`MemoryStoreTest`: rows outlive a scope and handles do not, eight threads of deposits on one account lose none, reads run beside each other, a read sees a commit whole). The compiler's tests, which held handles across calls, each run inside one `scope` now. D8 and D9 remain open.
**In plain terms:** today a Velle system over a database borrows one connection at startup and never gives it back, and the web layer lets one request at a time touch the system. That works for a developer's machine and fails in production: a dropped connection is fatal until restart, two copies of the application can interleave at the database, and nothing can run in parallel. The fix is to let the store know when a piece of work begins and ends, in Velle's own words, so the SQL store can take a pooled connection and a transaction for exactly that long, and take the locks the spec's own analysis says the work needs. The runtime must still import nothing from SQL.
**v0 stance:** built. The store side landed first (2026-10-27) and the lock's replacement the same day: every system runs over a store, the in-memory one by default, and the store serializes on the queue keys.
**See:** `wiki/evaluation.md` "The universal transaction" (U1–U5: a read is a read-only snapshot taking no queue key; delivering the serialization is the engineer's mechanism; the contract names the keys) and "serialization domain" (keys computed at admission, against pre-commit state plus the act's inputs, hopping through captured members) · `compiler/src/main/kotlin/velle/Hydration.kt` (`StateResolver`, `CommitCallback`: the whole store contract today, five fetches and `onCommit`) · `compiler/src/main/kotlin/velle/Runtime.kt` (`scope`, `beginSnapshot`, `inTransaction`, `openReadSnapshot`: where the boundaries already are) · `compiler/src/main/kotlin/velle/Domains.kt` (`QueueKey.Path`, `QueueKey.ValueOf`, `Widening`: the derived domain) · `compiler/src/main/kotlin/velle/Codegen.kt` ~350 (the keys reach the engineer as the commit function's KDoc today, and nowhere else at runtime) · `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt` (`onCommit`: `autoCommit = false`, write, `commit()`, on one connection; `attach(system, conn, dialect, bindings)`) · `stores/sql/src/testFixtures/kotlin/velle/sql/SqlStoreConformance.kt` (where the new behavior gets pinned per dialect) · `apis/json/src/main/kotlin/velle/api/VelleApi.kt` (`locked`: the lock, the clock, the scope) · `apis/spring/src/main/kotlin/velle/api/spring/VelleAutoConfiguration.kt` (`attach`: borrows the one connection from the `DataSource`) · the wiki guide `API-layer` · QUESTIONS.md "Serialization domains and concurrency" (OQ55, the precision of the keys this question would act on).

---

## 1. What exists

**The runtime is single-threaded by construction, not by contract.** `VelleSystem` has no lock, no synchronized block, and no thread-safe collection. With a store connected, `instances`, `byShape`, the fetch caches, and `captures` are one shared working set over the database; `beginSnapshot` empties it so the next fetch returns to the store. A commit faults rows into that set, applies its mutation set to it, and hands the set to `onCommit` inside the envelope. A read calls `openReadSnapshot`, which clears the set only when no envelope is open (`txn == null`). Two threads in the runtime at once therefore either read an envelope's half-applied state or clear the maps an envelope is using. The lock in `VelleApi.locked` is the only thing preventing that; the generated commit functions and the typed query surface do not lock.

**The store holds one connection.** `SqlStore(model, conn, dialect, bindings)` takes a `Connection`. `onCommit` turns `autoCommit` off, writes, commits or rolls back, turns it back on. Every fetch is an autocommit statement on the same connection. So: a read is consistent within one process only because the lock keeps the process quiet; a second instance of the application, or any other writer, can change rows between two fetches of one read; a dropped connection is not reconnected; HikariCP sees one connection permanently checked out; even with per-key serialization, two non-conflicting commits could not run at once.

**The queue keys exist at compile time only.** `Domains.kt` derives each act's and each scheduled firing's domain (`QueueKey.Path`, `QueueKey.ValueOf`, `Widening`). They reach the engineer as the generated commit function's KDoc ("Queue keys: [deposit.account]") and the contention map. Nothing at runtime evaluates a key's concrete value at admission; `Runtime.kt` does not mention them.

**The boundaries the store would need are already in the runtime.** `scope` at depth zero is one read (it clears the working set and the handle tables on exit). `inTransaction` is one commit or tick envelope (`beginSnapshot`, `Txn`, `checkNevers`, `onCommit`, `finalizeDeletes`, `drainAfterQueue`). A `scope` opened inside an envelope reads the envelope's state, which is right and stays.

## 2. The shape of the change

The contract grows by one idea, in the runtime's vocabulary. A sketch, not a signature:

```kotlin
interface StateResolver {
    fun begin(work: Work): WorkHandle            // Work.Read, or Work.Write
    fun serialize(work: WorkHandle, keys: Set<ConcreteKey>)   // write only, once the runtime has the keys' values
    fun end(work: WorkHandle, outcome: Outcome)  // Committed, Refused, Failed
    // fetchByKey, fetchAll, fetchReferencing, fetchCandidates, fetchCaptures: unchanged
}
```

- The runtime calls `begin` when `scope` enters at depth zero or an envelope opens, and `end` when it leaves. `onCommit` stays a separate callback, called between `serialize` and `end`, so the dialects' write path does not change.
- The SQL store implements `begin` as taking a connection from a `DataSource` and opening a transaction, `serialize` as taking one lock per key (PostgreSQL advisory locks; SQLite has one writer and needs nothing), `end` as commit or rollback and returning the connection. A connection, a pool, a transaction, and a dialect appear in `stores/sql` and nowhere above it.
- The in-memory runtime (no resolver) implements the same moments with in-process locks per key, or nothing until D1's second half lands.
- A store that is a REST call, a queue, or a file does what is honest for it, which may be nothing. The contract names the moments; the store decides what a moment costs. That is the rule `evaluation.md` already states for serialization, given a place in the contract.

## 3. Decisions

Each decision is one section: the decision in a sentence, then the recommendation and its costs, then the context. Dave answers under **Dave:**. D1 to D6 were ratified 2026-10-27 and are built except the second half of D1; D7 to D9 are open.

### D1 — Build the store's unit of work first and keep the global lock in `VelleApi` until the runtime's working set is per unit.

**Dave (2026-10-27):** ratified; "I'll want to tackle the single-lock-read right after." Second half built 2026-10-27 through D10: the working set is per thread and per unit (`VelleSystem.Session`), `VelleApi`'s lock is gone, and the wiki `API-layer` and `Serving-a-system` pages follow.

Pooled connections, reconnection, and a consistent read per request come from the store side alone, which is built. Letting two non-conflicting units run at once also needs the runtime's working set (`instances`, `byShape`, the fetch caches, `captures`, and the handle tables `idShape`, `keyOf`, `handleOf`) moved off `VelleSystem`'s fields into an object created at `begin` and dropped at `end`. That is a refactor inside `Runtime.kt` with no new imports; `SqlStore.current` is already a `ThreadLocal`, so the store side needs no change for it. The wiki `API-layer` ("The layers", "One request, end to end") and `Serving-a-system` (the `api.locked` bullet) describe the lock and follow it out.

**Context: a read inside an envelope against a read outside one** (as built 2026-10-27; Dave asked for this comparison 2026-10-28).

| | inside an act or firing (`txn != null`) | outside one: a query, `get`, `exists`, `currentShapeOf` |
|---|---|---|
| unit of work | the envelope's write unit, opened in `inTransaction` before the keys are valued; one connection, read-committed transaction | a read unit, opened lazily at the first fetch (`reader()`); one connection, read-only snapshot transaction |
| when it ends | with the envelope: `Committed` after `onCommit`, `Refused` on a `never`, `Failed` on an exception | at the outermost `scope`'s exit, or the moment an envelope opens in the same scope (`endUnit` before `begin(WRITE)`) |
| what it sees in the store | the state behind the envelope's key locks: `beginSnapshot()` after `serialize` drops what the key valuation read | one snapshot taken at the first statement; a commit landing on another connection meanwhile is not seen until the next read unit |
| its own writes | in memory, in the working set: an assign mutates the `Instance` in place (old value kept in `Txn.oldValues` for rollback), a create adds to `instances`/`byShape`; the store sees none of it until `onCommit` | none; a read writes nothing |
| the working set | hydrated on demand from the unit's connection, each fetch once per envelope (`fetchedAll`, `fetchedRefs`, `fetchedFilters`, `fetchMisses`) | `openReadSnapshot()` calls `beginSnapshot()` at each read entry while `txn == null`, so the cache is dropped per entry and rows are refetched; the snapshot transaction makes every refetch agree |
| a `scope` opened inside it | opens no unit, reads the envelope's state; a query from engineer code mid-commit reads in-progress writes (`openReadSnapshot` is a no-op) | nested scopes share the one read unit; handles live until the outermost exit |
| queue keys | valued at admission and locked through `serialize`; another envelope sharing a key waits (PostgreSQL), fails after the lock timeout, or is serialized by the single writer (SQLite) | none: a read takes no key and waits for no envelope (U3 orders writers only) and blocks no writer |
| what follows | `finalizeDeletes`, then the after-commit queue: each firing is its own envelope with its own unit and keys | nothing |

Why the global lock is still needed: both columns use the same fields on `VelleSystem` for the working set and the handle tables. A read on another thread would see the envelope's in-progress writes (its `openReadSnapshot` skips the clear while `txn != null`) or clear the maps the envelope is using. The store already keeps them apart at the database, one connection each; the runtime does not. After D1's second half each unit owns its working set, so the two columns no longer share anything but the store, and the store's locks (D4) order the envelopes that must be ordered.

In memory, with no store, the two columns differ less: the working set is the state, there is no snapshot connection, and a read sees whatever the maps hold. Per-unit working sets give an envelope nothing to copy from, so in memory a read must still be kept out while an envelope runs (a read-write lock: reads together, an envelope alone), while envelopes with disjoint keys may run together only if the state itself is safe to mutate from two threads, which `HashMap`s are not. D7 should be answered with this in view: in memory the honest unit is "envelopes one at a time on the keys, and reads either alongside each other or alone," not the SQL store's picture.

### D2 — A write unit is two calls: open it, then take the keys once their values are known.

**Dave (2026-10-27):** ratified. Built: `inTransaction` calls `begin(WRITE)`, values the keys (`actKeys`, `firingKeys`), calls `serialize`, then `beginSnapshot()`.

A key's value is computed at admission against pre-commit state and may hop through a captured member (`reopenTicket.ticket.closedBy`), so the unit must be open before the keys exist. Hence `begin`, then `serialize(keys)`, then `onCommit`, then `end`. After `serialize` returns the runtime drops its working set, so everything the envelope evaluates was read after the locks were held. Valuing `QueueKey.Path` and `QueueKey.ValueOf` at runtime did not exist before this (§1); the compile-time derivation did.

### D3 — A read is a snapshot; a write is read committed.

**Dave (2026-10-27):** ratified. Built in `PostgresDialect.beginRead` / `beginWrite` and `SqliteDialect`'s deferred and immediate transactions.

A read unit opens a read-only snapshot transaction (REPEATABLE READ in PostgreSQL; a deferred transaction in SQLite, which is a snapshot once the first statement runs), which is what "one consistent state" in U1 means for a read. A write cannot be a snapshot taken before its locks, since what it then read could be stale, so it runs read committed and relies on D2 for freshness.

### D4 — Keys are locked in the database, in sorted order, each waiting at most a store-configured timeout.

**Dave (2026-10-27):** ratified. Built: `PostgresDialect.lock` (`SET LOCAL lock_timeout`, `pg_advisory_xact_lock(hashtext(key))` per sorted key); `SqlStore.lockTimeout` defaults to ten seconds; SQLite locks nothing (`serializesKeys` false).

Advisory locks per key, so two processes serialize correctly. Sorting avoids deadlock between two commits with overlapping sets. A timeout turns a stuck lock into a failed envelope (`Outcome.Failed`, U2's in-envelope failure) rather than a hung request; the number is a store setting, never a spec concern. A `Widening` locks the shape's name.

### D5 — `SqlStore` takes a `DataSource`; the layer has no connection constructor.

**Dave (2026-10-27):** ratified. Built. `SqliteStore` keeps a `Connection` convenience that wraps the connection in `OneConnection`, because an in-memory SQLite database lives in its one connection; `PostgresStore` takes a `DataSource` only.

One construction path means the conformance suite tests the real one. The SQLite conformance run uses a temporary file in WAL mode so that every unit really takes a connection; the PostgreSQL run an unpooled `PGSimpleDataSource` per schema. The starter's `attach` reads the product name from one connection, returns it, and passes the `DataSource` through.

### D6 — This is one OQ, open until the lock is replaced.

**Dave (2026-10-27):** ratified.

It absorbed the two residue lines under QUESTIONS.md "Serving" (the one lock; the one connection). The lock's replacement is the second half of D1 and stays here rather than splitting out.

### D7 — In memory, with no store, the runtime serializes envelopes on their keys itself.

**Dave (2026-10-27):** answered by D10. There is no "no store" case: `MemoryStore.serialize` takes a lock per key text, sorted, so two envelopes on one key take turns and two with disjoint keys run together (`MemoryStoreTest`, "two envelopes on one queue key take turns"); its read unit holds the store's read lock, so reads run beside each other and a commit lands whole or not at all. The runtime takes no lock of its own.

Recommendation: yes. When the global lock goes (D1's second half), a system with no store has no `serialize` to lean on, since `UnitOfWork.None` does nothing, yet two commits over one account must still take turns (U3). The runtime keeps a lock per key text of its own and takes them, sorted, in `inTransaction` when `resolver == null`. Cost: a second implementation of "take these keys", small, inside `Runtime.kt`; the SQL store's conformance scenario for two envelopes on one key gets a twin in `RuntimeTest`. The alternative is to leave `VelleApi`'s global lock in place for in-memory systems only, which keeps two code paths in the API layer and makes the in-memory system behave differently from the stored one under load.

Context, from `examples/billing/billing.velle`: two `SubmitPayment` commits for one invoice carry the key `Invoice#<key>` (the compiler's "Queue key: [submitPayment.invoice]"); in memory today they are serialized by `VelleApi`'s one lock, which also serializes two payments for two different invoices.

### D8 — A read issued outside any `scope` closes its unit of work when the call returns.

**Dave:**

What happens today, and why it is not already so. A read on the runtime's own surface (`sys.get`, `sys.instancesOf`, `sys.query`, ...) with no `scope` around it opens a read unit at its first fetch (`reader()`), and nothing closes that unit when the call returns: the runtime only ends a unit at the outermost `scope`'s exit or when an envelope opens. So after a bare `sys.get` the thread keeps the unit open until it next leaves a scope. Over PostgreSQL that is a pooled connection and a snapshot transaction held for no one; over `MemoryStore` it is the store's read lock, which blocks every commit on every other thread until this thread exits a scope. The generated surface and `VelleApi` are never in this position, since `read(ref)`, the typed queries, and `api.scope` wrap every call in a scope. Who is: a direct call on `VelleSystem` from a test or hand-written code, and the generated spec harness (`SpecGen.kt`: `idOf`, `field`, `member`, `ref` call the runtime bare).

Recommendation: yes. Each bare call ends its unit as it returns, so it is one snapshot and holds nothing afterwards; two bare `get`s are two snapshots, and a caller who wants one wraps them in `scope`. One thing must stay as it is: the handle tables. The generated harness converts a reference with `idOf` outside any scope and hands the handle to the `commit` that follows, so a handle minted by a bare call has to live past that call, until the next outermost scope exit, as it does today (`scope`'s KDoc: "handles minted inside it, or in the gap before it"). D8 therefore separates two things the session currently ends together: the unit (connection, snapshot, read lock) ends with the bare call; the session's handle tables and working set end at the scope exit. Cost: a bare read after a bare read refetches, which it does today across read entries anyway; a bare `instancesOf` followed by bare `get`s per row is one snapshot per call instead of one for all, which only a caller outside any scope sees. The alternative is to refuse a fetch outside a scope with an error naming `scope`, which breaks the generated harness.

Context: `SqlStoreConformance` reads inside `sys.scope { }` throughout; the compiler's tests run each body inside one scope since D10; `WorkingSetTest` pins what a read entry clears.

### D9 — A firing's value key takes the committed value when the rule body states it, and the shape's width otherwise.

**Dave:**

Recommendation: yes. A `QueueKey.ValueOf(shape, field)` on a scheduled or after-commit firing is the uniqueness case: the firing creates a record whose `field` another envelope may also commit. The value is not known before the firing runs, so today the firing takes `AdmissionKey.Shape(shape)`, which serializes it against every envelope touching the shape. Where the rule's creation literal spells the field from the subject's stored members (`Reminder from { invoice: this, sentOn: today }`), the value is readable at admission and the narrow key can be taken; where it depends on the firing's own computation, the width stays. Cost: the key valuation has to evaluate a creation literal's field expression outside the firing, with the evaluator's existing expression evaluation over the subject; one more conformance-style test per case. The alternative is to leave the width, which is correct and coarse.

Context, from `examples/billing/billing.velle`:

```
rule RemindOverdue
    when (ActionableOverdue where
          not exists (Reminder where invoice == this and sentOn > today - 7 days))
    on Weekly {
    Reminder from { invoice: this, sentOn: today }   -- the firing creates a Reminder; its fields are the subject and the clock
}
```

### D10 — The in-memory state becomes a store of its own, and the runtime has no "no store" case.

**Dave (2026-10-27):** "understood, refactor out the MemoryStore." Built the same day; see the status line above for what landed. One consequence worth knowing: a handle (`CommitResult.Accepted.id`, the `Long` the generic surface hands out) now dies at the outermost `scope`'s exit for every system, the in-memory one included, which the generated surface never relied on and the compiler's own tests did; each of those tests runs inside one `scope` now, and a test that builds its records in a helper does so inside the scope that reads them (`VisibilityTest`, `TriggerFamilyTest`, the audits).

Recommendation: yes, as the way to do D1's second half. Over a store, a read needs no instances cached in the system between requests: the working set is a per-call memo so that one query over a thousand invoices fetches each customer once, and `scope` already drops it and the handle tables at the outermost exit. Nothing in a read wants another request's rows, and nothing in an envelope wants a read's. The only reason the working set lives on `VelleSystem` is that in memory, with no store, the same maps are the state. Nine places in `Runtime.kt` branch on `resolver == null` for that case.

Make the in-memory state a `MemoryStore` implementing `StateResolver` and `CommitCallback` (rows in maps, keys minted from a counter), and connect every system to a store from birth, the in-memory one by default. Then every unit, read or write, owns its working set: an object created at `begin` holding `instances`, `byShape`, the fetch caches, `captures`, and the handle tables, dropped at `end`; two units on two threads share nothing but the store. `MemoryStore.begin(READ)` hands out a snapshot (the maps are small and copy-on-write is one `HashMap` copy per write unit, or the store keeps a version per row); `MemoryStore.serialize` takes a lock per key; its `onCommit` applies the set under a write lock. That is D7 answered inside the store, where the SQL store already answers it, and the runtime keeps one code path. Costs: a store module's worth of code in the compiler (the in-memory store must stay in `compiler`, since every spec runs there before any store exists); `seed`, `instancesOf`, and the generated tests' handles are all per scope already, so the surface does not change; `RuntimeTest` and the audits, which read bare `instances` through `internal` members, need the working set reached through the current unit. The alternative is per-unit working sets with the in-memory maps kept as a special case on `VelleSystem`, which keeps the nine branches and gives in memory no snapshot for reads.

Context: `VelleSystem.scope` (`Runtime.kt`) already says what a unit is, "handles minted inside it ... are valid until the outermost scope exits; then, over a store, the handle maps and the hydrated working set are dropped, so memory is bounded by one unit of work and nothing outside ever holds a handle." D10 makes that sentence true for every system.

Two things need no decision because the current behavior is right: a `scope` inside an envelope reads the envelope, so `begin` fires at depth zero only; a refused commit ends its unit with a rollback, and any refusal record it writes (a `never` that records rather than refuses, Reference §21) goes through `onCommit` as today.

## 4. Items

- [ ] D8: a read outside any `scope` closes its unit at the call's end, once D8 is answered.
- [ ] D9: a firing's value key valued from the creation literal where it can be, once D9 is answered.
