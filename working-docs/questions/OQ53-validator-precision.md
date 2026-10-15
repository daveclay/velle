# OQ53 — Validator precision: legitimate specs the fail-closed checks reject

**Status:** open (2026-10-14) — gathered from the peptides and pharmacy passes; each entry rides OQ16's calibration where it says so
**In plain terms:** each check proves a syntactic slice and refuses the rest. Writing two whole businesses recorded where that forced a restructure the business did not ask for. Each entry below names the check, the spelling it refused, the spelling the spec used instead, and what the check would need to see. None is a change to a ruling; each is a sharper prover, which only ever removes errors.
**See:** `checks.md` (the catalog) · `compiler/src/main/kotlin/velle/Validator.kt` (the header lists the slices not yet implemented) · OQ16 (the certificate and discharge vocabulary the induction-shaped checks share)

---

## V6 — a capture of the base shape's own derived property is refused (peptides S6)

The first draft fixed the efficacy at the moment a trial completed:

```
shape CompleteTrial = LaidOutTrial and ObservedToEnd {
    captured efficacyAtCompletion: decimal = efficacy
}
```

`efficacy` is a derived property declared on `GreenhouseTrial` itself, so it is evaluable for every instance at every moment. V6's coarse slice looks through the derivation, finds it reading `PlotObservation.diseaseSeverity` and `GreenhousePlot.audpc`, and refuses because the refinement's predicate does not guarantee those shapes at entry. The example records the value in the result record instead, from the rule body at the completing commit, which reads the same number at the same moment. The cost is that `captured` lost its most natural use in the domain. **Wanted:** V6 treats the base shape's own derived properties as unconditionally evaluable.

## V29, V10, V26 — a creation whose only effect on a consulted aggregate is in the safe direction (pharmacy P4)

```
act StockLot {
    ...
    onHand: integer = received - sum(fills where ConsumingFill, quantity) + sum(stockAdjustments, delta)
}

shape ConsumingFill = Fill where this is not RejectedFill and not exists ReturnToStock for this

never (StockLot where onHand < 0)

rule ReturnUncollected when Uncollected on Nightly {
    ReturnToStock from { fill: this, returnedOn: today }
}
```

V29 refused the nightly rule: it creates a `ReturnToStock`, the balance reads it through `ConsumingFill`, and v0 cannot prove the firing keeps the lot out of the forbidden state. It always does: a return to stock removes a fill from the consuming set, which only raises the balance. V29 reasons by shape, not by direction. The spec decides the bound in the request's partition instead (`dispensable` on `FillPrescription` reads `quantity <= lot.onHand` once, at the request), which answers the counter but cannot state the books rule "stock never goes below zero". **Wanted:** V29, and V10 and V26 which share the posture, discharge a creation or deletion whose only effect on the aggregate a prohibition consults is in the safe direction.

## V2, through it V16 — the disarm proof does not look through a named refinement over `is not empty` (pharmacy P7)

```
shape QuarantinedLot = StockLot where quarantines is not empty

rule QuarantineRecalled when (RecalledLot where this is not QuarantinedLot) {
    Quarantine from { lot: this, reason: "recalled" }
}
```

V16 reported "rule cascade may not quiesce: QuarantineRecalled -> QuarantineRecalled — no disarming guard breaks the cycle". Creating the `Quarantine` makes `quarantines is not empty` true and so leaves the trigger state, but the disarm proof recognises `not exists W for this` and flag assignments, not a membership test against a refinement whose predicate is an inverse collection's emptiness. The spec spells the guard `not exists Quarantine for this` in both quarantine rules. **Wanted:** the disarm proof looks through a named refinement whose predicate is an inverse collection's `is not empty`.

## V10 — a `never` over any rule-written field fails closed (peptides S7, pharmacy P5; rides OQ16)

