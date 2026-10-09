# OQ45 — A `never` over a persistent act whose predicate can flip after the act lands

**Status:** open (2026-09-15) — surfaced by the peptides stress pass (`break-peptides.md`, S5); the example spells around it with a transient request, and no check warns about the persistent spelling. Reframed 2026-10-09: the persistent spelling is a legitimate choice, and the question is how the tool makes sure the author chose it
**In plain terms:** "never spray a halted trial" written as a `never` over the spray record is checked forever after, not just when the spray is requested — so the report that halts the trial is refused because a spray is already on file. Whether that is what the owner wants (the halt is rejected and someone is told) or not (the halt is recorded and the spray is shown as a finding) is the owner's decision; the question is how the tool makes the author see which one they wrote.
**v0 stance:** none — the spelling validates silently; the runtime does what §21 says (the invariant constrains every settled state)
**See:** README §21 (`never` — enforced at transaction end; input-constrained invariants compiled to the boundary) · README §4 "Transient acts" · `checks.md` A4 (the drift-exposed act partition — the same hazard for rules) · `evaluation.md` "An exposed call" (step 5) and "Processing one commit" · `examples/peptides/peptides.velle` (`ApplyTreatment`, `PlantTrial`) · OQ47 in `QUESTIONS.md` (what the rejection says and who hears it)

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

**The framing (Dave, 2026-10-09).** A `never` says "this is not a state the system can be in", and a rejection is the system refusing to move to such a state, whoever proposed the move: the caller of an act, or a scheduled rule firing at a tick. Whether a given situation belongs in that category, or should instead be accepted and recorded as valid state with the finding shown to a person, is a human decision per `never`. The language does not make that call. Its job is to make sure the author sees which of the two sentences they wrote, and, when a rejection happens, to tell the person affected exactly what was rejected and why (OQ47).

Read that way, the persistent spelling above is not a mistake. "There is never a spray on the books for a halted trial, and if a halt would put the books in that state, the halt is rejected and someone is told" is a legitimate business rule, and so is "a planting on a lapsed permit is a standing violation that rejects everything until a person renews or removes it". Silly, probably; humans write silly rules. What is not acceptable is the author writing the door-rule sentence ("turn sprays away at a halted trial") and getting the books-rule behaviour without knowing it.

**What makes the two spellings different for the author.** A `never` over a transient act can only ever be violated by that act, at its own arrival: the instance is gone afterwards. A `never` over a persistent exposed shape can be violated by a record that was accepted long ago, when some later act (the report), some later tick (the permit lapsing), or some later deletion changes what the predicate reads. The second kind is the one the author needs to have chosen on purpose.

Candidates, under the framing:

1. **An advisory that asks the owner's question (recommended).** Detection is the read-summary analysis A4 already uses, applied to `never` targets: the target is a persistent exposed shape and the predicate consults anything beyond the act's own stored fields, or reads the clock. The text is the owner's question, not a mechanism: "`never (TreatmentApplication where trial is HaltedTrial)` can become true for an application already on file, when a later report halts its trial. Then the report is rejected and must be reviewed by a person. If that is the rule, acknowledge it; if sprays should be turned away at the door and halts always recorded, state the `never` on a transient request (`expose transient shape ApplyTreatment`) and let the rule record the application." The acknowledgement is an explicit spelling, as every advisory's discharge is (the form is undecided; `tolerates`-style is the existing family). For the clock case the text names the tick: "can become true on a tick, when the permit lapses; from then on every transaction is rejected until a person acts."
2. **A README §21 note only.** Recommend the transient spelling for door rules and leave the persistent form silent. Rejected by the framing: the whole point is that the author must see the choice, and a note in the README is not seeing it at the line where they wrote it.
3. **A restriction** (a `never` over an exposed persistent shape may read only its own stored fields). Rejected by the framing: it takes the decision away from the human, and it forbids the legitimate books rules (a spending cap across all of a customer's orders).

Either way README §21 should say in plain words what the two spellings mean to a person: a `never` on a request is a door rule, checked once, at the request; a `never` on a record is a books rule, checked at the end of every transaction for as long as the record exists, and violable by later acts, ticks, and deletions. And the sentence that the boundary check for input-constrained invariants is in addition to the transaction-end check, not instead of it, still belongs there (OQ46).
