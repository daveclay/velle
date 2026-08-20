# Velle Evaluation Model (v0)

The operational semantics of a transpiled Velle system: what the runtime does, stated once, in execution order. Nothing here is newly decided — this consolidates semantics settled across the README (§4, §8, §11, §13, §16–§19, §21) so an implementer follows an algorithm instead of re-deriving one from rationale. The handful of genuinely new calls are **v0 spike choices** — execution decisions below the language, made for the runtime-testing spike, not semantics — marked `[S#]` and collected at the bottom.

The input/output surface (exposed commit functions, tick and clock functions, typed accessors) is the harness contract in README §22's scope statement; this document is what happens *inside* a call.

## State

The state is the set of all shape instances and their stored fields (README §4). Per instance, the runtime holds:

- **`id`** — opaque identity, fixed at creation, `==` only (§5).
- **Stored fields** — scalar values, to-one references, and declared `many` collections (a many-to-many's edge set as a set of references; a `many <scalar>`'s set of values — §6, ownership). *Inferred* `many` sides are derived from the declared side and need no storage of their own; derived collections (`= (Shape where ...)`) are computed on read like any derived property.
- **Timestamp fields** — `on create` fixed at the creation commit; `on update` advanced by every commit that writes a stored field of the instance, creation included (§5).
- **Captures** — per-membership memory: for each refinement membership currently held that declares captured properties, the values fixed at entry (§8; lifecycle below).

Derived properties are never stored — they are computed on read from current state (§7). Refinement membership is never stored — it is the predicate, evaluated against current state (§8). `[S1]` v0 state is in-memory only: no persistence, no crash recovery; the crash-window semantics the language defines (guards, backstops) remain exercisable because backstop ticks are explicit harness calls.

## Commits and transactions

A **commit** is one mutation entering state at a discrete moment: an exposed act instance, one rule firing's effects, or a tick (§4). A **transaction** is the all-or-nothing envelope: an act's commit plus every commit its consequences produce, except where a boundary intervenes (§11). Boundaries arise three ways — declared (`after commit`), inherent (schedule sources), forced (external effects; none exist in v0 — no mechanism performs one, so `tolerates loss` parses and validates but marks nothing reachable).

### An exposed call

```
commitAct(actInstance):
  1. boundary validation: type-level checks first — a duplicate reference in a `many`
     field is refused here (collections are sets; multiplicity that matters is data on
     an edge shape, §6) — then the input-constrained `never` guardrails compiled to
     this expose site (§21) — violation → refusal naming what was violated; nothing begins
  2. begin transaction T
  3. C0 := apply the act commit — insert the instance with the committer's fields
     (an exposed shape declares no `initially` or `timestamp` fields — V21; those
     clauses are evaluated at rule-created instances' creation commits, inside
     process)
  4. process(C0)                                   -- may recurse, growing T
  5. transaction-end `never` check over settled post-state (§21)   [S5]
  6. any error in 3–5 → roll back T entirely; report the error to the caller;
     no automatic retry — re-invoking re-evaluates generators (§5)              [S4]
  7. make T durable (v0: the in-memory state simply stands)
  8. drain the after-commit queue accumulated in T: each entry runs as a fresh
     transaction by this same algorithm, synchronously, in queue order          [S2]
```

### Processing one commit

```
process(C):
  1. delta: the fields C wrote and the instance C inserted — statically known
     per commit kind (literal assignment paths, §12; act shape; tick datum)
  2. membership flips: for every refinement whose predicate reads the delta
     (the derived trigger sets, §11 "Rules ground in commits"), evaluate
     pre-state vs post-state membership for affected instances:
       entrants — false before C, true after
       leavers  — true before C, false after
  3. captures: for each entrant into a capture-declaring refinement, evaluate
     its captured expressions against post-state now, and store them; for each
     leaver, mark its captures for retraction at the close of C — exit rules
     fired from C may still read them (§13, last-reader)
  4. freeze enforcement is not a runtime step: writes to frozen fields were
     proven impossible statically (§8) — v0 asserts and hard-errors if violated
  5. firings := for every rule whose trigger set includes C:
       `when R`          — one firing per entrant into R
       `when leaving R`  — one firing per leaver from R
     partition by preposition:
       on commit    → fire now, inside this transaction (step 6)
       after commit → append (rule, subject) to the transaction's after-commit queue
  6. for each in-transaction firing, in any order [S3]: apply its body as a new
     commit C' — all statements land together, `then` ordering respected within
     the body (§15) — and recurse: process(C')
  7. close C: retract the captures marked in step 3
```

