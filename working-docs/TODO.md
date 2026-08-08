# TODO

Actionable work only. Settled results live in `README.md` (§22 catalogs the open language items); open-question discussion lives in `open_questions.md` (OQ14–17, OQ20, each with a v0 stance). This file tracks work neither of those carries.

## Now: calibrate v0 against realistic specs

- [ ] Write bigger realistic specs against the v0 pipeline — `billing.velle` and `membership.velle` exist, but both were written to exercise the implementation; the point now is specs designed from a business domain outward. This is what answers OQ14–16 empirically, and it carries the deferred calibration riders: rung-recognition boundaries (README §20), boundary/apparatus legibility at scale (formerly OQ19), and `when leaving R on <schedule>` tick-exit semantics — a schedule-only leaving-rule observes only aging-out exits (derived in `evaluation.md` "Ticks"); stress-test that reading.

## v0 loose ends (surfaced by the implementation pass)

- [ ] `if`'s `then` must share the condition's line — newline-discipline surprise; decide whether that's the language rule or a parser limitation to lift.
- [ ] Author-named `many` fields have no commit story — F4 totality would demand a committer-supplied collection, and no syntax provides one; inferred inverses are the only working spelling. Decide: add syntax, or make the restriction official.
- [ ] Validator gaps (tracked in `Validator.kt`'s header): V11 branch-sensitive narrowing, V12 at-most-one proofs beyond the refinement slice (refinement subjects in `(Shape for expr)` are proven — see `singular_references.md`; base-shape to-one-inverse proofs stay runtime-enforced), V14 descent certificates, and the advisory A-series.
- [ ] Spec-generation phase 2 (boundary synthesis, scenario DSL, no-refire cases) and phase 3 (the `example` construct) — `testgen.md`.
- [ ] Dead references: `example_rules.md` and `example_predicates.md` still cite deleted files (`break_velle.md`, `example_composition_depth.md`, `discussion_hard_problems.md`).

## Post-v0 re-derivations

Cut from the spec by the v0 scope statement (README §22; `grammar.md` names the same list) — each re-enters through its own design pass, re-derived rather than restored from the old notes.

- [ ] `requires` — re-derive the rule-modifier keyword, distinct from `where`, and sync into the README. Its atomicity mechanism (lock, transaction, optimistic retry) stays a compiling concern, left to whatever fits the target.
- [ ] `visible to Role, Role` — re-derive field-level visibility. Carries its open sub-questions: how a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — with external-RBAC integration as the compiling-side alternative; and whether an undeclared-visibility field is fail-closed by default (language decision), plus enforcing that (compiling).
- [ ] Schedule definition — README §22 has the item, but two details live only here: event-anchored timeouts (working sketch: `via schedule <duration> after <Shape>`) and data-derived, not just literal, durations (`escalatedTo.role.timeoutMinutes`) — the grammar position must accept an expression.
- [ ] Cross-shape structural mixins — README §9 points at "Open/unresolved" but §22 has no such item; restore the item there (or take on the design: a trait like `Overdue` reusable across unrelated shapes).

## Research

- [ ] Prior art mining — Eve, Alloy, CUE, SQL still unstudied (Datalog got a first real pass in `example_predicates.md` #8). Now load-bearing: term rewriting (critical pairs, Newman's lemma) and CHR confluence checking, for OQ16's order-independence proof.
