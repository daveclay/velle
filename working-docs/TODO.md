# TODO

Actions only, one line each, verb-first. Context lives behind the links: open questions in `QUESTIONS.md` → `questions/`, decisions and reasoning in the investigation docs, worked examples in `patterns.md`, coined terms in `GLOSSARY.md`. If an item here needs a paragraph, the paragraph belongs in one of those and the item links to it.

## Now: calibrate v0 against realistic specs

- [ ] Write more realistic specs against the v0 pipeline — the empirical answer to OQ16 (which carries the V14 certificate whitelist, OQ15's residue). `examples/peptides/` is the first domain-first pass; its findings are in `break-peptides.md` and the section below.
- [ ] While spec-writing, exercise the calibration riders: rung-recognition boundaries (README §20), boundary/apparatus legibility at scale (formerly OQ19), and `when leaving R on <schedule>` tick-exit semantics (`evaluation.md` "Ticks" — derived, unexercised).
- [ ] Convert `billing.velle` (`ApplyDueChange`/`RecordDueChangeRefusal`) and `membership.velle` (`ApplyAssignment`/`RecordAssignmentRefusal`) to the anchored handled-once spelling — A4 flags them; `AdvisorySweepTest` inventories the debt. *(Superseded if those acts stay `transient` — both were migrated; confirm and close.)*
- [ ] Update README §8's "Frozen fields" fix text and `patterns.md` "Validation rejection is data" to the anchored spelling.
- [ ] Cover the remaining small constructs: 3+-binding sibling joins · per-hop freeze depth (`LockedLineItem = LineItem where order is SettledOrder { frozen ... }`) · self-referential derived property (V14 descent certificates accept the recurrence; exercised twice in `examples/peptides/`) · declared `many` field (README §6; exercised by `examples/enrollment/`).
- [ ] Add human-readable examples of *invalid* velle with the diagnostics each produces — `examples/` shows only working specs; nothing shows a reader what a rejection (e.g. V15's order-dependence error) looks like or how to fix it.

## Peptides stress pass (`break-peptides.md`)

- [ ] Rule on evaluation order for an input-constrained `never` over the act's own series: boundary check before processing, or a selector tie as a refusal — today a duplicate ordering datum is a runtime error, not a refusal (S3).
- [ ] Note in README §10/§12 that a ledger ordered by `timestamp on create` ties within one clock instant, and recommend an author-carried sequence datum or a per-commit harness clock step (S4).
- [ ] Decide OQ45 — a `never` over a persistent act whose predicate can flip after the act lands (S5).
- [ ] Relax V6 to accept a capture of the base shape's own derived property, which is evaluable for every instance (S6).
- [ ] Extend V15's episodes discharge to states flipped by a series creation read through an inferred inverse, not only by an own-column assignment (S8; rides OQ16).
- [ ] Teach the domain derivation to key a series read rooted at the subject's inferred inverse (`assays`, `climateReadings`, …) — 41 A5 widths on `examples/peptides/`, all of that shape (S12; rides the A5 stress-test item under serialization domains).
- [ ] Generate `never` cases for `or` ranges, and skip or demand a given for a positive case when a relationship `never` on the shape cannot be satisfied by synthesis (S11's remaining half).

## Cross-base unions (OQ44)

- [ ] Tighten V5, V6, V10, V12, V14, and V15's transition leg to reason per operand of a cross-base refinement (today: skipped, fail-open) — OQ44 exercises each; rides OQ16's calibration.
- [ ] Extend narrowing to `not (x is A)`, nested conditionals, and three-or-more-operand else-branches (`Validator.kt`, the narrowing block).
- [ ] Carry narrowing into a collection filter's source path, in the name check and in the read-summary collector (`Model.collectExpr`) — `if trial is GreenhouseTrial then count(trial.greenhousePlots where ...)` (`break-peptides.md` S14).
- [ ] Add a union notation to the class and state-flow diagrams (`diagrams.md`).

## Transient acts — Design B residue (`investigate-transient.md`)

- [ ] Decide an author-supplied refusal message on `never` (today a refusal names the violated `never`).
- [ ] Confirm outcome-mediated provenance satisfies `why` when provenance lands (README §22's `why` item).
- [ ] Grow V18 beyond the complement slice once V9's exhaustiveness engine exists.
- [ ] Revisit: can rules copy a transient container act *and its parts* into a durable tree? The part-copying rules appear to have no way to name the durable container a sibling rule just created (V17 bans the reference path, U1's snapshot hides the sibling's creation) — confirm or break the tracing; the outcome scopes OQ43.

## Runtime follow-ons (`investigate_runtime.md`)

- [ ] Build reverse-path candidate narrowing (per-watcher read paths walked backward from the mutation); bare-shape entrant diffs and aggregate pre-filters ride with it (§6).
- [ ] Switch the generated `System` to real time by default, controllable clock as the test affordance (§1).
- [ ] Implement V21 in the validator: `initially`/`timestamp` on an exposed shape is a compile error (OQ31, settled — `checks.md` V21).
- [x] Implement the exposure closure (`expose ... with` — README §6 "Inline part creation"): parser `withClause`, V22 + the V1 self-pair extension + closure shapes in the commit-kind derivation (V3/V15 reach), codegen nested input types, runtime closure commits, `ClosureTest`, and `examples/orders/` exercising it end-to-end. The V21 projection's *static* slice (back-reference unsuppliable is runtime-enforced today) rides the existing V21 item above.
- [ ] Remove the now-dead `initially` optional-parameter branch from `Codegen.kt` `commitFn` once V21 lands (optional `?` fields stay optional parameters — ratified 2026-08-20; no example spec puts `initially` on an exposed shape any more).
- [ ] Give the generated `SpecSupport` a `newSystem()` hook so the generated specs run over a store as the flow tests do (`examples/*/output/src/test/kotlin/velle/*OverSqliteTest.kt`; `investigate_runtime.md` §11).
- [ ] Decide whether two store obligations the conformance pass surfaced join `evaluation.md`'s universal-transaction contract: a deleted row's key is never reissued, and a scan's row order is the query surface's order (`investigate_runtime.md` §11; stated today in `Hydration.kt`'s kdoc).
- [ ] Fix the 16 compiler tests failing at HEAD — `ParserTest`'s billing fixture inventory, `RuntimeTest` committing `Customer` directly (billing enters through `SignUp` now), and fixture drift in `RuleGraphGenTest`, `StateFlowGenTest`, `DiagramGenTest`, `QueryCompilerTest`; verified present on a clean checkout before the store pass (2026-09-14).
- [ ] Teach SpecGen to generate refinement specs over rule-materialized shapes — the request/record split dropped its specs for PaidInvoice, SettledOrder, Delinquent, ClosedTicket, LockedNote, and kin (givens still reach those states; only the generated coverage vanished).

## v0 loose ends

- [ ] Decide: `if ... then` sharing the condition's line — language rule, or parser limitation to lift?
- [ ] Close validator gaps (tracked in `Validator.kt`'s header): V11 branch-sensitive narrowing · V12 at-most-one beyond the refinement slice (`singular_references.md`) · A-series beyond A4/A5.
- [ ] Add the static selector-discrimination check (selectors fail loudly at runtime today; the static proof rides OQ16 calibration — `investigate_runtime.md` §9).
- [ ] Extend the sibling-confluence audit's order sweep beyond identity-versus-reversed: seeded N-permutation sampling per commit (`audit-sibling-confluence.md`, residue; the knob is `VelleSystem.firingOrder`).
- [ ] Sharpen the audit's deliberately coarse spots as calibration demands: signed sums under positivity `never`s, `as`-aliased consult attribution, the cascade-induction write-up (`audit-sibling-confluence.md`, "What this audit does and does not establish").

## Serialization domains (OQ40/OQ42 settled → `evaluation.md` U3, `checks.md` A5, `audit-symmetric-evaluation.md`)

- [ ] Commutation-sweep extensions (`CommutationSweepTest`, OQ42 item 3's noted residue): randomized value exploration (value-boundary bugs the fixed small worlds miss), commit-versus-tick-firing pairs over `scheduledRuleDomains`, and triples.
- [ ] Precision follow-ups from the symmetric-evaluation audit (sound today, wider than necessary — `audit-symmetric-evaluation.md` P2–P4): element-scoped `this.` paths going opaque (P2), condition collector not expanding refinement filters / `is` atoms (P3), multi-hop route composition — payments' `ChargeResponse` width (P4).
- [ ] Stress-test the A5 advisory ruling against realistic specs once the OQ16 calibration question resolves (`docs/concurrency.md`, "Why the width warning is an advisory").
- [ ] Spec-generation phase 2 (boundary synthesis, scenario DSL, no-refire cases) and phase 3 (the `example` construct) — `testgen.md`.

## Delete investigation (`investigate-delete.md`, OQ37) — rulings implemented in v0 (2026-08-22)

- [ ] Stress-test rulings R1–R10 with adversarial use cases; anything that breaks reopens in the investigation doc — and changes the v0 implementation with it.
- [ ] Teach the serialization-domain analysis (Domains.kt) to propagate deletion-caused drift precisely — today a delete keys the deleted row and re-uses creation-shaped relevance at shape granularity (advisory A5 only).

## Language questions awaiting design

- [ ] Decide whether a duration may be built from a data value (`treatedAt + interval days`) — today a duration takes a literal count only, so "collect every N days" cannot be derived and the peptides collection tasks carry admin-stated due dates instead (`break-peptides.md` S13).
- [ ] Bootstrap/backfill triggers — OQ28.
- [ ] Erasure and retention — OQ27.

## Post-v0 re-derivations

- [ ] `requires` — OQ32 · `visible to` — OQ33 · schedule definition — OQ34 · cross-shape mixins — OQ35 (restore its README §22 item).

## Research

- [ ] Mine prior art: Eve, Alloy, CUE, SQL still unstudied; term rewriting (critical pairs, Newman's lemma) and CHR confluence checking are now load-bearing for OQ16. (Datalog's first pass landed: stratification → V14, recursive predicates → self-reference; its residue, sum types, settled as cross-base `or` — README §9; the adversarial pass is OQ44.)

## Doc chores

- [ ] Mine `random_notes.md`'s still-live bits (open-codegen/escape-hatch demands, visualization question) into proper homes, then delete it.