Recursion terminates because the validator proved quiescence — the condition graph is a DAG or its cycles are broken by disarming guards (checks catalog, V16); v0 additionally enforces a cascade-depth backstop that errors the transaction if exceeded `[S3]`.

Ordering within step 6 is **never observable in a valid spec**: the validator rejected any pair of sibling firings that could conflict (V1, V15). The claim is audited and executable — every example world is rebuilt under a reversed firing order on every build and must fingerprint identically (`working-docs/audit-sibling-confluence.md`). Where one firing's commit satisfies another rule's condition, ordering is causality — the recursion structure provides it.

### Transient acts (`expose transient`)

A shape exposed `transient` is an **input to the state, not a member of it** (README §4, "Transient acts"): its instance exists only within its own commit's transaction. The exposed-call algorithm changes in two places. Step 7 additionally **removes the act instance from the state** (it never becomes durable; only its consequences do). And in step 5's firing selection, **the act's partitions are decided exactly once, at C0**: consequence commits within the transaction never re-partition it — without this, an act whose own effects flip the partitioning state would drift into the other side mid-transaction (apply a withdrawal, go overdrawn, and be refused *by the same request*). Everything else is unchanged: the instance is fully present while its transaction runs, so its refinements evaluate normally at C0, its handling rules fire and read its fields, and the transaction-end `never` check sees a consistent world. By the time the after-commit queue drains (step 8), the act is gone — which is consistent, because the validator bans `after commit` and schedule triggers on transient-act conditions (checks catalog, V17): nothing that runs after the transaction may depend on the act.

Rollback needs no special case — an error rolls back the whole transaction, act included, exactly as for ordinary acts. `CommitResult.Accepted.id` remains an opaque receipt; the id names an instance that no longer exists, and since `id` supports only `==`, nothing can be asked of it.

### Entry and exit are commit-local

"Newly-satisfying" is always relative to one commit's pre/post states (§11) — the runtime never stores membership, never diffs history, and never needs to: both states are transiently available while processing C, and that is the only place transitions exist (the transition law, §11). September's re-entry is a new entering commit and fires entry rules again — episodes are free at commit granularity (§11); durability across boundaries is the guard apparatus's job, visible in the spec (§18).

### `after commit`

An `after commit` firing runs as its **own transaction**, begun only after the triggering transaction is durable (§11). The act stands even if the firing fails; the declared backstop schedule heals the gap — which in v0 means: the harness calls the backstop tick function, and the guard makes re-evaluation harmless (§18). `[S2]` v0 drains the queue synchronously, immediately after durability, in FIFO order — an execution choice invisible to a valid spec, since each entry is an independent transaction whose rule carries its own guard.

### Ticks

`tick(S)` is a commit whose changed datum is `today`/`now` — the clock the harness controls (README §5, §22). It does not advance the clock; clock movement is a separate harness call. Processing a tick:

- **Rules naming `S` with an entry-form condition** (`when R ... on S`): one firing per **current member** of R — re-checking, not transition-watching (§11). Guards in the condition make the sweep idempotent and self-healing (§16, §18). Each firing is its **own transaction** (§17): one record's failure rolls back only itself; the rest stand and disarm their guards; the next tick retries exactly the stragglers.
- **Rules naming `S` with `when leaving R`**: re-checking is impossible for exits (a non-member carries no trace), so the subjects are the **leavers at the tick commit itself** — instances whose membership flips false because the tick's datum changed (aging out) — the same commit-local detection as any commit, the tick being the commit (§17, "transient membership is a policy"). *Accepted for v0, but derived here rather than stated anywhere in the README — flagged for stress-testing against realistic specs (`working-docs/TODO.md`): in particular, exits caused by non-tick commits between ticks are observed by `on commit` leaving-rules only, so a leaving-rule that names only a schedule sees only aging-out exits — confirm that reading survives real use cases.*
- **Rules not naming `S`** are untouched: a tick serves exactly the rules that list it in `on`. This is why `when OverdueInvoice` with no schedule under-fires (never observes entry by aging) and the validator says so (V3).

