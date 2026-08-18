# Velle Diagrams

The design for generated diagrams — the comprehension artifacts of the
transpile output, alongside the runtime surface and the executable specs
(`testgen.md`). Compiling a Velle spec can produce pictures that let an
engineer or a product owner *see* the system: its shapes, its states and what
moves them, what causes what, and what one act's arrival sets in motion. Every
picture is derived from the same resolved model the validator checks.

Generation is opt-in per output module: a `--diagrams` flag on the generate
entry point writes `DIAGRAMS-<System>.md`, one markdown document of Mermaid
fences. Billing opts in — `examples/billing/output/DIAGRAMS-Billing.md` is the
living example of everything described below. Emitters:
`compiler/src/main/kotlin/velle/ClassDiagramGen.kt`, `StateFlowGen.kt`,
`RuleGraphGen.kt`, and `DiagramGen.kt` (sequence diagrams and document
assembly), with the shared commit-kind vocabulary in `Kinds.kt`.

## The projection principle

Three principles anchor the family. The author writes business facts in
intuitive syntax; comprehension is served by artifacts *derived from* the
spec, never by annotations added to it. Generated tests and diagrams are the
same derivation rendered twice — a test scenario can emit its own sequence
diagram, so a product owner reviews as pictures exactly what the test suite
executes as assertions.

The third is the family's own: **every artifact is a deterministic projection
of the one description.** UML's historical failure was never the pictures — it
was that diagrams were a *second*, hand-maintained description of the system,
and a second description drifts: drawn at design time, updated by nobody, and
eventually distrusted precisely because everyone knows nobody maintains it.
Velle removes the second description rather than the pictures. The spec is the
only authored thing; the runtime, the tests, and every diagram are projections
computed from it — same spec in, same artifact out, regenerated at every
compile. A projection cannot drift, and cannot disagree with a sibling
projection, because none of them is maintained; all of them are derived. This
is the README's "code is fungible" stance (§2) extended to documentation.
Determinism is also what makes the artifacts *reviewable*: a spec edit
surfaces as an artifact diff in the pull request, the system's behavioral
change visible as a picture change, with no human in the loop to forget to
update it.

One stance runs through every member: each diagram is **may**-something —
may-fire, may-cause, may-flip. The derivations over-approximate, matching the
runtime's relevance gating: a diagram that omits a possible consequence lies;
one that shows an unreachable branch merely hedges. Where a proof exists the
picture sharpens (a one-way arrow, a pruned edge, an exclusive diamond); where
it doesn't, the picture weakens honestly rather than guessing.

## The class diagram

Shapes and relationships — near-mechanical, with UML's own conventions carrying
what Velle derives:

- One class per shape, annotated `<<expose>>` / `<<expose transient>>`;
  refinements annotated `<<refinement>>`, attached to their base with the
  hollow-triangle arrow, each carrying its membership predicate in a note —
  membership is decided by the predicate, instant by instant, not fixed at
  creation. Composed refinements point at their operands with dashed arrows.
- `/name` marks a derived property — UML's marker for a derived attribute.
  Derivation expressions stay out of the class boxes (they live in the spec;
  Mermaid also treats parenthesized member lines as methods); the box says
  only that the property is derived. `captured` fields carry "captured at
  entry"; `frozen` clauses render as member lines.
- Declared relationships are edges labeled with the declaring field and the
  inferred inverse collection where one exists (`invoice (inverse lineItems)`),
  with cardinalities from the declaration (`one` / `many` / optional).
  A transient act's relationship gets no inverse — the instances are not kept.

## State-flow diagrams

A state is membership in a refinement — and memberships overlap (an invoice
can be Issued *and* Overdue *and* Archived at once), so the honest rendering is
not one flat state machine but one small machine per **axis** of membership,
one section per base shape. Refinements share an axis only when the compiler
proves their predicates disjoint (whole-expression complements, or
complementary comparisons on the same reads — `balance <= 0` against
`balance > 0`); everything else gets its own two-state axis. Where a proof is
missing the diagram gets weaker, never wrong. The author-declared state
partition (README §22) is the future sharpener — it would name the else-state
and turn proved-disjoint axes into declared ones.

