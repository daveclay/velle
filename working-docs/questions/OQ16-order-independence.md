# OQ16 — Order must not matter: can the compiler prove it?

**Status:** open — calibration; the completeness audit ran 2026-08-20 (its probes are `SiblingConfluenceAuditTest` and `CommutationSweepTest`): five check gaps found and fixed (read vocabulary, creation-driven flips, captured values, after/after pairs, `affects()` vocabulary), one genuine order-dependence found in the payments spec and resolved by a spec decision (C1 — release moved to `after commit, Nightly`, held for ratification), the Newman argument written with its residue stated (the cascade induction, disarm sufficiency, N-permutation sampling). The coarse slices are implemented in full. Since OQ15 settled (2026-08-19), this question also carries the V14 certificate-whitelist calibration: definition cycles (V14) and rule cascades (V16) owe the same kind of termination answer, so their certificate vocabulary grows here, once, for both.
**In plain terms:** when one commit triggers several rules at once and no data flows between them, the outcome must be the same whichever fires first — can the compiler prove that, and how many legitimate specs get rejected because it can't?
**v0 stance:** ship the easy static checks (literal-path write-write and read-write conflicts, transition interference on watched refinements, DAG-or-disarmed-cycle quiescence — checks.md V15/V16), fail closed on everything else; calibration is what running v0 against realistic specs is for. The first discharge is also built: a spendable `never` (a constraint, so boundary-enforced) discharges the direct-case instance aliasing (V1) and supplies the V14 acyclicity certificate.
**See:** Reference §12, §15 · checks.md V15–V16 · patterns.md "A cascade, concretely" (the transaction shape this question reasons about) · OQ15 (the between-transactions sibling — settled 2026-08-19 → Reference §19, the predecessor recurrence; its V14 whitelist residue rides this question)

---

**Settled direction: firing order within a transaction is never specified, and never matters in a valid spec.** Velle states timeless facts, not runtime call sequences; the runtime may fire sibling rules in any order, or in parallel, and every order must produce the same outcome — the same final state and the same set of produced facts. Two cases, sharply different:

- **Where data flows, order is causality, not policy.** If R3's condition matches on the commit R2's firing produces, the dependency graph orders them — the ordering Reference §15 already calls free. Nothing to prove; nothing was chosen.
- **Where no data flows, order must provably not matter.** Sibling firings — one commit matching R2 and R3 with no dependency between them — must *commute*. A spec whose outcome depends on an unstated order is **inconsistent**: it describes two different systems and never says which. That is a whole-spec compile error in Reference §1's deepest sense — the spec fails to be a self-coherent description — reported as one connected diagnostic naming the rules involved, exactly like one-writer.

An author can absolutely write inconsistent Velle:

```
-- both fire from the same AccountReview commit
rule AdjustTier when AccountReview {
    account.tier = <formula over history>
}

rule RecordTier when AccountReview {
    review.tierAtReview = account.tier     -- before or after the adjustment? unstated.
}
```

`tierAtReview` differs depending on which firing runs first — the spec is ambiguous about its own meaning. The diagnostic demands the intent, not an ordering: "`tierAtReview` depends on the unstated order of `AdjustTier` and `RecordTier` — state what it means" (read the pre-adjustment inputs the formula reads, or make the dependency real by conditioning on the adjusted state).

The check decomposes along familiar lines: **write-write** conflicts — two siblings assigning the same field — are one-writer (Reference §12) extended to transaction scope; **read-write** conflicts — one sibling writes what another reads in a body or condition — are the example above; **transition interference** — one sibling's commit enters a refinement another sibling's commit exits — makes the set of mid-transaction transitions order-dependent, so transition-watching rules would see different histories. Traversal order (depth-first vs. level-by-level) stops being a question at all: once outcomes are order-independent, every traversal is a valid compilation.

**Where the analysis runs out of statics — the calibration cases.** The `tierAtReview` example is *easy*: same trigger shape, literal paths to one field. The hard cases are where the conflict's existence depends on runtime data, which static analysis cannot see. Fail-closed means none of these are ever *missed* — they are rejected — so each is a legitimate spec the author must restructure until safety is provable, and the open work is how much of that burden calibration can remove:

