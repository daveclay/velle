# Breaking v0 with a domain: a crop-protection discovery pipeline

**Status:** first pass complete (2026-09-15) — the spec validates, runs in memory and over SQLite, and every finding below is either fixed in the compiler, spelled around in the spec with the cost recorded, or handed on as a TODO item or an open question. Scorecard at the bottom.
**Residue:** OQ47 (rejections: the author's refuse-or-record choice, the advisory that makes it visible, and what the person is told) · the TODO items under "Peptides stress pass" in `TODO.md`.

---

The earlier example specs each exist to exhibit one construct (deletion, the closure, unions). This pass inverts that: start from a real business — a biotech company screening candidate peptides against crop diseases — and write the whole thing, letting the domain decide which constructs get used and how hard. The spec is `examples/peptides/peptides.velle`; its generated module, the hand-written givens, and the pipeline tests are under `examples/peptides/output/`.

What the domain brings that no earlier example did:

- **A time series at every stage.** Lab plates read at hour marks; greenhouse plots scored at day marks; a climate sensor logging around the clock; daily site weather; field scouting at day marks; harvest. Every stage needs a running value over an ordered series (a peak; the area under a disease-progress curve), means over a series, "this series has gone quiet", "this plot is overdue for a score", and two series correlated against each other (a spray date against the next day's rainfall).
- **Gates between stages.** A greenhouse trial needs a standing lab hit; a field trial needs an effective greenhouse result on record; planting needs a permit in force; nothing may be sprayed on a halted trial.
- **A series that gets locked.** Once a trial is evaluated, its scores cannot be corrected or removed; before that, corrections are a ledger and removals are real deletions.
- **Configured collection.** When plots are scored or scouted is not written into the spec: an admin schedules a collection task per trial and day mark with a due date, for a trial of either stage, and can reschedule or cancel it until it is done.

Verdict scale, as in `break-b.md`: **HOLDS** (the language handles it), **TAX** (it handles it at a cost — a restructured spelling, a coarse check to work around), **GAP** (a check, the runtime, or the generator is wrong or missing, and something had to change).

## The findings

Each finding names the checked-in artifact that shows it. Numbers S1–S14; the scorecard at the end lists them all.

### S1 — GAP, fixed: the validator resolved `this` inside a collection filter against the filter's element

README §10 says `this` is always the outermost subject and a bare name is the innermost collection-filter's element. The runtime (`Evaluator.kt`) and the query compiler (`Query.kt`) read it that way. The validator's name resolution and the model's read-summary collector did not: inside `exists (assayReadings where hoursElapsed >= this.experiment.protocol.durationHours)` on a refinement of `Assay`, they resolved `this` to `AssayReading`, reported `'experiment' is not a member of 'AssayReading'`, and — because an unresolved read marks the whole summary opaque, and an opaque summary is treated as reading everything — V15 then paired the rule reading that refinement with every other rule in the spec (twenty-odd sibling-order errors from one mis-resolved name).

The mismatch had stayed hidden because every earlier spec used `this.<field>` inside a filter only where the element shape happens to carry a same-named field: `latest(DuesPayment where member == this.member and paidOn < this.paidOn by paidOn)` in `examples/loyalty/loyalty.velle` validated by coincidence (the element is also a `DuesPayment`), and was evaluated correctly at runtime by design.

Fixed in `compiler/src/main/kotlin/velle/Validator.kt` (the outermost `checkExpr` call records the subject `this` names; nested filter scopes no longer capture it) and `compiler/src/main/kotlin/velle/Model.kt` (`collectExpr` and `pathElementShape` carry the root subject beside the innermost scope). Probe: `F1 - this inside a collection filter names the outermost subject, not the element` in `compiler/src/test/kotlin/velle/ValidatorTest.kt`. The compiler suite's failing set is unchanged by the fix (the sixteen failures `TODO.md` already lists).

### S2 — GAP, fixed: an `as` alias used as a collection source lost its element type

`exists (fieldPlots as plot where not exists (plot.scoutings where scoutedOn > today - 7 days))` reported `'scoutedOn' is not a member of 'FieldPlot'`: the validator's collection check resolved the source path `plot.scoutings` without the alias table, got no element shape, and fell back to the enclosing scope. Fixed in the same `Validator.kt` change (the alias table is passed through). Probe: `F1 - an as-alias used as a collection source resolves to its element` in `ValidatorTest.kt`.

### S3 — GAP, ruled: a uniqueness `never` on a series datum was reached after the rules that read the series

The spec states that two readings at one hour mark are impossible:

```
expose shape AssayReading {
    assay: one Assay
    hoursElapsed: integer
    inhibitionPercent: decimal
    previous: one AssayReading? = latest(AssayReading where assay == this.assay and hoursElapsed < this.hoursElapsed by hoursElapsed)
    runningPeak: decimal = if previous is some then max(previous.runningPeak, inhibitionPercent) else inhibitionPercent
}

never (AssayReading where count(assay.assayReadings where hoursElapsed == this.hoursElapsed) > 1)
```

Committing a second reading at hour 24 does not produce a refusal. The `never` is checked at transaction end (`evaluation.md`, step 5), but the rules watching the series re-evaluate their conditions first (step 2), reach `latest(assayReadings by hoursElapsed)`, hit the tie, and fail loudly as README §10 says a selector must. The caller gets a `VelleRuntimeError` ("does not discriminate: 2 instances share the ordering datum"); the transaction rolls back, so nothing lands, but the outcome is an error rather than the refusal the author wrote. Evidence: `lab - a reading entered late slots into its hour mark and the running peak re-derives` in `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt`, which asserts the error and that nothing landed.

Ruled 2026-10-09 (README §21; `evaluation.md` "An exposed call", the guardrail's moment): the input-constrained guardrail is evaluated over pre-state plus the act's closure, before processing, so the arriving reading is counted among its siblings with the same spelling the transaction end uses, and the rules that depend on the invariant never run against a state it forbids. The runtime does not implement the guardrail yet (section 1 below); the test above asserts today's behaviour until it does.

### S4 — TAX, open: the ledger's commit-timestamp ordering ties within one clock instant

The ledger spelling from README §12 — the score in force is the latest correction —

```
shape ObservationCorrection {
    observation: one PlotObservation? initially required
    previousSeverity: decimal
    correctedSeverity: decimal
    reason: text
    correctedOn: timestamp on create
}

expose shape PlotObservation {
    plot: one GreenhousePlot
    dayAfterTreatment: integer
    recordedSeverity: decimal
    observedOn: Date
    diseaseSeverity: decimal = if observationCorrections is empty then recordedSeverity
                               else latest(observationCorrections by correctedOn).correctedSeverity
}
```

fails loudly the moment two corrections to one observation land at the same clock instant: the harness clock only moves when a test advances it, so two consecutive commits share `now`, and `latest(... by correctedOn)` ties. README §10 already says same-transaction siblings share a timestamp by definition; two transactions at one instant are the same hazard, and a real clock at millisecond resolution can produce it too. The test advances the clock sixty seconds between corrections (`greenhouse - a correction is a ledger entry the curve reads, never an overwrite`). README §12's own `EmailCorrection` example carries the same exposure. What it asks for: guidance — an author-carried sequence datum beside the timestamp, or the harness advancing its clock per commit — recorded as a TODO item.

### S5 — GAP, spelled around; open as OQ47: a `never` over a persistent act is a statement about history

First spelling, on the persistent act:

```
expose shape TreatmentApplication {
    trial: one FieldTrial
    appliedOn: Date
    rateGramsPerHa: decimal
}

shape HaltedTrial = FieldTrial where
    exists (fieldPlots where exists (phytotoxicityReports where severity >= 3))

never (TreatmentApplication where trial is HaltedTrial)
```

This validates, and it refuses an application on a halted trial. It also refuses the *severe report that halts the trial* whenever an application is already on record: the report's commit makes `trial is HaltedTrial` true for the application landed last week, the transaction-end check finds a `TreatmentApplication` in the forbidden configuration, and the report is refused with "never #20 over TreatmentApplication is violated". The same spelling for permits — `never (Planting where not trial is PermittedTrial)` with `PermittedTrial` reading `today` — is worse: a `never` that reads the clock is re-checked at every transaction end, so the day a permit expires, every commit in the whole system is refused for as long as a planting stands on that trial.

Nothing in the validator says a word. The advisory family that exists for this hazard (A4, drift-exposed act partition) covers a rule triggered by a partition of a persistent act; it does not cover a `never` over one.

The example's spelling is a transient request answered by a record — the request is checked at its moment and is gone before the state can drift:

```
expose transient shape ApplyTreatment {
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

Evidence: `field - a severe phytotoxicity report halts the trial and later applications are refused at the boundary` and `field - the request needs an effective greenhouse result on record, and planting needs a permit in force` in `PeptidesPipelineTest.kt` — the second lets the permit lapse and shows unrelated commits still landing. Whether the validator should warn, and what the warning asks the author, is OQ47 (Part 1).

### S6 — TAX, open: a capture of the base shape's own derived property is refused

The first draft fixed the efficacy at the moment a trial completed:

```
shape CompleteTrial = LaidOutTrial and ObservedToEnd {
    captured efficacyAtCompletion: decimal = efficacy
}
```

`efficacy` is a derived property declared on `GreenhouseTrial` itself, so it is evaluable for every instance at every moment. V6's coarse slice looks through the derivation, finds it reading `PlotObservation.diseaseSeverity` and `GreenhousePlot.audpc`, and refuses because the refinement's predicate does not "guarantee" those shapes at entry. The example records the value in the result record instead, from the rule body at the completing commit, which reads the same number at the same moment:

```
rule RecordGreenhouseResult when (CompleteTrial where not exists GreenhouseResult for this) {
    GreenhouseResult from {
        trial: this
        efficacy: efficacy
        effective: if efficacy >= protocol.efficacyThreshold then true else false
    }
}
```

The spelling works and is arguably the better one here (the field stage reads the record). The cost is that `captured` lost its most natural use in this domain. What it asks for: V6 to treat the base shape's own derived properties as unconditionally evaluable — recorded as a TODO item.

### S7 — TAX, open (rides OQ16): a `never` over any rule-written field fails closed

Two invariants were refused as "rule-maintained — v0 cannot yet prove the invariant inductively": a score range, because a correction rule assigned `diseaseSeverity`; and "no score before treatment", because a rule assigned `treatedAt`. Both rewrites improved the model — the score became the ledger of S4 (what was committed stays committed; the score in force is derived), and treatment became a record (`expose shape Treatment`) with `treatedAt` derived from it — but the check is what forced them, and it will force them on every spec where an act and a rule both write one field. The remaining assigned field in the spec (a plot's bench, frozen after treatment) carries no `never`.

### S8 — TAX, open (rides OQ16): the episodes discharge does not cover a state fed by a series

A greenhouse is out of band when its latest climate reading is outside its limits, and each stretch out of band is an episode:

```
shape OutOfBand = Greenhouse where
    climateReadings is not empty and
    (latest(climateReadings by recordedAt).temperature < minTemperature or
     latest(climateReadings by recordedAt).temperature > maxTemperature)

shape OpenExcursion = ClimateExcursion where not exists ExcursionEnd for this

rule OpenClimateExcursion when (OutOfBand where not exists OpenExcursion for this) { ... }

shape ClosableExcursion = OpenExcursion where not greenhouse is OutOfBand

rule CloseClimateExcursion when ClosableExcursion { ... }
```

V15 pairs the opener and the closer: both change membership in `OpenExcursion`, and it cannot see that one `ClimateReading` commit moves exactly one greenhouse either in or out, never both. The discharge that clears README §20's delinquency episodes requires the flipping commit to be an assignment to a column of the state's own shape; here the flip is the creation of a series point read through an inferred inverse. The `when leaving OutOfBand` spelling drew the same error. The example takes the diagnostic's own suggestion — `after commit, Hourly` on the closer, with `ClosableExcursion` as its guard — which is a legitimate spelling ("closed right after the reading, healed hourly") but not the one the business asked for. Recorded as a TODO item.

### S9 — HOLDS: the predecessor recurrence over a business datum

The running peak (`max`) and the area under the disease-progress curve (the trapezoid rule) are both spelled as README §19's predecessor recurrence over the hour or day mark, and everything the README claims for it held at runtime and over SQLite: a reading entered late slots into its hour and every later reading's running value re-derives; a voided score drops out and the chain closes over the gap; the base case and V14's descent certificate were accepted without a certificate declaration; mixed decimal and integer arithmetic in the trapezoid, `sum` over a derived field of a refinement-filtered inverse, `sum` over a derived collection's derived field, and decimal division (bounded to sixteen digits) all evaluated correctly. Evidence: the four `greenhouse -` tests and the first `lab -` test in `PeptidesPipelineTest.kt`, run three times (in memory, over SQLite through the generic protocol, over SQLite through the generated typed store).

### S10 — TAX, by design: what the series vocabulary lacks

Three things the domain reached for that the language does not have, each with a spelling that works:

- **No mean or maximum over a collection.** `sum` and `count` are the only aggregates; a mean is `sum(...) / count(...)` guarded against the empty collection, and a peak over a series is the recurrence (S9). `max`/`min` exist only over two values.
- **No difference between two dates.** The trapezoid needs the days between two observations; `Date - Date` does not exist (only `Date ± duration`). Every series therefore carries an integer day mark beside its date (`dayAfterTreatment`, `daysAfterPlanting`), which happens to be how agronomists already record them, but it is the language deciding the data model.
- **A boolean field cannot be written as a comparison.** `effective: efficacy >= protocol.efficacyThreshold` is not a value expression; the spelling is `if ... then true else false`.

Also in this family, already a TODO item: `then` must share the `if`'s line.

### S11 — GAP, fixed: generated boundary cases ignored range invariants spelled with `or`

The generated "a `AssayReading` with `hoursElapsed` 0 is accepted" case filled the act's other numeric fields with −1, which `never (AssayReading where inhibitionPercent < 0 or inhibitionPercent > 100)` refuses; the positive case failed. The generator avoided values other `never`s forbid, but only single-comparison ones. Fixed in `compiler/src/main/kotlin/velle/SpecGen.kt`: each arm of an `or` is a forbidden comparison. Regenerating every example changed no other output. Still open in the same family: the generator cannot see a relationship `never` on the shape (`never (PlotObservation where not plot.trial is TreatedTrial)`), so the given that supplies "any committed `GreenhousePlot`" has to deliver a plot on a treated trial — the demand is documented on the given, and the generated case passes, but the coupling is invisible from the generated test.

### S12 — TAX, open: every series rule widens to a whole-shape queue

`Validator.advisories` reports forty-one A5 contention-width advisories on the spec, none of A2, A4, or A6. Nearly all have one shape: a rule's condition reads a series through the subject's inferred inverse — `assays`, `assayReadings`, `climateReadings`, `treatments`, `phytotoxicityReports`, `weatherObservations` — and the domain derivation reports "consults X through 'assays' with no keyable owner", so every commit touching that shape joins one queue. Under the ruling that width is a throughput advisory this is not an error, but the domain is exactly where the keys are obvious (per experiment, per greenhouse, per trial, per site), and a pipeline whose every reading serializes system-wide is not what the spec describes. The precision items in `audit-symmetric-evaluation.md` (P2–P4) and the "stress-test A5 against realistic specs" TODO item now have a spec to run against.

### S13 — TAX, by design: a duration cannot be built from data

The collection task's first draft derived its due date: seven days after treatment, from a protocol's interval. `treatedAt + interval days` is not grammatical — a duration takes a literal count (`7 days`), never a field (`grammar.md`, DurationLiteral). So the spec cannot say "collect every N days after treatment" with N as data; the admin states each task's due date as a date, and the interval lives in whatever tool the admin uses. The task shape is the better model anyway (a due date is a decision, not a formula), but the limitation decided it. Recorded as a language question in `TODO.md`.

### S14 — union as a task target: HOLDS, with one narrowing gap for OQ44

`shape Trial = GreenhouseTrial or FieldTrial` and `CollectionTask.trial: one Trial` worked as README §6 promises: the inverse `collectionTasks` is inferred onto both operands and the union, the generated view carries the operand's name and the narrowing accessors, `frozen` and `undeletable` on a refinement of the task (`DoneTask`) clear their gates through the ordinary partitions, deleting a task with a union-typed reference is an ordinary `delete`, and the boundary accepted a trial of either shape. Evidence: the three `tasks -` tests in `PeptidesPipelineTest.kt`.

The gap: narrowing the reference is not carried into a collection filter's source path. The natural spelling of the pending count,

```
plotsPending: integer = if trial is GreenhouseTrial then
                            count(trial.greenhousePlots where lastObservedDay < this.dayMark)
                        else count(trial.fieldPlots where lastScoutedDay < this.dayMark)
```

is refused: the name check resolves `trial.greenhousePlots` against the union (which has no `greenhousePlots`), gets no element shape, and reports `'lastObservedDay' is not a member of 'CollectionTask'`; the read-summary collector has no narrowing at all, marks the branch opaque, and V15 floods again as in S1. The example counts each stage's plots by comparing their trial to the union-typed reference instead, which needs no narrowing and reads the task's day mark through `this.` two filters deep. Added to OQ44's list of narrowing forms.

## What needs Dave's input

### 1. Which comes first at a commit: the rules watching a series, or the `never` that keeps the series well-ordered? (S3) — ruled

Ruled 2026-10-09, no input needed. The `never` comes first: the boundary guardrail for an input-constrained invariant is evaluated over pre-state plus the act's closure before any rule runs, with the worked example of the duplicate hour mark in `evaluation.md` "An exposed call". What remains is implementation: v0's runtime runs every `never` at transaction end only (`compiler/src/main/kotlin/velle/Runtime.kt`, `checkNevers`), and the item to add the boundary pass is in `TODO.md`.

### 2. Should the ledger's ordering carry an author datum beside the timestamp? (S4)

README §12's ledger and this spec's corrections both order by `timestamp on create`. Within one clock instant that ties. Either the README notes the exposure and recommends an author-carried sequence (the caller supplies `sequence: integer`, as it supplies the correlation key), or the harness clock advances by one unit per commit so the tie cannot happen in tests — which hides it rather than answering it.

### 3. A `never` over a persistent act that reads state which can flip later — OQ47

Spelled out as OQ47 in `QUESTIONS.md`. The short form: whether a situation is rejected or recorded is the author's choice per `never`, so the persistent spelling is legitimate when chosen on purpose; the question is the advisory that makes the author see that a `never` over a standing record can be violated by a later act, tick, or deletion, and asks which they meant (Part 1), and then what the rejection says and who hears it, including a scheduled rule's rejection, which reaches nobody today (Part 2).

## Scorecard

| # | finding | verdict | where it stands |
|---|---|---|---|
| S1 | `this` inside a filter resolved to the element by the validator | GAP | fixed; probe in `ValidatorTest.kt` |
| S2 | `as` alias as a collection source lost its element | GAP | fixed; probe in `ValidatorTest.kt` |
| S3 | uniqueness `never` on a series datum reached after the rules that read it | GAP | ruled 2026-10-09 → README §21, `evaluation.md`; runtime implementation in `TODO.md` |
| S4 | ledger ordering by commit timestamp ties within one instant | TAX | open — section 2; TODO |
| S5 | `never` over a persistent act is a statement about history | GAP | spelled around (transient request); OQ47 |
| S6 | capture of the base's own derived property refused by V6 | TAX | spelled around (record); TODO |
| S7 | `never` over any rule-written field fails closed (V10) | TAX | spelled around (ledger, record); rides OQ16 |
| S8 | episodes discharge does not cover a series-fed state (V15) | TAX | spelled around (`after commit, Hourly`); TODO |
| S9 | predecessor recurrence over a business datum | HOLDS | `PeptidesPipelineTest.kt` |
| S10 | no collection mean/max, no date difference, no boolean from a comparison | TAX | by design; spellings recorded |
| S11 | generated boundary cases ignored `or` range invariants | GAP | fixed in `SpecGen.kt`; relationship-never coupling noted |
| S12 | every series rule widens to a whole-shape queue (A5) | TAX | open; TODO |
| S13 | a duration cannot be built from data — due dates are stated, not derived | TAX | by design; language question in TODO |
| S14 | union as a task target; narrowing lost on a filter's source path | HOLDS / GAP | the gap added to OQ44 |
