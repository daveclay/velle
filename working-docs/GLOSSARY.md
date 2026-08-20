# Glossary

Coined terms used across the working docs, one line each, with the pointer that defines them. Language constructs the README defines (shape, refinement, rule, guard, fold, capture, tick, act) aren't repeated here — this covers the working vocabulary invented along the way. Rule of use: a doc's first use of one of these terms should read sensibly to someone who has only this file.

## Acts and the trust boundary

- **act** — a shape instance committed from outside the system through `expose`; a request or message crossing the trust boundary (README §4).
- **transient act** — an act that exists only within its own commit's transaction (`expose transient`): an input to the state, not a member of it (README §4; decision record `investigate-transient.md`).
- **drift / drift-exposed partition** — a partition of a *persistent* act over mutable state is re-evaluated at every later change to that state, so a long-handled act "drifts" between sides: a spurious refusal per flip, a stale re-fired write per flip back. Worked exhibit: `examples/partition-drift/`; flagged by advisory A4.
- **handled-once act partition** — the fix idiom for drift under persistence: the partition is scoped to *unhandled* acts, each side anchored by its own rule's outcome evidence. Live in `payments.velle`.
- **anchor / anchored spelling** — the outcome-evidence conjunct (`... where not exists EditApplication for this`) that pins an already-handled act out of its partition.
- **correlation key** — a client-supplied business key (the idempotency-key pattern) a transient act carries and its outcomes copy, so callers can match responses to requests and duplicate submissions can be recognized (OQ26, settled 2026-08-19 → README §4 "Transient acts"): a documented idiom, deliberately not language machinery — the key's uniqueness is the client's to own, the dedup guard the author's to write.
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
- **predecessor recurrence** — the derived-history spelling of an order-dependent fold: a derived `previous` (`latest ... by` the ordering datum) plus self-reference one hop through it, so each record derives its running value from its predecessor's and the current total is a selector read; the served spelling for tick-cadence order-dependent folds (README §19; OQ15, ruled 2026-08-19).
- **arrival-order semantics** — what a commit-cadence stored fold describes: commits are serialized, so fold order is commit order — the order records arrived — which diverges from the business's ordering datum when a record arrives late (README §19; OQ15).

## Relationships and collections

