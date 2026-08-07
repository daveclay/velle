# Spec Index — Billing

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## CorrectEmailSpec.kt

- ApplyEmailCorrection - entering CorrectEmail fires its effects

## PaymentSpec.kt

- TrackLargestPayment - entering Payment fires its effects
- never - a Payment where amount at most 0 is refused
- never - a Payment with amount 1 is accepted

## PaidInvoiceSpec.kt

- SendReceipt - entering PaidInvoice fires its effects
- EmailReceipt - entering UnemailedReceipt fires after the transaction

## OverdueInvoiceSpec.kt

- RemindOverdue - the Weekly sweep serves ActionableOverdue

## IssuedInvoiceSpec.kt

- ApplyDueChange - entering ApplicableDueChange fires its effects
- RecordDueChangeRefusal - entering RefusedDueChange fires its effects

## ArchivedInvoiceSpec.kt

- NoteUnarchival - leaving ArchivedInvoice fires the exit reaction

## LineItemSpec.kt

- never - a LineItem where quantity at most 0 is refused
- never - a LineItem with quantity 1 is accepted
- never - a LineItem where price below 0 is refused
- never - a LineItem with price 0 is accepted
