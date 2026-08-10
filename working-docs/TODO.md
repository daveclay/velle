# TODO

Actionable work only. Settled results live in `README.md` (§22 catalogs the open language items); open-question discussion lives in `open_questions.md` (OQ14–17, OQ20, each with a v0 stance). This file tracks work neither of those carries.

## Now: calibrate v0 against realistic specs

- [ ] Write bigger realistic specs against the v0 pipeline — three exist (`billing`, `membership`, and `payments`, the first written business-outward: the errors-are-refinements interaction pattern end-to-end, retries, timeouts, compensation, the handled-once act partition). Continuing this is what answers OQ14–16 empirically, and it carries the deferred calibration riders: rung-recognition boundaries (README §20), boundary/apparatus legibility at scale (formerly OQ19), and `when leaving R on <schedule>` tick-exit semantics — a schedule-only leaving-rule observes only aging-out exits (derived in `evaluation.md` "Ticks"); still unexercised by any spec.
- [ ] Apply the handled-once act partition to the older specs and the README pattern text — the payments spec surfaced that a bare state partition over acts is **drift-exposed**: an already-applied act drifts into the refused side when the partitioning state changes later, recording a spurious refusal (once per flip), and drifts back to re-fire a stale write. Worked exhibit: `examples/partition-drift/` — both spellings over one domain, with `DriftDemonstrationTest` proving the misbehavior; the fix idiom is scoping the partition to *unhandled* acts, each side anchored by the outcome evidence its own rule produces (also live in `payments.velle`'s `UnhandledAddressChange`). Latent in `billing.velle` (`RefusedDueChange`), `membership.velle` (`RefusedAssignment`), README §8's "Frozen fields" fix text, and `open_questions.md`'s "Validation rejection is data" appendix — update all four. Also a **candidate compiler advisory**: the drifting spec validates clean (the hazard is semantic), but "an act-shape partition over a non-monotone state predicate, with no handled-anchor" is statically recognizable — the same conjunct analysis the disarm proof uses. Related coarseness note: when two such families write one field, V1 correctly refuses the spec (a state flip is one commit driving both writers) — the hazard leaks out sideways only in that special case.
- [ ] Small-construct coverage, remaining after payments: a self-referential derived property (`root = parent?.root` — blocked until V14 grows descent certificates; today it's rejected as an uncertified cycle); a declared `many` field (blocked on the commit-story item below); 3+-binding sibling joins; per-hop freeze depth (`LockedLineItem = LineItem where order is SettledOrder { frozen ... }`).

## v0 loose ends (surfaced by the implementation pass)

- [ ] `if`'s `then` must share the condition's line — newline-discipline surprise; decide whether that's the language rule or a parser limitation to lift.
- [ ] Author-named `many` fields have no commit story — F4 totality would demand a committer-supplied collection, and no syntax provides one; inferred inverses are the only working spelling. Same family: a collection-valued field init from a filtered traversal (`basedOn: (this.invoices where OverdueInvoice)`, from the retired `example_rules.md`) appears in no grammar production. Decide: add syntax, or make the restrictions official.
- [ ] Validator gaps (tracked in `Validator.kt`'s header): V11 branch-sensitive narrowing, V12 at-most-one proofs beyond the refinement slice (refinement subjects in `(Shape for expr)` are proven — see `singular_references.md`; base-shape to-one-inverse proofs stay runtime-enforced), V14 descent certificates, and the advisory A-series.
- [ ] Spec-generation phase 2 (boundary synthesis, scenario DSL, no-refire cases) and phase 3 (the `example` construct) — `testgen.md`.

## Open language questions inherited from the retired example docs

- [ ] Bootstrap/backfill triggers — "for each *existing* member, once, immediately" when a rule is added to a live system; deferred by the retired `example_rules.md` and tracked nowhere else. Adjacent to schedule definition and the tick law, but distinct: it is about first deployment against pre-existing state, where entry commits already happened before the rule existed.
- [ ] Sum types — a field anchored to *either* of two shapes (an `Escalation` chaining from an `Alert` or a prior `Escalation`); left open by the retired `example_predicates.md`'s Datalog comparison. Today's workaround is two optional fields plus a `never` xor-invariant; whether that is the idiom or a union construct is warranted is undecided.

## Post-v0 re-derivations

Cut from the spec by the v0 scope statement (README §22; `grammar.md` names the same list) — each re-enters through its own design pass, re-derived rather than restored from the old notes.

- [ ] `requires` — re-derive the rule-modifier keyword, distinct from `where`, and sync into the README. Its atomicity mechanism (lock, transaction, optimistic retry) stays a compiling concern, left to whatever fits the target.
- [ ] `visible to Role, Role` — re-derive field-level visibility. Carries its open sub-questions: how a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — with external-RBAC integration as the compiling-side alternative; and whether an undeclared-visibility field is fail-closed by default (language decision), plus enforcing that (compiling).
- [ ] Schedule definition — README §22 has the item, but two details live only here: event-anchored timeouts (working sketch: `via schedule <duration> after <Shape>`) and data-derived, not just literal, durations (`escalatedTo.role.timeoutMinutes`) — the grammar position must accept an expression.
- [ ] Cross-shape structural mixins — README §9 points at "Open/unresolved" but §22 has no such item; restore the item there (or take on the design: a trait like `Overdue` reusable across unrelated shapes).

## Research

- [ ] Prior art mining — Eve, Alloy, CUE, SQL still unstudied. Datalog got a first real pass while the predicate grammar was derived: stratified negation became V14's stratification, recursive predicates resolved as ordinary self-reference, and the sum-types question above is its remaining residue. Now load-bearing: term rewriting (critical pairs, Newman's lemma) and CHR confluence checking, for OQ16's order-independence proof.