Both passes had prohibitions refused as guarantees ("v0 cannot yet prove it inductively over its writers", V10). Peptides: a score range, because a correction rule assigned `diseaseSeverity`; "no score before treatment", because a rule assigned `treatedAt`. Both rewrites improved the model (the score became a ledger, treatment became a record with `treatedAt` derived from it), but the check forced them, and it will force them on every spec where an act and a rule both write one field. Pharmacy: `never (Drug where reorderPoint < 0)` moved to the act (`never (SetReorderPoint where reorderPoint < 0)`), leaving the drug's own committed value unbounded; `never (Prescription where quantity <= 0)` survived only by shrinking the correction act to the one field no prohibition bounds, which changed what the business can do. The inductive proof over rule writers is OQ16's discharge-vocabulary work.

## V25 — the accepted false positive: disjointness that is interval arithmetic (OQ37's coarseness ruling)

The existence-dependency check uses the shared refinement-overlap disjointness prover and nothing finer: no window arithmetic, no lifetime analysis. The ruling (2026-08-14) accepts the false positive that follows, and names its discharge as restructure, never a signature:

```
rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }   -- the evidence is the guard's memory
}

rule PruneOldReminders when (Reminder where sentOn < today - 90 days) on Monthly {
    delete this    -- error: RemindOverdue's guard reads Reminder's existence, and the
                   -- disjointness (90-day condition vs 3-day window) is interval arithmetic
                   -- over sentOn, beyond the refinement-overlap prover
}
```

A reader can see nothing re-arms. The discharge moves the rate limit's memory off the prunable evidence onto a field witness (`lastRemindedOn: Date?` on the invoice, written by the reminder rule), so no guard reads `Reminder`'s existence and the prune is legal under any coarseness. The ruling set itself a calibration test: if realistic specs show this restructure dominating, that is the evidence for sharpening the prover, a backward-compatible relaxation that only ever removes errors. Neither pass has produced a data point yet. **Wanted:** the data point, then the sharpening if it comes.

## V24 — referential completeness does not reason from the deleter's scope (pharmacy P6)

The first pharmacy draft voided a never-filled prescription with a `delete`, scoped to `prescription is not FilledPrescription`. V24 listed every shape holding a required `one Prescription` as a stranded referrer, fourteen in all, fills included, although the deleter's scope excludes any prescription with a fill. The other thirteen are legitimately present on an unfilled prescription, so the spec kept the prescription and recorded the void (reversal as data, README §22), which is the better model in a regulated domain. The check's blindness stands on its own: V24 reads the referrers off the relationship graph and never asks whether the deleter's condition makes a referrer impossible. **Wanted:** V24 discharges a referrer whose existence the deleter's scope provably excludes, by the same shared prover V25 uses.

## Deletion against folds and snapshots (OQ37's one flagged, unruled edge)

A derivation recalculates when a record it aggregates over is deleted: `balance = amount - sum(payments, amount)` jumps when a `Payment` goes, and the invoice may re-enter `OverdueInvoice` and be reminded again. Correct by construction, since a derivation is a current-state computation, not a proof of once-ness. What was flagged and never ruled: a consumer that assumed monotone history, a stored fold twin or a nightly snapshot (README §19, §20's rungs), inherits the non-monotonicity without any check saying so. No example exercises a deleter against a fold or a snapshot. **Wanted:** a spec that does, and a ruling on whether the existence-dependency check should name the fold or snapshot the way it names a guard.

## The rest, each one line in `QUESTIONS.md`

- V21 (`initially`/`timestamp` on an act is a compile error; OQ31, settled) is in `checks.md` and not in the validator; once it lands, the now-dead `initially` optional-parameter branch in `Codegen.kt` `commitFn` goes (optional `?` fields stay optional parameters, ratified 2026-08-20; no example spec puts `initially` on an act any more).
- The slices `Validator.kt`'s header lists as not implemented: V11 branch-sensitive narrowing beyond the union slice; V12 at-most-one beyond the refinement slice (`singular_references.md`); the A-series beyond A4/A5.
- The static selector-discrimination check: a `latest`/`first` whose candidates can collide within one transaction fails loudly at runtime today (README §10); the static proof, or a demand for a discriminating datum at compile time, rides OQ16.
