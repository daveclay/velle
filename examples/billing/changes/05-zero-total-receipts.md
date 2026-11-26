# What changed from 04-nudge.velle to 05-zero-total-receipts.velle

This report compares what two versions of a spec say. It describes a system in general,
never the records of a running one. Each line points at the place in the spec that says it.
Where the report has no words for a change yet, it shows the Velle instead and says so.

## What was edited

- An Invoice is now a PaidInvoice when its balance is at most 0. Before: when its total is above 0 and its balance is at most 0.
  In the spec: 05-zero-total-receipts.velle, line 87, `shape PaidInvoice = Invoice where balance <= 0`

## What that changes

What the compiler works out from the edits, beyond what each edit says on its own. Each is
something that can now happen or can no longer happen; none says what will happen.

### OpenInvoice

- OpenInvoice can now cause SendReceipt to run.
  In the spec: 05-zero-total-receipts.velle, line 87, `shape PaidInvoice = Invoice where balance <= 0`

## What happens to a record like this

What the edits do to a record of the kind the system keeps, worked out from the two specs alone.
It says which kind of record, never which records or how many.

### An Invoice like this

- An Invoice becomes a PaidInvoice by this change alone when its balance is at most 0 and its total is not above 0.
  In the spec: 05-zero-total-receipts.velle, line 87, `shape PaidInvoice = Invoice where balance <= 0`
- SendReceipt could then run for it, though nothing happened to the Invoice itself: the change to the spec is what made it a PaidInvoice.
  In the spec: 05-zero-total-receipts.velle, line 87, `shape PaidInvoice = Invoice where balance <= 0`