Arrow directions are proven, not guessed, from three sources:

- **Monotone `exists` atoms.** `exists X for this` can only be satisfied,
  never unsatisfied — v0 has no delete (OQ37) — so a refinement guarded by one
  is a one-way state, a negated one is born-a-member with a one-way exit, and
  a conjunction of a positive and a negated atom is a **once-through chain**:
  never a member → member → out permanently, with the terminal state drawn
  explicitly.
- **Sign-pinned folds.** `never (Payment where amount <= 0)` proves every
  payment amount positive, so `sum(payments, amount)` only grows and `balance`
  only falls — the input-constrained refusal is what licenses drawing the
  Payment arrow toward Paid and never away. Where the senses conflict, the
  edge honestly says *may flip* and is drawn in both directions.
- **Time as a commit kind.** A predicate reading `today` gets a "time passes"
  edge in the direction the clock can move it — `due < today` can only be
  satisfied by time, never unsatisfied.

Entry and exit decorate the states: `when X` rules as entry actions, `when
leaving X` as exit actions, `captured` fields as entry captures, `frozen` as a
property of being in the state. A transient act renders as UML's choice
pseudostate — partitions decided once, at the commit, the final state being
the instance's removal — and when the partition guards are complements the
diagram states the totality outright: every commit takes exactly one branch,
V18 as a picture.

