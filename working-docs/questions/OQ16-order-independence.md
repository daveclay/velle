# OQ16 — Order must not matter: can the compiler prove it?

**Status:** open — calibration and the completeness audit; the coarse slices are implemented in full (2026-08-19: V15's three legs, V16, V14 with certificates, the first spent-invariant discharge), so nothing ruled remains unbuilt. Since OQ15 settled (2026-08-19), this question also carries the V14 certificate-whitelist calibration: definition cycles (V14) and rule cascades (V16) owe the same kind of termination answer, so their certificate vocabulary grows here, once, for both.
**In plain terms:** when one commit triggers several rules at once and no data flows between them, the outcome must be the same whichever fires first — can the compiler prove that, and how many legitimate specs get rejected because it can't?
**v0 stance:** ship the easy static checks (literal-path write-write and read-write conflicts, transition interference on watched refinements, DAG-or-disarmed-cycle quiescence — checks.md V15/V16), fail closed on everything else; calibration is what running v0 against realistic specs is for. The first discharge is also built: a spendable `never` (input-constrained, so boundary-enforced) discharges the direct-case instance aliasing (V1) and supplies the V14 acyclicity certificate.
**See:** README §12, §15 · checks.md V15–V16 · patterns.md "A cascade, concretely" (the transaction shape this question reasons about) · OQ15 (the between-transactions sibling — settled 2026-08-19 → README §19, the predecessor recurrence; its V14 whitelist residue rides this question)

---

**Settled direction: firing order within a transaction is never specified, and never matters in a valid spec.** Velle states timeless facts, not runtime call sequences; the runtime may fire sibling rules in any order, or in parallel, and every order must produce the same outcome — the same final state and the same set of produced facts. Two cases, sharply different:

- **Where data flows, order is causality, not policy.** If R3's condition matches on the commit R2's firing produces, the dependency graph orders them — the ordering README §15 already calls free. Nothing to prove; nothing was chosen.
- **Where no data flows, order must provably not matter.** Sibling firings — one commit matching R2 and R3 with no dependency between them — must *commute*. A spec whose outcome depends on an unstated order is **inconsistent**: it describes two different systems and never says which. That is a whole-spec compile error in README §1's deepest sense — the spec fails to be a self-coherent description — reported as one connected diagnostic naming the rules involved, exactly like one-writer.

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

The check decomposes along familiar lines: **write-write** conflicts — two siblings assigning the same field — are one-writer (README §12) extended to transaction scope; **read-write** conflicts — one sibling writes what another reads in a body or condition — are the example above; **transition interference** — one sibling's commit enters a refinement another sibling's commit exits — makes the set of mid-transaction transitions order-dependent, so transition-watching rules would see different histories. Traversal order (depth-first vs. level-by-level) stops being a question at all: once outcomes are order-independent, every traversal is a valid compilation.

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

- **Calibration.** How many legitimate specs the fail-closed checks reject is a question with zero data points: the example specs pass, but they were written alongside the checks. The loop is TODO.md's first item — write realistic specs, and triage every rejection into one of three outcomes: the restructure the diagnostic demands is acceptable authoring; a new certificate form earns whitelist entry (the decreasing-measure spelling is the standing candidate, deliberately left undesigned until real rejections shape it); or a new discharge form earns vocabulary entry. Term rewriting's critical pairs and CHR confluence tests remain the prior art to mine (TODO.md) for how rich the provable set can get.

- **The completeness audit — does "accepted" actually mean "order never matters"?** The shipped checks have the shape of Newman's lemma from term rewriting: if every *pair* of adjacent steps commutes (local confluence — run the two in either order and reach the same state) and every run terminates, then all orders reach the same result. V15 checks the pairs, V16 checks termination, and `evaluation.md` ("Processing one commit", step 6) asserts the conclusion outright: "Ordering within step 6 is never observable in a valid spec." Nobody has verified that the coarse slices actually deliver that sentence. The audit does, on the OQ42 precedent (`audit-symmetric-evaluation.md`: a case matrix, a verdict per case, every verdict pinned by an executable probe). What it takes:

  1. **Pin the step relation.** A state is the store mid-transaction plus the pending firings; a step is one firing's commit (`process(C)`). Bodies read the *evolving* state — a sibling's earlier write is visible to a later sibling's body — which is what makes each channel below a real divergence channel rather than a snapshot artifact.
  2. **Enumerate the divergence channels** — every distinct way two unordered firings can produce different outcomes (field write vs. read, creation vs. existence read, aggregate and timestamp reads, membership-transition history, captured values, the firing set itself, after-commit queue order) — and map each channel to the check and the read-vocabulary that covers it. One verdict per channel: covered, covered coarsely, or gap.
  3. **Build the order-permutation probe.** The evaluator picks step-6 order arbitrarily; give it a test-only ordering knob, then a sweep that runs each probe spec — and every example spec — under multiple sibling orders and compares final state plus produced facts. Any difference on a spec the validator accepted is a soundness bug by definition; this is the claim in executable form, the `CommutationSweepTest` idea aimed inside the transaction.
  4. **Fix what it finds, then write the argument.** Vocabulary widenings where the matrix shows gaps, each pinned by a probe; then the short written Newman argument — the step relation, the per-channel coverage table as the local-confluence leg, V16 plus the runtime depth backstop as the termination leg — with the residue stated honestly.

  Suspects already identified by reading the validator (statically visible, not yet verified against the semantics — the audit's first matrix rows): the V15 read-write leg compares assigned fields against `ReadSummary.fields` only, so a body's existence read over a shape a sibling *creates*, aggregate reads through inferred inverse collections (`collShapes`), timestamp reads any sibling write advances (`collFields`), and summaries flagged `opaque` (whose own contract says a consumer needing soundness must treat them as "may read anything") are all outside its vocabulary; `affects()` — which builds V16's condition-graph edges and gates which rule pairs count as coincidable siblings at all — has the same narrowness; an entrant's captures evaluate against post-state at the entering commit, so a sibling's write can change a captured *value* even where the transition itself is definite; and the after-commit queue drains in the order the siblings appended to it, so its claimed order-invisibility rests entirely on each entry carrying its own guard.
- **Discharge vocabulary.** The aliasing case is what showed `never` invariants (since adopted — README §21) are not just verification but *proof inputs*: `never (Customer where referrer == this)` turns the collision condition into a proven impossibility the disjointness analysis may use — the author states a data invariant, the prover spends it. The first slice is built: an input-constrained `never` (no rule writes what its predicate reads, so the boundary enforces it) discharges exactly this direct case in the one-writer analysis, and doubles as V14's acyclicity certificate. How much further spent invariants reach — and what else belongs in the discharge toolbox (conditioning one sibling on the other's outcome to make the dependency real; some not-yet-designed decreasing-measure spelling for cycles) — is calibration's concrete form. The certificate vocabulary is deliberately shared with V14's well-foundedness check (ruled at OQ15's settling, 2026-08-19; this question now owns the growth of that whitelist): a definition cycle in the derived-property graph and a rule cascade here owe the same kind of answer — a stated reason the chain provably ends — so one certificate family (strict descent on a creation-fixed datum, acyclicity supplied by a `never` invariant, an eventual decreasing-measure spelling) feeds both V14 and V16, calibrated once, with the same diagnostic language on both sides.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (the parcel-splitting example above) — undecidable in general. The folds precedent (README §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined — it is the termination leg of the completeness audit above.
