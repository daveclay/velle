# Open questions and work items — the index

One line per question and per work item here; the discussion lives in `questions/OQ<n>-*.md`, where each file's header states the question in plain terms before the dense part begins. OQ tags are stable — numbers are never renumbered or reused (next free: OQ61), so references like `OQ16` stay valid forever, and gaps in the sequence are questions already settled (table at the bottom).

**Questions and items.** A question (an OQ) is a design decision with discussion worth keeping. A work item is one action, verb-first, one line. An item whose whole context fits in its line stands alone; an item with context lives under the question that holds it, and the context is in that question's file, never here. Items are grouped by the part of Velle they change, not by where they were found; S-numbers and P-numbers are findings of the peptides and pharmacy passes, recorded in OQ16's calibration record.

**When a question gets a tag:** any open design question with discussion worth keeping, or referenced from more than one doc, gets a tag and a file *at birth* — wherever it surfaced. An investigation document is a working file for an open question. When the question settles, the result promotes to the README or the normative docs, anything else worth keeping finds a permanent home (`patterns.md` at the root for a worked spelling, `GLOSSARY.md` for a coined term, or another open question), and the file is deleted; an investigation's reasoning is not kept. History is git.

**Lifecycle:** opened (tag + file) → discussion and items accumulate in the file → settled → the result promotes, the file is deleted, and the entry moves to the settled table with a pointer to where it retired.

The current milestone is calibrating v0 against realistic specs. Nothing here blocks the build; OQ16 — which since OQ15's settling also carries the V14 certificate-whitelist calibration — is precisely the question v0 exists to answer empirically, and `examples/peptides/` and `examples/pharmacy/` are its first two data points.

## Calibration

