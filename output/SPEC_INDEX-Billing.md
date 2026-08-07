# Spec Index — Billing

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## CorrectEmailSpec.kt

- ApplyEmailCorrection - a new CorrectEmail sets customer.email

## PaymentSpec.kt

- TrackLargestPayment - a new Payment sets invoice.customer.largestPayment
- never - a Payment where amount at most 0 is refused
- never - a Payment with amount 1 is accepted

## PaidInvoiceSpec.kt

- SendReceipt - a new PaidInvoice produces a Receipt
- EmailReceipt - a new UnemailedReceipt produces a ReceiptEmail after the commit

## OverdueInvoiceSpec.kt

- RemindOverdue - at the Weekly tick, an ActionableOverdue produces a Reminder

## IssuedInvoiceSpec.kt

- ApplyDueChange - a new ApplicableDueChange sets invoice.due
- RecordDueChangeRefusal - a new RefusedDueChange produces a DueChangeRefusal

## ArchivedInvoiceSpec.kt

- NoteUnarchival - an UnarchiveRequest for an ArchivedInvoice produces an UnarchiveNotice

## LineItemSpec.kt

- never - a LineItem where quantity at most 0 is refused
- never - a LineItem with quantity 1 is accepted
- never - a LineItem where price below 0 is refused
- never - a LineItem with price 0 is accepted
