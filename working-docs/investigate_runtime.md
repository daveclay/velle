# Investigation: how Velle is used at runtime

**Status: in discussion.** Reframes the transpile target and dissolves the mechanism-plugin design. Settled outcomes promote to README §22; this doc records the reasoning.

## The reframing

No one runs an application *in* Velle, and writing something like a webserver in Velle would be horrible. Velle is a higher-level abstraction language that separates business concerns from computer-science runtime concerns.

In practice: an engineer captures the discussion with their product owner in Velle, then compiles it. What comes out is code they use *from* their application. They "use" the Velle by writing the lower-level abstractions Velle intentionally leaves out — APIs, DBs, SQL, JSON — and mapping those frameworks and mechanisms onto the transpiled Velle functions.

This mostly *ratifies* what v0 already built rather than rethinking it. MockHarness was scoped as "a spike to test the runtime, not the mechanism-plugin design," with real transport plugins (`configure DefaultRestAPI using REST { ... }`) deferred. This reframing says: the spike *is* the design. The generated function surface is the product; transport, persistence, and serialization are the engineer's code, permanently. The "External input mechanisms" deferred item — plugin API, `configure` vocabulary, mechanism registration — dissolves instead of landing post-v0. The extension framework drops from three hats (types, functions, mechanisms) to two (types, functions). This is consistent with README §22's own line that REST/GraphQL/bus transport "is traditional-code territory Velle doesn't compete in."

## 1. Drop `using X` from `expose`

`expose Thing` alone still carries everything load-bearing: it marks the trust boundary, it is where input-constrained `never` guardrails compile to, and it is what the reachability/unfireable-rule analysis keys off. None of that ever needed the mechanism name. What `using MockHarness` names today is the *only* behavior there will ever be — "generate a function taking this shape as input, giving the engineer something to call to submit a mutation" — so the clause is pure noise.

Grammar impact is small: `exposeDecl` loses its `using MechanismName` tail; `using` can leave the keyword list.

Two things from the old mechanism design do not dissolve — they redistribute:

