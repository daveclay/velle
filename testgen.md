# Velle Spec Generation (testgen)

The design for generated executable specs — the third artifact of the transpile
output (README §22, "The extension framework"): alongside the runtime surface,
the transpiler emits **tests derived from the Velle spec**, organized so a human
can *find* the use case. The philosophy this serves: reading code is the hardest
problem in software; Velle surfaces what matters — the spec, and readable,
well-organized executable specs that verify it — and pushes generated code down.
A human reads the `.velle` file and these specs to understand the system; the
code below them is scoped to a function at a time.

## Organization: one file per business state

The unit a human searches for is a *state and what reacts to it* — a refinement
plus the rules hanging off it. One generated spec file per **story root**:

- A rule files under its condition's story root. The root is computed by walking
  the condition: a composition takes its leftmost operand (`ActionableOverdue` →
  `OverdueInvoice`); a partition refinement over an act shape whose predicate
  tests membership in another refinement roots *there* (`ApplicableDueChange` →
  `IssuedInvoice` — the freeze's story); a chain rule whose trigger shape is
  produced, not exposed, roots with its producer (`EmailReceipt` → `UnemailedReceipt`
  → `Receipt` ← produced by `SendReceipt` → `PaidInvoice`).
- `never`s and act-triggered rules file under their act shape.
- Every file opens with the **Velle it verifies, quoted verbatim** (pretty-printed
  from the AST) — reading the spec file is reading the description plus its proof.
- A generated `SPEC_INDEX.md` lists every file and every case sentence: the
  table of contents for "what does this system do."

## The case catalog (what is derived, per construct kind)

| Construct | Generated cases |
|---|---|
| Entry rule | the entering commit produces the effects; produced facts link back (`field: this` ⇒ field == subject); assignment values land (`invoice.due = newDue` ⇒ readable equality) |
| Guarded / `after commit` rule | firing happened after the boundary; the disarm holds (subject left its trigger state); backstop ticks are harmless (counts stable) |
| Tick sweep | member at tick → effect + linkage; the guard holds across ticks; a literal time window (`today - 7 days`) is advanced past and the sweep reopens |
| `when leaving` rule | exit produces the effects; the subject is no longer a member |
| Partition pair | the act subject is a member of exactly one side |
| `never` (single comparison against a literal, on an exposed act) | a violating act is refused and nothing commits; a boundary-legal act is accepted |

## The given/derived split

Generated cases need scenario data the spec doesn't contain — *which commits
reach the interesting state* is human judgment. The contract:

- **The generator derives the whens and thens.** Effects, linkage, disarm,
  refusals, windows — everything above is mechanical.
- **The developer owes the givens — and the generator demands them by name.**
  It emits a `RequiredGivens` interface, one documented method per scenario the
  spec's cases require (`enterSendReceipt(): Long` — "perform the commit that
  makes one new subject enter PaidInvoice; return its id"; `someInvoice(): Long`).
  The developer implements it as `class Givens(sys)` beside the generated specs;
  a missing given is a compile error naming exactly what's owed. Human judgment
  stays, but in one findable place, named in business language.
- Generated tests **sanity-check the givens** (the returned subject must actually
  be a member of the condition) so a wrong given fails loudly, not silently.

## Phases

1. **(built)** Story-root organization, provenance headers, the case catalog
   above, `RequiredGivens` demanding, `SPEC_INDEX.md`.
2. Boundary-value synthesis for refinement membership (predicate-driven value
   solving beyond single-literal comparisons); "already a member doesn't
   re-fire" cases; a scenario DSL (`given/whenCommitted/then` with
   `entered`/`produced`/`refusedBecause` vocabulary) replacing plain assertions;
   fold value assertions via the evaluator.
3. **The `example` construct**: the spec itself carries named example instances,
   collapsing the human-givens tier into full derivation — the description
   becomes self-illustrating (README §22).