The first build demonstrated that these are analyses, not illustrations: the
once-through classifier noticed that billing's `ArchivedInvoice = exists
ArchiveRequest for this and not exists UnarchiveRequest for this` has a
**terminal** third state — once unarchived, `not exists UnarchiveRequest` is
false forever, so no later ArchiveRequest can ever re-archive the invoice. A
latent spec fact no sequence view could surface, found by the projection.

## The rule graph

The whole system's cause map on one page: a Mermaid flowchart whose nodes are
the exposed acts and the rules, where an edge means "this commit's writes can
change that rule's condition" — the sequence diagrams' may-fire derivation
rendered once, globally, instead of per act. Velle has no control flow, so it
is deliberately *not* a flowchart of steps; the notation is bent to the
semantics:

- **Fan-out is conjunctive, never a choice.** Every outgoing edge whose guard
  holds fires, in no order — so guards ride the edges as labels ("when
  PaidInvoice"), and no diamond is drawn for them. Classic flowcharts only
  have exclusive branching; drawing Velle's fan-out that way would fabricate a
  decision nobody makes.
- **A diamond is a claim of exclusivity, drawn only where proven.** Two places
  qualify: the accept/refuse split an input-constrained `never` compiles to (a
  `never` targeting the exposed act and reading only its own stored fields
  becomes a boundary diamond — violated means refused, nothing begins), and a
  transient act's partitions when their guards are provably exclusive
  (complements get a yes/no diamond annotated "exactly one branch").
- **Edge style is transaction structure.** Solid fires inside the same
  envelope; dotted crosses a boundary — `after commit`, or armed now and swept
  at the labeled tick.
- **The direction proofs prune impossible edges.** Reusing the state-flow
  analyses: a write that provably moves a condition toward false cannot arm
  the rule (a Payment only lowers `balance`, so no edge from the payment
  envelope to `RemindOverdue` — a payment quiets reminders, never causes
  them), a creation moving a membership toward true cannot fire a `when
  leaving` rule, and a condition provably false at birth drops the birth edge
  (`commitInvoice` is inert — a new invoice wakes no rule, visibly).
- **Guard-disarm is a labeled self-loop.** A rule whose own write provably
  moves its own condition toward false gets a self-edge reading "disarms its
  own guard" — the canonical-guard pattern (README §13) made visible as
  negative feedback, for both the after-commit disarm and the
  sweep-with-memory.

Conditions too long for an edge label truncate to their refinement head with
the full predicate in a legend under the fence.

## Sequence diagrams

Velle has no call graph — rules react to commits, nothing calls anything — but
everything a sequence diagram needs is statically derived, and Velle's
semantics map onto notation UML already has for exactly the right distinctions.
One diagram per exposed act, showing the act's **may-fire cascade**:

- **Lifelines** — the committer, the system, each schedule, and any external
  party the cascade involves.
- **The transaction envelope is a frame** — UML's `critical` region means
  "everything in this box is one atomic unit," which is exactly what an
  envelope is: the act's commit, its in-transaction firings, and the
  transaction-end `never` check, standing or falling together.
- **`after commit` is an async arrow** — UML's open arrowhead means "the
  sender does not wait for this," which is exactly the declared boundary: the
  arrow leaves the triggering frame and starts a new one, entered only after
  the first is durable.
- **Independent sibling firings are a `par` fragment** — and Velle is the rare
  system where drawing that is *provably honest*: V16 requires sibling order
  not to matter, so the diagram never has to invent a false sequence.
- **A partition or outcome dispatch is an `alt` fragment**, with the
  refinement predicates as the branch guards.
- **A backstop cadence is a loop on the schedule's lifeline** — "and this
  re-checks every Hourly tick until the guard disarms."

Boundary validation renders ahead of the envelope (a violation is a refusal
and nothing begins), and unresolved conditions stay visible as "when
⟨condition⟩" notes — the diagram says what *may* happen, never what will.

## Three tiers of concreteness

1. **Static, per exposed act** (compile time — built): the may-fire cascade
   from the derived trigger sets, with unresolved conditions left as guards.
   Complete, but branchy for deep cascades.
2. **Example-grounded** (future — rides the `example` construct, README §22;
   `testgen.md` phase 3): spec-carried example instances collapse every `alt`,
   diamond, and guard to the branch actually taken — one clean diagram per
   named scenario, generated alongside the test that executes it. Drift
   between "what the picture says" and "what the test asserts" becomes
   structurally impossible, since both render from the same derivation.
3. **Runtime trace** (future — rides the deferred `why`/provenance item): a
   firing record mapped back to source is precisely the data needed to render
   *what actually happened* for one real commit — provenance's most legible
   output form.

## Notation

Notation is presentation, not semantics: the load-bearing content is the
derived structure — frames, arrows, guards. The default target is **Mermaid**:
it renders natively wherever markdown is browsed (GitHub, GitLab, IDE
previews) with no toolchain, its syntax carries the whole mapping above
(`critical` frames, `-)` async arrows, `par`/`alt`/`loop`, statechart choice
pseudostates, flowchart diamonds), and one format covers every family member.
**PlantUML** is the designated opt-in second target where richer frame
labeling, activation bars, and lifeline destruction (a natural rendering for
transient acts — a lifeline that ends at its frame's close *is* `expose
transient`) earn the Java dependency. Both being text formats serves the
determinism principle directly: regenerated artifacts diff in pull requests,
so a spec edit surfaces as a readable diagram diff in review.

## Limits and future members

- **Time stays symbolic** ("next `Hourly` tick") until the schedule-definition
  construct lands (OQ34) — Velle knows cadence names, not durations, so no
  artifact may imply wall-clock timing.
- **Branch explosion** in deep cascades is real for tier 1; tier 2
  (example-grounded) is the answer, not more compact notation.
- **The contention map** (OQ40, open) is the family's *between*-envelope
  member: which acts queue on which keys, and where a system-wide queue forms.
  A sequence diagram is the *within*-envelope view; together they answer "what
  does this system do when things happen," and neither substitutes for the
  other.
- **UML's timing diagram proper** (state versus a time axis) maps to a
  *different* artifact than cascades: one instance's refinement memberships
  over its lifetime — the episode machinery would render it naturally, but it
  is its own diagram kind, not yet designed.
