# What changed from 00-billing.velle to 01-tidy.velle

This report compares what two versions of a spec say. It describes a system in general,
never the records of a running one. Each line points at the place in the spec that says it.
Where the report has no words for a change yet, it shows the Velle instead and says so.

## What was edited

- LineItem is written differently and means the same, if `velle.finance` is right that the sides may be swapped for `decimal * Money`.
  In the spec: 01-tidy.velle, line 46, `act LineItem`
