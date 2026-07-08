# Velle Language Reference

Draft reference, organized by keyword/construct. Derived from the design exploration in `README.md`, `random_notes.md`, `discussion_hard_problems.md`, and `example_invoice_payment.md` — those remain the record of *why* each construct looks the way it does; this doc is meant to become the settled *what*. Update it as constructs stabilize; open questions are called out explicitly rather than silently omitted.

## shape

A typed record — Velle's only structural noun. Replaces objects, functions, and (per Inputs and Outputs, below) constructors, since all three reduce to "a set of typed properties."

```
shape Customer {
    name: text
    email: text
}
```

## Scalars

Property types seen so far: `text`, `integer`, `decimal`, `boolean`, `Date`, `Money`. A trailing `?` marks a property optional (`processedOn: Date?`); properties are required by default.

## Relationships (`one`, `many`)

```
shape Invoice {
    customer: one Customer
    payments: many Payment
}
```

`one`/`many` declare cardinality directly on a property. The inverse side of a relationship is inferred, not separately declared — e.g. `Customer` does not need its own `invoices: many Invoice` field for `Customer where count(invoices ...)` to work; it's derived from `Invoice.customer`.

## Derived properties

A property can be defined as a computation over other properties instead of stored data:

```
balance: Money = amount - sum(payments, amount)
```

Derived properties are recomputed from current data, not cached/stored — they're the mechanism by which refinement membership can change without any explicit action (see `where`, below).

## Refinements (`where`)

The core idea of the language: a condition is a named subset of a shape, defined by a predicate — not a branch.

```
shape OverdueInvoice = Invoice where balance > 0 and due < today
```

`where` is reused for two purposes with the same meaning: defining a refinement (above), and filtering a collection inline by one (`invoices where OverdueInvoice`, used inside `count(...)`). The compiler goal (not yet exercised in examples) is to check refinement **exhaustiveness and overlap** — proving a set of refinements fully partitions a shape, or flagging when two overlap.

Refinements are **pure predicates, not triggers** — `OverdueInvoice` doesn't "fire" when it becomes true; it's simply evaluable against current data at any time. Only rules (below) react to something actually happening.

## `rule ... on ...`

A top-level reaction attached to a refinement — replaces `if`/`else` branching and imperative "then do X" sequencing for state-driven behavior.

```
rule SendReceipt on SettledInvoice {
    Receipt for invoice sentOn: now
}
```

A rule fires when a shape newly satisfies the refinement named in `on`. There is no polling, clock, or re-evaluation pass in the model — a rule is only reconsidered when data it depends on changes (see `produces` and `via schedule`, below, for how that's kept true even for purely time-dependent refinements).

## `produces`

Guards a rule against firing more than once for the same input, by tying the rule to a shape that serves as durable evidence it already ran.

```
rule SendReceipt on SettledInvoice produces Receipt {
    Receipt for invoice sentOn: now
}
```

This is sugar — the compiler derives an implicit "hasn't happened yet" guard from it. Spelled out manually, it's an ordinary refinement:

```
shape UnacknowledgedSettledInvoice = SettledInvoice where not exists Receipt for this
```

There is no separate "evidence" or "error" category of shape — an evidence shape is an ordinary shape that happens to also serve as a guard. This is also how errors are handled: an outcome (success or failure) is just a refinement of a result shape, and reacting to failure (`rule ReleaseInventory on FailedCharge produces InventoryRelease`) uses the exact same mechanism as reacting to success — no `return`/`throw` distinction.

**Guard granularity matters**: the shape a rule `produces`, and what that shape's `for` target references, determines what "already happened" is scoped to. A `FlagNotification` referencing `accountFlag: one AccountFlag` (the specific flagging event) behaves differently from one referencing `customer: one Customer` (the customer in general) — the former allows a fresh notification on a later re-flagging; the latter would silently suppress it.

## `for`

Associates a newly produced shape instance with the subject it's about:

```
Receipt for invoice sentOn: now
```

## `then`

Explicit, opt-in ordering between two effects that have no data dependency forcing an order:

```
rule InitiateCharge on Order produces ChargeAttempt {
    AuditLogEntry for order loggedOn: now
    then
    ChargeAttempt for order requestedOn: now
}
```

Effects listed without `then` are unordered — the transpiler/AI-assisted codegen is free to run them in any order or in parallel. Effects joined by `then` are forced into that order. Ordering that *is* implied by data dependency (one effect's input is another's output) never needs `then` — it falls out of the input/output graph for free.

## `each ... produces ...`

Applies a rule across every member of a refined collection, combined with the `produces` guard per member:

```
rule FlagOverdueAccounts on DailyReview {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
}
```

No separate loop construct — `each` composes the original "for each" iteration idea with `produces`, applied to a filtered set instead of a single shape.

## `via schedule every <interval>`

Explicit registration of a timer, analogous to `via API` registering a REST endpoint. The runtime is responsible for creating an instance of the shape on the declared cadence:

```
shape DailyReview via schedule every 1 day {
    ranOn: Date
}
```

This is the *only* way a purely time-dependent refinement (like `OverdueInvoice`, which depends on `today`) gets re-checked — nothing in Velle executes purely on the passage of time by default. A scheduled tick is a shape instance like any other (the same category as a `Payment` arriving or a `ChargeResponse` coming back), so it can be a rule's `on` target like any other trigger.

## Inputs and Outputs

A shape can act as a function by declaring input properties and an `output`:

```
shape ApplyPayment {
    invoice: one Invoice
    payment: one Payment
    output: invoice with payments += payment
}
```

An "object" shape is a degenerate case of a "function" shape whose output is itself. There's no `return`/function-call model — invoking a shape like this produces (or updates) a shape, the same as any rule's effect.

## Open / unresolved

- **Mapping** (shape-to-shape translation, e.g. API DTO → domain shape) — part of the original design goals, not yet exercised in a worked example.
- **Reversal** — if a shape moves *out* of a refinement it previously satisfied (e.g. a refunded payment moves an invoice from `SettledInvoice` back to `PartiallyPaidInvoice`), do rules run symmetrically on the way out, or only on the way in? Unresolved.
- **Escape hatch / override syntax** — how a human marks part of a spec as intentionally hand-implemented/AI-implemented rather than declarative. Deferred; agreed to be a lesser concern until the core language settles.
- **Compiled guardrails** — the idea that the compiler/transpiler should structurally enforce best practices (e.g. forced prepared statements, automatic error-context capture) as a byproduct of codegen. A design principle, not yet a syntax construct.
- **`why` / provenance** — a command to trace which rule/refinement produced a given piece of state, mapped back to Velle source. Agreed as a goal; no syntax proposed yet.
