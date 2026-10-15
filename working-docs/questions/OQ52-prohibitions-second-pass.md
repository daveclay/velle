# OQ52 — Prohibitions, second pass: the guardrail's runtime, when a refusal is checked, and a constraint that ages into violation

**Status:** open (2026-10-14) — gathered from the peptides and pharmacy passes after the rejections rulings (OQ46–OQ48, settled 2026-10-09 and 2026-10-11). §1, §2, and §3 ruled 2026-10-14, build and doc rewording pending; §4 open
**In plain terms:** the rejections rulings said what a refusal, a constraint, and a guarantee are and what a rejection carries. Writing two whole businesses against them left three things undecided or unbuilt: the runtime does not run the boundary guardrail the ruling describes (ruled 2026-10-14: the door check replaces the end check for the prohibitions whose verdict the rules cannot change, and nothing is checked twice); the finalized documents disagreed about whether a refusal is checked once or twice (ruled 2026-10-14: once, at whichever moment is safe); and a constraint that reads the clock can be violated by nothing but the passage of time, with no act to refuse (ruled 2026-10-14: a validation error, the clock being a writer with no caller, with the request spelling and the recorded-decision spelling as the discharges).
**See:** README §21 (the three kinds of prohibition) · `evaluation.md` "An exposed call" (the guardrail's moment), "Errors, refusals, and retry", "Ticks" · `checks.md` V18, V29 · `examples/peptides/peptides.velle`, `examples/pharmacy/pharmacy.velle` and their flow tests · `compiler/src/test/kotlin/velle/TickRejectionTest.kt`

---

## 1. The boundary guardrail: re-ruled as a replacement for the end check, not an addition

Ruled 2026-10-09 (OQ46): a refusal or constraint is evaluated over pre-state plus the act's closure before any rule runs, with the same spelling the transaction end uses, in addition to the transaction-end check. **Re-ruled 2026-10-14:** the end check is the meaning, and the door check is where the end check can be done early with the same verdict. A prohibition constrains the settled transaction, and its rejection names the act in flight, whether or not that act caused the violation. A prohibition whose predicate reads nothing any rule writes, creates, or deletes cannot change its verdict by the rules running, so it is checked at the door and skipped at the end. Every other prohibition is checked at the end only. Nothing is checked twice, and the door costs no second fetch or evaluation. The classification is the compiler's existing one (`Validator.spendableNevers` in `compiler/src/main/kotlin/velle/Validator.kt`: no rule writes a field the predicate reads, no rule deletes a shape it consults, the predicate is not opaque).

The v0 runtime has no door: every `never` is checked at transaction end (`compiler/src/main/kotlin/velle/Runtime.kt`, `checkNevers`), with a relevance test over the envelope's footprint as the only selection. The case that found the gap (peptides): two readings at one hour mark are declared impossible, and the rules watching the series read `latest(... by hoursElapsed)`:

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

Committing a second reading at hour 24 does not produce a refusal today: the rules re-evaluate first, hit the tie, and fail loudly as README §10 says a selector must. The transaction rolls back, so nothing lands, but the caller gets a `VelleRuntimeError` instead of the refusal the author wrote. `lab - a reading entered late slots into its hour mark and the running peak re-derives` in `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt` asserts today's behaviour and says so in a comment. This prohibition reads only what the request supplies, so it is in the door set, and the rules never run on the duplicate: the benefit the October 9 ruling wanted comes with the door set for free.

**To build:**

- The runtime gets the door set from the compiler's classification, or derives it from the same facts (the rules' writes, creations, and deletions against each predicate's reads).
- In `applyCommit`, for the act's own commit only and never for a rule firing's commit, after the whole closure is inserted and before the firings are computed, evaluate the door set with the relevance test over the act's footprint alone. No clock bypass: time reads never make a commit relevant. Record the act on the transaction before the check runs, since `commit` sets `txn.act` only after `applyCommit` returns and the rejection must name the act.
- `checkNevers` at transaction end skips the door set.
- Flip the peptides test to assert the refusal. Run the pharmacy flow tests and `compiler/src/test/kotlin/velle/TickRejectionTest.kt`, which stay on the end check.

**Decided 2026-10-14: the deleting commit does not run the guardrail.** A deletion happens only inside a rule firing (README §12), so the deleting commit is a mid-transaction commit with siblings. A check there would make sibling firing order observable (`evaluation.md` S3; the runtime fires siblings one at a time in declaration order) and would break the deletion-side compensation pattern, where a `when deleted` rule repairs what the deletion flipped, with no second spelling for the author to move the sentence to. In v0 nothing reaches it anyway: V26 refuses any deleter of a shape a `never` consults other than the `never`'s own base, and deleting the own base only shrinks the forbidden set. `evaluation.md` "An exposed call" already assigns deletion by a rule to the end check. Reopen only if V26 gains a disjointness discharge.

## 2. When is a refusal checked: decided, once, at whichever moment is safe

**Decided 2026-10-14**, by §1's re-ruling: a refusal is checked once. If its predicate reads nothing the request's handling moves, it is checked at the door. Otherwise it is checked at the end, where the request's handling has settled, and the rejection names the request. The three finalized documents each had half of this.

- README §21: a refusal "is checked once, at the request, and the record the request leaves behind carries nothing." Right that it is once; the moment is the door only for the door set.
- `checks.md` V18: a `never` "constrains the settled transaction (§21) — for an atom the act's own consequences can flip, the `never` is the stronger statement, refusing even the act that would create the configuration." Right for the end set.
- `evaluation.md` "An exposed call": the boundary guardrail runs "in addition to step 5, not instead of it." Wrong: instead of, for the door set.

The pharmacy spec is the evidence. The simplest of its four cases, with every shape it needs:

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

The prohibition reads `fillsRecorded`, a count of records the request's own rule creates, so it is not in the door set and is checked at the end: a request numbered 1 against a prescription with no fills lands a fill, `fillsRecorded` becomes 1, the prohibition reads `1 != 2`, and the request is refused. That is what the v0 runtime does today, and the spec and its tests already match it. Sixty-one of the pharmacy's hundred and five tests failed on this spelling, all on the fill request, and the spec moved each such sentence onto the record, where it reads what it means:

```
never (Fill where fillNumber != count(prescription.fills where fillNumber <= this.fillNumber))
never (Fill where count(verifications) > 1)
never (Fill where count(pickups) > 1)
never (PickUp where fill is not VerifiedFill or exists ReturnToStock for fill)
```

Evidence: `fill - data refusals at the boundary keep nothing, business refusals land on record with the reason` and `verify - a licensed pharmacist verifies once, never their own controlled fill, and a rejection returns the stock` in `examples/pharmacy/output/src/test/kotlin/velle/PharmacyFlowTest.kt`.

**Owed to the finalized documents:** README §21's sentence becomes "checked once: at the request when the request's handling leaves what the predicate reads alone, otherwise at the settled transaction"; `evaluation.md`'s "in addition to step 5" becomes "instead of step 5, for the prohibitions the door covers"; V18's note stands as the end-set statement. The guidance to authors stays the pharmacy's: a refusal that reads a value the handling moves is answered after the handling, and a sentence about the books belongs on the record as a constraint.

**The three-way statement that falls out (pharmacy P13), owed to `patterns.md` and README §21.** The counter ended up with three answering mechanisms on one request, and they are not interchangeable: a **refusal** (`never` over the transient request) for data that is wrong on arrival and stays wrong, where the caller is told and nothing is kept; a **refusal recorded as data** (the `RefusedFill` partition, README §8's rejection-as-data idiom) for the patient's situation, evaluated once at the request and kept with a reason the counter reads back; and a **constraint** on the record the request produces (`never (Fill where ...)`) for a sentence about the books that the request's own handling makes true. Which of the three a sentence belongs to is decided by one question: does the request's own handling change what the sentence reads? The spec's comments on `FillPrescription`, `VerifyFill`, and `PickUp` say which is which and why.

## 3. A constraint that reads the clock: decided, a validation error

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

**Decided 2026-10-14: a constraint whose predicate reads the clock is a validation error, by V29's own reasoning.** In velle's model, time advancing is a commit (the tick), so `today` and `now` are data with a writer, and that writer fires with nobody asking. V29 already refuses a firing with no caller that can produce a forbidden state, fail closed, because the rejection has no caller to answer and repeats at every tick. A constraint on a persistent shape that reads `today` or `now`, directly or through a refinement it consults, is that case with the clock as the writer: the books can age into the forbidden state with no act to refuse, and v0 proves nothing about which direction time moves a predicate. The scheduled rule is not the thing to check. In the permit case the nightly rule writes scouting reminders and touches nothing the constraint reads; it is rejected only because the runtime re-checks every clock-reading constraint when time has moved, and with no scheduled rule at all the next act is refused instead.

What decided it against "allow it, the rejection is useful information": the runtime's end check collects every violating record, not only what the transaction touched (`checkNevers` in `compiler/src/main/kotlin/velle/Runtime.kt`). Once two records have aged into a constraint, the act that repairs one is refused because the other still violates, so the books cannot be repaired from inside velle. A spec that validates clean and then locks itself is worse than a diagnostic.

The check, a V30 or an extension of V29, refuses a `never` or `requires` on a persistent shape whose predicate's read summary includes a time read. The discharges, named in the diagnostic:

- state it on the request, where a refusal reads the clock once at the moment the person acts: `never (PlantTrial where trial is not PermittedTrial)` in place of `never (Planting where trial is not PermittedTrial)`;
- record the decision as data, so the constraint reads a field and not the clock: `permit: one Permit` on `Planting`, set at the request;
- retract it, when the sentence is not a rule at all but a hope, and model what the business does with the state instead: `never (Invoice where paidOn is none and dueOn < today)` becomes a refinement `OverdueInvoice` and a scheduled rule that acts on it.

Not refused: a refusal that reads the clock, since a transient act is checked once at the request (checks.md V3 already says a time-dependent partition of a request is read at the request); and a constraint comparing dates to each other without `today`, such as `never (Permit where expiresOn < issuedOn)`, since only an act can change what it reads. The accepted false positive is the harmless kind, `never (Planting where plantedOn > today)`, which aging can only make less true; the check cannot tell, and the discharge is the request spelling the author should have written, since only the request supplies the date.

The three specs that exhibit the case all validate with zero diagnostics today (checked 2026-10-14 against `Validator.validate`): the permit-on-the-record spelling, the overdue-invoice spelling, and the spec in `compiler/src/test/kotlin/velle/TickRejectionTest.kt`. That test's spec becomes invalid under the check, and the test needs a spec the compiler cannot see through to keep exercising the runtime backstop, or it becomes the validator's exhibit instead. The runtime backstop stays: `TickRejected` and the end-check refusal remain for a record that ages into a constraint the compiler never saw, such as one arriving through a legacy mapping (README §21). `field - the request needs an effective greenhouse result on record, and planting needs a permit in force` in `PeptidesPipelineTest.kt` shows the request spelling with the permit lapsing and unrelated commits still landing.

## 4. Smaller items, each one line in `QUESTIONS.md`

- The compiler's user-facing text still says "rule-maintained" (the V10 diagnostic, `Validator.kt`) and "input-constrained" (the sequence-diagram note, the rule-graph legend, the state-flow legend: `DiagramGen.kt`, `RuleGraphGen.kt`, `StateFlowGen.kt`), where README §21 says refusal, constraint, guarantee (2026-10-10).
- `patterns.md` "Validation rejection is data" and README §8's "Frozen fields" fix text still show a bare state partition over a persistent act, which A4 flags; the example should go `transient` (which dissolves the hazard) or take the anchored handled-once spelling.
- Whether a `never` may carry an author-supplied sentence as the top line of its rejection: moved to OQ61 (2026-10-14), deferred until the generated rendering or the engineer's own rendering from the rejection data proves insufficient.
- A `never` rejection of an after-commit firing should be carried as a `Rejection` the way a tick's is thrown; `drainAfterQueue` (`Runtime.kt`) records it in the failures list as a string today. With §3's ruling a clock-reading constraint is invalid velle and V29 covers the rest, so this is reachable only through the runtime backstop, a record that aged into a constraint the compiler never saw; kept as a tidy-up for consistency with the tick's rejection (Dave, 2026-10-14: leave it).
- Levels of advisory checks: moved to OQ62 (2026-10-14), a question about the validator as a whole and not about prohibitions.
