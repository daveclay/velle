# Spec Index — Membership

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## MemberSpec.kt

- SendWelcome - entering Member fires its effects
- RestoreService - the Nightly sweep serves Member
- ScoreEngagement - the Nightly sweep serves Member

## VisitSpec.kt

- CountVisit - entering Visit fires its effects
- PingAnalytics - entering Visit fires its effects
- never - a Visit where minutes at most 0 is refused
- never - a Visit with minutes 1 is accepted

## UnappliedDepositSpec.kt

- ApplyDeposit - entering UnappliedDeposit fires after the transaction

## ActiveMemberSpec.kt

- RenewMembership - the Monthly sweep serves ActiveMember
- ApplyCharge - entering UnappliedCharge fires after the transaction

## DelinquentSpec.kt

- TrackLowestBalance - entering Delinquent fires its effects
- SuspendDelinquents - the Nightly sweep serves Delinquent
- OpenDelinquencyEpisode - entering Delinquent fires its effects
- CloseDelinquencyEpisode - leaving Delinquent fires the exit reaction

## ChronicDelinquentSpec.kt

- OpenAccountReview - entering ChronicDelinquent fires its effects

## ClosedTicketSpec.kt

- NoticeReopen - leaving ClosedTicket fires the exit reaction
- ApplyAssignment - entering ApplicableAssignment fires its effects
- RecordAssignmentRefusal - entering RefusedAssignment fires its effects
- EscalateUrgent - the Daily sweep serves UrgentQueue

## DepositSpec.kt

- never - a Deposit where amount at most 0 is refused
- never - a Deposit with amount 1 is accepted

## TicketSpec.kt


## Not yet generated

- never #1: never over a refinement
- never #4: multi-conjunct predicate