- **Who-may-commit** (caller identity, ambient policy, rate — OQ20's residue, parked "at the expose site or its configuration") moves cleanly *out of the language*: it is exactly the code the engineer writes in their wrapper before calling `commitThing(...)`. OQ20's residue is retired by this decision.
- **Per-exposure field policy** does *not* dissolve, because it determines the generated function's *signature*: which fields the committer supplies vs. which are internal, and supplied-vs-generated `id` at a legacy/trust boundary. That item detaches from "mechanism configuration" and reattaches directly to `expose` design.

Ticks fit the same story with zero change: schedule names already transpile to tick functions, and under this model the engineer calls them from their real scheduler (cron, Quartz, whatever). The one adjustment is the clock — the generated `System` currently has a harness-controlled clock (`setTime`/`advance`); production use wants real time as the default, with the controllable clock kept as the test affordance.

## 2. Hydration: demand-hydrated evaluation over engineer-owned storage

Three ideas that compose into one design:

- GraphQL's resolver as precedent: the spec generates the interface an engineer implements to fetch data.
- Velle already knows enough to build the SQL that fetches its data — not to *execute* it, but as a builder utility: the engineer provides ORM mappings, Velle outputs SQL (or an agnostic query object that translates to SQL).
- Velle knows the entire rule flow, so it knows what state a commit needs to satisfy the rule chain.

Together: Velle is a per-commit decision kernel that *demand-hydrates* the state it needs through engineer-supplied resolvers, evaluates in memory, and hands the mutation set back through the commit callback (§3). Velle does not dictate where authority lives — it *defers* to the engineer. Behind the resolvers there may be one DB, multiple DBs, API calls, file storage, any mix; Velle never knows or cares. This fits the reframing better than replay did — engineers already have storage, and Velle owning a parallel system of record via a commit log would contradict "traditional-code territory Velle doesn't compete in." Replay also had real problems: unbounded log growth, boot time, and a second source of truth beside the storage the enterprise already has.

**The resolver model is the right interface shape.** The questions Velle needs to ask during evaluation are few and typed: fetch an instance by id, fetch the instances related to X through field F (the fold/join reads), and fetch the members-or-candidates of a refinement (the scan reads). And the compiler can do better than a generic resolver interface: because the reachable read set is statically computable (below), it can generate a *per-act* resolver interface — an engineer who hasn't provided a fetch path the rule chain needs gets a compile error in *their* code. The structural-impossibility move again: you cannot wire up an act without answering every question its rule chain might ask.

**The SQL builder is candidate generation, and the line is drawn hard there.** Velle's predicate language is small enough to compile to a query IR (and render SQL given ORM mappings) — no lambdas, finite expressions, folds become aggregation subqueries. But if the SQL is treated as *the* evaluation of a refinement predicate, there are two evaluators for one predicate — the runtime's and the database's — and they will drift on nulls, decimal comparison, timezone/`today`. The safe stance: the generated query is a *pre-filter* returning a candidate superset, and the runtime re-checks the authoritative predicate in memory on what comes back. Over-fetching is a performance cost; a semantic mismatch is silently wrong behavior. Correctness stays in one evaluator; the SQL builder is purely an optimization utility.

**The read set is statically computable, and the per-rule worry dissolves.** The reachable read set of a commit is the transitive closure over the rule graph from the trigger shape, through the definition graph — computable at the shape/relationship level, with instance-level fetches resolved lazily during evaluation (which Invoice isn't known until the act's reference is read). The concern that subsequent rules might need updated data not present at a prior rule's firing mostly dissolves: within one transaction, everything earlier rules created or assigned is already in the runtime's working set — its own mutations, no fetch needed. The only data that could have "changed" mid-transaction is data changed by *other* transactions, and a transaction must not see that — evaluation runs against one consistent snapshot, so external fetches are cached for the envelope, never re-issued. Per-rule re-fetching would be a bug.

Two consequences:

- **Ticks make the query IR nearly mandatory, not optional.** A tick evaluates refinement conditions against every current member — with state living in engineer-owned storage, that is a scan. Without the compiled pre-filter, every tick resolver is "fetch the whole table." The SQL-builder utility is load-bearing for schedules even if act commits could live on keyed lookups alone.
- **The universal transaction.** Velle assumes one "universal" transaction: the envelope's resolver reads see a consistent snapshot, its mutation writes land atomically, and concurrent commits are serialized against conflicting state. Under the hood, *the engineer is responsible for realizing that guarantee* — trivially with one DB transaction, or with real work when the envelope spans multiple DBs, API calls, and files (distributed-transaction territory Velle stays out of). In practice: if the low-level code hits an error, it is up to the engineer to ensure the black-box state of the Velle system stays coherent. What must be pinned in the docs is the *contract* — the exact guarantees Velle assumes of the universal transaction — because every confluence and one-writer proof rests on them; how the engineer delivers those guarantees is theirs.

## 3. Commit callbacks

The engineer registers callbacks that let them insert records into a real DB, call an external API, or write to a file. Three decisions determine whether this stays coherent with the transaction model:

**The unit is the transaction, not the instance.** The runtime's `Txn` already accumulates the full mutation set — the act's create plus every rule-fired create and assign in the envelope. The callback receives that whole set atomically, once, at transaction close. That is what maps to a DB transaction on the engineer's side; per-instance events would force them to reassemble atomicity the runtime already had.

**Failure semantics: the callback runs inside the envelope.** If the engineer's DB write throws, the whole commit rolls back and the caller gets a refusal/error — the persistence write joins the all-or-nothing envelope. This is the only stance consistent with the transition law: an after-the-fact callback that fails leaves state the DB never saw, and no sweep can find that work because the trigger was never data. The spec-level vocabulary for the other choice already exists (`after commit`, `tolerates loss`) — an engineer who wants fire-and-forget persistence of some effect gets it by the spec saying so, not by the callback being lossy.

**The callback is the write half of §2's design.** Resolver reads hydrate the evaluation; the callback's writes land inside the same universal transaction (§2) — which is also the concrete mechanism for the in-envelope failure stance above. (An earlier draft of this doc proposed commit-log-out / replay-in with the runtime authoritative; §2 overturns that.)

One more thing the callback quietly solves: **external effects**. The intent-before-effect pattern produces intent facts as data; the engineer's callback observing "an `EmailIntent` entered state" *is* the effect-execution loop. One registration point serves both persistence and effects — worth stating in the docs so nobody goes looking for a separate effects API.

## 4. Rule-execution hook — deferred, on principle

A hook around rule execution, so the engineer can be made aware of a rule's execution outside of its shape. Deferred — and there is a design-philosophy argument, not just YAGNI: anything an external system legitimately needs to know about must be *data in a commit* — the run-once-guards rule ("the only durable memory in Velle is data") applied at the boundary. If a spec wants the outside world to react to a rule firing, the rule should produce a fact, and the commit callback already delivers it. A rule hook would be an untraceable side channel around that.

The one legitimate residue — "which rule produced this mutation," for debugging/tracing — is provenance, already the deferred `why` item. A cheap early version, if wanted, is rule-attribution *metadata on the commit callback payload*, not a separate hook.

## 5. Spike: the hydrating runtime over SQLite

Built and running (2026-08-11): `VelleSystem.connect(resolver, callback)` in the runtime, and in billing's developer-owned output module a model-driven `SqliteStore` implementing both halves plus a `BillingApp` that commits through the generated surface and reads back with its own SQL (`gradle :examples:billing:output:runApp`). With a resolver connected, the runtime's in-memory state becomes a per-envelope working set: each transaction opens a fresh snapshot, faults state in on demand — memoized for the envelope, never re-issued (§2's snapshot rule) — and hands its mutation set to the callback inside the envelope, after the `never` check. Everything is opt-in; without a resolver the v0 in-memory behavior is untouched, and the full test suite passes.

What the spike confirmed:

- **The three resolver questions held.** Every state read in the runtime and evaluator reduced to exactly §2's catalog — fetch by id, fetch the instances referencing X through field F, fetch all of a shape. No fourth question emerged. (The spike collapses the *per-act generated interfaces* to one generic three-method resolver driven by the compiled model; the per-act compile-error ergonomics are the next rung, unexercised.)
- **The transaction mapping is clean.** Envelope → one SQLite transaction (a payment's commit lands the `Payment` row, the fold's assign, and the `Receipt` atomically); `after commit` firing → its own DB transaction; in-envelope callback failure → SQLite rollback plus runtime rollback, caller gets the error. §3's three decisions survived contact intact.
- **Guards work against engineer-owned storage across processes.** Run the app twice: the second process's weekly tick sweeps an overdue invoice created by the first, and the reminder guard suppresses re-nagging by hydrating the first run's `Reminder` row — cross-tick memory as data, read from the engineer's DB.
- **The scan cost is real, and not just for ticks.** Every commit currently hydrates the full table of every watcher's base shape (pre/post member sets) and every `never`'s base. §2 called the query IR "nearly mandatory" for ticks; empirically the static read-set derivation and pre-filter are load-bearing for *ordinary commits* too. They are what makes this viable beyond a spike, not an optimization.

What the spike surfaced as gaps in the contract:

- **Captures have no storage home.** Per-membership memory (`ArchivedInvoice.archivedOn`) lives only in the runtime's process; archive in one run, unarchive in the next, and the exit rule's capture read fails. The resolver/callback surface needs a capture channel — a runtime-owned table the engineer hosts, or captures re-derived at hydration — a real hole in §2/§3 as written, promoted to the open list below.
- **A bare `id` doesn't name its table.** The runtime's references carry no shape, but engineer storage is per-shape. The spike keeps an id→shape index populated at creation, hydration, and reference conversion — workable, because every path an id enters by knows the target shape from the model — but the resolver contract should state it: by-id fetches are always shape-qualified.
- **Id minting.** The runtime mints ids above the storage's max (`maxId()` on the resolver). Fine for the spike; the supplied-vs-generated `id` question at trust/legacy boundaries remains with the per-exposure field policy item.

Not exercised, deliberately: generated per-act resolver interfaces, the query IR / SQL pre-filter, the production clock default (the app passes real time explicitly).

## Outcomes

Settled enough to promote:

- Drop `using` from `expose` — grammar (`exposeDecl`, keyword list) + README §22; the "External input mechanisms" deferred item dissolves; extension framework drops the mechanism hat.
- Commit callback: transaction-unit payload, in-envelope failure (the callback's writes join the engineer's DB transaction).
- Hydration: demand-hydrated evaluation over engineer-owned storage via generated per-act resolver interfaces — Velle defers on where authority lives (one DB, many, APIs, files); the query IR / SQL builder is a pre-filter utility, never the authoritative evaluator; replay-in is rejected.
- OQ20's who-may-commit residue retires to engineer wrapper code.

Still open, re-homed:

- Per-exposure field policy (committer-suppliable fields, supplied-vs-generated `id`) — now attaches directly to `expose` design.
- Capture persistence (from the spike, §5): per-membership memory must survive the process for the exit-rule last-reader contract to hold over engineer-owned storage — a capture channel on the resolver/callback surface, or re-derivation at hydration. Undesigned.
- The universal-transaction contract: the exact guarantees Velle assumes (snapshot reads, atomic writes, serialization of conflicting commits) stated precisely — the engineer realizes them however their storage requires; the confluence and one-writer proofs now rest on this contract.
- Production clock default (real time, controllable clock as test affordance).
- Rule-execution hook — rejected as a hook; residue folds into the `why`/provenance item.