- **[OQ16](questions/OQ16-order-independence.md)** — can the compiler prove sibling firing order never matters? · calibration; completeness audit done 2026-08-20 (probes in `SiblingConfluenceAuditTest`; F1–F5 fixed, payments C1 resolved spec-side and held for ratification, Newman argument + residue); carries the V14 certificate-whitelist calibration (OQ15's residue) and the calibration record of the two realistic specs
  - [ ] Write more realistic specs against the v0 pipeline — the empirical answer to OQ16; peptides and pharmacy are the first two.
  - [ ] While spec-writing, exercise the calibration riders: rung-recognition boundaries (README §20), boundary/apparatus legibility at scale (formerly OQ19), and `when leaving R on <schedule>` tick-exit semantics (`evaluation.md` "Ticks" — derived, unexercised; pharmacy exercised it once and it held).
  - [ ] Ratify C1: payments' stock release runs as its own transaction after the exhausting commit (OQ16, "Residue held here"; live in `examples/payments/payments.velle` since 2026-08-20).
  - [ ] Extend V15's episodes discharge to states flipped by a series creation read through an inferred inverse, not only by an own-column assignment (S8).
  - [ ] Extend the sibling-confluence audit's order sweep beyond identity-versus-reversed: seeded N-permutation sampling per commit (the knob is `VelleSystem.firingOrder`).
  - [ ] Sharpen the audit's deliberately coarse spots as calibration demands: signed sums under positivity `never`s, `as`-aliased consult attribution, the cascade-induction write-up.
  - [ ] Stress-test the delete rulings (README §12 "Deletion", `checks.md` V23–V28) with adversarial use cases; anything that breaks reopens the ruling and changes the v0 implementation with it. The riders the rulings left open are OQ53's V24, V25, and fold items.
  - [ ] Cover the remaining small constructs: 3+-binding sibling joins · per-hop freeze depth · self-referential derived property · declared `many` field.
  - [ ] Mine prior art: Eve, Alloy, CUE, SQL still unstudied; term rewriting (critical pairs, Newman's lemma) and CHR confluence checking are load-bearing here.

## Prohibitions: refusals, constraints, guarantees (README §21)

- **[OQ52](questions/OQ52-prohibitions-second-pass.md)** — after the rejections rulings: the boundary guardrail's runtime, whether a refusal is checked once or twice, and a constraint that reads the clock and ages into violation · opened 2026-10-14 from the two passes
  - [ ] Implement the boundary guardrail as ruled: run `checkNevers` over the refusals and constraints with a footprint of the act's commit alone, after C0's insert and before `process(C0)`; then flip the peptides late-reading test to assert a refusal (§1).
  - [ ] Decide whether the deleting commit runs the boundary guardrail before processing — a deletion can make a `not exists` prohibition true (§1).
  - [ ] Decide which wording stands about a refusal's moment — README §21 ("checked once, at the request") or `checks.md` V18 and `evaluation.md` "An exposed call" (at the settled transaction as well) — and fix the other (§2).
  - [ ] Decide what a constraint that reads the clock gets when a persistent record falls into it by aging: a check with the transient-request spelling as the discharge, or a ruling that the whole-system refusal is the constraint's meaning (§3).
  - [ ] Add the three-way statement — refusal, refusal recorded as data, constraint on the record, chosen by whether the request's handling changes what the sentence reads — to `patterns.md` and README §21 (§2).
  - [ ] Align the compiler's user-facing text with the prohibition vocabulary: "rule-maintained" in the V10 diagnostic, "input-constrained" in the diagram legends and notes (§4).
  - [ ] Update README §8's "Frozen fields" fix text and `patterns.md` "Validation rejection is data" to a `transient` act or the anchored spelling (§4).
  - [ ] Decide whether a `never` may carry an author-supplied sentence as the top line of its rejection (§4).
  - [ ] Carry a `never` rejection of an after-commit firing as a `Rejection` the way a tick's is thrown (`drainAfterQueue` records a string today) (§4).
  - [ ] Decide the levels of advisory checks: the A-series as a documenting artifact, from "warn at the line" down to "record what the spec entails" (§4).

## Validator proofs

- **[OQ53](questions/OQ53-validator-precision.md)** — legitimate specs the fail-closed checks reject: V6, V29/V10/V26, the disarm proof, V10's induction · opened 2026-10-14 from the two passes
  - [ ] Relax V6 to accept a capture of the base shape's own derived property, which is evaluable for every instance (S6).
  - [ ] Let V29, V10, and V26 discharge a creation or deletion whose only effect on the aggregate a prohibition consults is in the safe direction (P4).
  - [ ] Teach the disarm proof (V2, and V16 through it) to look through a named refinement whose predicate is an inverse collection's `is not empty` (P7).
  - [ ] Prove a `never` over a rule-written field inductively instead of failing closed (S7, P5; rides OQ16's discharge vocabulary).
  - [ ] Collect the data point OQ37's coarseness ruling asked for: whether V25's accepted false positive (window arithmetic the refinement-overlap prover cannot see) forces the field-witness restructure often enough to justify sharpening the prover.
  - [ ] Let V24 discharge a referrer whose existence the deleter's scope provably excludes (P6: the fills of a never-filled prescription).
  - [ ] Write a spec that deletes against a stored fold twin or a snapshot and rule whether the existence-dependency check should name them (OQ37's flagged edge).
  - [ ] Implement V21 in the validator (`initially`/`timestamp` on an act is a compile error; OQ31), then remove the now-dead `initially` optional-parameter branch from `Codegen.kt` `commitFn`.
  - [ ] Close the validator gaps `Validator.kt`'s header lists: V11 branch-sensitive narrowing · V12 at-most-one beyond the refinement slice (`singular_references.md`) · the A-series beyond A4/A5.
  - [ ] Add the static selector-discrimination check (selectors fail loudly at runtime today; README §10; rides OQ16).

## Expressions, refinements, and unions

- **[OQ44](questions/OQ44-union-stress.md)** — stress-testing cross-base unions: the checks that still assume one base, the runtime, the generated output, and the diagrams, each against an adversarial spec · opened 2026-09-13 at OQ29's close
  - [ ] Tighten V5, V6, V10, V12, V14, and V15's transition leg to reason per operand of a cross-base refinement (today: skipped, fail-open); rides OQ16's calibration.
  - [ ] Extend narrowing to `x is not A`, nested conditionals, and three-or-more-operand else-branches (`Validator.kt`, the narrowing block).
  - [ ] Carry narrowing into a collection filter's source path, in the name check and in the read-summary collector (`Model.collectExpr`) (S14).
  - [ ] Resolve the element of a refinement-filtered collection as the refinement, so an aggregate or selector over `claims where PaidClaim` can read `PaidClaim`'s own body properties (P8).
  - [ ] Add a union notation to the class and state-flow diagrams (`diagrams.md`).
- **[OQ34](questions/OQ34-schedule-definition.md)** — schedule definition (deferred, post-v0; carries event-anchored timeouts and data-derived durations)
  - [ ] Decide whether a duration may be built from a data value (`treatedAt + interval days`); today a duration takes a literal count only (S13, P12).
- [ ] Decide: `if ... then` sharing the condition's line — language rule, or parser limitation to lift? (S10, P12)
- [ ] Decide whether the value vocabulary grows: a mean or maximum over a collection, an nth selector (second-largest), `Date - Date`, a boolean written as a comparison; by design today, and both passes spelled around all of them (S10, P12).

## Acts, transient and exposed

- **[OQ43](questions/OQ43-transient-containers.md)** — transient containers: a closure whose container is `transient act` · V17's direction forces the all-transient tree; v0 refuses the combination (`checks.md` V22) — remaining: the scoped V17 carve-out, V18's reach over transient parts, the durable-container/transient-branch mix; holds the transient-acts decision's residue
  - [ ] Revisit: can rules copy a transient container act *and its parts* into a durable tree? Confirm or break the tracing; the outcome scopes OQ43.
  - [ ] Confirm outcome-mediated provenance satisfies `why` when provenance lands (README §22's `why` item).
- **[OQ54](questions/OQ54-exhaustiveness.md)** — the exhaustiveness engine: V9, every request answered, `states of` · README §8's unbuilt goal, which V18's full form, the state partition declaration, and deletion's positive-exhaustive gate all wait on
  - [ ] Grow V18 beyond the complement slice once V9's exhaustiveness engine exists.
- **[OQ60](questions/OQ60-transient-acts-in-practice.md)** — transient acts in practice: whether payments and the drift exhibit should be respelled as transient requests, and whether transient should be the default · two threads left by the 2026-08-10 decision; not being worked

## Serialization domains and concurrency

- **[OQ55](questions/OQ55-serialization-domain-precision.md)** — the widths realistic specs produce: forty-one A5 advisories on peptides, fifty-seven on pharmacy, nearly all a series read through an inferred inverse · the derivation is sound (OQ40, OQ42 settled) and the ruling stays advisory (`patterns.md`, "A wide domain: the two authors"); this is precision
  - [ ] Teach the domain derivation to key a series read rooted at the subject's inferred inverse (S12).
  - [ ] Fix the derivation's resolution of a sibling join's second binding against the first binding's element shape instead of the subject (`Domains.kt`; P11).
  - [ ] Precision follow-ups from the symmetric-evaluation audit (OQ55 §3): element-scoped `this.` paths going opaque, the condition collector not expanding refinement filters or `is` atoms, multi-hop route composition.
  - [ ] Teach the derivation to propagate deletion-caused drift precisely (today a delete re-uses creation-shaped relevance at shape granularity).
  - [ ] Commutation-sweep extensions (`CommutationSweepTest`): randomized value exploration, commit-versus-tick-firing pairs over `scheduledRuleDomains`, triples.
  - [ ] Stress-test the A5 advisory ruling against realistic specs once OQ16 resolves (`patterns.md`, "A wide domain: the two authors").

## Spec generation

- **[OQ56](questions/OQ56-spec-generation.md)** — the generator's blind spots: boundary cases, aged exits, rule-materialized refinements, the suite's own reads · `testgen.md` phases 2 and 3 ride here
  - [ ] Count a closure part as exposed in `SpecGen.kt`, so the prohibitions over a part get boundary cases (P10).
  - [ ] Let a generated boundary case name the role or relationship refusals on its act, or demand a given per act, or skip a positive case a relationship `never` makes unsatisfiable by synthesis (P10, S11).
  - [ ] Generate `never` cases for `or` ranges (S11).
  - [ ] Generate spec cases for a `never` with a non-comparison predicate: one test per act the constraint can reject, asserting the refusal and carrying the rendered message.
  - [ ] Give a `when leaving R on <Schedule>` rule a generated case that ages a member out and ticks, or state that it is not generated (P10).
  - [ ] Teach SpecGen to generate refinement specs over rule-materialized shapes (PaidInvoice, SettledOrder, Delinquent, ClosedTicket, LockedNote, and kin; OQ31's residue).
  - [ ] Make the generated specs name an ordering datum instead of `last(shape)` over the runtime's scan order.
  - [ ] Give the generated `SpecSupport` a `newSystem()` hook so the generated specs run over a store as the flow tests do.
  - [ ] Spec-generation phase 2 (boundary synthesis, scenario DSL, no-refire cases) and phase 3 (the `example` construct) — `testgen.md`.

## Reads: the query API and stores (README §22)

- **[OQ57](questions/OQ57-reads-after-oq51.md)** — reads after OQ51: what a query loads, promises, and leaves to the store · OQ51 settled 2026-10-12; most items stated by Dave at the settling
  - [ ] Make the loading of associated and joined records configurable in the query API: lazy handles first; later a query names the relationships it wants loaded with the root, nested to any depth, batched per level.
  - [ ] Support transaction isolation levels for reads: the read request names the isolation it wants and the store binding maps it.
  - [ ] Decide whether a shape may declare a default read order that a query inherits when it names none.
  - [ ] Give a registered query its two remaining effects: a named candidate method in the generated typed store, and a generated test per registered query; both need the registry as an input to `gradle generate`.
  - [ ] Decide whether the plain accessors (`invoices()`, lists of handles in scan order) stay now that the query API exists.
  - [ ] Let the typed builder filter on collections (`many` fields and inferred inverses) and name a union-typed reference's operand (`Codegen.kt`, `fieldHandle`).
  - [ ] Make ordering by the store key an advisory warning, not an error; the cursor's hidden tie-break is the sanctioned use.
  - [ ] Decide whether "a deleted row's key is never reissued" joins `evaluation.md`'s universal-transaction contract (stated today in `Hydration.kt`'s kdoc).

## Runtime and clock

- **[OQ58](questions/OQ58-runtime-follow-ons.md)** — candidate narrowing, the production clock, timestamp ties · the runtime's leftovers plus peptides S4
  - [ ] Build reverse-path candidate narrowing (per-watcher read paths walked backward from the mutation); bare-shape entrant diffs and aggregate pre-filters ride with it.
  - [ ] Switch the generated `System` to real time by default, with the controllable clock as the test affordance.
  - [ ] Decide what a ledger ordered by `timestamp on create` does about ties within one clock instant: note it in README §10/§12 and recommend an author-carried sequence datum, or step the harness clock per commit (S4).
  - [ ] Fix the sixteen compiler tests failing at HEAD (`BillingFixtureTest`, `RuntimeTest`, and fixture drift in four generator tests; same sixteen on 2026-10-14).

## Deletion, erasure, retention

- **[OQ27](questions/OQ27-erasure.md)** — erasure and retention (right-to-be-forgotten) · language-side resolved via OQ37-R10 (`? initially required` + ordinary `= none` rules); residue: compilation's unretrievability guarantee. The delete rulings are README §12 "Deletion" and `checks.md` V23–V28; their stress test is under OQ16.

## Larger language questions

- **[OQ28](questions/OQ28-bootstrap-backfill.md)** — bootstrap/backfill: a new rule against pre-existing state · not designed
- **[OQ59](questions/OQ59-escape-hatch.md)** — the escape hatch: open codegen · deferred, post-v0; the demands from the project's earliest notes, kept when those notes were retired
- **[OQ33](questions/OQ33-visible-to.md)** — `visible to` field visibility, re-derived (deferred, post-v0; now also carries the authenticated-user and role notes, and OQ51's read-side viewer)
- **[OQ35](questions/OQ35-mixins.md)** — cross-shape structural mixins · design sketched 2026-09-13; syntax undecided
  - [ ] Restore README §22's mixin item, which README §9 still points at.

## Examples and docs

- [ ] Add human-readable examples of *invalid* velle with the diagnostics each produces — `examples/` shows only working specs; nothing shows a reader what a rejection (e.g. V15's order-dependence error) looks like or how to fix it.

## Settled

| tag | retired to |
|---|---|
| OQ1–4, 6, 8–13, 18 | settled before this index existed; results live in the README |
| OQ5 — external input | README §22 "External input (`expose`)" |
| OQ7 — exit reads | README §13; two residual threads became §22 items (latency vocabulary; `on commit of` narrowing) |
| OQ14 — guard adoption | resolved by author judgment: the canonical guard form (README §18) stands as what fold diagnostics (§19) demand — no sugar added |
| OQ19 — boundary/apparatus legibility | became a calibration rider on the spec-writing item (OQ16) |
| OQ36 — the universal-transaction contract | `evaluation.md`, "The universal transaction" (U1–U5: snapshot, atomicity, serialization, permanence, no side doors — precise guarantee + in-practice prose per clause, the proof-spends table, the exclusions); pointers in README §11/§22; glossary updated. U3's serialization-domain refinement (the derived queue key) spun off as OQ40 |
| OQ30 — author-named `many` fields: the commit story | README §6 (ownership frame; "Committing and assigning collections"; "Renamed and derived collections"), `grammar.md` (propType, `empty`, setExpr, assignment note, operator line), `checks.md` (V19–V20; F2/F4/V1 extensions), `evaluation.md` (stored edge sets; boundary duplicate refusal). Residue rulings OQ30-R1–R6 promoted with it; R5 spun off OQ38 (bags), R7 spun off OQ39 (inline part creation) |
| OQ20 — commit-refusal | not a primitive: refusal is compiled boundary code from `never` (README §21); the who-may-commit residue retired to engineer wrapper code (README §22) |
| OQ21 — construct set · OQ24 — harness boundary | README §22's scope statement |
| OQ22 — grammar | `grammar.md` (normative) |
| OQ23 — builtin surface | README §5, §10 |
| OQ25 — operational semantics & check catalog | `evaluation.md`, `checks.md` (normative) |
| OQ40 — serialization domains (the derived queue key) | `evaluation.md` U3 (the derivation, conflict-iff-domains-intersect, the surfaces, the cross-store exclusion); `checks.md` A5 (the width advisory — ruled advisory 2026-08-18, cadence discharge, dead tolerance); `grammar.md` (`tolerates contention` on rules and nevers); `diagrams.md` (the contention map, built); glossary updated. Implementation: `Domains.kt`, `ContentionMapGen.kt`, the queue-key contract in `Codegen.kt`, `DomainsTest`/`CommutationTest`. Trust residue (soundness audit, property sweep, the advisory dial) spun off as OQ42 |
| OQ41 — the compilation-artifact family | `diagrams.md` (normative): the projection principle, the four built members (class, state-flow, rule graph, sequence — `--diagrams`, billing's `DIAGRAMS-Billing.md`) with their proof-backed mappings, the tier ladder, notation (Mermaid default, PlantUML designated opt-in); README pointers in the opening and §22's architecture; glossary "Generated artifacts" section. Residue rides existing homes: tier 2 on `example` (`testgen.md` phase 3), tier 3 on provenance (§22), the contention map settled with OQ40 (built), the timing-diagram kind on the episode machinery |
| OQ26 — correlation keys for transient acts | resolved by author judgment (2026-08-19): the language offers nothing beyond what already exists — it trusts the client's key mechanism and the author's spelling, because anything more (storing, generating, or uniqueness-checking client keys) sits below the abstraction layer velle occupies. The convention promoted as the documented idiom in README §4 "Transient acts" ("Correlating responses to requests"); glossary updated. The four undesigned items close with it: the threading ceremony is the author's visible idempotency decision (the "no guard sugar" trade); uniqueness is the client's to own; in-flight duplicates are two serialized transactions met by whatever guard the author wrote; and the mechanism stays informal — no new language surface |
| OQ42 — trusting the domain derivation | the 13-case symmetric-evaluation matrix, every verdict pinned by `SymmetricEvaluationAuditTest` probes ( F1 — a reassignment's incoming reference went unkeyed for transient/ambiguous-inverse acts — fixed in `walkAssignment`, membership/enrollment contracts gained the keys; R1 ruled 2026-08-19: captures are committed state readable at admission → `evaluation.md` U3's admission-moment clause, captures included). Body-side `correlatable` (`bodyCorrelationFields`, 2026-08-18). The generated commutation sweep (`CommutationSweepTest` over every example spec; `DomainKeys.kt` as the queue-key contract's reference implementation). The A5 dial ruled advisory, reaffirmed → `patterns.md` ("A wide domain: the two authors"). Residue is OQ55: sweep extensions (randomized values, tick pairs, triples), audit precision P2–P4, and the A5 stress test once OQ16 resolves |
| OQ31 — committer-suppliable fields | ruled 2026-08-20: there is no field policy — which fields the committer supplies is read off the declaration forms. Promoted to README §22 "External input (`expose`)": the generated commit function takes every stored field of the exposed shape as a required parameter (convenience defaults live in the engineer's wrapper); derived properties are legal and never parameters (nothing stored to supply — on a transient act, the once-evaluated arrival-time value); `initially`/`timestamp` on an exposed shape is a compile error (`checks.md` V21) — system-maintained values live on unexposed shapes, supplied by a materializing rule's `from` block or generated at the record's creation, with §4's transient materialization as the served spelling. Examples migrated to the split 2026-08-20; implementation residue → OQ53 (V21 in the validator, `commitFn` cleanup) and OQ56 (SpecGen coverage for rule-materialized records) |
| OQ38 — duplicates in a relationship: a multiplicity-bearing `many`? | ruled 2026-08-20: no — collections stay sets, with no bag modifier. A relationship that genuinely carries multiplicity is modeled as data on an edge shape (a quantity, a role, a time) — the graduation point the boundary refusal's diagnostic already names. README §6 ("Committing and assigning collections") states the decided semantics; nothing new to promote |
| OQ39 — inline part creation (the exposure closure) | settled 2026-08-21. README §4 (one commit = one statically-shaped closure, the single instance as degenerate case), §6 "Inline part creation" (`expose ... with`: closure membership, V19 view-form resolution, trees only, bag input semantics, creation-only, part-triggered container writes refused totally — derive or container granularity), §20 (the business-object rung's input-side spelling), §22 (scope statement; External input). `grammar.md` (withClause; `with` contextual), `checks.md` (V22; V1 self-pair and V21 projection extensions), `evaluation.md` (closure commits), glossary "closure". Implementation (2026-08-21): Parser/Model closure resolution, Validator V22 + V1 self-pairs + closure commit-kinds, Codegen nested input types, Runtime closure commits (`ClosureTest`), `examples/orders/` end-to-end. Transient containers spun off as OQ43 (v0 refuses the combination, V22); the transient-materialization tracing parked in OQ43 |
| OQ15 — ordered folds and firing order at a tick | ruled 2026-08-19: rules carry no ordering clause — firing order is never language surface (an ordering clause would contradict OQ16's never-specified-order direction, manage at execution time what structure makes impossible, and restate an intent that already lives in the data model as the recurrence's ordering datum). The **predecessor recurrence** is the served spelling, promoted to README §19 ("Order-dependent folds at a tick") with the worked streak example, the late-arrival argument (a stored fold describes arrival order and keeps a wrong value; the recurrence re-derives to the truth), and the well-foundedness check (`checks.md` V14: stratify, then certify — certificate acceptance implemented in `Validator.kt` alongside the settling). Commit-cadence for the mutation form is arrival-order semantics, the permanent answer. Ties are model-side; `id` can never tiebreak (README §5, §10). Residue — growing the V14 certificate whitelist — rides OQ16 as one shared certificate vocabulary (V14 + V16). Glossary gained *predecessor recurrence*, *arrival-order semantics*, *certificate* |
| OQ29 — sum types / union shapes | settled 2026-09-13: `or` composes unrelated base shapes under README §9's unchanged property rule — a cross-base union exposes `id` and the inverses inferred through fields typed with it, everything else by per-operand narrowing; a static union (a pure `or` of bases) is a legal relationship target, the polymorphic association (README §6); narrowing by `is <Operand>` and the exhaustive else-branch (README §10); `for` matching by acceptance (README §14). `grammar.md` (propType, refinement composition), `checks.md` (F2, F3, V1, V3, V11, V13, V17, A2). Implemented in the v0 compiler (`UnionTest`), exercised by `examples/gallery/`. The rule-subject use composes with OQ35's mixin; the adversarial pass is OQ44 |
| OQ37 — deleting records | README §4 (derived legality), §8 "Frozen fields" (`undeletable`), §12 "Deletion", §13 "Deletion as a second exit cause"; grammar.md `ruleDecl`/`deletion`; checks.md V23–V28, A6; evaluation.md "The deleting commit". the stress-test pass is under OQ16 |
| OQ45 — a `never` over a persistent act whose predicate can flip after the act lands | merged into OQ47 (2026-10-09): one question with the rejection surface, under one principle |
| OQ32 — `requires`, re-derived | merged into OQ49 (2026-10-11): the rule-modifier form is subsumed by `evaluation.md` U3 and the refuse-or-record choice (README §21); the keyword re-enters there as a requirement stated on a shape |
| OQ49 — `requires` as a requirement stated on a shape, the positive twin of `never` | settled 2026-10-11: `X requires P` means `never (X where not P)`, the parser derives the complement, the subject is any shape or refinement name, membership is spelled `is`, a violation renders as the requirement unmet, and both keywords declare a prohibition → README §21 and §6, `grammar.md` `requiresDecl`, `checks.md` F2, `testgen.md`, `evaluation.md`; built in the v0 compiler (`RequirementTest`), `examples/pharmacy/` rewrites its eighteen negated prohibitions |
| OQ46 — which runs first, the rules that read a series or the `never` that keeps it well-ordered | ruled 2026-10-09: the boundary guardrail for refusals and constraints is evaluated over pre-state plus the act's closure, before processing, with one spelling for both moments and in addition to the transaction-end check → README §21, `evaluation.md` "An exposed call" (the guardrail's moment, with the worked example). Runtime implementation and the deleting-commit question are OQ52 §1 |
| OQ47 — rejections: the author's refuse-or-record choice per `never`, the advisory that would make it visible, and what a rejection carries and who hears it | settled 2026-10-11: refusal and constraint are different kinds of statement with different subjects, and a constraint's enforcement points are derived; no advisory, since a clear rejection makes the author's choice self-correcting and the calculation becomes a documenting artifact instead; a `never` refusal returns a `Rejection` (the act, the violating records, the pivot, the witnesses, the evaluation, and a rendered message) and a tick's rejection throws the same data with the rule and its subject in the act's place; the commit function's result is the engineer's rejection surface → README §21 and §22, `evaluation.md` "Errors, refusals, and retry" and "Ticks", `examples/shipping/`. The tick's repetition and delivery are OQ48; the author-supplied sentence and after-commit firings are OQ52 items; generated cases for non-comparison prohibitions are OQ56 |
| OQ48 — a scheduled rule that could be rejected by a `never`: error, advisory, or allowed | ruled 2026-10-11: a validation error. No valid use case survives (each is a guard, a record-and-show, or a transient-request spelling), a scheduled sweep must disarm itself, and the guarantee prover already fails closed on a rule that can reach a prohibition; a rule firing with no caller (on a schedule or after commit) that creates a record a constraint consults is refused with the guarantee's discharges, after-commit included as the same no-caller shape → `checks.md` V29, README §21, `evaluation.md` "Ticks". What validation cannot see, a clock-reading constraint violated by aging, stays a thrown `TickRejected` (`compiler/src/test/kotlin/velle/TickRejectionTest.kt`); carrying an after-commit firing's rejection as data is an OQ52 item |
| OQ50 — `act` in place of `expose`: the keyword that marks a shape as committable from outside should be the word the prose already uses | settled 2026-10-12: `act X { ... }` and `transient act X { ... }` replace `expose shape` and `expose transient shape`; an act is declared in exactly one place, where its fields are, and the statement written apart from its shape (`expose X with ...` under a `shape` declared elsewhere) is dropped rather than respelled; `shape` and `act` are the two structural nouns → README §3, §4, §6, §22 "External input (`act`)", `grammar.md` `actDecl`, `checks.md` V21, `evaluation.md`, `diagrams.md` (`<<act>>`), the glossary; built in the v0 compiler (`act` reserved, `expose` gone, the class-diagram stereotype and the runtime's not-an-act refusal reworded), every example rewritten and regenerated. The `act` plus `arrives` and `shape` plus `arrives` spellings were considered and withdrawn the same day |
| OQ51 — reading the system: what a caller may compose, where a query lives, what the read API promises | settled 2026-10-12, built 2026-10-13: the spec declares definitions and never a query; the caller composes a query from the spec's declared vocabulary in the spec's own predicate language, with a required order and cursor pages, and gets model objects back — one `Ref<T>` for both sides of the store boundary; text operators `contains`/`starts with`/`ends with`/`like`; a read is a read-only snapshot → README §5, §10, §22; `evaluation.md` "A read is a read-only snapshot"; `checks.md` F2; the investigation docs are retired with it (history is git); follow-ons are OQ57 |
| OQ17 — rejection scope | settled 2026-09-14 by author judgment: the transaction boundary already answers it. Refusal is whole and pre-commit — the unit accepted or refused is the commit's closure (README §4), nothing commits on refusal, and the result names the violated type or `never` (README §22 scope statement; `evaluation.md` "Errors, refusals, and retry"). Partial refusal is not a language question: a request that should succeed in part is separate acts, or one act that lands whole with its refused subset a refinement (`patterns.md`, "Validation rejection is data"). The author-supplied refusal message stays an OQ52 item |