## The universal transaction

Where state lives beyond the process — the hydrating runtime over engineer-owned storage (`working-docs/investigate_runtime.md` §2–§7) — the runtime reads through engineer-supplied resolvers and lands each transaction's mutation set through a commit callback. The **universal transaction** is the contract those two surfaces must jointly honor, per envelope. It is stated here because it is semantics — what "transaction" *means* once the store is real — and it is the one part of this document addressed to the *store* implementer rather than the runtime implementer: each clause states the guarantee in one sentence, then says what it means in practice. v0's in-memory state `[S1]` satisfies every clause trivially (one process, one thread, no store). No clause is validator-checkable — these are the premises the checks' theorems rest on, not checks themselves (`checks.md` gains nothing).

Velle never verifies these guarantees at runtime — it cannot; they concern behavior across processes and stores it never sees. That is what makes the contract worth reading carefully: **a broken clause fails nothing visibly.** The spec still validates, the tests still pass — but the things the validator proved (no double-applied deposits, invariants that hold, rules firing exactly when they should) quietly stop being true. How you deliver the guarantees is entirely yours: **one `SERIALIZABLE` database transaction per envelope delivers all five trivially**, and that should be the default choice until measurement says otherwise. An envelope that spans multiple databases, API calls, or files is distributed-transaction territory, and Velle stays out of it — coherence there is your engineering. The clauses below are the definition of coherent.

**U1 — Snapshot.**
*The guarantee: every resolver read within one envelope is answered from a single consistent state — the settled end-state of the transaction serialized immediately before this one (U3).*

In practice: while Velle processes one commit, every question it asks your store must be answered *from the same frozen moment*. If it asks two questions and someone else's transaction lands between your two answers, each answer was true at *some* moment — just not the same one, and that is a breach even though neither answer was individually wrong. The straightforward implementation: open one database transaction with consistent-read isolation when the envelope begins, and answer every resolver call from it. The runtime meets you halfway — it never asks the same question twice in one envelope (fetches are memoized), and it reads the envelope's own in-progress writes from memory — so you never serve mid-envelope state, and must not. A tick is the same deal: each tick opens a fresh look at settled state.

**U2 — Atomicity.**
*The guarantee: the envelope's mutation set becomes durable as one unit, or not at all.*

In practice: at the close of the envelope, your commit callback receives everything the transaction did as one payload — the act's insert, every rule-produced insert and update, and the capture writes and deletes. Write all of it or none of it. If anything goes wrong — your database throws, a constraint fires, a connection dies — the whole envelope must disappear and the error propagate; the callback runs *inside* the envelope, so throwing from it *is* the rollback signal, and the caller gets the error. No later envelope may ever observe half a payload. With one database this is just `BEGIN … COMMIT`/`ROLLBACK`; with storage spanning systems, making failure leave no visible half-state is your job. One thing you never see: refused acts — boundary validation runs before the envelope exists, so a refusal reaches your store as nothing at all.

**U3 — Serialization.**
*The guarantee: the observable history is equivalent to some order in which transactions ran one at a time, each reading (U1) its predecessor's settled end-state — with real time respected between conflicting transactions.*

