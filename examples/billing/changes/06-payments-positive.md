# What changed from 05-zero-total-receipts.velle to 06-payments-positive.velle

This report compares what two versions of a spec say. It describes a system in general,
never the records of a running one. Each line points at the place in the spec that says it.
Where the report has no words for a change yet, it shows the Velle instead and says so.

## What was edited

- A Payment is now forbidden when its amount is at most 0.
  In the spec: 06-payments-positive.velle, line 69, `never (Payment where amount <= 0)`

## What that changes

What the compiler derives from the edits for declarations nobody edited. Each is something
that can now happen or can no longer happen; none says what will happen.

### Invoice

- RecordPayment creating a Payment can no longer make an Invoice stop being a PaidInvoice.
  In the spec: 06-payments-positive.velle, line 69, `never (Payment where amount <= 0)`
- RecordPayment creating a Payment can no longer make an Invoice become an OverdueInvoice.
  In the spec: 06-payments-positive.velle, line 69, `never (Payment where amount <= 0)`

### RecordPayment

- RecordPayment can no longer cause RemindOverdue to run, later rather than in the same commit.
  In the spec: 06-payments-positive.velle, line 69, `never (Payment where amount <= 0)`

## What happens to a record like this

What the edits do to a record of the kind the system keeps, worked out from the two specs alone.
It says which kind of record, never which records or how many.

### A Payment like this

- A Payment stored before this change is now forbidden when its amount is at most 0. Its next change could be refused.
  In the spec: 06-payments-positive.velle, line 69, `never (Payment where amount <= 0)`
