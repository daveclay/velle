# Glossary

Coined terms used across the working docs, one line each, with the pointer that defines them. Language constructs the README defines (shape, refinement, rule, guard, fold, capture, tick, act) aren't repeated here — this covers the working vocabulary invented along the way. Rule of use: a doc's first use of one of these terms should read sensibly to someone who has only this file.

## Acts and the trust boundary

- **act** — a shape instance committed from outside the system through `expose`; a request or message crossing the trust boundary (README §4).
- **transient act** — an act that exists only within its own commit's transaction (`expose transient`): an input to the state, not a member of it (README §4; decision record `investigate-transient.md`).
- **drift / drift-exposed partition** — a partition of a *persistent* act over mutable state is re-evaluated at every later change to that state, so a long-handled act "drifts" between sides: a spurious refusal per flip, a stale re-fired write per flip back. Worked exhibit: `examples/partition-drift/`; flagged by advisory A4.
- **handled-once act partition** — the fix idiom for drift under persistence: the partition is scoped to *unhandled* acts, each side anchored by its own rule's outcome evidence. Live in `payments.velle`.
- **anchor / anchored spelling** — the outcome-evidence conjunct (`... where not exists EditApplication for this`) that pins an already-handled act out of its partition.
- **correlation key** — a client-supplied business key (the idempotency-key pattern) a transient act carries and its outcomes copy, so callers can match responses to requests (OQ26).
- **C0 pinning** — a transient act's partitions are evaluated against the state at its arrival commit and never re-evaluated by its own consequences (`evaluation.md`, "Transient acts").
- **HOLDS / TAX / GAP** — break-doc verdicts: the design handles the case · handles it at a cost · needs a new obligation or fails (`break-b.md`).

## Patterns

- **errors-are-refinements** — valid/invalid are refinement memberships, not control flow; consequence rules hang off the valid subset (README §8).
- **rejection-as-data** — a refusal lands as a fact the caller reads back; nothing unwinds (`patterns.md`, "Validation rejection is data").
- **conditioned acceptance** — "don't accept unless X" is the *definition* of accepted, not a transaction statement (`patterns.md`).
- **intent-before-effect** — an external effect hangs off a durable intent fact; executing the effect is the engineer's loop observing the intent (README §11).
- **materialize, then decide** — the transient-act idiom for async work: the handler copies the act into a durable intent within its transaction; decisions and effects hang off the intent (`break-b.md` Case 5).
- **ledger / episode** — history patterns: an append-only record read via `latest` · a bounded occurrence fact counted later (README §12, §20).
- **rung / rung recognition** — a spelling's position on the pattern spectrum (e.g. incremental vs. recompute — one description, two spellings); diagnostics point at the twin rung (README §19–§20).

## Proofs and checks

- **fail-closed** — when the prover can't decide, the spec is rejected, never silently accepted; calibration then grows the provable set.
- **calibration** — the post-v0 work of running realistic specs to learn how coarse the fail-closed checks can stay before they reject too much (OQ15–OQ16).
- **one-writer** — every stored field has provably one writing rule per coincidence class (README §12; check V1).
- **disarm proof** — showing a rule's effects falsify its own trigger condition, breaking a re-fire cycle (check V2).
- **confluence** — sibling firings commute: every firing order yields the same outcome (OQ16; check V15).
- **quiescence** — a transaction terminates: eventually no rule's condition is newly matched (OQ16; check V16).
- **discharge / spent invariant** — an established `never` used as a proof input by other analyses ("the author states the invariant; the prover spends it," README §21).
- **check IDs** — V1–V18 are validator errors, A-series are advisories (A4 = drift-exposed partition), F-series are type/form checks; all cataloged in `checks.md`.
- **OQ tags** — stable open-question numbers; index in `QUESTIONS.md`.

## Runtime and storage (`investigate_runtime.md`)

- **envelope** — one act's transaction: the act's commit plus every consequence commit, all-or-nothing.
- **universal transaction** — the guarantee set Velle assumes of the engineer's storage per envelope (snapshot reads, atomic writes, serialized conflicting commits); the engineer realizes it (OQ36).
- **hydration / resolver** — the runtime demand-fetches the state an envelope needs through engineer-implemented storage questions: by key, by reference, by shape, captures (§2, §5).
- **candidate pre-filter / superset contract** — a compiled query trusted only for *exclusion*: it may over-return, never under-return; the authoritative predicate check stays in one evaluator, in memory (§6).
- **polarity-dual** — how the pre-filter compiler keeps the superset contract: inexpressible predicate parts degrade to TRUE in positive position, FALSE under an odd number of negations (§6).
- **relevance gating** — a commit evaluates only the watched conditions its mutation footprint can affect, per static read summaries (§6).
- **self-contained condition** — one reading nothing beyond the base shape's own columns; the criterion for using the pre-filter soundly mid-envelope (§6).
- **scan floor** — the fallback fetch: all of a shape, when no keyed or filtered path applies.
- **retraction** — deletion of a capture row at the exit commit's close: the contract's one sanctioned delete, legal because a capture is memory, not a record of the world (§7).
- **session handle / store key** — runtime identity is process-local; durable identity belongs to the store and round-trips as `Ref.Persisted`/`StoreKey` (§8).
