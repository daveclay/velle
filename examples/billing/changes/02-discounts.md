# What changed from 01-tidy.velle to 02-discounts.velle

This report compares what two versions of a spec say. It describes a system in general,
never the records of a running one. Each line points at the place in the spec that says it.
Where the report has no words for a change yet, it shows the Velle instead and says so.

## What was edited

- A LineItem is no longer refused when its price is below 0.
  In the spec: 01-tidy.velle, line 58, `never (LineItem where price < 0)`

## What that changes

What the compiler derives from the edits for declarations nobody edited. Each is something
that can now happen or can no longer happen; none says what will happen.

### Invoice

- A new LineItem can now make an Invoice stop being an OverdueInvoice.
  In the spec: 01-tidy.velle, line 58, `never (LineItem where price < 0)`

### The spec as a whole

- The compiler no longer writes 2 of the example tests it writes from the spec. They checked that a LineItem whose price is below 0.00 is refused, and that a LineItem whose price is 0.00 is accepted.
  In the spec: 01-tidy.velle, line 58, `never (LineItem where price < 0)`
