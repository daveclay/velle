# OQ45 — A `never` over a persistent act whose predicate can flip after the act lands

**Status:** open (2026-09-15) — surfaced by the peptides stress pass (`break-peptides.md`, S5); the example spells around it with a transient request, and no check warns about the persistent spelling
**In plain terms:** "never spray a halted trial" written as a `never` over the spray record is checked forever after, not just when the spray is requested — so the report that halts the trial is refused because a spray is already on file. Should the validator warn, should the README steer authors to the transient spelling, or should such a `never` be restricted?
**v0 stance:** none — the spelling validates silently; the runtime does what §21 says (the invariant constrains every settled state)
**See:** README §21 (`never` — enforced at transaction end; input-constrained invariants compiled to the boundary) · README §4 "Transient acts" · `checks.md` A4 (the drift-exposed act partition — the same hazard for rules) · `evaluation.md` "An exposed call" (step 5) and "Processing one commit" · `examples/peptides/peptides.velle` (`ApplyTreatment`, `PlantTrial`)

---

## The case

A field trial halts when any of its plots reports severe crop injury, and nothing may be sprayed on a halted trial:

```
expose shape FieldTrial {
    name: text
}

expose shape FieldPlot {
    trial: one FieldTrial
    block: integer
}

expose shape PhytotoxicityReport {
    plot: one FieldPlot
    observedOn: Date
    severity: integer
}

shape HaltedTrial = FieldTrial where
    exists (fieldPlots where exists (phytotoxicityReports where severity >= 3))

expose shape TreatmentApplication {
    trial: one FieldTrial
    appliedOn: Date
}

never (TreatmentApplication where trial is HaltedTrial)
```

README §21 classifies the `never` as input-constrained (only external acts can change what it reads) and says it is compiled into the boundary. It is also, by §21's first paragraph, a constraint on every transaction's settled state. Both are true at once, and the second is what bites: an application lands on Monday; on Wednesday a severe report arrives; at the report's transaction end the application on file is a `TreatmentApplication where trial is HaltedTrial`, and the report is refused — "never #N over TreatmentApplication is violated". The business meant "do not accept a spray on a halted trial"; the spec says "there is never a spray on record for a trial that is now halted", which forbids halting.

The permit version is worse because its predicate reads the clock:

```
expose shape Permit {
    trial: one FieldTrial
    expiresOn: Date
}

shape PermittedTrial = FieldTrial where exists (permits where expiresOn >= today)

expose shape Planting {
    trial: one FieldTrial
    plantedOn: Date
}

never (Planting where not trial is PermittedTrial)
```

A `never` that reads `today` is re-checked at every transaction end (`evaluation.md`, the relevance rule's time exception). The day the permit expires, the planting on file violates it, and every commit in the system is refused until someone renews the permit or deletes the planting.

## What the language already offers

The transient spelling says what the business meant: the request is an input to the state, checked at its moment, gone before the state can drift — and the record it leaves carries no `never`.

```
expose transient shape ApplyTreatment {
    trial: one FieldTrial
    appliedOn: Date
}

shape TreatmentApplication {
    trial: one FieldTrial
    appliedOn: Date
    recordedOn: timestamp on create
}

never (ApplyTreatment where trial is HaltedTrial)

rule RecordApplication when ApplyTreatment {
    TreatmentApplication from { trial: trial, appliedOn: appliedOn }
}
```

Checked in as `examples/peptides/peptides.velle` (`ApplyTreatment`, `PlantTrial`), with the halting report and the lapsed permit both exercised in `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt`.

## The question

The persistent spelling has a defined meaning — §21's — that the author almost certainly did not intend. By the split recorded when A6 replaced a hard error (an error is for a statement whose meaning the spec leaves undefined; a defined meaning the author may not have intended is an advisory), this is advisory territory. Candidates:

1. **An advisory in A4's family.** "A `never` over a persistent exposed shape reads state that later commits can change (`HaltedTrial` through `trial`; `today`); it constrains the record for its whole life, not the act at its arrival. If the sentence is about the request, make the act `expose transient` and state the `never` on it; if it is about the record forever, say so with `tolerates`-style acknowledgement." The detection is the same read-summary analysis A4 uses, applied to `never` targets: any consult beyond the act's own stored fields, or a clock read.
2. **A README §21 note only.** Recommend the transient spelling for request-time invariants and leave the persistent form as it is.
3. **A restriction.** A `never` over an exposed persistent shape may read only that shape's own stored fields (the pure boundary check); anything else must be on a transient act or a rule-created record. Structural, in the "structural impossibility beats checked discipline" spirit — but it also forbids legitimate settled-world invariants over persistent acts, such as a spending cap that must hold across all of a customer's orders.

Option 1 fits the recorded advisory/error split; option 3 is the stronger structural answer but over-reaches. Either way the README's "compiled into the boundary" phrase for input-constrained invariants deserves a sentence saying the boundary check is in addition to, not instead of, the settled-state check.
