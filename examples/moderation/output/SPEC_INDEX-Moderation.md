# Spec Index — Moderation

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## PublishSpec.kt

- ApplyPublish - a new Publish sets listing.isDraft

## DraftSpec.kt

- AnnouncePublication - a listing that leaves Draft produces a PublicationNotice
- NoteDiscard - a listing in Draft that is deleted produces a SellerNotice
- RecordDraftClosure - a listing that leaves Draft produces a DraftClosure

## OrphanReportSpec.kt

- NotifyReporter - a new OrphanReport produces a ReporterNotice

## DiscardDraftSpec.kt

- ApplyDiscard - a new DiscardDraft produces a DiscardRecord
- RefuseDiscard - a new DiscardDraft produces a DiscardRefusal

## SettlePayoutSpec.kt

- ApplySettle - a new SettlePayout produces a Settlement

## CloseAccountSpec.kt

- ApplyClose - a new CloseAccount 

## HandOverAndCloseSpec.kt

- ApplyHandOver - a new HandOverAndClose sets payout.seller

## OrphanedListingSpec.kt

- SweepOrphanedDrafts - a new OrphanedListing 

## ListingSpec.kt


## Not yet generated

- never #2: non-comparison predicate
- never #3: non-comparison predicate
- never #1: non-literal bound
