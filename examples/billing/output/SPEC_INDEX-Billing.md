# Spec Index — Billing

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## SignUpSpec.kt

- AdmitCustomer - a new SignUp produces a Customer

## CorrectEmailSpec.kt

- ApplyEmailCorrection - a new CorrectEmail sets customer.email

## BillCustomerSpec.kt

- OpenInvoice - a new BillCustomer produces an Invoice
- SendReceipt - a new PaidInvoice produces a Receipt
- EmailReceipt - a new UnemailedReceipt produces a ReceiptEmail after the commit
- RemindOverdue - at the Weekly tick, an ActionableOverdue produces a Reminder
- ApplyDueChange - a new ApplicableDueChange sets invoice.due
- RecordDueChangeRefusal - a new RefusedDueChange produces a DueChangeRefusal
- NoteUnarchival - an UnarchiveRequest for an ArchivedInvoice produces an UnarchiveNotice

## SubmitPaymentSpec.kt

- RecordPayment - a new SubmitPayment produces a Payment
- never - a SubmitPayment where amount at most 0 is refused
- never - a SubmitPayment with amount 1 is accepted

## PaymentSpec.kt

- TrackLargestPayment - a new Payment sets invoice.customer.largestPayment

## LineItemSpec.kt

- never - a LineItem where quantity at most 0 is refused
- never - a LineItem with quantity 1 is accepted
- never - a LineItem where price below 0 is refused
- never - a LineItem with price 0 is accepted
