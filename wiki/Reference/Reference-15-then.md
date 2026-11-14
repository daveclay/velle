# 15. `then`

A rule body may hold several statements. Velle runs them in any order unless one depends on another's result. `then` forces an order between two statements when the data does not. This page teaches when `then` is needed.

"Write the audit entry before attempting the charge":

```
rule InitiateCharge when Order {
    AuditLogEntry from {
        order: this
        loggedOn: now
    }
    then
    ChargeAttempt from {
        order: this
        requestedOn: now
    }
}
```

Neither statement reads what the other produces, so without `then` either could run first. With `then`, the audit entry is always written first.

When one statement reads another's result, the order is already fixed and `then` is not needed.

A rule body is one commit ([§11](Reference-11-rule-when-on)). `then` orders statements inside it. Nothing outside the rule can see a state between the two statements.

`then` sits between two statements; `from` is the form of one creation statement ([§14](Reference-14-for)). A one-statement body has no `then`. A `for each` block ([§14](Reference-14-for)) is one statement to the `then` around it. Inside the block, `then` orders the block's statements each time it runs.

Rules using `then`: `examples/membership/membership.velle`, `examples/pharmacy/pharmacy.velle`.

## Related pages

- [§11 rule](Reference-11-rule-when-on), for what one commit means.
