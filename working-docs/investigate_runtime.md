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

## 2. Hydration: DB-authoritative, demand-hydrated evaluation

Three ideas that compose into one design:

- GraphQL's resolver as precedent: the spec generates the interface an engineer implements to fetch data.
- Velle already knows enough to build the SQL that fetches its data — not to *execute* it, but as a builder utility: the engineer provides ORM mappings, Velle outputs SQL (or an agnostic query object that translates to SQL).
- Velle knows the entire rule flow, so it knows what state a commit needs to satisfy the rule chain.

Together: the engineer's DB is the system of record, and Velle is a per-commit decision kernel that *demand-hydrates* the state it needs through engineer-supplied resolvers, evaluates in memory, and hands the mutation set back through the commit callback (§3). This fits the reframing better than replay did — engineers already have a database, and Velle owning a parallel system of record via a commit log would contradict "traditional-code territory Velle doesn't compete in." Replay also had real problems: unbounded log growth, boot time, and a second source of truth beside the DB the enterprise already has.

**The resolver model is the right interface shape.** The questions Velle needs to ask during evaluation are few and typed: fetch an instance by id, fetch the instances related to X through field F (the fold/join reads), and fetch the members-or-candidates of a refinement (the scan reads). And the compiler can do better than a generic resolver interface: because the reachable read set is statically computable (below), it can generate a *per-act* resolver interface — an engineer who hasn't provided a fetch path the rule chain needs gets a compile error in *their* code. The structural-impossibility move again: you cannot wire up an act without answering every question its rule chain might ask.

**The SQL builder is candidate generation, and the line is drawn hard there.** Velle's predicate language is small enough to compile to a query IR (and render SQL given ORM mappings) — no lambdas, finite expressions, folds become aggregation subqueries. But if the SQL is treated as *the* evaluation of a refinement predicate, there are two evaluators for one predicate — the runtime's and the database's — and they will drift on nulls, decimal comparison, timezone/`today`. The safe stance: the generated query is a *pre-filter* returning a candidate superset, and the runtime re-checks the authoritative predicate in memory on what comes back. Over-fetching is a performance cost; a semantic mismatch is silently wrong behavior. Correctness stays in one evaluator; the SQL builder is purely an optimization utility.

**The read set is statically computable, and the per-rule worry dissolves.** The reachable read set of a commit is the transitive closure over the rule graph from the trigger shape, through the definition graph — computable at the shape/relationship level, with instance-level fetches resolved lazily during evaluation (which Invoice isn't known until the act's reference is read). The concern that subsequent rules might need updated data not present at a prior rule's firing mostly dissolves: within one transaction, everything earlier rules created or assigned is already in the runtime's working set — its own mutations, no fetch needed. The only data that could have "changed" mid-transaction is data changed by *other* transactions, and a transaction must not see that — evaluation runs against one consistent snapshot, so external fetches are cached for the envelope, never re-issued. Per-rule re-fetching would be a bug.

Two consequences:

- **Ticks make the query IR nearly mandatory, not optional.** A tick evaluates refinement conditions against every current member — with a DB-authoritative store, that is a scan. Without the compiled pre-filter, every tick resolver is "fetch the whole table." The SQL-builder utility is load-bearing for schedules even if act commits could live on keyed lookups alone.
- **A new open question replaces hydration: the concurrency contract.** With the runtime stateless per commit, two app instances can evaluate commits concurrently against the same DB. Velle's semantics assume the envelope is serialized against conflicting state. The engineer's DB transaction is presumably the enforcement mechanism — resolver reads and callback writes sharing one DB transaction at some stated isolation level — but what Velle *requires* (serializable? read-committed plus optimistic checks?) has to be pinned, because every confluence and one-writer proof now rests on it.

## 3. Commit callbacks

The engineer registers callbacks that let them insert records into a real DB, call an external API, or write to a file. Three decisions determine whether this stays coherent with the transaction model:

**The unit is the transaction, not the instance.** The runtime's `Txn` already accumulates the full mutation set — the act's create plus every rule-fired create and assign in the envelope. The callback receives that whole set atomically, once, at transaction close. That is what maps to a DB transaction on the engineer's side; per-instance events would force them to reassemble atomicity the runtime already had.

**Failure semantics: the callback runs inside the envelope.** If the engineer's DB write throws, the whole commit rolls back and the caller gets a refusal/error — the persistence write joins the all-or-nothing envelope. This is the only stance consistent with the transition law: an after-the-fact callback that fails leaves state the DB never saw, and no sweep can find that work because the trigger was never data. The spec-level vocabulary for the other choice already exists (`after commit`, `tolerates loss`) — an engineer who wants fire-and-forget persistence of some effect gets it by the spec saying so, not by the callback being lossy.

**The callback is the write half of §2's design.** Resolver reads hydrate the evaluation; the callback's writes join the same engineer-controlled DB transaction — which is also the concrete mechanism for the in-envelope failure stance above. (An earlier draft of this doc proposed commit-log-out / replay-in with the runtime authoritative; §2 overturns that.)

One more thing the callback quietly solves: **external effects**. The intent-before-effect pattern produces intent facts as data; the engineer's callback observing "an `EmailIntent` entered state" *is* the effect-execution loop. One registration point serves both persistence and effects — worth stating in the docs so nobody goes looking for a separate effects API.

## 4. Rule-execution hook — deferred, on principle

A hook around rule execution, so the engineer can be made aware of a rule's execution outside of its shape. Deferred — and there is a design-philosophy argument, not just YAGNI: anything an external system legitimately needs to know about must be *data in a commit* — the run-once-guards rule ("the only durable memory in Velle is data") applied at the boundary. If a spec wants the outside world to react to a rule firing, the rule should produce a fact, and the commit callback already delivers it. A rule hook would be an untraceable side channel around that.

The one legitimate residue — "which rule produced this mutation," for debugging/tracing — is provenance, already the deferred `why` item. A cheap early version, if wanted, is rule-attribution *metadata on the commit callback payload*, not a separate hook.

## Outcomes

Settled enough to promote:

- Drop `using` from `expose` — grammar (`exposeDecl`, keyword list) + README §22; the "External input mechanisms" deferred item dissolves; extension framework drops the mechanism hat.
- Commit callback: transaction-unit payload, in-envelope failure (the callback's writes join the engineer's DB transaction).
- Hydration: DB-authoritative, demand-hydrated evaluation via generated per-act resolver interfaces; the query IR / SQL builder is a pre-filter utility, never the authoritative evaluator; replay-in is rejected.
- OQ20's who-may-commit residue retires to engineer wrapper code.

Still open, re-homed:

- Per-exposure field policy (committer-suppliable fields, supplied-vs-generated `id`) — now attaches directly to `expose` design.
- The concurrency contract: what isolation Velle requires of the engineer's DB transaction (serializable? read-committed plus optimistic checks?) — the confluence and one-writer proofs now rest on it.
- Production clock default (real time, controllable clock as test affordance).
- Rule-execution hook — rejected as a hook; residue folds into the `why`/provenance item.
