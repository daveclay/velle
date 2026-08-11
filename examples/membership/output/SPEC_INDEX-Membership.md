# Spec Index — Membership

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## MemberSpec.kt

- SendWelcome - a new Member produces a WelcomeNote
- RestoreService - at the Nightly tick, a Member sets suspended
- ScoreEngagement - at the Nightly tick, a Member sets engagementScore

## VisitSpec.kt

- CountVisit - a new Visit sets member.visitCount
- PingAnalytics - a new Visit produces an AnalyticsPing
- never - a Visit where minutes at most 0 is refused
- never - a Visit with minutes 1 is accepted

## UnappliedDepositSpec.kt

- ApplyDeposit - a new UnappliedDeposit sets member.balance and applied after the commit

## ActiveMemberSpec.kt

- RenewMembership - at the Monthly tick, an ActiveMember produces a Charge
- ApplyCharge - a new UnappliedCharge produces a ChargeApplication and sets member.balance after the commit

## DelinquentSpec.kt

- TrackLowestBalance - a new Delinquent sets lowestBalance
- SuspendDelinquents - at the Nightly tick, a Delinquent sets suspended
- OpenDelinquencyEpisode - a new Delinquent produces a DelinquencyFlag
- CloseDelinquencyEpisode - a member that leaves Delinquent produces a DelinquencyResolution

## ChronicDelinquentSpec.kt

- OpenAccountReview - a new ChronicDelinquent produces an AuditEntry and an AccountReview

## ClosedTicketSpec.kt

- NoticeReopen - a ReopenTicket for a ClosedTicket produces a ReopenNotice
- ApplyAssignment - a new ApplicableAssignment sets ticket.assignee
- EscalateUrgent - at the Daily tick, an UrgentQueue produces an Escalation

## DepositSpec.kt

- never - a Deposit where amount at most 0 is refused
- never - a Deposit with amount 1 is accepted

## TicketSpec.kt


## AssignTicketSpec.kt


## Not yet generated

- never #1: never over a refinement
- never #4: multi-conjunct predicate
- never #5: non-comparison predicate
