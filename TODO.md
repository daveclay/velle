# TODO

Split two ways, per `README.md` `## Principles`. **Language & structure** is what a human can declare — syntax, grammar, modeling idioms; the description, complete on its own. **Compiling** is what happens once a spec exists — executing it correctly, safely, efficiently (compiler obligations, guardrails, mechanisms, tooling); a separate, later phase, never part of resolving the language question. A few items were originally written as one bullet spanning both; those are split below and cross-referenced rather than force-fit into one side.

## Language & structure of Velle

### Settled, not yet synced into README.md

- [ ] `requires` — new rule-modifier keyword, distinct from `where` (its atomicity mechanism is a compiling concern, below) — `break_velle.md` #1
- [ ] Event-anchored schedules, surface syntax `via schedule <duration> after <Shape>` (the scheduling mechanism itself is a compiling concern, below) — `break_velle.md` #3
- [ ] `visible to Role, Role` field-level visibility syntax — `break_velle.md` #5
- [ ] Retroactive-invalidation modeling idiom (`supersedes`, corrections as new immutable instances, never in-place edits) — `break_velle.md` #6
- [ ] Split `README.md`'s single blurred "`why` / provenance" entry into two: data lineage is already resolved as an ordinary language pattern (explicit `basedOn`-style fields, populated by the rule author, not inferred by the compiler) — `break_velle.md` #6; source provenance is a compiling/tooling concern, below

*Synced this pass: transaction boundaries — the commit/transaction distinction (a rule's body is exactly one commit; a firing's effects are a new commit matching further conditions; a transaction is the all-or-nothing envelope, a descriptive term, never a keyword), the one-transaction default, the `after commit` preposition (boundary inherent at schedule sources, forced at external effects), the transition law, the two-way boundary/apparatus check, order-never-specified-and-must-provably-not-matter, and the re-grounded guard-durability story (crash windows exist only at boundaries) — now `README.md` §4, §11 "Transactions and `after commit`", §15, §17, §18. The open proofs and residue stay in `open_questions.md` OQ16–17, OQ20, tracked below.*

*Synced this pass: `and`/`or` refinement composition, self-referential/recursive shape and derived-property definitions, and the full predicate expression grammar (comparisons, `is`, `exists`, `count`/`sum`, `as` bindings, `this`/bare-name scoping, `latest`/`first` selectors, sibling joins, `.`-vs-`?.` narrowing) — now `README.md` `## Composing refinements`, `## Predicate expressions`, `## for`, and the `## Derived properties` self-reference note. `for` field ambiguity was revised after an initial colon-pair form (`for referee: this`) read badly and didn't survive a two-condition stress case (`example_composition_depth.md`) — settled instead as `exists (Shape where predicate)`, `for <expr>` staying as sugar for the unambiguous case only, sharing its fallback with cardinality ambiguity (`latest`/`first` over a `where`-filtered collection) rather than growing a second mechanism. `example_predicates.md` considers the predicate grammar itself fully settled: every remaining loose end (selector ordering, self-referential evaluation, `produces`/`requires` mechanisms, narrowing-analysis depth) is a compiling concern, tracked below, not a language gap. One adjacent, still-open item surfaced along the way: derived-property *value*-expression grammar (`if`/`else`, arithmetic) has never been formalized the way boolean predicates now have — added to `README.md` `## Open / unresolved`.

*Also synced: `produces` is a small inline `Mapping`, made visible in the syntax — the guard-scope field moves to an optional `for <field>` on `produces` itself, and the rule body becomes a plain `from { field: value, ... }` mapping, replacing the old overloaded in-body `for` — now `README.md` `## produces`, `## for`, `## then` — `example_predicates.md` #14. Totality *checking* remains a compiling concern, below.*

*Correction to #7 (`as` bindings), synced: the original motivating example (`CustomerWithBadInvoice`, `inv is OverdueInvoice and count(inv.payments where FailedPayment) >= 1`) never actually forced `as` — filtering by the named refinement directly and plain multi-hop traversal both already cover it with no binding. `as` only earns its keep when a deeper nested scope needs to reach a middle level's own field (the invoice-amount-vs-payment-amount case now in #7 and `README.md`'s `## Predicate expressions`). Surfaced while applying the "don't require defensive `as` when the compiler already catches real ambiguity" principle to `example_composition_depth.md`, which turned out to need zero `as` bindings across all nine of its refinements once corrected.

### Open design questions