In practice: "as if one at a time" does **not** mean single-threaded. Two envelopes that touch entirely different data may run fully in parallel — no observer can tell, and the contract doesn't care. The obligation is only between envelopes that touch the *same* data: those must take turns, and the one acknowledged first goes first (that is all "last-in-wins" ever meant). The subtle part: "touching the same data" includes *checks*, not just writes. Velle's guard idiom reads "no `DepositApplication` exists for this deposit yet," then creates one. Run two envelopes for the same account concurrently under plain snapshot isolation and *both* see "none exists yet" — both fire, and the deposit applies twice. That failure mode (write skew) is exactly what popular defaults like Postgres `REPEATABLE READ` permit, so **snapshot isolation is not sufficient**. Two honest implementations: `SERIALIZABLE` isolation on one database, which buys the whole clause outright; or a lock on the entity the envelope revolves around — `SELECT … FOR UPDATE` on the account row, a per-account mutex, a partition key — so same-account envelopes serialize and different accounts run in parallel.

Knowing *what value to lock on* is answered from the spec itself (OQ40, settled 2026-08-18). The compiler derives, per exposed act and per schedule-fired rule firing, a **serialization domain**: the set of **queue keys** the envelope's work revolves around, computed from the envelope's whole read-and-write footprint — predicate reads included, which is what catches the guard example above (the guard read and the witness creation meet on the same key). Two envelopes conflict iff their domains intersect: path keys by the row they evaluate to (`deposit.account`), value keys by equal committed values (the uniqueness case, where no row exists yet to lock), and a whole-shape width with anything touching that shape. The domain is derived, never declared — no author writes a queue key — and it reaches the engineer as the generated commit function's contract ("Queue key: [deposit.account]") and reaches both engineer and product owner as the contention map (`diagrams.md`). Where a read correlates to no key the domain honestly widens to the whole shape and the A5 advisory (`checks.md`) demands a stated policy: correlate the read, move the rule to a schedule, or declare `tolerates contention` on the declaration carrying the read. Delivering the serialization across stores remains the engineer's mechanism choice, per the exclusions below; the contract's job is to *name* the keys. What the mechanism owes the contract is one moment: each key's concrete value is computed *at admission*, before the envelope's work runs, against pre-commit state plus the act's own inputs — and "pre-commit state" includes **captured members**, which are committed state recorded at membership entry (typically real columns), so a key may hop through one (`reopenTicket.ticket.closedBy`) and the store must keep captures readable at admission time (ruled 2026-08-19; OQ42 audit R1).

**U4 — Permanence.**
*The guarantee: once an envelope is reported accepted, its effects are durable — every later snapshot reflects them.*

In practice: "accepted" must mean *it is in the store the next read hits, and it stays there*. Velle's entire recovery story is built on data: a `DepositApplication` row is the only memory that a deposit was applied. If that row vanishes — a cache that evicts, an acknowledgment sent before the write is actually durable, a restore from a stale backup — the hourly backstop sweep sees an unapplied deposit and applies it again. The same holds across processes: a second runtime hydrating your store must find everything the first one committed, because guard rows, episode flags, and capture rows are how it knows what already happened. This clause is also what `after commit` rules stand on — their firing begins only after the triggering transaction is durable, so they always see its effects.

**U5 — No side doors.**
*The guarantee: between one transaction's end and the next one's snapshot, Velle-visible state does not change — every mutation to the data behind the resolvers enters as a commit through the generated surface.*

In practice: **reading is always safe** — query the tables backing Velle shapes with your own SQL as much as you like; reads commit nothing (§4). Writing is the door that must stay shut: a hand-run `UPDATE`, a migration script, an admin console, another service writing the same tables — each changes data with no rules fired, no invariants checked, no guards observing. Everything Velle proved assumed changes arrive as commits; a side-door write is a case none of that analysis considered, and nothing will flag it — the data is simply wrong in ways the spec promised were impossible. Your own tables *beside* the Velle-backed ones are none of the contract's business. When outside data must flow in, it enters as commits through the exposed surface, or through a mapping that carries its validation obligation (§21).

### What each clause carries

The language-side layer, for the reader who works on Velle rather than against it: every static analysis that makes a validated spec trustworthy is a theorem with these clauses as premises. The map from clause to the proofs that spend it — what specifically stops being true when a clause is violated:

