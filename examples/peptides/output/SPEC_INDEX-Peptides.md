# Spec Index — Peptides

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## HitExperimentSpec.kt

- RecordLabHit - a new HitExperiment produces a LabHit
- RetractLabHit - a labExperiment that leaves HitExperiment produces a LabHitRetraction

## ApprovedGreenhouseRequestSpec.kt

- ApproveGreenhouseTrial - a new ApprovedGreenhouseRequest produces a GreenhouseTrial
- RecordGreenhouseResult - a new CompleteTrial produces a GreenhouseResult
- ApplyCorrection - a new ApplicableCorrection produces an ObservationCorrection
- RefuseCorrection - a new RefusedCorrection produces a CorrectionRefusal
- ApplyVoid - a new ApplicableVoid produces a VoidRecord
- RefuseVoid - a new RefusedVoid produces a VoidRefusal
- ApplyMove - a new ApplicableMove sets plot.bench
- RefuseMove - a new RefusedMove produces a MoveRefusal

## RefusedGreenhouseRequestSpec.kt

- RefuseGreenhouseTrial - a new RefusedGreenhouseRequest produces a GreenhouseRefusal

## OutOfBandSpec.kt

- OpenClimateExcursion - a new OutOfBand produces a ClimateExcursion
- CloseClimateExcursion - a new ClosableExcursion produces an ExcursionEnd after the commit

## SilentGreenhouseSpec.kt

- RaiseSensorAlert - at the Hourly tick, a SilentGreenhouse produces a SensorAlert

## ApprovedFieldRequestSpec.kt

- ApproveFieldTrial - a new ApprovedFieldRequest produces a FieldTrial
- NoticeHalt - a new HaltedTrial produces a HaltNotice

## RefusedFieldRequestSpec.kt

- RefuseFieldTrial - a new RefusedFieldRequest produces a FieldRefusal

## PlantTrialSpec.kt

- RecordPlanting - a new PlantTrial produces a Planting

## ApplyTreatmentSpec.kt

- RecordApplication - a new ApplyTreatment produces a TreatmentApplication
- NoticeWashOff - a new WashedOffApplication produces a ReapplicationNotice

## OpenTaskSpec.kt

- RemindTask - at the Daily tick, an OverdueTask produces a TaskReminder

## DoneTaskSpec.kt

- ApplyReschedule - a new ApplicableReschedule sets task.dueOn
- RefuseReschedule - a new RefusedReschedule produces a RescheduleRefusal
- ApplyCancel - a new ApplicableCancel produces a TaskCancellation
- RefuseCancel - a new RefusedCancel produces a CancelRefusal

## PeptideSpec.kt


## AssayReadingSpec.kt

- never - a AssayReading where hoursElapsed below 0 is refused
- never - a AssayReading with hoursElapsed 0 is accepted

## GreenhousePlotSpec.kt


## PlotObservationSpec.kt

- never - a PlotObservation where dayAfterTreatment below 0 is refused
- never - a PlotObservation with dayAfterTreatment 0 is accepted

## CorrectObservationSpec.kt


## FieldPlotSpec.kt


## ScoutingSpec.kt


## HarvestSpec.kt

- never - a Harvest where yieldKgPerHa below 0 is refused
- never - a Harvest with yieldKgPerHa 0 is accepted

## PhytotoxicityReportSpec.kt


## CollectionTaskSpec.kt

- never - a CollectionTask where dayMark below 0 is refused
- never - a CollectionTask with dayMark 0 is accepted

## TreatmentSpec.kt


## WeatherObservationSpec.kt


## Not yet generated

- never #9: non-comparison predicate
- never #15: non-comparison predicate
- never #1: non-literal bound
- never #2: complex comparison subject
- unique #17: a key, unique (assay, hoursElapsed): no bound to cross
- requires #4: multi-conjunct predicate
- never #5: complex comparison subject
- never #7: non-comparison predicate
- unique #19: a key, unique (plot, dayAfterTreatment): no bound to cross
- never #8: complex comparison subject
- requires #10: multi-conjunct predicate
- never #11: complex comparison subject
- never #12: complex comparison subject
- unique #21: a key, unique (plot, daysAfterPlanting): no bound to cross
- never #14: complex comparison subject
- unique #18: a key, trial: one GreenhouseTrial unique: no bound to cross
- unique #20: a key, unique (site, observedOn): no bound to cross
