# OQ56 — Spec generation: the generator's blind spots

**Status:** open (2026-10-14) — gathered from the peptides and pharmacy passes and the settled questions whose generator residue was parked (OQ31, OQ47, the store conformance pass)
**In plain terms:** the generated executable specs are the projection principle applied to tests (`testgen.md`); every blind spot below is a place the generated suite says less than the spec does, so a hand-written test carries the coverage or the coupling is invisible from the generated file.
**See:** `testgen.md` (the case catalog, the given/derived split, phases 2 and 3) · `compiler/src/main/kotlin/velle/SpecGen.kt` · each example's `SPEC_INDEX-<System>.md` ("Not yet generated" lists what was skipped and why)

---

## Boundary cases (pharmacy P10, peptides S11)

- **A closure part is "not exposed".** `SPEC_INDEX-Pharmacy.md` lists the five prohibitions over `Prescription` under "shape is not an act". A prescription enters only through `act PrescriberOrder { ... } with prescriptions`, and `SpecGen.kt` does not count a closure part as exposed (`model.exposed`), so no boundary case is generated for the schedule II and five-refill bounds.
- **One "any Staff" given serves every role-gated act.** The boundary cases for `CountLot` and `SetReorderPoint` both fetch their staff member through `someStaff()`, and each act carries a role refusal the generator cannot see. The coupling is documented on the given (`examples/pharmacy/output/src/test/kotlin/velle/Givens.kt`) and invisible from the generated test. The same half-open finding from peptides: the generator cannot see a relationship `never` on the shape (`never (PlotObservation where plot.trial is not TreatedTrial)`), so the given that supplies "any committed `GreenhousePlot`" has to deliver a plot on a treated trial. **Wanted:** a generated boundary case names the role or relationship refusals on its act, or demands a given per act, or skips the positive case when a relationship `never` cannot be satisfied by synthesis.
- **`never` cases for `or` ranges.** Fixed for the positive case (each arm of an `or` is a forbidden comparison, S11); the negative cases for `or` ranges are not generated.
- **Non-comparison predicates.** A `never` with a non-comparison predicate gets no case ("Not yet generated"). **Wanted:** one test per act the constraint can reject, asserting the refusal and carrying the rendered message, so a spec documents its own constraints in its generated suite (`examples/shipping/` has none today; OQ47's residue).

## Rules and states

- **A `when leaving R on <Schedule>` rule gets a case that asserts nothing.** The case for pharmacy's `NoticeExpiry` drives a commit-caused exit and checks only that membership ended; the rule names only `Daily`, so no notice is expected and none is asserted. The generator has no way to exit a state by aging. The hand-written `expiry -` test carries the coverage. **Wanted:** a case that ages a member out and ticks, or a stated "not generated".
- **Refinement specs over rule-materialized shapes.** The request/record split (OQ31) dropped the generated specs for `PaidInvoice`, `SettledOrder`, `Delinquent`, `ClosedTicket`, `LockedNote`, and kin: givens still reach those states; only the generated coverage vanished.

## The generated suite's own reads

- **`last(shape)` over the runtime's scan order.** The generated specs read the newest record with `last(shape)` (`SpecGen.kt`; `SpecSupport`'s `last(shape)`), and the store promises no order (Reference §22). **Wanted:** name an ordering datum. The plain accessors (`invoices()`) keep today's order until then (OQ57).
- **The generated specs run in memory only.** `SpecSupport` builds its system directly. **Wanted:** a `newSystem()` hook so the generated specs run over a store as the flow tests do (`examples/*/output/src/test/kotlin/velle/*OverSqliteTest.kt`).

## Phases 2 and 3 (`testgen.md`)

Boundary synthesis, the scenario DSL, no-refire cases (phase 2); the `example` construct, letting the spec declare its own named instances so the generated specs derive scenario data from the description (phase 3; Reference §22 "Spec-carried examples").
