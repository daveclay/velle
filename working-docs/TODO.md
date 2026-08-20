# TODO

Actions only, one line each, verb-first. Context lives behind the links: open questions in `QUESTIONS.md` → `questions/`, decisions and reasoning in the investigation docs, worked examples in `patterns.md`, coined terms in `GLOSSARY.md`. If an item here needs a paragraph, the paragraph belongs in one of those and the item links to it.

## Now: calibrate v0 against realistic specs

- [ ] Write more realistic specs against the v0 pipeline (three exist: `billing`, `membership`, `payments`) — the empirical answer to OQ16 (which carries the V14 certificate whitelist, OQ15's residue).
- [ ] While spec-writing, exercise the calibration riders: rung-recognition boundaries (README §20), boundary/apparatus legibility at scale (formerly OQ19), and `when leaving R on <schedule>` tick-exit semantics (`evaluation.md` "Ticks" — derived, unexercised).
- [ ] Convert `billing.velle` (`ApplyDueChange`/`RecordDueChangeRefusal`) and `membership.velle` (`ApplyAssignment`/`RecordAssignmentRefusal`) to the anchored handled-once spelling — A4 flags them; `AdvisorySweepTest` inventories the debt. *(Superseded if those acts stay `transient` — both were migrated; confirm and close.)*
- [ ] Update README §8's "Frozen fields" fix text and `patterns.md` "Validation rejection is data" to the anchored spelling.
- [ ] Cover the remaining small constructs: 3+-binding sibling joins · per-hop freeze depth (`LockedLineItem = LineItem where order is SettledOrder { frozen ... }`) · self-referential derived property (V14 descent certificates now accept the recurrence — exercise it in an example spec) · declared `many` field (OQ30 settled — README §6; exercised by `examples/enrollment/`).

## Transient acts — Design B residue (`investigate-transient.md`)

- [ ] Decide an author-supplied refusal message on `never` (today a refusal names the violated `never`).
- [ ] Confirm outcome-mediated provenance satisfies `why` when provenance lands (README §22's `why` item).
- [ ] Grow V18 beyond the complement slice once V9's exhaustiveness engine exists.

## Runtime follow-ons (`investigate_runtime.md`)

- [ ] Build reverse-path candidate narrowing (per-watcher read paths walked backward from the mutation); bare-shape entrant diffs and aggregate pre-filters ride with it (§6).
- [ ] Switch the generated `System` to real time by default, controllable clock as the test affordance (§1).
- [ ] Design committer-suppliable fields — OQ31 (determines generated commit-function signatures).

## v0 loose ends

- [ ] Decide: `if ... then` sharing the condition's line — language rule, or parser limitation to lift?
- [ ] Close validator gaps (tracked in `Validator.kt`'s header): V11 branch-sensitive narrowing · V12 at-most-one beyond the refinement slice (`singular_references.md`) · A-series beyond A4/A5.
- [ ] Add the static selector-discrimination check (selectors fail loudly at runtime today; the static proof rides OQ16 calibration — `investigate_runtime.md` §9).

## Serialization domains (OQ40/OQ42 settled → `evaluation.md` U3, `checks.md` A5, `audit-symmetric-evaluation.md`)

- [ ] Commutation-sweep extensions (`CommutationSweepTest`, OQ42 item 3's noted residue): randomized value exploration (value-boundary bugs the fixed small worlds miss), commit-versus-tick-firing pairs over `scheduledRuleDomains`, and triples.
- [ ] Precision follow-ups from the symmetric-evaluation audit (sound today, wider than necessary — `audit-symmetric-evaluation.md` P2–P4): element-scoped `this.` paths going opaque (P2), condition collector not expanding refinement filters / `is` atoms (P3), multi-hop route composition — payments' `ChargeResponse` width (P4).
- [ ] Stress-test the A5 advisory ruling against realistic specs once the OQ16 calibration question resolves (`docs/concurrency.md`, "Why the width warning is an advisory").
- [ ] Spec-generation phase 2 (boundary synthesis, scenario DSL, no-refire cases) and phase 3 (the `example` construct) — `testgen.md`.

## Delete investigation (`investigate-delete.md`, OQ37)

- [ ] Resolve the two remaining opens: exit-rules-at-deletion; the `delete` statement's edge cases.
- [ ] Stress-test rulings R1–R10 with adversarial use cases; anything that breaks reopens in the investigation doc.

## Language questions awaiting design

- [ ] Bootstrap/backfill triggers — OQ28.
- [ ] Sum types / union shapes — OQ29.
- [ ] Erasure and retention — OQ27.

## Post-v0 re-derivations

- [ ] `requires` — OQ32 · `visible to` — OQ33 · schedule definition — OQ34 · cross-shape mixins — OQ35 (restore its README §22 item).

## Research

- [ ] Mine prior art: Eve, Alloy, CUE, SQL still unstudied; term rewriting (critical pairs, Newman's lemma) and CHR confluence checking are now load-bearing for OQ16. (Datalog's first pass landed: stratification → V14, recursive predicates → self-reference; its residue is OQ29.)

## Doc chores

- [ ] Mine `random_notes.md`'s still-live bits (open-codegen/escape-hatch demands, visualization question) into proper homes, then delete it.
