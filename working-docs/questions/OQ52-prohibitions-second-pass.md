# OQ52 — Prohibitions, second pass: the guardrail's runtime, when a refusal is checked, and a constraint that ages into violation

**Status:** open (2026-10-14) — gathered from the peptides and pharmacy passes after the rejections rulings (OQ46–OQ48, settled 2026-10-09 and 2026-10-11)
**In plain terms:** the rejections rulings said what a refusal, a constraint, and a guarantee are and what a rejection carries. Writing two whole businesses against them left three things undecided or unbuilt: the runtime does not run the boundary guardrail the ruling describes; the finalized documents disagree about whether a refusal is checked once or twice; and a constraint that reads the clock can be violated by nothing but the passage of time, with no act to refuse.
**See:** README §21 (the three kinds of prohibition) · `evaluation.md` "An exposed call" (the guardrail's moment), "Errors, refusals, and retry", "Ticks" · `checks.md` V18, V29 · `examples/peptides/peptides.velle`, `examples/pharmacy/pharmacy.velle` and their flow tests · `compiler/src/test/kotlin/velle/TickRejectionTest.kt`

---

## 1. The boundary guardrail is ruled, not built

Ruled 2026-10-09 (OQ46): a refusal or constraint is evaluated over pre-state plus the act's closure before any rule runs, with the same spelling the transaction end uses, in addition to the transaction-end check. The v0 runtime runs every `never` at transaction end only (`compiler/src/main/kotlin/velle/Runtime.kt`, `checkNevers`).

The case that found it (peptides): two readings at one hour mark are declared impossible, and the rules watching the series read `latest(... by hoursElapsed)`:

```
act AssayReading {
    assay: one Assay
    hoursElapsed: integer
    inhibitionPercent: decimal
    previous: one AssayReading? = latest(AssayReading where assay == this.assay and hoursElapsed < this.hoursElapsed by hoursElapsed)
    runningPeak: decimal = if previous is some then max(previous.runningPeak, inhibitionPercent) else inhibitionPercent
}

never (AssayReading where count(assay.assayReadings where hoursElapsed == this.hoursElapsed) > 1)
```

Committing a second reading at hour 24 does not produce a refusal today: the rules re-evaluate first, hit the tie, and fail loudly as README §10 says a selector must. The transaction rolls back, so nothing lands, but the caller gets a `VelleRuntimeError` instead of the refusal the author wrote. `lab - a reading entered late slots into its hour mark and the running peak re-derives` in `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt` asserts today's behaviour and says so in a comment.

**To build:** run `checkNevers` over the refusals and constraints with a footprint of the act's commit alone, after C0's insert and before `process(C0)`. The runtime needs the classification `Validator.spendableNevers` computes. Then flip the peptides test to assert the refusal.

**To decide:** whether the deleting commit runs the guardrail before processing. A deletion can make a `not exists` prohibition true (`evaluation.md` "The deleting commit").

## 2. When is a refusal checked: once at the request, or at the request and again at the settled transaction?

The three finalized documents say three things about a `never` over a transient act.

- README §21: a refusal "is checked once, at the request, and the record the request leaves behind carries nothing."
- `checks.md` V18: "a rule's partition evaluates at the act's commit, while a `never` constrains the settled transaction (§21) — for an atom the act's own consequences can flip, the `never` is the stronger statement, refusing even the act that would create the configuration."
- `evaluation.md` "An exposed call": the boundary guardrail evaluates refusals and constraints before any rule runs, "in addition to step 5, not instead of it."

The pharmacy spec is where the difference shows. The simplest of its four cases, with every shape it needs:

```
act Prescription {
    quantity: integer
    fillsRecorded: integer = count(fills)
}

transient act FillPrescription {
    prescription: one Prescription
    fillNumber: integer
}

shape Fill {
    prescription: one Prescription
    fillNumber: integer
}

-- a fill request must carry the next number
never (FillPrescription where fillNumber != prescription.fillsRecorded + 1)

rule RecordFill when FillPrescription {
    Fill from { prescription: prescription, fillNumber: fillNumber }
}
```

A request numbered 1 against a prescription with no fills: at the request the prohibition is false. The rule records the fill, `fillsRecorded` becomes 1, and at the end of the transaction the prohibition reads `1 != 2`, true. Under README §21's words the request is accepted; under V18's words it is refused, and v0's runtime refuses it (it checks every `never` at the end only). Sixty-one of the pharmacy's hundred and five tests failed on the first spelling, all on the fill request; the verify-once, pickup-once, and shelf-bound refusals failed the same way.

The spec took V18's reading and moved each sentence onto the record, where it means what it says:

```
never (Fill where fillNumber != count(prescription.fills where fillNumber <= this.fillNumber))
never (Fill where count(verifications) > 1)
never (Fill where count(pickups) > 1)
never (PickUp where fill is not VerifiedFill or exists ReturnToStock for fill)
```

The first counts the arriving fill among its siblings exactly as the peptides hour-mark uniqueness does, and catches gaps and repeats in one sentence. Evidence: `fill - data refusals at the boundary keep nothing, business refusals land on record with the reason` and `verify - a licensed pharmacist verifies once, never their own controlled fill, and a rejection returns the stock` in `examples/pharmacy/output/src/test/kotlin/velle/PharmacyFlowTest.kt`.

**The decision:** which wording stands.

- If a refusal is checked **once, at the request** (README §21 as written), `never (FillPrescription where fillNumber != prescription.fillsRecorded + 1)` means what it says; the runtime evaluates refusals at the boundary and skips them at step 5; V18's note and `evaluation.md`'s "in addition to" apply to constraints only.
- If a refusal is checked **at both moments** (V18 and `evaluation.md` as written), README §21's "checked once, at the request" needs different words, and the guidance to authors is the pharmacy's: a refusal may read only what the request's handling leaves alone; a sentence about a value the handling moves belongs on the record as a constraint.

The spec and its tests are consistent with the second reading today. Nothing in either pass needs the first.

**The three-way statement that falls out (pharmacy P13), owed to `patterns.md` and README §21.** The counter ended up with three answering mechanisms on one request, and they are not interchangeable: a **refusal** (`never` over the transient request) for data that is wrong on arrival and stays wrong, where the caller is told and nothing is kept; a **refusal recorded as data** (the `RefusedFill` partition, README §8's rejection-as-data idiom) for the patient's situation, evaluated once at the request and kept with a reason the counter reads back; and a **constraint** on the record the request produces (`never (Fill where ...)`) for a sentence about the books that the request's own handling makes true. Which of the three a sentence belongs to is decided by one question: does the request's own handling change what the sentence reads? The spec's comments on `FillPrescription`, `VerifyFill`, and `PickUp` say which is which and why.

## 3. A constraint that reads the clock is violated by aging

Settled half (OQ47, 2026-10-11): a `never` on a persistent act is a constraint on the books, a different kind of statement from the refusal the owner's sentence sounds like, and the rejection carries enough (the record on file, the act refused, the record it read through, the evaluation) that no advisory is needed to make the author's choice self-correcting. The transient request answered by a record stays the spelling for an author who means only to turn the request away:

```
transient act ApplyTreatment {
    trial: one FieldTrial
    appliedOn: Date
    rateGramsPerHa: decimal
}

shape TreatmentApplication {
    trial: one FieldTrial
    appliedOn: Date
    rateGramsPerHa: decimal
    recordedOn: timestamp on create
}

never (ApplyTreatment where trial is HaltedTrial)

rule RecordApplication when ApplyTreatment {
    TreatmentApplication from { trial: trial, appliedOn: appliedOn, rateGramsPerHa: rateGramsPerHa }
}
```

The unsettled half is the permit case. The persistent spelling `never (Planting where trial is not PermittedTrial)`, where `PermittedTrial` reads `today`, is a constraint that the books can fall into by aging alone: the day a permit expires, no transaction is to blame, `checkNevers` re-checks a clock-reading `never` at every transaction end (time moves between envelopes), and every act in the whole system is refused for as long as a planting stands on that trial, until a person acts. V29 does not see it, since no rule produces the state. The peptides example avoids it with the transient-request spelling above; `field - the request needs an effective greenhouse result on record, and planting needs a permit in force` in `PeptidesPipelineTest.kt` lets the permit lapse and shows unrelated commits still landing.

**To decide**, by V29's own reasoning: a check (the transient-request spelling as the discharge, the way V29 names its discharges), or a ruling that the whole-system refusal is the constraint's meaning. `TickRejectionTest.kt` has the tick side (a clock-reading constraint violated by aging stays a thrown `TickRejected`).

## 4. Smaller items, each one line in `QUESTIONS.md`

- The compiler's user-facing text still says "rule-maintained" (the V10 diagnostic, `Validator.kt`) and "input-constrained" (the sequence-diagram note, the rule-graph legend, the state-flow legend: `DiagramGen.kt`, `RuleGraphGen.kt`, `StateFlowGen.kt`), where README §21 says refusal, constraint, guarantee (2026-10-10).
- `patterns.md` "Validation rejection is data" and README §8's "Frozen fields" fix text still show a bare state partition over a persistent act, which A4 flags; the example should go `transient` (which dissolves the hazard) or take the anchored handled-once spelling.
- Whether a `never` may carry an author-supplied sentence ("a shipment is never on the books for a cancelled order") as the top line of its rejection above the derived rendering; a string after the predicate is the smallest syntax (deferred at OQ47).
- A `never` rejection of an after-commit firing should be carried as a `Rejection` the way a tick's is thrown; `drainAfterQueue` (`Runtime.kt`) records it in the failures list as a string today. Reachable only through a clock-reading constraint, V29 covering the rest.
- Levels of advisory checks: treat the A-series as a documenting artifact of validation, from "warn at the line" down to "record what the spec entails" (a generated test that makes a constraint's rejections visible and executable, or a generated note), so a calculable case the author may not have intended is exposed without being noisy. OQ47's decision was no warning, since a clear rejection makes a one-sided author self-correcting.
