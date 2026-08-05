# Velle Evaluation Model (v0)

The operational semantics of a transpiled Velle system: what the runtime does, stated once, in execution order. Nothing here is newly decided — this consolidates semantics settled across the README (§4, §8, §11, §13, §16–§19, §21) so an implementer follows an algorithm instead of re-deriving one from rationale. The handful of genuinely new calls are **v0 spike choices** — execution decisions below the language, made for the runtime-testing spike, not semantics — marked `[S#]` and collected at the bottom.

The input/output surface (exposed commit functions, tick and clock functions, typed accessors) is the harness contract in README §22's scope statement; this document is what happens *inside* a call.

## State

The state is the set of all shape instances and their stored fields (README §4). Per instance, the runtime holds:

- **`id`** — opaque identity, fixed at creation, `==` only (§5).
- **Stored fields** — scalar values and to-one references. `many` sides are derived from the inverse `one` (§6) and need no storage of their own.
- **Timestamp fields** — `on create` fixed at the creation commit; `on update` advanced by every commit that writes a stored field of the instance, creation included (§5).
- **Captures** — per-membership memory: for each refinement membership currently held that declares captured properties, the values fixed at entry (§8; lifecycle below).

Derived properties are never stored — they are computed on read from current state (§7). Refinement membership is never stored — it is the predicate, evaluated against current state (§8). `[S1]` v0 state is in-memory only: no persistence, no crash recovery; the crash-window semantics the language defines (guards, backstops) remain exercisable because backstop ticks are explicit harness calls.

## Commits and transactions

A **commit** is one mutation entering state at a discrete moment: an exposed act instance, one rule firing's effects, or a tick (§4). A **transaction** is the all-or-nothing envelope: an act's commit plus every commit its consequences produce, except where a boundary intervenes (§11). Boundaries arise three ways — declared (`after commit`), inherent (schedule sources), forced (external effects; none exist in v0 — no mechanism performs one, so `tolerates loss` parses and validates but marks nothing reachable).

### An exposed call

```
commitAct(actInstance):
  1. boundary validation: the input-constrained `never` guardrails compiled to this
     expose site (§21) — violation → refusal naming the violated `never`; nothing begins
  2. begin transaction T
  3. C0 := apply the act commit — insert the instance; evaluate `initially`
     expressions and generators; stamp `timestamp on create`/`on update`
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

Ordering within step 6 is **never observable in a valid spec**: the validator rejected any pair of sibling firings that could conflict (V16). Where one firing's commit satisfies another rule's condition, ordering is causality — the recursion structure provides it.

### Entry and exit are commit-local

"Newly-satisfying" is always relative to one commit's pre/post states (§11) — the runtime never stores membership, never diffs history, and never needs to: both states are transiently available while processing C, and that is the only place transitions exist (the transition law, §11). September's re-entry is a new entering commit and fires entry rules again — episodes are free at commit granularity (§11); durability across boundaries is the guard apparatus's job, visible in the spec (§18).

### `after commit`

An `after commit` firing runs as its **own transaction**, begun only after the triggering transaction is durable (§11). The act stands even if the firing fails; the declared backstop schedule heals the gap — which in v0 means: the harness calls the backstop tick function, and the guard makes re-evaluation harmless (§18). `[S2]` v0 drains the queue synchronously, immediately after durability, in FIFO order — an execution choice invisible to a valid spec, since each entry is an independent transaction whose rule carries its own guard.

### Ticks

`tick(S)` is a commit whose changed datum is `today`/`now` — the clock the harness controls (README §5, §22). It does not advance the clock; clock movement is a separate harness call. Processing a tick:

- **Rules naming `S` with an entry-form condition** (`when R ... on S`): one firing per **current member** of R — re-checking, not transition-watching (§11). Guards in the condition make the sweep idempotent and self-healing (§16, §18). Each firing is its **own transaction** (§17): one record's failure rolls back only itself; the rest stand and disarm their guards; the next tick retries exactly the stragglers.
- **Rules naming `S` with `when leaving R`**: re-checking is impossible for exits (a non-member carries no trace), so the subjects are the **leavers at the tick commit itself** — instances whose membership flips false because the tick's datum changed (aging out) — the same commit-local detection as any commit, the tick being the commit (§17, "transient membership is a policy"). *Accepted for v0, but derived here rather than stated anywhere in the README — flagged for stress-testing against realistic specs (`TODO.md`): in particular, exits caused by non-tick commits between ticks are observed by `on commit` leaving-rules only, so a leaving-rule that names only a schedule sees only aging-out exits — confirm that reading survives real use cases.*
- **Rules not naming `S`** are untouched: a tick serves exactly the rules that list it in `on`. This is why `when OverdueInvoice` with no schedule under-fires (never observes entry by aging) and the validator says so (V3).

## Capture lifecycle

One timeline per membership (§8, §13): **entry commit** — captured expressions evaluated against post-state, values fixed; **during membership** — readable wherever the refinement's properties are in scope (narrowing, §8); **exit commit** — exit rules fired from that commit may read them (the last reader); **close of exit commit** — retracted. Re-entry re-captures from scratch. A capture never survives its transaction — which is why a capture-reading exit rule can never be `after commit` (validated, V7); durable reactions read reified acts instead (§13).

## Errors, refusals, and retry

Three distinct outcomes of an exposed call, never conflated:

- **Refusal** (step 1): the act violates a type or an input-constrained `never` — named in the result; no transaction ever began. The general rejection-scope question stays open (OQ17); this minimal shape is the settled v0 answer.
- **Rejection-as-data**: not an error at all — the act commits, the refusing fact lands, the caller reads it back (the pattern in `open_questions.md`'s appendix). The runtime does nothing special.
- **Transaction error** (step 6): an unexpected failure mid-transaction — the whole envelope rolls back, the caller is told, and retry is the caller's re-invocation `[S4]`, which re-evaluates generators (§5).

## v0 spike choices

Execution decisions for the spike — below the language, revisable without touching semantics:

- **[S1] In-memory state.** No persistence, no crash recovery. Backstop/guard semantics stay exercisable via explicit backstop ticks.
- **[S2] Synchronous `after commit`.** The queue drains immediately after the triggering transaction, FIFO, on the calling thread. Unobservable to a valid spec (independent guarded transactions).
- **[S3] Deterministic iteration + depth backstop.** Sibling firings run in declaration order (any order is valid — V16 proved commutativity); a cascade-depth limit errors the transaction as a belt-and-suspenders backstop behind the static quiescence proof.
- **[S4] No automatic retry.** A failed transaction reports; retrying is the caller calling again. (A real runtime might retry internally; the semantics — generators re-evaluated, same commits re-produced — don't change.)
- **[S5] Runtime `never` check at transaction end.** Rule-maintained invariants are statically proven, so a runtime violation is an internal error (a bug in the proof or the runtime) — v0 checks anyway because in-memory checking is cheap and the spike exists to find exactly such bugs.
