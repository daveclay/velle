# Time & Immutability

Current state of velle (as of 7/25) - describes "facts" of a system using `shape` and `rule`. These definitions are true regardless of _when_ they occur. However, the answer at runtime to these statements could be different depending on _when_ a definition is "executed" given real data.

## Example

```
shape Invoice {
    lineItems: many LineItem
    issued: Date
    currentTotal: sum(lineItems.amount)
    totalWhenIssued: sum(lineItems.amount) "when issued"
}
```

An `Invoice` has two "total" values: a "current" total and a "total" that is the sum of the `amount` of all the `lineItems` at the time the `Invoice` was `issued`.

## Problem

Velle currently doesn't have an explicit way to distinguish these.

## Hints at a Solution

Velle is intended to avoid having to solve "runtime" computer science problems like variables and functions. The language is intended to capture a dialog between a Product Owner and an Engineer.

The difference of `currentTotal` vs `totalWhenIssued` is a Product Owner concern, so it is appropriate to capture using Velle.

`shape` often _looks_ like it can describe immutable ledger-like models that capture the state at a given time. Also, `currentTotal: sum(lineItems.amount)` looks like it describes a "running calculation". 

It is not clear that Velle actually describes the difference between the two - and it's important enough to warrant some way to describe the difference.

## Questions

- Does answering this question rely on some notion of "committing" or "saving" some data at some point in time where values are "captured"?
- How much does that start to bring in concepts of data storage, where we might pollute Velle with runtime execution concerns?