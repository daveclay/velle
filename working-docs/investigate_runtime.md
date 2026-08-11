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

## 2. Commit callbacks

The engineer registers callbacks that let them insert records into a real DB, call an external API, or write to a file. Three decisions determine whether this stays coherent with the transaction model:

**The unit is the transaction, not the instance.** The runtime's `Txn` already accumulates the full mutation set — the act's create plus every rule-fired create and assign in the envelope. The callback receives that whole set atomically, once, at transaction close. That is what maps to a DB transaction on the engineer's side; per-instance events would force them to reassemble atomicity the runtime already had.

**Failure semantics: the callback runs inside the envelope.** If the engineer's DB write throws, the whole commit rolls back and the caller gets a refusal/error — the persistence write joins the all-or-nothing envelope. This is the only stance consistent with the transition law: an after-the-fact callback that fails leaves state the DB never saw, and no sweep can find that work because the trigger was never data. The spec-level vocabulary for the other choice already exists (`after commit`, `tolerates loss`) — an engineer who wants fire-and-forget persistence of some effect gets it by the spec saying so, not by the callback being lossy.

**The missing half is hydration.** A write-out callback with an in-memory-authoritative runtime means every restart starts empty. The callback needs an inverse: a way to load state at startup. Velle's state is commit-shaped — append-heavy, every change traceable to a commit — so the natural pairing is *commit-log-out / replay-in*: the callback is the durable log, boot replays it, and the runtime stays authoritative. The alternative (DB-authoritative, runtime reads through) is a much bigger design, rejected for now. Some hydration answer has to ship in the same breath as callbacks, or the feature only works for demos.

One more thing the callback quietly solves: **external effects**. The intent-before-effect pattern produces intent facts as data; the engineer's callback observing "an `EmailIntent` entered state" *is* the effect-execution loop. One registration point serves both persistence and effects — worth stating in the docs so nobody goes looking for a separate effects API.

## 3. Rule-execution hook — deferred, on principle

A hook around rule execution, so the engineer can be made aware of a rule's execution outside of its shape. Deferred — and there is a design-philosophy argument, not just YAGNI: anything an external system legitimately needs to know about must be *data in a commit* — the run-once-guards rule ("the only durable memory in Velle is data") applied at the boundary. If a spec wants the outside world to react to a rule firing, the rule should produce a fact, and the commit callback already delivers it. A rule hook would be an untraceable side channel around that.

The one legitimate residue — "which rule produced this mutation," for debugging/tracing — is provenance, already the deferred `why` item. A cheap early version, if wanted, is rule-attribution *metadata on the commit callback payload*, not a separate hook.

## Outcomes

Settled enough to promote:

- Drop `using` from `expose` — grammar (`exposeDecl`, keyword list) + README §22; the "External input mechanisms" deferred item dissolves; extension framework drops the mechanism hat.
- Commit callback: transaction-unit payload, in-envelope failure, replay hydration as the paired requirement.
- OQ20's who-may-commit residue retires to engineer wrapper code.

Still open, re-homed:

- Per-exposure field policy (committer-suppliable fields, supplied-vs-generated `id`) — now attaches directly to `expose` design.
- Production clock default (real time, controllable clock as test affordance).
- Rule-execution hook — rejected as a hook; residue folds into the `why`/provenance item.