- **Instance aliasing through relationships.** Write-write detection works on paths, but paths name *routes*, not instances:

  ```
  rule PromoteBuyer when QualifiedPurchase {
      customer.tier = "gold"
  }

  rule PromoteReferrer when (QualifiedPurchase where customer.referrer is some) {
      customer.referrer.tier = "advocate"
  }
  ```

  Both fire from one purchase commit, writing `tier` of two *differently-reached* instances. They collide exactly when `customer.referrer == customer` — a customer who referred themselves. Whether that configuration can ever exist is a fact about the data, not the declarations; deciding it statically is the aliasing problem, undecidable in general. The rejection is honest, and the diagnostic can even name the collision condition — which points at the discharge below.

- **Value-dependent transition interference.** Two siblings write *different* fields — one-writer is silent — but a refinement reads both: one writes `balance`, another writes `creditLimit`, and `Overextended = Account where balance > creditLimit` watches the pair. Whether the two orderings produce different mid-transaction transition histories depends on the actual numbers in flight; statically there is only "both inputs of one predicate written by unordered siblings — *potential* interference."

- **Value-dependent quiescence.** The static condition graph sees only a cycle:

  ```
  rule SplitOversizedParcel when (Parcel where weight > 30) {
      Parcel from { shipment: shipment, weight: weight / 2 }
      Parcel from { shipment: shipment, weight: weight / 2 }
  }
  ```

  The cascade in fact terminates — halving falls below 30 — but proving it needs an arithmetic measure argument: termination proving, the halting problem in miniature.

**Open:**

- **Calibration.** How many legitimate specs the fail-closed checks reject is a question with zero data points: the example specs pass, but they were written alongside the checks. The loop is the first item under this question in `QUESTIONS.md` — write realistic specs, and triage every rejection into one of three outcomes: the restructure the diagnostic demands is acceptable authoring; a new certificate form earns whitelist entry (the decreasing-measure spelling is the standing candidate, deliberately left undesigned until real rejections shape it); or a new discharge form earns vocabulary entry. Term rewriting's critical pairs and CHR confluence tests remain the prior art to mine (an item under this question) for how rich the provable set can get.

