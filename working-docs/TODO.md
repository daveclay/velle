# TODO

Actions only, one line each, verb-first. Context lives behind the links: open questions in `QUESTIONS.md` → `questions/`, decisions and reasoning in the investigation docs, worked examples in `patterns.md`, coined terms in `GLOSSARY.md`. If an item here needs a paragraph, the paragraph belongs in one of those and the item links to it.

## Now: calibrate v0 against realistic specs

- [ ] Write more realistic specs against the v0 pipeline — the empirical answer to OQ16 (which carries the V14 certificate whitelist, OQ15's residue). `examples/peptides/` is the first domain-first pass and `examples/pharmacy/` the second; their findings are in `break-peptides.md` and `break-pharmacy.md` and the two sections below.
- [ ] While spec-writing, exercise the calibration riders: rung-recognition boundaries (README §20), boundary/apparatus legibility at scale (formerly OQ19), and `when leaving R on <schedule>` tick-exit semantics (`evaluation.md` "Ticks" — derived, unexercised).
- [ ] Convert `billing.velle` (`ApplyDueChange`/`RecordDueChangeRefusal`) and `membership.velle` (`ApplyAssignment`/`RecordAssignmentRefusal`) to the anchored handled-once spelling — A4 flags them; `AdvisorySweepTest` inventories the debt. *(Superseded if those acts stay `transient` — both were migrated; confirm and close.)*
- [ ] Update README §8's "Frozen fields" fix text and `patterns.md` "Validation rejection is data" to the anchored spelling.
- [ ] Cover the remaining small constructs: 3+-binding sibling joins · per-hop freeze depth (`LockedLineItem = LineItem where order is SettledOrder { frozen ... }`) · self-referential derived property (V14 descent certificates accept the recurrence; exercised twice in `examples/peptides/`) · declared `many` field (README §6; exercised by `examples/enrollment/`).
- [ ] Add human-readable examples of *invalid* velle with the diagnostics each produces — `examples/` shows only working specs; nothing shows a reader what a rejection (e.g. V15's order-dependence error) looks like or how to fix it.

## Peptides stress pass (`break-peptides.md`)

- [ ] Implement the boundary guardrail as ruled (README §21; `evaluation.md` "An exposed call", the guardrail's moment): run `checkNevers` over the refusals and constraints (README §21) with a footprint of the act's commit alone, after C0's insert and before `process(C0)` — the runtime needs the classification `Validator.spendableNevers` computes; the peptides test `lab - a reading entered late slots into its hour mark and the running peak re-derives` then asserts a refusal instead of a runtime error (S3).
- [ ] Decide whether the deleting commit runs the boundary guardrail before processing — a deletion can make a `not exists` prohibition true (`evaluation.md` "The deleting commit").
- [ ] Align the compiler's user-facing text with the prohibition vocabulary (README §21, 2026-10-10): the V10 diagnostic says "rule-maintained" (`Validator.kt`), and the sequence-diagram note, rule-graph legend, and state-flow legend say "input-constrained" (`DiagramGen.kt`, `RuleGraphGen.kt`, `StateFlowGen.kt`).
- [ ] Note in README §10/§12 that a ledger ordered by `timestamp on create` ties within one clock instant, and recommend an author-carried sequence datum or a per-commit harness clock step (S4).
- [ ] Decide what a constraint that reads the clock gets when a persistent record falls into it by aging — peptides' permit case, `never (Planting where trial is not PermittedTrial)`: no transaction is to blame, `checkNevers` (`Runtime.kt`) re-checks a clock-reading `never` at every transaction end, and every act in the system is refused until a person acts; V29 does not see it, since no rule produces the state. By V29's reasoning a check (the transient-request spelling as the discharge) or a ruling that the whole-system refusal is the constraint's meaning (S5's remaining half; `compiler/src/test/kotlin/velle/TickRejectionTest.kt` has the tick side).
- [ ] Relax V6 to accept a capture of the base shape's own derived property, which is evaluable for every instance (S6).
- [ ] Extend V15's episodes discharge to states flipped by a series creation read through an inferred inverse, not only by an own-column assignment (S8; rides OQ16).
- [ ] Teach the domain derivation to key a series read rooted at the subject's inferred inverse (`assays`, `climateReadings`, …) — 41 A5 widths on `examples/peptides/`, all of that shape (S12; rides the A5 stress-test item under serialization domains).
- [ ] Generate `never` cases for `or` ranges, and skip or demand a given for a positive case when a relationship `never` on the shape cannot be satisfied by synthesis (S11's remaining half).

## Pharmacy stress pass (`break-pharmacy.md`)

- [ ] Decide which of two wordings about a refusal's moment stands — README §21 ("checked once, at the request") or `checks.md` V18 and `evaluation.md` "An exposed call" (checked at the settled transaction as well, "the stronger statement") — and fix the other; the pharmacy spec's fill-number, verify-once, and pickup-once sentences show the difference (`break-pharmacy.md`, section 1).
- [ ] Let V29, V10, and V26 discharge a creation or deletion whose only effect on the aggregate a prohibition consults is in the safe direction — `never (StockLot where onHand < 0)` was refused because the nightly return to stock creates a record the balance reads, and a return only raises it (P4).
- [ ] Teach the disarm proof (V2, and V16 through it) to look through a named refinement whose predicate is an inverse collection's `is not empty` — `this is not QuarantinedLot` with `QuarantinedLot = StockLot where quarantines is not empty` is a disarm the rule's `Quarantine` creation performs (P7).
- [ ] Count a closure part as exposed in `SpecGen.kt`: the five prohibitions over `Prescription` in `examples/pharmacy/pharmacy.velle` are listed as "shape is not an act" and get no boundary case (P10).
- [ ] Let a generated boundary case name the role or relationship refusals on its act, or demand a given per act instead of one `some<Shape>()` given shared by every act that references the shape — `CountLot` and `SetReorderPoint` both draw their staff member from `someStaff()` (P10; S11's remaining half).
- [ ] Give a `when leaving R on <Schedule>` rule a generated case that ages a member out and ticks, or state that it is not generated — today's case drives a commit-caused exit and asserts nothing (P10).
- [ ] Fix the serialization-domain derivation's resolution of a sibling join's second binding: `exists (drug.ingredients as ing, order.patient.allergies as al where ing == al)` reports "'order' cannot be resolved on 'Ingredient'" — the second path is resolved against the first binding's element shape instead of the subject (`Domains.kt`; P11, beside S12).
- [ ] Add the three-way statement from `break-pharmacy.md` P13 to `patterns.md` and README §21: a refusal for data wrong on arrival, a refusal recorded as data for the requester's situation, a constraint on the record for a sentence the request's own handling makes true — chosen by whether the request's handling changes what the sentence reads.

## Cross-base unions (OQ44)

- [ ] Tighten V5, V6, V10, V12, V14, and V15's transition leg to reason per operand of a cross-base refinement (today: skipped, fail-open) — OQ44 exercises each; rides OQ16's calibration.
- [ ] Extend narrowing to `x is not A`, nested conditionals, and three-or-more-operand else-branches (`Validator.kt`, the narrowing block).
- [ ] Carry narrowing into a collection filter's source path, in the name check and in the read-summary collector (`Model.collectExpr`) — `if trial is GreenhouseTrial then count(trial.greenhousePlots where ...)` (`break-peptides.md` S14).
- [ ] Resolve the element of a refinement-filtered collection as the refinement, so an aggregate or selector over `claims where PaidClaim` can read `PaidClaim`'s own body properties — `sum(claims where PaidClaim, patientPays)` reports "'Claim' has no member 'patientPays'" (`break-pharmacy.md` P8).
- [ ] Add a union notation to the class and state-flow diagrams (`diagrams.md`).

## Transient acts — Design B residue (`investigate-transient.md`)

- [ ] Confirm outcome-mediated provenance satisfies `why` when provenance lands (README §22's `why` item).
- [ ] Grow V18 beyond the complement slice once V9's exhaustiveness engine exists.
- [ ] Revisit: can rules copy a transient container act *and its parts* into a durable tree? The part-copying rules appear to have no way to name the durable container a sibling rule just created (V17 bans the reference path, U1's snapshot hides the sibling's creation) — confirm or break the tracing; the outcome scopes OQ43.

## Runtime follow-ons (`investigate_runtime.md`)
- [ ] Make the loading of associated and joined records configurable in the query API (`investigate-reads.md` R5, Dave 2026-10-12): the first build returns references as typed handles navigated lazily; later, following GraphQL's lead, a query names the relationships it wants loaded with the root, nested to any depth, the framework loads exactly those batched per level, and every unnamed reference stays a lazy handle — so the N+1 cost is the caller's choice, not the framework's default.
- [ ] Support transaction isolation levels for reads: today a query through the read API is a read-only snapshot and nothing else (`evaluation.md`, "A read is a read-only snapshot"; `investigate-reads.md` R4); let the read request name the isolation it wants (read-committed, repeatable-read, serializable) and let the store binding map it — Dave, 2026-10-12.

- [ ] Build reverse-path candidate narrowing (per-watcher read paths walked backward from the mutation); bare-shape entrant diffs and aggregate pre-filters ride with it (§6).
- [ ] Switch the generated `System` to real time by default, controllable clock as the test affordance (§1).
- [ ] Implement V21 in the validator: `initially`/`timestamp` on an act is a compile error (OQ31, settled — `checks.md` V21).
- [x] Implement the exposure closure (`act ... with` — README §6 "Inline part creation"): parser `withClause`, V22 + the V1 self-pair extension + closure shapes in the commit-kind derivation (V3/V15 reach), codegen nested input types, runtime closure commits, `ClosureTest`, and `examples/orders/` exercising it end-to-end. The V21 projection's *static* slice (back-reference unsuppliable is runtime-enforced today) rides the existing V21 item above.
- [ ] Remove the now-dead `initially` optional-parameter branch from `Codegen.kt` `commitFn` once V21 lands (optional `?` fields stay optional parameters — ratified 2026-08-20; no example spec puts `initially` on an act any more).
- [ ] Give the generated `SpecSupport` a `newSystem()` hook so the generated specs run over a store as the flow tests do (`examples/*/output/src/test/kotlin/velle/*OverSqliteTest.kt`; `investigate_runtime.md` §11).
- [ ] Decide whether one store obligation the conformance pass surfaced joins `evaluation.md`'s universal-transaction contract: a deleted row's key is never reissued (`investigate_runtime.md` §11; stated today in `Hydration.kt`'s kdoc). The other, a scan's row order as the query surface's order, closed 2026-10-12: no store obligation; every query names its order (`investigate-reads.md` R10), and the generated specs' `last(shape)` reads name a datum.
- [ ] Decide whether a shape may declare a default read order that a query inherits when it names none (`investigate-reads.md` R10 leaves it open; today every query must name its order) — Dave, 2026-10-12.
- [ ] Fix the 16 compiler tests failing at HEAD — `ParserTest`'s billing fixture inventory, `RuntimeTest` committing `Customer` directly (billing enters through `SignUp` now), and fixture drift in `RuleGraphGenTest`, `StateFlowGenTest`, `DiagramGenTest`, `QueryCompilerTest`; verified present on a clean checkout before the store pass (2026-09-14).
- [ ] Teach SpecGen to generate refinement specs over rule-materialized shapes — the request/record split dropped its specs for PaidInvoice, SettledOrder, Delinquent, ClosedTicket, LockedNote, and kin (givens still reach those states; only the generated coverage vanished).

## v0 loose ends
- [ ] Implement the text operators `contains`, `starts with`, `ends with`, `like` (README §5, §10; `grammar.md`, ruled 2026-10-12 in `investigate-reads.md` R1): the contextual words in the lexer, `comparison` in the parser, validator typing (text × text → boolean), the evaluator (`like` with `%`, `_`, and `\` escapes), the query compiler rendering each to `LIKE` with escaped wildcards under the superset contract, and tests; README states them ahead of the implementation.

- [ ] Decide: `if ... then` sharing the condition's line — language rule, or parser limitation to lift?
- [ ] Close validator gaps (tracked in `Validator.kt`'s header): V11 branch-sensitive narrowing · V12 at-most-one beyond the refinement slice (`singular_references.md`) · A-series beyond A4/A5.
- [ ] Levels of advisory checks: treat the A-series as a documenting artifact of validation, with levels from "warn at the line" down to "record what the spec entails" (generated tests that make a constraint's rejections visible and executable, or a generated note), so a calculable case the author may not have intended is exposed without being noisy (the rejections decision, 2026-10-11: no warning, since a clear rejection makes a one-sided author self-correcting).
- [ ] Generate spec cases for a `never` with a non-comparison predicate (`SpecGen.kt` skips them, "Not yet generated"): one test per act the constraint can reject, asserting the refusal and carrying the rendered message, so a spec documents its own constraints in its generated suite (`examples/shipping/` has none today).
- [ ] Decide whether a `never` may carry an author-supplied sentence ("a shipment is never on the books for a cancelled order") as the top line of its rejection above the derived rendering; a string after the predicate is the smallest syntax (deferred at the rejections decision, 2026-10-11).
- [ ] Carry a `never` rejection of an after-commit firing as a `Rejection` the way a tick's is thrown: `drainAfterQueue` (`Runtime.kt`) records it in the failures list as a string today (reachable only through a clock-reading constraint, V29 covering the rest).
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

- [ ] Answer the eleven read questions in `investigate-reads.md` section 11 (no reads in the spec, the dropped spec-side constructs, the read request and result as data, reads as snapshot-only transactions, shipping before visibility, how a query is registered, the REST filter wire form, stable pages, withdrawing scan order, `contains`, what to build first) — OQ51.
- [ ] Make ordering by the store key an advisory warning, not an error: a read (composed or registered, `investigate-reads.md` section 4) or a selector that names `id` as an order datum has a defined meaning but one the author probably did not intend, since the key is minted and carries no business order (README §5, §10); the cursor's hidden tie-break (`investigate-reads.md` R2) is the sanctioned use and raises nothing — Dave, 2026-10-12.
- [ ] Decide whether a duration may be built from a data value (`treatedAt + interval days`) — today a duration takes a literal count only, so "collect every N days" cannot be derived and the peptides collection tasks carry admin-stated due dates instead (`break-peptides.md` S13).
- [ ] Bootstrap/backfill triggers — OQ28.
- [ ] Erasure and retention — OQ27.

## Post-v0 re-derivations

- [ ] `visible to` — OQ33 · schedule definition — OQ34 · cross-shape mixins — OQ35 (restore its README §22 item).

## Research

- [ ] Mine prior art: Eve, Alloy, CUE, SQL still unstudied; term rewriting (critical pairs, Newman's lemma) and CHR confluence checking are now load-bearing for OQ16. (Datalog's first pass landed: stratification → V14, recursive predicates → self-reference; its residue, sum types, settled as cross-base `or` — README §9; the adversarial pass is OQ44.)

## Doc chores

- [ ] Mine `random_notes.md`'s still-live bits (open-codegen/escape-hatch demands, visualization question) into proper homes, then delete it.