- [ ] How a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — the external-RBAC alternative is a compiling/integration concern, below
- [ ] Undeclared-visibility field: should the *language semantics* be fail-closed by default? (enforcing that is a compiling concern, below)
- [ ] Canonical reversal pattern — `example_invoice_payment.md` #5 showed two valid options (resolution artifact vs. grace period) but never settled on "the" idiom for the common case
- [ ] True cross-shape structural mixins — a trait reusable across unrelated shapes (not just within one shape's own refinement family)
- [ ] Escape hatch / override syntax — how a human marks part of a spec as a contract (signature + conditions + invariants) instead of a declarative body; deferred all the way back in `discussion_hard_problems.md` (compiler-emitted conformance tests + generated implementation are a compiling concern, below)
- [ ] Data-derived (not literal) schedule durations — does the `via schedule <duration> after <Shape>` grammar position accept an expression (e.g. `escalatedTo.role.timeoutMinutes`), not just a literal (`10 minutes`)? — surfaced by `example_predicates.md` #9
- [ ] `Mapping` — full spec; the original shape-to-shape translation use case from the design goals has never been worked through end-to-end. Now also: a direct per-member spelling ("one `Reservation` per line item of the order") as *sugar over the guard-correlated fan-out* — the batch cases are already expressible and atomic as per-record rules whose commit-source firings share the triggering act's transaction, but the inverted spelling gets unpleasant as correlation deepens (README §20, "All-or-nothing batches"; ergonomics-adjacent to OQ14)
- [ ] Transaction boundaries, remaining open threads — the boundaries themselves are settled (one-commit default, `after commit`, synced-to-README item above); still open: semantics inside the joined commit (stepwise-vs-endpoint transitions, termination, transitive one-writer), rejection scope and whether commit-refusal is primitive or derivable from reified refusal — `open_questions.md` OQ16–17, OQ20 (the runtime mechanism — db transaction, orchestrated queue, sagas — is a compiling concern, out of scope there too)

## Compiling Velle into code

### Settled obligations, not yet cataloged

- [ ] Blanket compiler obligation for `produces`: safety + liveness under concurrent writers, not just single-writer chains — `break_velle.md` #4
- [ ] `produces`-as-`Mapping` totality checking — every field the output shape declares must have an explicit value in the rule body, or it's a compile error — `break_velle.md` #6

*Synced this pass: correctly (and efficiently) evaluating self-referential/recursive shape and derived-property definitions, and ordering `latest`/`first` selectors by implicit creation moment, are now noted in `README.md`'s `## Open / unresolved` "Compiled guardrails" bullet — `example_predicates.md` #9–11. The full running catalog remains the bigger, deferred task below.*

### Open design questions

- [ ] Scheduling framework mechanism itself — both calendar cadence (`Daily`) and event-anchored timeouts (`via schedule ... after ...`) still assume an undesigned cron-like framework; now also needs to support data-derived durations, not just literals
- [ ] `requires`'s atomicity mechanism — lock, transaction, or optimistic-retry, left to whatever fits the target, per the human/computer split
- [ ] Reconcile `requires` (`break_velle.md` #1) and `produces`'s "blanket compiler obligation" (`break_velle.md` #4) as *mechanisms* — same underlying implementation technique underneath, or two genuinely different ones that happen to look similar from the language side?
- [ ] External RBAC integration, as the alternative to a `Role` defined purely as a predicate
- [ ] Enforcing fail-closed semantics for an undeclared-visibility field (once the language decision above is made)
- [ ] Source provenance tooling — a `why` command tracing generated/running behavior back to the Velle source construct responsible (rule/refinement/shape); compile-time/source-level, closer to a source map
- [ ] Compiler-emitted conformance tests + generated implementation for escape-hatch contracts — the execution side of the escape-hatch language decision above
- [ ] Given/Then spec-generation tooling — decided conceptually (`README.md` `# Testing`), no concrete generator designed
- [ ] Parser / implementation — intentionally not started; revisit once shapes/rules/refinements feel stable

### Bigger, deferred on purpose

- [ ] Compiled guardrails catalog — start an actual running list of everything compiling must always enforce (forced prepared statements, atomic `produces`, forced totality checks, correctly evaluating self-referential definitions, erroring on out-of-scope bare names instead of scope-walking to resolve them, reporting a declaration change that creates new `for` field ambiguity (README §14) as one diagnostic connecting the declaration to every reference it now affects rather than an isolated error at either site, etc.) rather than leaving it as a scattered principle

## Neither — process / research

- [ ] Bigger realistic worked examples — after the language open questions settle; carries the deferred calibration questions (rung-recognition boundaries, README §20; boundary-apparatus legibility at scale, formerly OQ19; `when leaving R on <schedule>` tick-exit semantics — leavers at the tick commit itself, derived in `evaluation.md` "Ticks" — stress-test that reading, since a schedule-only leaving-rule observes only aging-out exits)

- [ ] Prior art mining — Eve, Alloy, CUE, Datalog, SQL, named in the very first design conversation; `example_predicates.md` #8 did a first real pass on Datalog specifically, the rest remain unstudied. Now also: term rewriting (critical pairs, Newman's lemma) and CHR confluence checking, load-bearing for OQ16's order-independence proof (`open_questions.md`)
