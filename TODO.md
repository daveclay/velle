# TODO

Tracking work beyond the `break_velle.md` stress tests themselves — syncing settled decisions back into the reference doc, and open questions that came up but got deliberately deferred rather than resolved.

## Sync into LANGUAGE.md

Constructs settled in `example_refinements.md` / `break_velle.md` that haven't been folded back into the reference doc yet.

- [ ] `and`/`or` combinator for named refinements (mixins) — `example_refinements.md`
- [ ] `requires` — atomic check-then-act rule modifier — `break_velle.md` #1
- [ ] Blanket compiler obligation for `produces` (safety + liveness under concurrent writers, not just single-writer chains) — `break_velle.md` #4
- [ ] Event-anchored schedules, `via schedule <duration> after <Shape>` — `break_velle.md` #3 (surface syntax only, mechanism still deferred)
- [ ] `visible to Role, Role` field-level visibility — `break_velle.md` #5
- [ ] `produces` as an inline `Mapping` + totality checking — `break_velle.md` #6
- [ ] Retroactive-invalidation modeling pattern (`supersedes`, corrections as new immutable instances) — `break_velle.md` #6
- [ ] Split `LANGUAGE.md`'s single blurred "`why` / provenance" entry into the two distinct concerns below (source provenance vs. data lineage)

## Open design questions (deliberately deferred, not decided)

- [ ] Scheduling framework mechanism itself — both calendar cadence and event-anchored timeouts still assume an undesigned cron-like framework
- [ ] How a `Role` (e.g. `PatientRole`) is actually defined — condition over an implicit `viewer`, external RBAC, something else
- [ ] Undeclared-visibility field: fail closed (compiler error) vs. some default?
- [ ] Reconcile `requires` (#1) with the "blanket compiler obligation" framing (#4) — same mechanism, or two genuinely different things that happen to look similar?
- [ ] Canonical reversal pattern — `example_invoice_payment.md` #5 showed two valid options (resolution artifact vs. grace period) but never settled on "the" idiom for the common case
- [ ] True cross-shape structural mixins — a trait reusable across unrelated shapes (not just within one shape's own refinement family)
- [ ] Predicate syntax itself — the expression language used inside `where`/`requires`/`visible to ... where` (comparisons, `and`/`or`/`not`, `is`, `exists`, `count`, `sum`, relationship traversal) has only ever been used by example, never formally specified as its own grammar with defined precedence and semantics — negation and disjunction semantics surfaced as real ambiguities in `break_velle.md` #6 without a home to be resolved in
- [ ] Source provenance — tracing generated/running behavior back to the Velle source construct responsible (rule/refinement/shape), the original `why`-command motivation from the very first design conversation; compile-time/source-level, closer to a source map
- [ ] Data lineage — tracing a specific produced effect instance back to the specific data instances that justified it at the moment it fired; runtime/instance-level, this is what #6's explicit `basedOn`-in-`produces` resolution actually addressed, but only for cases a human thought to model explicitly
- [ ] Escape hatch / override syntax — deferred all the way back in `discussion_hard_problems.md`, never revisited since

## Bigger, deferred on purpose

- [ ] Compiled guardrails catalog — start an actual running list of what compiling must always enforce (forced prepared statements, atomic `produces`, forced totality checks, etc.) rather than leaving it as a scattered principle
- [ ] `Mapping` — full spec beyond "`produces` is a small inline Mapping"; the original DTO-to-shape translation use case from the design goals has never been worked through end-to-end
- [ ] Prior art mining — Eve, Alloy, CUE, Datalog, SQL, named in the very first design conversation, never revisited in depth since
- [ ] Parser / implementation — intentionally not started; revisit once shapes/rules/refinements feel stable
- [ ] Given/Then spec-generation tooling — decided conceptually (`README.md` `# Testing`), no concrete generator designed
