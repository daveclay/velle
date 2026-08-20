# Spec Index — Loyalty

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## JoinSpec.kt

- AdmitMember - a new Join produces a Member
- IssueKeycard - a new CertifiedMember produces a Keycard

## QualifiedPurchaseSpec.kt

- PromoteBuyer - a new QualifiedPurchase sets member.tier
- PromoteReferrer - a new QualifiedPurchase sets member.referrer.tier

## VipPurchaseSpec.kt

- ThankVip - a new VipPurchase produces a ThankYouNote

## EnrollmentSpec.kt

- FileWaiver - a new Enrollment produces a SafetyWaiver
- BookOrientation - a new Enrollment produces an OrientationRecord

## MemberSpec.kt


## PurchaseSpec.kt

- never - a Purchase where amount at most 0 is refused
- never - a Purchase with amount 1 is accepted

## Not yet generated

- never #1: shape not exposed
