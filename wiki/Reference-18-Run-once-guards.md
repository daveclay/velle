# 18. Run-once guards

Commit-triggered rules already fire exactly once per commit (`## rule`), so an ordinary rule needs no guard — inside a transaction there is no gap for a firing to be lost in, so a guard on a plain rule is dead machinery, and the compiler says so. A guard earns its place in exactly two situations: **durability across a transaction boundary** — an `after commit` rule's firing must survive the gap between transactions (`## rule`, "Transactions and `after commit`") — and **cross-tick memory** — a tick rule needs "already handled" as data (`## Schedule triggers`). The canonical form — and the only form; there is no guard sugar (below) — is a named refinement whose predicate the rule's own body falsifies:

```
shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

`after commit` fires immediately, in its own transaction; `on Hourly` is the reconciliation backstop that heals anything the boundary gap dropped — safe to add precisely because the guard makes re-evaluation harmless. One line reads: *immediately, and self-healing hourly*. Boundary and apparatus are checked against each other both ways: declaring `after commit` without the guard-and-backstop apparatus is the stranding error, and this apparatus without a boundary is the dead-machinery diagnostic ("did you mean `after commit`?").

**The guard is a data invariant, and it can be nothing else.** Run-once protection requires durable memory that a firing happened, and the only durable memory in Velle is data. A runtime keeping hidden fired-flags somewhere no shape describes would be untraceable state, forbidden everywhere else — so the witness must be data: a produced shape, or a stored field. There is no separate "evidence" or "error" category of shape — an evidence shape is an ordinary shape that happens to also serve as a guard witness, and reacting to failure (`when FailedCharge`) uses the exact same mechanism as reacting to success — no `return`/`throw` distinction.

**The disarm law.** The connection between gate and witness is a compiler obligation, not a keyword: the body must provably falsify the trigger's own predicate — *a guarded rule's trigger must be a state its own effects provably exit*. A rule on `UnappliedDeposit` that forgets the `DepositApplication` line fails to compile ("this rule never leaves its trigger state") — the double-deposit bug caught as structural incoherence, not a runtime surprise.

**Two witness kinds, one analysis.** Producing evidence falsifies an `exists` predicate; a flag assignment falsifies a flag predicate:

```
shape Deposit {
    account: one Account
    amount: Money
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    this.applied = true
}
```

Both are dischargeable trigger states; the same disarm proof covers both, and the one-writer check covers the flag. Witness grain is the author's choice, history opt-in at every step: a boolean (*that* it happened), `appliedOn: Date?` (*when* — guard on `appliedOn is none`, disarm with `this.appliedOn = now`), or a full evidence shape (payload, provenance).

**Granularity is predicate content, not annotation.** Once-per-deposit is `not exists DepositApplication for this`; at-most-once-per-customer-ever is `not exists (FlagNotification where customer == this.customer)`. The guard's unit is whatever its predicate says — and it must be something the data contains.

## No guard sugar

The canonical form is the spelling — no sugar layer sits over it. Every guard is a triple *(witness shape, correlation predicate, optional temporal scope)*, and only the witness is keyword-nameable; the connection between condition and witness has a different shape in every realistic use case:

```
-- identity: the witness correlates 1:1 with the trigger instance
Deposit where not exists DepositApplication for this

-- self: guard state lives on the trigger instance; no join at all
Deposit where not applied

-- chosen key: "once per customer" — the granularity is a product decision
AccountFlag where not exists (FlagNotification where customer == this.customer)

-- chosen key + time window: a rate limit; the guard expires
OverdueInvoice where
    not exists (Reminder where invoice == this and sentOn > today - 3 days)

-- paired apparatus: an episode's correlation is maintained by entry/exit rules
-- ("Episodes as data", ## State-change patterns)
```

The correlation predicate is not mechanism — it *is* the business rule: "once per customer" vs. "once per flag" is a product decision, and the refinement is already its clearest spelling. Sugar would either grow a clause for the join (at which point it is barely shorter than the refinement) or assume one (hiding the decision). With no common desugaring, candidate phrases (`until X`, `once per customer by X`, `at most every 3 days`, an `episode of` generator) are not one construct but a pile of special cases, each hiding a decision that should stay visible. Even the purely mechanical residue — the 1:1 `not exists W for this` idiom — stays unsweetened: the hand-written refinement names a trigger state worth naming, and connecting witness to trigger is the disarm proof's job, checked rather than spelled.

Guard soundness is structural, not assumed: a mutation and its witness are statements of one body, and a body is exactly one commit — never partially applied (`## rule`, "Transactions and `after commit`").
