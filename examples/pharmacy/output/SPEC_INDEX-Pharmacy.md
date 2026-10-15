# Spec Index — Pharmacy

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## HireStaffSpec.kt

- RecordHire - a new HireStaff produces a Staff
- RemindLicence - at the Daily tick, a LicenceExpiringSoon produces a LicenceReminder

## EffectivePriceSpec.kt

- ApplyWithdrawal - a new ApplicableWithdrawal produces a PriceWithdrawal
- RefuseWithdrawal - a new RefusedWithdrawal produces a PriceWithdrawalRefusal

## SetReorderPointSpec.kt

- ApplyReorderPoint - a new SetReorderPoint sets drug.reorderPoint
- never - a SetReorderPoint where reorderPoint below 0 is refused
- never - a SetReorderPoint with reorderPoint 0 is accepted

## MatchingCountSpec.kt

- ConfirmCount - a new MatchingCount produces a CountConfirmation

## DiscrepantCountSpec.kt

- AdjustCount - a new DiscrepantCount produces a StockAdjustment

## ExpiredLotSpec.kt

- QuarantineExpired - at the Nightly tick, an ExpiredLot produces a Quarantine

## RecalledLotSpec.kt

- QuarantineRecalled - a new RecalledLot produces a Quarantine

## FillSpec.kt

- NoticeRecall - a new Fill produces a RecallNotice
- LogControlled - a new Fill produces a ControlledLogEntry

## LowStockSpec.kt

- Reorder - at the Nightly tick, a LowStock produces a PurchaseOrder

## SetAllergiesSpec.kt

- ApplyAllergies - a new SetAllergies sets patient.allergies

## AddAllergySpec.kt

- ApplyAllergy - a new AddAllergy sets patient.allergies

## FilledPrescriptionSpec.kt

- ApplyCorrection - a new ApplicableCorrection sets prescription.directions
- RefuseCorrection - a new RefusedCorrection produces a CorrectionRefusal
- ApplyVoid - a new ApplicableVoid produces an EntryVoid
- RefuseVoid - a new RefusedVoid produces a VoidRefusal

## AllergyConflictSpec.kt

- AlertAllergy - a new AllergyConflict produces a PharmacistAlert

## InteractionConflictSpec.kt

- AlertInteraction - a new InteractionConflict produces a PharmacistAlert

## ApplicableFillSpec.kt

- RecordFill - a new ApplicableFill produces a Fill
- NoticeReady - a new ReadyForPickup produces a Notice
- ReturnUncollected - at the Nightly tick, an Uncollected produces a ReturnToStock
- RecordCounterExit - a fill that leaves ReadyForPickup produces a CounterExit
- AlertCounter - a new ReadyForPickup produces a CounterAlert
- SubmitClaim - a new InsuredFill produces a Claim
- FollowUpClaim - at the Hourly tick, a StaleClaim produces a ClaimFollowUp

## RefusedFillSpec.kt

- RefuseFill - a new RefusedFill produces a FillRefusal

## VerifyFillSpec.kt

- RecordVerification - a new VerifyFill produces a Verification

## PickUpSpec.kt

- RecordPickup - a new PickUp produces a Pickup

## CurrentSpec.kt

- QueueRefill - a new ApplicableRefill produces a RefillTask
- RefuseRefill - a new RefusedRefill produces a RefillRefusal
- NagRefillTask - at the Daily tick, an OverdueRefillTask produces a TaskNag
- QueueAutoRefill - at the Daily tick, an AutoRefill produces a RefillTask
- NoticeExpiry - a prescription that leaves ActivePrescription produces a Notice

## RefillRefusalSpec.kt

- AskPrescriber - a new RefillRefusal produces an AuthorisationRequest and a Notice

## AdjudicationSpec.kt

- RecordAdjudication - a new Adjudication produces a ClaimResponse

## ControlledSpec.kt

- SnapshotControlled - at the Monthly tick, a Controlled produces a ControlledInventorySnapshot

## StaffSpec.kt


## LicenceRenewalSpec.kt


## DrugSpec.kt


## InteractionSpec.kt


## PriceChangeSpec.kt

- never - a PriceChange where unitPrice below 0 is refused
- never - a PriceChange with unitPrice 0 is accepted

## StockLotSpec.kt

- never - a StockLot where received at most 0 is refused
- never - a StockLot with received 1 is accepted

## CountLotSpec.kt

- never - a CountLot where counted below 0 is refused
- never - a CountLot with counted 0 is accepted

## PrescriptionSpec.kt


## DiscontinuationSpec.kt


## FillPrescriptionSpec.kt


## WithdrawPriceChangeSpec.kt


## VoidEntrySpec.kt


## ConflictOverrideSpec.kt


## MonitoringCheckSpec.kt


## CounsellingSpec.kt


## Not yet generated

- requires #34: the role gate an act's `by` clause states
- requires #8: multi-conjunct predicate
- requires #23: shape is not an act
- never #27: shape is not an act
- never #31: shape is not an act
- never #25: multi-conjunct predicate
- requires #26: multi-conjunct predicate
- requires #40: the role gate an act's `by` clause states
- requires #28: complex comparison subject
- never #29: non-comparison predicate
- never #30: non-literal bound
- requires #32: multi-conjunct predicate
- never #33: complex comparison subject
- requires #1: shape is not an act
- requires #2: non-comparison predicate
- requires #35: the role gate an act's `by` clause states
- never #3: complex comparison subject
- never #4: non-literal bound
- requires #5: multi-conjunct predicate
- never #7: complex comparison subject
- requires #11: multi-conjunct predicate
- never #13: shape is not an act
- never #14: shape is not an act
- never #15: shape is not an act
- never #16: shape is not an act
- never #17: shape is not an act
- requires #18: non-literal bound
- requires #19: multi-conjunct predicate
- requires #20: multi-hop comparison subject
- never #21: complex comparison subject
- never #22: complex comparison subject
- never #24: multi-conjunct predicate
- requires #36: the role gate an act's `by` clause states
- requires #37: the role gate an act's `by` clause states
- requires #38: the role gate an act's `by` clause states
- requires #39: the role gate an act's `by` clause states
- requires #41: the role gate an act's `by` clause states
