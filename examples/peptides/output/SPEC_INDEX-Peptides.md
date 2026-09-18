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


## TreatmentSpec.kt


## PlotObservationSpec.kt

- never - a PlotObservation where dayAfterTreatment below 0 is refused
- never - a PlotObservation with dayAfterTreatment 0 is accepted

## CorrectObservationSpec.kt


## WeatherObservationSpec.kt


## FieldPlotSpec.kt


## ScoutingSpec.kt


## HarvestSpec.kt

- never - a Harvest where yieldKgPerHa below 0 is refused
- never - a Harvest with yieldKgPerHa 0 is accepted

## PhytotoxicityReportSpec.kt


## CollectionTaskSpec.kt

- never - a CollectionTask where dayMark below 0 is refused
- never - a CollectionTask with dayMark 0 is accepted

## Not yet generated

- never #13: non-comparison predicate
- never #20: non-comparison predicate
- never #1: non-literal bound
- never #2: complex comparison subject
- never #4: complex comparison subject
- never #5: multi-conjunct predicate
- never #6: complex comparison subject
- never #7: complex comparison subject
- never #9: complex comparison subject
- never #10: non-comparison predicate
- never #11: complex comparison subject
- never #12: complex comparison subject
- never #14: multi-conjunct predicate
- never #15: complex comparison subject
- never #16: complex comparison subject
- never #17: complex comparison subject
- never #19: complex comparison subject
