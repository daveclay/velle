# Spec Index — Membership

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## SignUpSpec.kt

- AdmitMember - a new SignUp produces a Member
- RenewMembership - at the Monthly tick, an ActiveMember produces a Charge
- ApplyCharge - a new UnappliedCharge produces a ChargeApplication and sets member.balance after the commit
- TrackLowestBalance - a new Delinquent sets lowestBalance
- SuspendDelinquents - at the Nightly tick, a Delinquent sets suspended
- OpenDelinquencyEpisode - a new Delinquent produces a DelinquencyFlag
- CloseDelinquencyEpisode - a member that leaves Delinquent produces a DelinquencyResolution
- OpenAccountReview - a new ChronicDelinquent produces an AuditEntry and an AccountReview

## MemberSpec.kt

- SendWelcome - a new Member produces a WelcomeNote
- RestoreService - at the Nightly tick, a Member sets suspended
- ScoreEngagement - at the Nightly tick, a Member sets engagementScore

## ChangeEmailSpec.kt

- RecordEmailChange - a new ChangeEmail produces an EmailChange

## VisitSpec.kt

- CountVisit - a new Visit sets member.visitCount
- PingAnalytics - a new Visit produces an AnalyticsPing
- never - a Visit where minutes at most 0 is refused
- never - a Visit with minutes 1 is accepted

## MakeDepositSpec.kt

- RecordDeposit - a new MakeDeposit produces a Deposit
- ApplyDeposit - a new UnappliedDeposit sets member.balance and applied after the commit
- never - a MakeDeposit where amount at most 0 is refused
- never - a MakeDeposit with amount 1 is accepted

## RaiseTicketSpec.kt

- FileTicket - a new RaiseTicket produces a Ticket
- NoticeReopen - a ReopenTicket for a ClosedTicket produces a ReopenNotice
- ApplyAssignment - a new ApplicableAssignment sets ticket.assignee
- EscalateUrgent - at the Daily tick, an UrgentQueue produces an Escalation

## CloseTicketSpec.kt

- RecordTicketClosure - a new CloseTicket produces a TicketClosure

## AssignTicketSpec.kt


## Not yet generated

- never #1: never over a refinement
- never #4: multi-conjunct predicate
- never #5: non-comparison predicate
