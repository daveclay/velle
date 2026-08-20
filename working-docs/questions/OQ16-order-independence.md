# OQ16 — Order must not matter: can the compiler prove it?

**Status:** open — calibration; the hard cases are data-dependent, where fail-closed rejects legitimate specs. Since OQ15 settled (2026-08-19), this question also carries the V14 certificate-whitelist calibration: definition cycles (V14) and rule cascades (V16) owe the same kind of termination answer, so their certificate vocabulary grows here, once, for both.
**In plain terms:** when one commit triggers several rules at once and no data flows between them, the outcome must be the same whichever fires first — can the compiler prove that, and how many legitimate specs get rejected because it can't?
**v0 stance:** ship the easy static checks (literal-path write-write and read-write conflicts, DAG-or-disarmed-cycle quiescence — checks.md V15/V16), fail closed on everything else; calibration is what running v0 against realistic specs is for.
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

- **The analysis itself.** Commutativity/confluence checking is charted territory — term rewriting's critical pairs and Newman's lemma (local confluence + termination ⇒ confluence, which ties this proof to quiescence below), CHR confluence tests, Datalog evaluation strategies. What Velle's version is — and how coarse it can be before it rejects legitimate specs — is the work. Fail-closed is given (uncertainty errors, README §12's stance); calibration is not.
- **Discharge vocabulary.** The aliasing case is what showed `never` invariants (since adopted — README §21) are not just verification but *proof inputs*: `never (Customer where referrer == this)` turns the collision condition into a proven impossibility the disjointness analysis may use — the author states a data invariant, the prover spends it. Whether declared invariants feed the confluence and one-writer analyses — and what else belongs in the discharge toolbox (conditioning one sibling on the other's outcome to make the dependency real; some not-yet-designed decreasing-measure spelling for cycles) — is calibration's concrete form. The certificate vocabulary is deliberately shared with V14's well-foundedness check (ruled at OQ15's settling, 2026-08-19; this question now owns the growth of that whitelist): a definition cycle in the derived-property graph and a rule cascade here owe the same kind of answer — a stated reason the chain provably ends — so one certificate family (strict descent on a creation-fixed datum, acyclicity supplied by a `never` invariant, an eventual decreasing-measure spelling) feeds both V14 and V16, calibrated once, with the same diagnostic language on both sides.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (the parcel-splitting example above) — undecidable in general. The folds precedent (README §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined.
