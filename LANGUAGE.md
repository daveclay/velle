# Velle Language Reference

Draft reference, organized by keyword/construct. Derived from the design exploration in `README.md`, `random_notes.md`, `discussion_hard_problems.md`, and `example_invoice_payment.md` — those remain the record of *why* each construct looks the way it does; this doc is meant to become the settled *what*. Update it as constructs stabilize; open questions are called out explicitly rather than silently omitted.

## Principles

Two rules that filter every design decision in this document, stated up front because they were both gotten wrong once before being pinned down (see `example_predicates.md` §9's correction history).

**Velle describes, it doesn't execute — and those are two separate phases, not one.** First, make declarative statements about a system's conceptual models: shapes, relationships, rules, behaviors. That description is complete and meaningful entirely on its own. Only afterward does a human engineer pick up the description and make runtime decisions from it — storage strategy, caching, mutability, execution order, data structures, traditional computer-science machinery generally. Velle's own scope stops at *can this be declaratively stated*; it never extends to *how should this be executed*. If a design question can only be answered by talking about caching, mutable-vs-immutable storage, or when a computation runs, it has strayed into the second phase and doesn't belong in this document.

**Corollary: truthfulness never depends on whether the underlying implementation is mutable or immutable.** A refinement or derived property means the same thing regardless of how the current state it's evaluated against came to be — recomputed from a mutable field, or derived from an immutable stream of captured facts, the answer must be identical at any given instant. Mutability/immutability is a fact about caching safety (whether a stored value can be trusted without recomputation) — it is never a fact about what's true, and it is never itself a resolution to a language-design question.

## Philosophy

Computers execute in terms of stacks, registers, and addresses; humans don't think in those terms when solving problems. Conventional software engineering is the effortful, lossy act of translating a human problem into computer mechanics — functions, variables, scope, closures. Velle removes that translation step: it describes a system directly as shapes, relationships, and rules, and leaves the computer-mechanics translation to compilation.

Interactions are described as shapes with states, not function calls. Instead of a function taking parameters and returning a result or throwing an error, an interaction has an input state and several potential resulting states — success, error, retry, and so on — expressed as ordinary refinements (see `produces` and the errors-are-refinements pattern in `example_invoice_payment.md`).

Velle separates human concerns from computer concerns. Capturing rules, data shapes, interactions, relationships, and conditions is human judgment about system design; "compiling" that judgment into running code is a mechanical, computer concern. Code itself is treated as fungible — a disposable, regenerable artifact of compiling the spec, not the durable source of truth. This was true even before AI (most software engineering is changing existing code to meet business change, not writing greenfield systems), and AI-generated code makes it more true: code changes faster, and the more of it AI writes, the less confidently engineers can say it does what's intended just by reading it.

The gap this is meant to close: existing test frameworks have no opinion about structuring around use cases rather than code (JUnit is code-centric; Cucumber/BDD gets closer but isn't structured enough). Velle aims to be a concise, use-case-oriented system design language humans can use to capture requirements and judgment without first translating them into computer-science concepts — then to extrapolate those requirements into executable tests, modular code, and tooling for organizing and reading AI- or human-generated code.

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

A derived property's formula may reference the same property one hop away through a relationship (self-reference) — e.g. `root: Foo? = none if parent is none else (parent if parent.root is none else parent.root)`. This needs no special syntax; correctly evaluating it is a compiler obligation (`## Principles`), not a language concern — see Predicate expressions, below.

## Refinements (`where`)

The core idea of the language: a condition is a named subset of a shape, defined by a predicate — not a branch.

```
shape OverdueInvoice = Invoice where balance > 0 and due < today
```

`where` is reused for two purposes with the same meaning: defining a refinement (above), and filtering a collection inline by one (`invoices where OverdueInvoice`, used inside `count(...)`). The compiler goal (not yet exercised in examples) is to check refinement **exhaustiveness and overlap** — proving a set of refinements fully partitions a shape, or flagging when two overlap.

Refinements are **pure predicates, not triggers** — `OverdueInvoice` doesn't "fire" when it becomes true; it's simply evaluable against current data at any time. Only rules (below) react to something actually happening.

A refinement's predicate may reference the shape it refines, directly or through a relationship — self-reference needs no special syntax (see `## Derived properties` and Predicate expressions, below).

## Composing refinements (`and`, `or`)

A refinement can be built from other named refinements instead of restating their predicates:

```
shape Open         = SupportTicket where status != "closed"
shape Overdue      = SupportTicket where due < today
shape HighPriority = SupportTicket where priority == "high"

shape UrgentOverdueTicket = Overdue and HighPriority and Open
shape NeedsAttention      = Overdue or Escalated
```

`A and B` / `A or B` desugar to conjunction/disjunction of the operands' own predicates — not new mechanism, `and`/`or` in a new position (between named refinements instead of inside one `where`). They only typecheck when both operands share a base shape, or one refines the other; `Overdue or SuccessfulCharge` is a compile error, since combining unrelated shapes is meaningless. `and` binds tighter than `or`; `not` binds tighter than both (`A and B or C` ≡ `(A and B) or C`).

The intended style: small, deliberately atomic refinements named to read like traits (`Open`, `Overdue`, `HighPriority` — no type suffix), with composites built as pure intersections/unions of trait names rather than restated predicates. Not solved: true cross-shape structural mixins, where a trait like `Overdue` is reusable across unrelated shapes (e.g. both `SupportTicket.due` and `Invoice.due`) — see Open/unresolved.

## Predicate expressions

The expression language usable inside `where`, `requires`, and `visible to ... where`.

**Comparisons:** `==`, `!=`, `<`, `<=`, `>`, `>=`. `==` is the only spelling for value equality — `=` is reserved for shape definition (`shape X = Y where ...`).

**`is`** — one operator, three sanctioned right-hand forms:
- optionality: `assignee is none`, `response is some` — valid on an optional (`?`) field or to-one relationship
- collection emptiness: `corrections is not empty` — valid on a `many` relationship or collection expression
- refinement membership: `alert is UnacknowledgedAlert`, `this is FlaggedCustomer` — valid whenever the left side's shape shares a base with the named refinement

**`exists`** — `exists Receipt for this`, `not exists AccountFlagResolved for this` — tests whether any instance of a shape references the given subject via `for`.

**Aggregates** take a collection expression as their first argument — a relationship filtered by `where` (`invoices where OverdueInvoice`) is already a complete collection expression on its own, not special call syntax:

```
count(<collection-expr>)
sum(<collection-expr>, <field>)
```

**Relationship traversal** is ordinary dot access (`response.outcome`, `ward.admissions`), including through a relationship back to the same shape (self-reference — no special syntax needed).

**`as` — naming an intermediate binding.** `this` is the built-in, always-present alias for the outermost subject a refinement is defined over; a bare unqualified field name means the innermost collection-filter's element. Between those two, `as` names any other level a predicate needs to reach back to:

```
shape CustomerWithBadInvoice =
  Customer where exists (
    invoices as inv where
      inv is OverdueInvoice
      and count(inv.payments where FailedPayment) >= 1
  )
```

An alias is visible within the predicate it's declared over and anything nested inside that scope, the same as a SQL correlated subquery — it doesn't leak to the enclosing scope or across to a sibling `as` binding. Not covered: binding two independent, unrelated collection paths in the same predicate (a true multi-table join) — no worked example has forced this yet, see Open/unresolved.

**Duration arithmetic**: `today - 7 days`, `now + 14 days` — an integer literal plus a unit (`seconds`/`minutes`/`hours`/`days`/`weeks`), added to or subtracted from a `Date`/`DateTime`.

Grammar, informally:

```
predicate    := disjunction
disjunction  := conjunction ("or" conjunction)*
conjunction  := negation ("and" negation)*
negation     := "not"? atom
atom         := comparison | isExpr | existsExpr | "(" predicate ")"

comparison   := expr ("==" | "!=" | "<" | "<=" | ">" | ">=") expr
isExpr       := expr "is" ("none" | "some" | "empty" | "not empty" | ShapeName)
existsExpr   := "exists" ShapeName "for" expr

expr         := path (("+" | "-") duration)?
path         := ("this" | Identifier) ("." Identifier)*
             | aggregateCall
             | "(" ShapeName "for" expr ")"   -- cardinality unresolved, see Open/unresolved

aggregateCall := "count" "(" collectionExpr ")"
              | "sum" "(" collectionExpr "," Identifier ")"
collectionExpr := path ("as" Identifier)? ("where" predicate)?

duration     := IntegerLiteral ("seconds"|"minutes"|"hours"|"days"|"weeks")
```

See `example_predicates.md` for the worked derivation of every rule above.

## `rule ... on ...`

A top-level reaction attached to a refinement — replaces `if`/`else` branching and imperative "then do X" sequencing for state-driven behavior.

```
rule SendReceipt on SettledInvoice {
    Receipt for invoice sentOn: now
}
```

A rule fires when a shape newly satisfies the refinement named in `on`. There is no polling, clock, or re-evaluation pass in the model — a rule is only reconsidered when data it depends on changes (see `produces` and Schedule triggers, below, for how that's kept true even for purely time-dependent refinements).

Prefix `on` (`rule X on Refinement { ... }`) is specifically for data-driven triggers. Schedule-driven triggers use a different position — postfix, after the rule body — precisely so the two don't read as the same kind of thing even though both mechanically react to a shape existing. See Schedule triggers, below.

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

`for` is also reused as a query expression inside predicates (e.g. `(NurseVerification for this).nurse`) — see Predicate expressions and Open/unresolved for the unresolved cardinality question this raises.

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
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
} on Daily
```

No separate loop construct — `each` composes the original "for each" iteration idea with `produces`, applied to a filtered set instead of a single shape.

## Schedule triggers (postfix `on`)

A rule can be triggered by a named schedule instead of (or in addition to) a refinement, using `on` *after* the rule body rather than before it:

```
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
} on Daily
```

`on` accepts a comma-separated list (`on Daily, Hourly`) for a rule that needs to run on more than one cadence. `Daily` is a placeholder name, not a built-in or sugar for a specific interval — what actually defines a schedule (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be provided by some cron-like scheduling framework. Only the rule-side trigger syntax is settled.

This postfix form is the *only* way a purely time-dependent refinement (like `OverdueInvoice`, which depends on `today`) gets re-checked — nothing in Velle executes purely on the passage of time by default. A scheduled tick is conceptually a shape instance like any other (the same category as a `Payment` arriving or a `ChargeResponse` coming back), but it's referenced by name in `on`, not declared inline as a custom shape the way earlier drafts of this doc did.

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
- **Schedule definition** — `on Daily` (postfix) settles how a rule *references* a schedule; what actually defines `Daily` (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be a cron-like scheduling framework.
- **Reversal** — resolved as a non-issue for the language itself (it's a business-policy choice, expressed via which artifact shapes a human declares — see `example_invoice_payment.md` #5), but no single canonical pattern has been adopted yet; the consolidated top-of-file example still doesn't reflect a chosen policy.
- **Escape hatch / override syntax** — how a human marks part of a spec as intentionally hand-implemented/AI-implemented rather than declarative. Deferred; agreed to be a lesser concern until the core language settles.
- **Compiled guardrails** — the idea that the compiler/transpiler should structurally enforce best practices (e.g. forced prepared statements, automatic error-context capture, correctly evaluating self-referential shape/derived-property definitions) as a byproduct of codegen. A design principle, not yet a syntax construct.
- **`why` / provenance** — a command to trace which rule/refinement produced a given piece of state, mapped back to Velle source. Agreed as a goal; no syntax proposed yet.
- **`for`-as-expression cardinality** — `(NurseVerification for this).nurse`-style expressions assume exactly one matching instance exists; no selection/ordering primitive exists for when more than one could (e.g. a re-verified order, a customer with two historical grace periods).
- **Sibling joins** — binding two independent, unrelated collection paths in the same predicate (a true multi-table join, as opposed to `as`'s single-chain aliasing); no worked example has forced this yet.