- **ownership (of a relationship)** — what `one`/`many` declare: the declaring shape's property is the stored side — rules assign it, committers supply it — and the inverse is inferred, derived, unassignable; direction is not encoded because inference makes both sides traverse (README §6).
- **graduation point** — the moment an edge carries data (when, who, a grade, a quantity), it stops being a bare `many` edge and becomes a business fact with its own shape; join shapes are never ceremony, only facts (README §6).
- **fan-out assignment** — a collection-path assignment (`this.invoices.customer = ...`): one write per member of the collection, one `many` hop only, the mutated field named in the statement (README §6; checks V20, V1's coarse extension).
- **input closure** — the transport-level input to a generated commit function for a multi-part act: the act plus the inline part values that ride with it; whether that closure is "one instance" is OQ39's question.

## Proofs and checks

- **fail-closed** — when the prover can't decide, the spec is rejected, never silently accepted; calibration then grows the provable set.
- **calibration** — the post-v0 work of running realistic specs to learn how coarse the fail-closed checks can stay before they reject too much (OQ16, which also carries the V14 certificate whitelist since OQ15 settled).
- **one-writer** — every stored field has provably one writing rule per coincidence class (README §12; check V1).
- **disarm proof** — showing a rule's effects falsify its own trigger condition, breaking a re-fire cycle (check V2).
- **sibling firings / siblings** — two rule firings triggered by the same commit, where neither firing caused the other, so the runtime may run them in either order (`evaluation.md`, "Processing one commit" step 6). "Sibling" in these docs always means sibling rule firings, never a data relationship.
- **confluence** — sibling firings commute: every firing order yields the same outcome (OQ16; check V15).
- **quiescence** — a transaction terminates: eventually no rule's condition is newly matched (OQ16; check V16).
- **discharge** — satisfy a derived proof obligation; standard proof-theory usage, throughout README §18–§21. "The disarm proof discharges" = the compiler completes the required proof (the body provably falsifies its trigger); a "dischargeable state" is a trigger state the rule's own effects provably exit; an "undischarged obligation" is a compile error.
- **spent invariant** — an established `never` used as a proof input by other analyses ("the author states the invariant; the prover spends it," README §21); only a fully-discharged invariant is spendable.
- **certificate** — the stated reason a static cycle provably ends, drawn from a decidable whitelist: strict descent on a creation-fixed datum plus a base case, or acyclicity supplied by a `never` invariant. Consumed by V14 for definition-graph cycles and V16 for cascade quiescence; one shared vocabulary (ruled at OQ15's settling, 2026-08-19), grown by OQ16's calibration.
- **pinned (firing set)** — a commit's entrant/leaver sets are computed from one pre/post diff before any sibling firing runs, so a sibling's write cannot change who fires at that commit; what it can change is read by *bodies*, which see the evolving state (`evaluation.md` step 5/6; the distinction V15's read-write leg is built on).
- **polarity (of a consult)** — which way creating an instance can move a predicate: toward true (+), toward false (−), or both/unknown (0); signs come from `exists` under negation, refinement absorption, and count comparisons. Same-direction sibling effects on one predicate cannot race (`audit-sibling-confluence.md` P2).
- **fresh-instance argument** — a freshly created instance cannot satisfy a positive `exists ... for this` over a shape the same commit does not create, because nothing can reference it yet; so its creation provably cannot move consults gated behind such a refinement, and a fresh instance never *leaves* anything (`audit-sibling-confluence.md` P3; the V12/V18 family's reasoning aimed at consult analysis).
- **check IDs** — V1–V18 are validator errors, A-series are advisories (A4 = drift-exposed partition), F-series are type/form checks; all cataloged in `checks.md`.
- **OQ tags** — stable open-question numbers; index in `QUESTIONS.md`.

## Generated artifacts (`diagrams.md`, `testgen.md`)

- **projection principle** — every generated artifact (runtime, tests, diagrams) is a deterministic projection of the one authored spec: same spec in, same artifact out, regenerated at every compile — nothing is hand-maintained, so nothing can drift or disagree with a sibling (`diagrams.md`).
- **may-fire / may-cause** — the artifacts' over-approximation stance, matching the runtime's relevance gating: omitting a possible consequence lies, showing an unreachable one merely hedges. Proofs sharpen the picture (one-way arrows, pruned edges, exclusive diamonds); missing proofs weaken it honestly ("may flip"), never guess.
- **membership axis** — one independently varying dimension of refinement membership on a shape; state-flow diagrams render one small machine per axis, grouping refinements onto a shared axis only when their predicates are provably disjoint (`diagrams.md`).
- **rule graph** — the whole system's cause map on one page: acts and rules as nodes, "this commit's writes can change that rule's condition" as edges, guards on the edges, decision diamonds only where exclusivity is proven (`diagrams.md`).

## Runtime and storage (`investigate_runtime.md`)

- **envelope** — one act's transaction: the act's commit plus every consequence commit, all-or-nothing.
- **universal transaction** — the five-clause contract (U1 snapshot, U2 atomicity, U3 serialization, U4 permanence, U5 no side doors) the engineer's storage must honor per envelope; normative in `evaluation.md` (settled OQ36); U3's queue-key derivation settled with OQ40 (2026-08-18) — see the U3 clause and `checks.md` A5.
- **serialization domain** — the compiler-computed key set an envelope revolves around, expressed as paths from the act (`{this.account}` for a deposit): two envelopes conflict iff their domains intersect, so the domain is exactly what an implementation queues conflicting work on — derived from the envelope's footprint, never declared (OQ40, settled → `evaluation.md` U3; built in `compiler/.../Domains.kt`).
- **queue key** — the engineer/PO-facing name for one member of a serialization domain: work sharing a queue key is handled one item at a time in arrival order; separate queues run independently. Chosen over "lock"/"synchronized" (which name one mechanism) and "serialize" (which engineers read as data marshalling) (OQ40, settled; stated on the generated commit functions).
- **contention map** — a compilation artifact (text or diagram) stating each act's queue key in business words ("deposits queue per account"), what contends with what, and every wide domain with the read that caused it — how unintentional system-wide queueing becomes visible to engineers and product owners before production (OQ40, settled; built — `ContentionMapGen.kt`, emitted in `DIAGRAMS-<System>.md`, normative in `diagrams.md`).
- **hydration / resolver** — the runtime demand-fetches the state an envelope needs through engineer-implemented storage questions: by key, by reference, by shape, captures (§2, §5).
- **candidate pre-filter / superset contract** — a compiled query trusted only for *exclusion*: it may over-return, never under-return; the authoritative predicate check stays in one evaluator, in memory (§6).
- **polarity-dual** — how the pre-filter compiler keeps the superset contract: inexpressible predicate parts degrade to TRUE in positive position, FALSE under an odd number of negations (§6).
- **relevance gating** — a commit evaluates only the watched conditions its mutation footprint can affect, per static read summaries (§6).
- **self-contained condition** — one reading nothing beyond the base shape's own columns; the criterion for using the pre-filter soundly mid-envelope (§6).
- **scan floor** — the fallback fetch: all of a shape, when no keyed or filtered path applies.
- **retraction** — deletion of a capture row at the exit commit's close: the contract's one sanctioned delete, legal because a capture is memory, not a record of the world (§7).
- **session handle / store key** — runtime identity is process-local; durable identity belongs to the store and round-trips as `Ref.Persisted`/`StoreKey` (§8).