- **The completeness audit — does "accepted" actually mean "order never matters"?** Executed 2026-08-20 (the channel matrix, findings F1–F5/C1/T1, and the precision work P1–P4 are history; the residue is below). Every enumerated divergence channel is now rejected, discharged, or safe by the commit semantics, each verdict pinned by an executable probe (`SiblingConfluenceAuditTest`; the whole-corpus order-permutation sweep in `CommutationSweepTest` re-checks every example world on every build via the runtime's test-only firing-order knob). Each suspect this file previously listed was confirmed and fixed; the payments spec yielded the audit's one genuine order-dependence (C1), resolved spec-side. What the audit leaves open, here: the induction from pairwise-sibling commutation to full-cascade commutation is argued from the recursion structure but not proven; order sampling is identity-versus-reversed, not N-permutations; and the polarity and fresh-instance analyses are deliberately coarse (sums unsigned, `as`-aliased consults unattributed) — each a calibration surface, not a model change.
- **Discharge vocabulary.** The aliasing case is what showed `never` prohibitions (since adopted — Reference §21) are not just verification but *proof inputs*: `never (Customer where referrer == this)` turns the collision condition into a proven impossibility the disjointness analysis may use — the author states a prohibition, the prover spends it. The first slice is built: a constraint (no rule writes what its predicate reads, so the boundary enforces it) discharges exactly this direct case in the one-writer analysis, and doubles as V14's acyclicity certificate. How much further spent prohibitions reach — and what else belongs in the discharge toolbox (conditioning one sibling on the other's outcome to make the dependency real; some not-yet-designed decreasing-measure spelling for cycles) — is calibration's concrete form. The certificate vocabulary is deliberately shared with V14's well-foundedness check (ruled at OQ15's settling, 2026-08-19; this question now owns the growth of that whitelist): a definition cycle in the derived-property graph and a rule cascade here owe the same kind of answer — a stated reason the chain provably ends — so one certificate family (strict descent on a creation-fixed datum, acyclicity supplied by a `never` prohibition, an eventual decreasing-measure spelling) feeds both V14 and V16, calibrated once, with the same diagnostic language on both sides.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (the parcel-splitting example above) — undecidable in general. The folds precedent (Reference §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined — it is the termination leg of the completeness audit above.

## Calibration record

Two whole businesses have been written against v0, each starting from the domain and letting it decide which constructs get used and how hard. Each finding named the checked-in artifact that shows it; the fixed ones are history (git), and the open ones are work items by feature in `QUESTIONS.md`. Verdicts: **HOLDS** (the language handles it), **TAX** (it handles it at a cost: a restructured spelling, a coarse check to work around), **GAP** (a check, the runtime, or the generator is wrong or missing, and something had to change).

**Peptides** (`examples/peptides/`, first pass 2026-09-15): a crop-protection discovery pipeline — a time series at every stage (lab plates at hour marks, greenhouse plots at day marks, climate sensors, site weather, field scouting), gates between stages, a series that gets locked once evaluated, and admin-configured collection tasks over a union of trial kinds.

| # | finding | verdict | where it stands |
|---|---|---|---|
| S1 | `this` inside a filter resolved to the element by the validator | GAP | fixed; probe in `ValidatorTest.kt` |
| S2 | `as` alias as a collection source lost its element | GAP | fixed; probe in `ValidatorTest.kt` |
| S3 | uniqueness `never` on a series datum reached after the rules that read it | GAP | ruled 2026-10-09 (OQ46), built 2026-10-14: the door check, Reference §21 and `evaluation.md` "An exposed call"; `PeptidesPipelineTest` asserts the refusal |
| S4 | ledger ordering by commit timestamp ties within one instant | TAX | OQ58 §3 |
| S5 | `never` over a persistent act is a statement about history | GAP | halt half ruled 2026-10-11 (OQ47, V29); clock-reading half ruled 2026-10-14: a validation error, `checks.md` V30 |
| S6 | capture of the base's own derived property refused by V6 | TAX | OQ53 |
| S7 | `never` over any rule-written field fails closed (V10) | TAX | OQ53; rides this question's discharge vocabulary |
| S8 | episodes discharge does not cover a series-fed state (V15) | TAX | below, "V15's episodes discharge" |
| S9 | predecessor recurrence over a business datum | HOLDS | `PeptidesPipelineTest.kt` |
| S10 | no collection mean/max, no date difference, no boolean from a comparison | TAX | by design; spellings recorded in the spec; the vocabulary question is an item under "Language questions" in `QUESTIONS.md` |
| S11 | generated boundary cases ignored `or` range prohibitions | GAP | fixed in `SpecGen.kt`; the relationship-`never` coupling is OQ56 |
| S12 | every series rule widens to a whole-shape queue (A5) | TAX | OQ55 |
| S13 | a duration cannot be built from data | TAX | OQ34 |
| S14 | union as a task target; narrowing lost on a filter's source path | HOLDS / GAP | the gap is in OQ44 §3 |

**Pharmacy** (`examples/pharmacy/`, first pass 2026-10-11): a retail pharmacy — four kinds of people acting on the same records, a request that arrives as several records (the prescriber's order and its prescriptions, one closure), relationships that run both ways and across edge sets (interactions, allergies), role-gated acts, two kinds of answer at the counter, and time in the records rather than in the rules.

| # | finding | verdict | where it stands |
|---|---|---|---|
| P1 | V3 refused every partition of a transient act over a time-dependent state | GAP | fixed; probe in `ValidatorTest.kt` |
| P2 | V29 paired tick rules with refusals over transient acts | GAP | fixed; probe in `ValidatorTest.kt` |
| P3 | a refusal reading a value the request's own handling moves refuses its own request | TAX | by design: a refusal is checked once, at the door when its predicate reads nothing the handling moves and at the settled transaction otherwise (Reference §21, `checks.md` V18, `evaluation.md` "An exposed call", ruled 2026-10-14) |
| P4 | V29 cannot see that a record only raises an aggregate | TAX | OQ53 |
| P5 | `never` over a rule-written field fails closed (V10) | TAX | OQ53; rides this question |
| P6 | deleting an unfilled prescription strands fourteen referrers (V24) | TAX | by design; void as a record (reversal as data) |
| P7 | disarm proof does not look through a refinement over `is not empty` (V2/V16) | TAX | OQ53 |
| P8 | filtered aggregate cannot reach a refinement-body property | TAX | OQ44 §3 |
| P9 | closure, two-field view, edge-set join, two-hop view, captures, schedule-only exit, dated ledger, gated deletion, union recipient, refusals | HOLDS | `PharmacyFlowTest.kt` |
| P10 | generator: closure part "not exposed"; one staff given for every role gate; schedule-only exit case asserts nothing | TAX | OQ56 |
| P11 | 57 A5 widths; the allergy join's second binding resolved against the first binding's element | TAX | OQ55 |
| P12 | `then` on the `if` line; no duration from data; no boolean from a comparison | TAX | by design; `QUESTIONS.md` items |
| P13 | three answering mechanisms on one request, chosen by one question | HOLDS | `patterns.md` "Three answers to one request", Reference §21 |
| P14 | inline and standalone exposure together is "exposed twice" | — | moot since OQ50: an act is declared in one place |

**Batches** (`examples/batches/`, first pass 2026-10-16): a peptide inventory whose containers come from three places — fermented from a strain, ordered from a vendor, mixed from other batches. Not a whole business: one modeling question (polymorphism over a shared record, the supertype-with-three-subtypes of an object language) stressed against a domain that needs it. The spelling that holds inverts the hierarchy: one base shape for what every batch has, one shape per kind of provenance, `origin: one Origin` over a static union of the three (Reference §6, §9). Each finding is pinned by a probe in `examples/batches/output/src/test/kotlin/velle/BatchesProbesTest.kt`.

| # | finding | verdict | where it stands |
|---|---|---|---|
| B1 | a predicate over a cross-base union is not name-checked: a field the operands declare separately reads fine in a `never` over the union, against Reference §9, and the runtime resolves it per instance; a nonsense name passes too (`Validator.walkRefExpr` skips a subject with no single base) | GAP | OQ44 §2 item 10; the want behind it is OQ35 |
| B2 (retired 2026-10-21 by OQ63: a `one` field named in `with` lets the origin arrive nested in the batch, Reference §6; the probe now shows the construct) | the batch and its origin cannot arrive in one commit: a closure creates parts pointing back at the act, the batch points forward; the unclaimed origin is reportable state (`UnclaimedOrigin`) and a `never` over it refuses the origin's own commit | TAX | OQ63 |
| B3 | the inferred inverse of `PeptideBatch.origin` is `peptideBatchs` — Reference §6's "pluralized with `s`" is literal; the spec declares the view under the readable name on the union body | TAX | item under "Expressions, refinements, and unions" in `QUESTIONS.md` |
| B4 | `origin is Composition` does not license `origin.components` as a filter's source; the spec filters over the declared shape and matches the composition by comparison | TAX | OQ44 §3 (S14's form) |
| B5 | a field read through a `many` hop (`(Component where ...).source`, `origin.components.source`) validates and cannot be evaluated; so a derived set of ancestor batches, and the transitive "never draws from itself", has no spelling that runs; the one-step `never` stands, and the commit order makes it the only reachable case | GAP | item under "Expressions, refinements, and unions"; the acyclicity certificate is OQ44 §2 item 8 |
| B6 | a derived collection reading itself through a `many` hop draws no V14 — the self-read is not in V14's definition graph; masked by B5 | GAP | OQ53 |
| B7 | the generator synthesized a boundary case for `never (PeptideBatch where remainingMg < 0)` with nothing to vary, since `remainingMg` is derived; the case failed | GAP | fixed in `SpecGen.kt` ("derived comparison subject"); `SPEC_INDEX-Batches.md` |
| B8 | the inverted spelling: a union-typed field as the one-of-three, narrowing per operand at the surface, the kind as a derivation, refinements per kind, the closure for a mixture's components, what is left as a derivation over the inverse, four refusals and a depletion rule | HOLDS | `BatchesFlowTest.kt`, over SQLite twice |

**V15's episodes discharge does not cover a state fed by a series (peptides S8).** A greenhouse is out of band when its latest climate reading is outside its limits, and each stretch out of band is an episode:

```
shape OutOfBand = Greenhouse where
    climateReadings is not empty and
    (latest(climateReadings by recordedAt).temperature < minTemperature or
     latest(climateReadings by recordedAt).temperature > maxTemperature)

shape OpenExcursion = ClimateExcursion where not exists ExcursionEnd for this

rule OpenClimateExcursion when (OutOfBand where not exists OpenExcursion for this) { ... }

shape ClosableExcursion = OpenExcursion where greenhouse is not OutOfBand

rule CloseClimateExcursion when ClosableExcursion { ... }
```

V15 pairs the opener and the closer: both change membership in `OpenExcursion`, and it cannot see that one `ClimateReading` commit moves exactly one greenhouse either in or out, never both. The discharge that clears Reference §20's delinquency episodes (entrant/leaver exclusivity: a rule entering a condition whose conjuncts include every conjunct of the condition another rule leaves cannot co-fire with it, when every shared gain-kind is an own-column write to the shared base) requires the flipping commit to be an assignment to a column of the state's own shape; here the flip is the creation of a series point read through an inferred inverse. The `when leaving OutOfBand` spelling drew the same error. The example takes the diagnostic's own suggestion, `after commit, Hourly` on the closer with `ClosableExcursion` as its guard, a legitimate spelling ("closed right after the reading, healed hourly") but not the one the business asked for.

## Residue held here

- **C1, held for ratification since 2026-08-20.** The widened checks found one genuine order-dependence in `examples/payments/payments.velle`: releasing a reservation on exhaustion flips the order out of `ReadyToShip`, so when the release and an address-change refusal were triggered by the same commit, running the release first re-opened `ApplicableAddressChange` mid-transaction and the change was applied while the already-pinned refusal still landed; the other order only refused. The handled-once partition protects a request across transactions, not across the commits inside one. The decision taken: `ReleaseStockOnExhaustion` runs `after commit, Nightly`, so by the time it fires the refusal is on record and the interference is structurally impossible (probe J in `SiblingConfluenceAuditTest` shows the old spelling diverging and the new one confluent; the comment above the rule records it). Alternatives considered and not taken: releasing only on a schedule tick (weakens the compensation story, stock stays held until the next tick); a `never` over the applied-and-refused double outcome (turns the bad order into a rollback, so the outcome is still order-dependent, just loud). Ratify, or choose an alternative.
- **The sibling-confluence audit's residue:** the induction from pairwise-sibling commutation to full-cascade commutation is argued, not proven; the order sweep is identity-versus-reversed, not N-permutations (seeded N-permutation sampling per commit is the extension; the knob is `VelleSystem.firingOrder`); the polarity and fresh-instance analyses are deliberately coarse (signed sums under positivity `never`s, `as`-aliased consult attribution, the cascade-induction write-up).
- **The delete rulings' stress test** (Reference §12 "Deletion", `checks.md` V23–V28): adversarial use cases; anything that breaks reopens the ruling and changes the v0 implementation with it. Pharmacy P6 (V24's fourteen stranded referrers) is the first data point, resolved by modeling the void as a record. The three riders the rulings left open are OQ53: V25's accepted false positive and the data point its coarseness ruling asked for, V24 not reasoning from the deleter's scope, and deletion against a fold twin or a snapshot.
- **Constructs the passes have not covered:** 3+-binding sibling joins; per-hop freeze depth (`LockedLineItem = LineItem where order is SettledOrder { frozen ... }`); the self-referential derived property beyond peptides' two uses (V14's descent certificates accept the recurrence); the declared `many` field beyond `examples/enrollment/`.
- **Prior art still unstudied:** Eve, Alloy, CUE, SQL; term rewriting (critical pairs, Newman's lemma) and CHR confluence checking are load-bearing for this question. Datalog's first pass landed: stratification → V14, recursive predicates → self-reference; its residue, sum types, settled as cross-base `or` (OQ29), with the adversarial pass in OQ44.
