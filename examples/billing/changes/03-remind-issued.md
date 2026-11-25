# What changed from 02-discounts.velle to 03-remind-issued.velle

This report compares what two versions of a spec say. It describes a system in general,
never the records of a running one. Each line points at the place in the spec that says it.
Where the report has no words for a change yet, it shows the Velle instead and says so.

## What was edited

- An Invoice is now an ActionableOverdue when it is an OverdueInvoice and it is an IssuedInvoice and it is not an ArchivedInvoice. Before: when it is an OverdueInvoice and it is not an ArchivedInvoice.
  In the spec: 03-remind-issued.velle, line 121, `shape ActionableOverdue = OverdueInvoice and IssuedInvoice and not ArchivedInvoice`
- RemindOverdue changed. Now: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is after 14 days before today, it creates a Reminder with invoice set to it, sentOn set to today. Before: at each Weekly tick, for every Invoice of which it is true that it is an ActionableOverdue and there is no Reminder whose invoice is it and whose sentOn is after 7 days before today, it creates a Reminder with invoice set to it, sentOn set to today.
  In the spec: 03-remind-issued.velle, line 128, `rule RemindOverdue`

## What that changes

What the compiler derives from the edits for declarations nobody edited. Each is something
that can now happen or can no longer happen; none says what will happen.

### Issuance

- A new Issuance can now cause RemindOverdue to run, later rather than in the same commit.
  In the spec: 03-remind-issued.velle, line 128, `rule RemindOverdue`

## What happens to a record like this

What the edits do to a record of the kind the system keeps, worked out from the two specs alone.
It says which kind of record, never which records or how many.

### An Invoice like this

- An Invoice stops being an ActionableOverdue by this change alone when it is an OverdueInvoice, it is not an ArchivedInvoice, and it is not an IssuedInvoice.
  In the spec: 03-remind-issued.velle, line 121, `shape ActionableOverdue = OverdueInvoice and IssuedInvoice and not ArchivedInvoice`
- At the next Weekly tick, RemindOverdue no longer runs for it.
  In the spec: 03-remind-issued.velle, line 121, `shape ActionableOverdue = OverdueInvoice and IssuedInvoice and not ArchivedInvoice`
