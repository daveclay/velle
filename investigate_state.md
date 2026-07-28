# State Changes

Velle needs a way for product owners to describe how state changes occur in the system.

## Philosophy

Any system can be considered a black box with its persistent store as a single state. From Velle's perspective, mutations are committed to change this state, and the truth of the system must be consistent.

Sometimes a system uses a ledger design to store mutations over history, considering the "latest" to be the current value. Other times, the system may allow a single field to be mutated directly. Velle must be able to handle both cases.

React is a similar analogy/approach with mutations committing to a single state tree. What I'm taking from that is the idea of a mutation being an arbitrary shape, and thinking of "commit" as an agnostic term for all underlying CRUD operations. The "state" is agnostic to the storage mechanism.

## Example


### Mutation in place
```
shape Customer {
    email: text
}

shape CorrectEmail { customer: one Customer, corrected: text }
```

How could velle connect the `corrected` field to the `Customer` `email`? 

### Mutation with ledger design

In a ledger design, nothing is ever edited. Each change is a new record — an ordinary shape instance — and the "current value" is a derived property that selects the latest one:

```
shape Customer {
    signupEmail: text
    email: text = if exists EmailCorrection for this
                  then latest(EmailCorrection for this).corrected
                  else signupEmail
}

shape EmailCorrection {
    customer: one Customer
    corrected: text
    correctedOn: Date
}
```

Nothing here is new mechanism: `EmailCorrection` is a reified act like any other shape, `latest(...)` orders by `correctedOn` (the shape's only `Date` property), and the `exists` check narrows the then-branch so `.corrected` is provably evaluable — the collection can't be empty there.

Notably, the ledger case dissolves the question the in-place case leaves open. There is no "connect `corrected` to `email`" statement to invent, because the connection *is* the derived property — declared on `Customer` itself, readable in place. Commit means insert, the ledger is append-only, and the stored truth (`signupEmail`, plus the correction history) is never touched. The reconciliation gap only exists when a committed shape must reach out and change some other shape's stored field.