| clause | spent by |
|---|---|
| U1 Snapshot | sibling-commutation/confluence analyses (all of a commit's firings read one state); relevance gating; the transaction-end `never` check (a settled world to check) |
| U2 Atomicity | disarm soundness (mutation and witness are statements of one body — one payload); the capture last-reader contract (capture read and retraction land in one envelope); fan-out atomicity (§20, all-or-nothing batches) |
| U3 Serialization | one-writer at transaction scope; last-in-wins as the cross-commit order; guard soundness under concurrency (no two envelopes both see the guard armed) |
| U4 Permanence | `after commit` + backstop healing; intent-before-effect (the intent durably precedes the effect's envelope); cross-process guards and episodes |
| U5 No side doors | relevance gating; inductive `never` proofs; every derived trigger set (a side-door write fires no rules — drift stops being commit-mediated) |

### What the contract deliberately excludes

- **Mechanism.** One DB transaction, sagas over three stores, a file plus an API — Velle never knows. The contract is the *what*; the *how* is the engineer's, permanently (`working-docs/investigate_runtime.md` §2).
- **The world/state gap.** No clause makes an external effect atomic with its record — nothing can (§11, "the gap between the world and the state is irreducible"). The contract governs state; the gap is answered above the contract by intent-before-effect, `after commit`, and `tolerates loss`.
- **The pre-filter.** `fetchCandidates` over-returning is always legal — a performance surface under the superset contract (`working-docs/investigate_runtime.md` §6), orthogonal to correctness. Only `fetchCaptures` sits on the correctness side of that line (§7's deliberate asymmetry there), and its guarantees are U2/U4's.
- **Refusal handling.** Wrapper code may log, rate-limit, or audit refused acts in the engineer's own tables — that data is theirs, not Velle-visible state, and U5 doesn't touch it.

## Capture lifecycle

One timeline per membership (§8, §13): **entry commit** — captured expressions evaluated against post-state, values fixed; **during membership** — readable wherever the refinement's properties are in scope (narrowing, §8); **exit commit** — exit rules fired from that commit may read them (the last reader); **close of exit commit** — retracted. Re-entry re-captures from scratch. A capture never survives its transaction — which is why a capture-reading exit rule can never be `after commit` (validated, V7); durable reactions read reified acts instead (§13).

## Errors, refusals, and retry

Three distinct outcomes of an exposed call, never conflated:

- **Refusal** (step 1): the act violates a type or an input-constrained `never` — named in the result; no transaction ever began. The general rejection-scope question stays open (OQ17); this minimal shape is the settled v0 answer.
- **Rejection-as-data**: not an error at all — the act commits, the refusing fact lands, the caller reads it back (the pattern in `working-docs/patterns.md`, "Validation rejection is data"). The runtime does nothing special.
- **Transaction error** (step 6): an unexpected failure mid-transaction — the whole envelope rolls back, the caller is told, and retry is the caller's re-invocation `[S4]`, which re-evaluates generators (§5).

## v0 spike choices

Execution decisions for the spike — below the language, revisable without touching semantics:

- **[S1] In-memory state.** No persistence, no crash recovery. Backstop/guard semantics stay exercisable via explicit backstop ticks.
- **[S2] Synchronous `after commit`.** The queue drains immediately after the triggering transaction, FIFO, on the calling thread. Unobservable to a valid spec (independent guarded transactions).
- **[S3] Deterministic iteration + depth backstop.** Sibling firings run in declaration order (any order is valid — V16 proved commutativity); a cascade-depth limit errors the transaction as a belt-and-suspenders backstop behind the static quiescence proof.
- **[S4] No automatic retry.** A failed transaction reports; retrying is the caller calling again. (A real runtime might retry internally; the semantics — generators re-evaluated, same commits re-produced — don't change.)
- **[S5] Runtime `never` check at transaction end.** Rule-maintained invariants are statically proven, so a runtime violation is an internal error (a bug in the proof or the runtime) — v0 checks anyway because in-memory checking is cheap and the spike exists to find exactly such bugs.
