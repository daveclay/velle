# 15. `then`

Explicit, opt-in ordering between two effects that have no data dependency forcing an order:

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

Effects listed without `then` are unordered — the transpiler/AI-assisted codegen is free to run them in any order or in parallel. Effects joined by `then` are forced into that order. Ordering that *is* implied by data dependency (one effect's input is another's output) never needs `then` — it falls out of the input/output graph for free.

`then` and `from` don't compete: `then` orders *statements*, `from` is the *form* of one statement. There is no one-effect header collapse — a rule whose body is a single effect is simply a one-statement body (`## Assignment (mutation in place)`'s no-sugar stance extends here). A rule's body is exactly one commit, so `then` orders effects *within* that commit — an ordering commitment for compilation, never an observable intermediate state (`## rule`, "Transactions and `after commit`").
