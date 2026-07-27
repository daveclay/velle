# Velle Language Reference

Velle is a language intended to describe a system that is agnostic to how the system is executed at runtime.

The goal is that Velle captures discussions with a Product Owner without adding further complexity of how that system runs on a computer. Reading Velle describes the system - its conceptual models and behaviors - without introducing computer science terms and concepts. It abstracts away the stack, functions, variables, scope, closures in favor of shapes, relationships, and rules.

## 1. Principles

**Velle describes, it doesn't execute — and those are two separate phases, not one.** First, make declarative statements about a system's conceptual models: shapes, relationships, rules, behaviors. That description is complete, meaningful, and self-consistent entirely on its own. Velle is then "compiled" to make runtime decisions — storage strategy, caching, mutability, execution order, data structures, traditional computer-science machinery generally. Velle's own scope stops at *can this be declaratively stated*; it never extends to *how should this be executed*. If a design question can only be answered by talking about caching, mutable-vs-immutable storage, or when a computation runs, it has strayed into the second phase and doesn't belong in this document.

**"Compiling" means validating a coherent spec, not primarily building an executable.** The compiler's main job is checking that shapes, relationships, and rules form a strongly-typed, self-coherent system description — producing runnable code is a possible downstream output, not the defining purpose (the same instinct already behind refinement exhaustiveness/overlap checking, `## Refinements`, and totality checking, `## produces`). This gives ambiguity a home: a construct that would resolve one way today and a different way after some unrelated, later change to the spec (a field added to some other shape, say) is a compile error, not a runtime concern or a style nit — compiling *is* the later event that forces a human to notice and be explicit, rather than the moment a silent, schema-dependent resolution quietly starts pointing somewhere else.

Because ambiguity can be introduced by a change *anywhere* in the spec, "compiling" has to mean re-validating the whole spec as one coherent unit every time, not incrementally re-checking just the file or shape that changed — a traditional compiler's file-scoped, incrementally-cached model doesn't fit here, since the whole point is catching effects at a distance (a field added to `Referral` invalidating a `Customer` refinement that never mentions the change). Velle's "compiler" is closer to a global logic/consistency checker running over the whole spec as a single knowledge base — the same category of thing as a theorem prover or constraint solver checking a whole model for coherence — than to a traditional per-file compiler, even though it also happens to be the thing that may eventually emit code.

## 2. Philosophy

Computers execute in terms of stacks, registers, and addresses; humans don't think in those terms when solving problems. Conventional software engineering is the effortful, lossy act of translating a human problem into computer mechanics — functions, variables, scope, closures. Velle removes that translation step: it describes a system directly as shapes, relationships, and rules, and leaves the computer-mechanics translation to compilation.

Interactions are described as shapes with states, not function calls. Instead of a function taking parameters and returning a result or throwing an error, an interaction has an input state and several potential resulting states — success, error, retry, and so on — expressed as ordinary refinements (see `produces` and the errors-are-refinements pattern in `example_invoice_payment.md`).

Velle separates human concerns from computer concerns. Capturing rules, data shapes, interactions, relationships, and conditions is human judgment about system design; "compiling" that judgment into running code is a mechanical, computer concern. Code itself is treated as fungible — a disposable, regenerable artifact of compiling the spec, not the durable source of truth. This was true even before AI (most software engineering is changing existing code to meet business change, not writing greenfield systems), and AI-generated code makes it more true: code changes faster, and the more of it AI writes, the less confidently engineers can say it does what's intended just by reading it.

The gap this is meant to close: existing test frameworks have no opinion about structuring around use cases rather than code (JUnit is code-centric; Cucumber/BDD gets closer but isn't structured enough). Velle aims to be a concise, use-case-oriented system design language humans can use to capture requirements and judgment without first translating them into computer-science concepts — then to extrapolate those requirements into executable tests, modular code, and tooling for organizing and reading AI- or human-generated code.

## 3. shape

A typed record — Velle's only structural noun. Replaces objects, functions, and (per Inputs and Outputs, below) constructors, since all three reduce to "a set of typed properties."

```
shape Customer {
    name: text
    email: text
}
```

## 4. Scalars

Property types seen so far: `text`, `integer`, `decimal`, `boolean`, `Date`, `Money`. A trailing `?` marks a property optional (`processedOn: Date?`); properties are required by default.

## 5. Relationships (`one`, `many`)

```
shape Invoice {
    customer: one Customer
    payments: many Payment
}
```

`one`/`many` declare cardinality directly on a property. The inverse side of a relationship is inferred, not separately declared — e.g. `Customer` does not need its own `invoices: many Invoice` field for `Customer where count(invoices ...)` to work; it's derived from `Invoice.customer`.

## 6. Derived properties

A property can be defined as a computation over other properties instead of stored data:

```
balance: Money = amount - sum(payments, amount)
```

Derived properties are recomputed from current data, not cached/stored — they're the mechanism by which refinement membership can change without any explicit action (see `where`, below).

A derived property's formula may reference the same property one hop away through a relationship (self-reference) — e.g. `root: Foo? = none if parent is none else (parent if parent.root is none else parent.root)`. This needs no special syntax; correctly evaluating it is a compiler obligation (`## Principles`), not a language concern — see Predicate expressions, below.

## 7. Refinements (`where`)

The core idea of the language: a condition is a named subset of a shape, defined by a predicate — not a branch.

```
shape OverdueInvoice = Invoice where balance > 0 and due < today
```

`where` is reused for two purposes with the same meaning: defining a refinement (above), and filtering a collection inline by one (`invoices where OverdueInvoice`, used inside `count(...)`). The compiler goal (not yet exercised in examples) is to check refinement **exhaustiveness and overlap** — proving a set of refinements fully partitions a shape, or flagging when two overlap.

Refinements are **pure predicates, not triggers** — `OverdueInvoice` doesn't "fire" when it becomes true; it's simply evaluable against current data at any time. Only rules (below) react to something actually happening.

A refinement's predicate may reference the shape it refines, directly or through a relationship — self-reference needs no special syntax (see `## Derived properties` and Predicate expressions, below).

### Refinement properties

A refinement can declare properties of its own, in a body after its predicate — data that belongs to the refined state rather than to the base shape:

```
shape ArchivedInvoice = Invoice where exists ArchiveRequest for this {
    captured archivedBy: one User = (ArchiveRequest for this).requestedBy
    captured archivedOn: Date = today
}
```

Asking an `Invoice` for `archivedBy` is nonsensical — only an archived invoice has an archiver. Rather than polluting the base shape with an optional field for every refined state's data (secretly correlated with that state), the property lives on the refinement, where membership itself guarantees its presence. The base shape's declaration stays a clean statement of what *every* instance has; each refinement's body states what membership *adds*.

Refinement properties come in exactly the same two kinds as base-shape properties — there is no third, "assigned" kind:

- **Derived** — plain `= expr`, recomputed live from current data, exactly as in `## Derived properties`. The only novelty is scope: it's evaluable exactly where membership holds (`priceDrift` below is one).
- **Captured** — marked with the leading keyword `captured`: evaluated once at the moment the current membership begins, fixed for the duration of that membership, absent before entry, retracted on exit, re-captured on re-entry. The marker is required because a bare `= expr` in body position is a live derivation — the two kinds must read differently. Capturing `today`/`now` anchors them to the entry moment: `archivedOn` above is the membership's start date, with no implicit system timestamp needed (the same stance `latest`/`first` already take).

**Every captured value traces to data.** There is no ambient execution context — no "current user", no request-scoped magic. `archivedBy` can only reach a `User` through the data graph, which forces the act carrying that data to be reified as a shape (`ArchiveRequest`) before the refinement can capture from it. That's a feature, not a workaround: reified acts are independently required for occurrence identity under re-entry (see `investigate_time.md`), and they are what `why`/provenance will walk. (`(ArchiveRequest for this)` above is legal only while the spec proves at most one can exist per invoice — `## Predicate expressions`' `for`-query rule; the moment re-archival enters the model, the reference must become an ordered selection — see Open/unresolved.)

**Entry-evaluability guardrail.** A captured property's expression must be provably evaluable at the moment membership begins: every reference in it must be guaranteed by the refinement's own predicate, or be unconditionally present on the base shape. `(ArchiveRequest for this)` is legal above precisely because the predicate asserts `exists ArchiveRequest for this` — the predicate narrows the capture expression, the same machinery by which `is some` licenses `.`. A capture reading something its predicate doesn't guarantee is a compile error. A refinement whose captures need nothing beyond the base shape's own data (`captured balanceWhenOverdue: Money = balance` on `OverdueInvoice`) can be entered by drift; one whose predicate requires an act-fact can only be entered by that act occurring — the compiler derives which kind each refinement is from its predicate, the human never declares it.

**Visibility and narrowing.** From the base shape, refinement properties are invisible: `invoice.archivedBy` is a compile error unless `invoice` has been narrowed by `is ArchivedInvoice` earlier in the same conjunction (or the corresponding branch of a conditional) — `is <Refinement>` narrows exactly the way `is some` does. A property whose formula reads properties of *two* refinements lives on their intersection, where both are in scope and provably present:

```
shape Reconciled = Quoted and Delivered {
    priceDrift: Money = billedTotal - quotedTotal
}
```

**Membership is unchanged.** A refinement with properties is still a pure predicate as to *membership* — properties change what a member *has*, never when membership *holds*. Captured properties are per-membership memory, state-layer through and through: they retract on exit. If the business cares about past memberships ("who archived it back in March, before it was unarchived?"), that was never a property — it's history, modeled as occurrence facts plus `latest(... by ...)`. See `investigate_time.md` for the state/effect stratification this rests on.

## 8. Composing refinements (`and`, `or`)

A refinement can be built from other named refinements instead of restating their predicates:

```
shape Open         = SupportTicket where status != "closed"
shape Overdue      = SupportTicket where due < today
shape HighPriority = SupportTicket where priority == "high"

shape UrgentOverdueTicket = Overdue and HighPriority and Open
shape NeedsAttention      = Overdue or Escalated
```

`A and B` / `A or B` desugar to conjunction/disjunction of the operands' own predicates — not new mechanism, `and`/`or` in a new position (between named refinements instead of inside one `where`). They only typecheck when both operands share a base shape, or one refines the other; `Overdue or SuccessfulCharge` is a compile error, since combining unrelated shapes is meaningless. `and` binds tighter than `or`; `not` binds tighter than both (`A and B or C` ≡ `(A and B) or C`).

Composition carries refinement properties (`## Refinements`): `A and B` has the union of its operands' properties — a same-name collision between two *distinct* declarations is a compile error, while the same declaration inherited through a shared base is fine. `A or B` exposes only properties both operands inherit from a shared declaration, since a member may belong to either side alone. A composite may declare its own body (`Reconciled`, above), and a refinement of a refinement inherits its base's properties down the chain.

The intended style: small, deliberately atomic refinements named to read like traits (`Open`, `Overdue`, `HighPriority` — no type suffix), with composites built as pure intersections/unions of trait names rather than restated predicates. Not solved: true cross-shape structural mixins, where a trait like `Overdue` is reusable across unrelated shapes (e.g. both `SupportTicket.due` and `Invoice.due`) — see Open/unresolved.

## 9. Predicate expressions

The expression language usable inside `where`, `requires`, and `visible to ... where`.

**Comparisons:** `==`, `!=`, `<`, `<=`, `>`, `>=`. `==` is the only spelling for value equality — `=` is reserved for shape definition (`shape X = Y where ...`).

**`is`** — one operator, three sanctioned right-hand forms:
- optionality: `assignee is none`, `response is some` — valid on an optional (`?`) field or to-one relationship
- collection emptiness: `corrections is not empty` — valid on a `many` relationship or collection expression
- refinement membership: `alert is UnacknowledgedAlert`, `this is FlaggedCustomer` — valid whenever the left side's shape shares a base with the named refinement; within a conjunction it also *narrows*, licensing access to the refinement's own properties (`## Refinements`), the same way `is some` licenses `.` on an optional

**`exists`** — `exists Receipt for this`, `not exists AccountFlagResolved for this` — tests whether any instance of a shape references the given subject via `for`.

**`for` field ambiguity.** `for <expr>` matches by type: whichever field on the shape has a type matching `<expr>`'s type is the one compared against (or populated, in an effect clause). This breaks the moment a shape has two fields of the same type — e.g. `Referral { referrer: one Customer, referee: one Customer }` makes `exists Referral for this` ambiguous, and there's no reading of "this customer" that says whether it means referrer or referee. `for <expr>` is sugar for the common, unambiguous case; when it doesn't apply — two fields match, or a second condition on the same matched instance is needed — fall back to the general `where`-filtered form `exists` already shares with `count`/`sum`:

```
exists (Referral where referee == this)
exists (Referral where referee == this and referrer is VipCustomer)
```

Reads as an actual sentence and needs nothing beyond `==` and `where`. `for <expr>` never grows a second syntax to handle what it can't — it just stops applying, and the general form was already there to fall back to.

**Aggregates** take a collection expression as their first argument — a relationship filtered by `where` (`invoices where OverdueInvoice`) is already a complete collection expression on its own, not special call syntax:

```
count(<collection-expr>)
sum(<collection-expr>, <field>)
```

**Relationship traversal** is ordinary dot access (`response.outcome`, `ward.admissions`), including through a relationship back to the same shape (self-reference — no special syntax needed).

**Dot access through an optional** requires the left side to be provably non-absent — either its type isn't optional, or it's been narrowed by an `is some`/`is none` check earlier in the same conjunction (`and`) or the corresponding branch of a value-expression conditional. Plain, unnarrowed `.` on an optional is a compile error. `?.` — reusing the existing `?` optionality marker in a new position, not a new symbol — is the explicit escape valve: it short-circuits the rest of the chain to `none` if that link is absent, with no narrowing required:

```
shape Foo {
    parent: Foo?
    root: Foo? = if parent?.root is none then parent else parent?.root
}
```

Both forms stay legal — narrowed `.` when a predicate already needs to branch on presence anyway, `?.` when it doesn't and propagation is all that's wanted.

**`as` — naming an intermediate binding.** `this` is the built-in, always-present alias for the outermost subject a refinement is defined over; a bare unqualified field name means the innermost collection-filter's element. Plain traversal through any number of hops needs neither — `exists (invoices where OverdueInvoice and count(payments where FailedPayment) >= 1)` needs no binding at all, since each hop is evaluated relative to whatever scope is current at that point in the text. `as` only earns its keep when a *deeper* nested scope needs to reach back to a *middle* level's own field — `this` skips past the middle straight to the root, and bare names in the deeper scope mean the deeper level, not the middle one:

```
shape CustomerWithOverpayment =
  Customer where exists (
    invoices as inv where
      count(inv.payments where amount > inv.amount) >= 1
  )
```

`inv.amount`, needed from inside `payments`' own `where`, has nowhere else to come from — `inv` is what keeps the middle level reachable once a deeper scope opens. An alias is visible within the predicate it's declared over and anything nested inside that scope, the same as a SQL correlated subquery — it doesn't leak to the enclosing scope or across to a sibling `as` binding.

**Sibling joins** — correlating two independently-filtered collections against each other (not a straight chain) — take a comma-separated list of bindings instead of one, all visible to each other and to a single shared `where`:

```
shape CustomerWithMatchingIssue = Customer where
    exists (tickets as tix, orders as ord
        where tix is OverdueTicket and ord is ReturnedOrder and ord.product == tix.product)
```

Every binding in the list must be reachable as a relationship from the same enclosing subject (`this`, or the enclosing `as` alias) — a correlated join relative to a shared parent, not an arbitrary cross-product of any two shapes in the system. A single binding (`invoices where OverdueInvoice`, `invoices as inv where ...`) is just the one-binding case of this same rule.

**`for` as a query expression** (e.g. `(NurseVerification for this).nurse`) is legal only when the compiler can prove at most one matching instance exists *and* exactly one field matches by type — because the shape carries a `produces` guard scoped to that same target, or the relationship is to-one from the other side. Otherwise, disambiguate with `latest`/`first` over a `where`-filtered collection instead — the same fallback as the field-ambiguity case above, not a second mechanism:

```
latest(Shape for expr)
first(Shape for expr)
latest(Referral where referrer == this).referee
```

Ordered by an explicit `Date`/`DateTime` property on the collection's element shape — there is no implicit system timestamp. How that property is identified when the shape has more than one (or none) is not yet settled — see Open/unresolved.

**Duration arithmetic**: `today - 7 days`, `now + 14 days` — an integer literal plus a unit (`seconds`/`minutes`/`hours`/`days`/`weeks`), added to or subtracted from a `Date`/`DateTime`.

Grammar, informally:

```
predicate      := disjunction
disjunction    := conjunction ("or" conjunction)*
conjunction    := negation ("and" negation)*
negation       := "not"? atom
atom           := comparison | isExpr | existsExpr | "(" predicate ")"

comparison     := expr ("==" | "!=" | "<" | "<=" | ">" | ">=") expr
isExpr         := expr "is" ("none" | "some" | "empty" | "not empty" | ShapeName)
existsExpr     := "exists" ShapeName "for" expr           -- sugar; legal only when exactly one field matches expr's type
               | "exists" "(" collectionExpr ")"          -- general form; required when more than one field
                                                           -- matches, or more than one condition is needed

expr           := path (("+" | "-") duration)?
path           := pathRoot (accessor Identifier)*
               | aggregateCall
               | selectorCall
               | "(" ShapeName "for" expr ")"   -- sugar; legal only when provably at-most-one AND exactly one
                                                 -- field matches expr's type; else use selectorCall with a
                                                 -- "where"-filtered collectionExpr instead
pathRoot       := "this" | Identifier
accessor       := "." | "?."   -- "." requires the left side provably non-absent (unoptional, or narrowed by a
                                -- prior "is some"/"is none" in the same conjunction); "?." short-circuits to
                                -- none instead, no narrowing required

aggregateCall  := "count" "(" collectionExpr ")"
               | "sum" "(" collectionExpr "," Identifier ")"
selectorCall   := ("latest" | "first") "(" collectionExpr ")"   -- ordered by an explicit Date/DateTime property
                                                                 -- of the element shape
collectionExpr := binding ("," binding)* ("where" predicate)?
binding        := path ("as" Identifier)?

duration       := IntegerLiteral ("seconds"|"minutes"|"hours"|"days"|"weeks")
```

See `example_predicates.md` for the worked derivation of every rule above.

## 10. `rule ... on ...`

A top-level reaction attached to a refinement — replaces `if`/`else` branching and imperative "then do X" sequencing for state-driven behavior.

```
rule SendReceipt on SettledInvoice {
    Receipt for invoice sentOn: now
}
```

A rule declares that an effect corresponds to a refinement — `on Refinement` names *what* the rule reacts to, not *when* or *how* that reaction gets detected. Whether the underlying mechanism is a check made at write-time, a scheduled sweep, an event stream, a runtime data-structure instantiation, or some mix of these for the same rule is left open by the declaration itself; the only contract that has to hold regardless of mechanism is that the effect happens if and only if the subject is or becomes a member of the refinement, exactly once per newly-satisfying instance (see `produces`, below, for how "exactly once" is guaranteed without runtime bookkeeping). Picking and implementing the actual detection mechanism is a compiling concern (`## 1. Principles`), not part of what the rule means — see Schedule triggers, below, for how that stays true even for purely time-dependent refinements.

Prefix `on` (`rule X on Refinement { ... }`) is specifically for data-driven triggers, and means *entering* — the rule reacts to an instance becoming a member. Its mirror, reacting to an instance *leaving* a refinement, is `on leaving` (see Exit triggers, below). Schedule-driven triggers use a different position — postfix, after the rule body — precisely so the two don't read as the same kind of thing even though both mechanically react to a shape existing. See Schedule triggers, below.

## 11. `produces`

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

**Guard granularity matters**: the shape a rule `produces`, and what field that shape's guard is scoped to, determines what "already happened" is scoped to. A `FlagNotification` scoped to `accountFlag: one AccountFlag` (the specific flagging event) behaves differently from one scoped to `customer: one Customer` (the customer in general) — the former allows a fresh notification on a later re-flagging; the latter would silently suppress it. Since this choice can carry business meaning rather than just resolving a mechanical ambiguity, state it explicitly with `for <field>` on `produces` whenever it isn't the obvious one:

```
rule RecordReferral on ReferralRequest produces Referral for referrer from {
    referrer: this.referrer
    referee: this.referee
    referredOn: now
}
```

Omit `for <field>` when only one field's type obviously matches the trigger (the common case, e.g. `produces Receipt` alone); require it when the guard is deliberately keyed on something else, the same field-ambiguity rule as `## Predicate expressions`' `for` section.

## 12. Exit triggers (`on leaving`)

Prefix `on R` reacts to an instance *entering* a refinement — becoming a member. `on leaving R` is its mirror: a reaction to an instance that was a member of R and stopped being one.

```
rule RestoreService on leaving Delinquent produces ServiceRestoration {
    ServiceRestoration from {
        account: this
        restoredOn: now
    }
}
```

**Not expressible as a complement.** Reacting to entering `Compliant` is not the same thing: a newly created, never-delinquent account also "enters" `Compliant` — *became compliant* and *was always compliant* are indistinguishable from current data alone. `leaving` needs no such reconstruction: only a member can leave, so the trigger is inherently transitional. This completes the trigger vocabulary — prefix `on` for entry, `on leaving` for exit, postfix `on` for schedules (below). The `produces` guard applies identically, and guard granularity (`## produces`) decides what a *repeated* exit means, exactly as it does for repeated entries.

**What an exit rule may read.** At the moment the rule fires, the instance is no longer a member of R, and everything membership implied is gone with it. The body may read the instance's current data and durable evidence produced while it was a member; it must not read anything only membership in R could supply — R's own captured properties above all, which retract at the very moment the rule fires — such a read can never be satisfied, and is a compile error. The discipline this enforces: a rule acting on a membership should record what it acted on in its evidence mapping, because evidence is the only thing that survives the exit.

**Mutation policy on evidence.** The sharper use of `on leaving` is answering what happens to evidence when its premise is later falsified — the rule fired, the effect escaped, and then the instance left the refinement. A producing rule declares this as a clause:

```
rule SuspendService on Delinquent produces ServiceSuspension {
    ServiceSuspension from { account: this, suspendedOn: now }
    on leaving Delinquent: compensate ServiceRestoration
}
```

Three policies, each an answer a Product Owner already gives in the wild:

- **`stands`** — the evidence is history and stays true on its own ("the quote is the quote; prices drift"). No reaction.
- **`forbidden`** — while the evidence exists, any change that would cause the exit is rejected ("you can't edit line items on an issued invoice"). Immutability in Velle is exactly this — not a property of a field, but a lien held by an effect that witnessed it, acquired when the evidence is produced and lifted if it's compensated away.
- **`compensate X`** — the exit produces a compensating fact ("invoices are never edited — voided and reissued"). Sugar for a dedicated `on leaving` rule that fires only for instances whose evidence exists and produces `X` scoped to that evidence. Evidence scoping settles the edge cases: a membership too brief for the producing rule to fire has no evidence, so its exit compensates nothing; repeated exits are guarded per compensated evidence, the same granularity rule as `produces` itself.

Deleting evidence is never one of the options — a produced fact records something that happened in the world (the email was sent), and deleting the record makes the description lie. Which policy applies when none is declared — default `stands`, or a compile error that forces the question — is unsettled; see Open / unresolved, and `investigate_time.md` for the full derivation.

## 13. `for`

Associates a newly produced shape instance with the subject it's about:

```
Receipt for invoice sentOn: now
```

`for <expr>` matches by **type**, not name: whichever field on the produced shape has a type matching `<expr>`'s type is the one populated. When a shape has more than one field of that type, name the field explicitly: `Referral for referrer: this.referrer`.

Inside a rule body, `from { field: value, ... }` is the general, clearer way to write this — every field as an ordinary, totality-checked mapping entry, with no field singled out syntactically:

```
Referral from {
    referrer: this.referrer
    referee: this.referee
    referredOn: now
}
```

This is what `produces` was always doing conceptually made visible in the syntax. The guard-scope field (when one needs stating) lives on `produces` itself, not inside the mapping — see `## produces`, above. `for` as a *query* expression (`(NurseVerification for this).nurse`, `exists Shape for expr`) is unaffected by this — see `## Predicate expressions`, below.

## 14. `then`

Explicit, opt-in ordering between two effects that have no data dependency forcing an order:

```
rule InitiateCharge on Order produces ChargeAttempt for order {
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

`then` and `from` don't compete: `then` orders *statements*, `from` is the *form* of one statement. `AuditLogEntry` above isn't the `produces` target, so it carries no guard-scope annotation at all — `from { }` there is just a clearer mapping, nothing guards it. When a rule body is exactly one effect and it *is* the `produces` target, `produces X for field from { mapping }` collapses header and body into one line — shorthand for `produces X for field { X from { mapping } }`, the same kind of collapse `each X produces Y { Y for this ... }` already does for iteration and production.

## 15. `each ... produces ...`

Applies a rule across every member of a refined collection, combined with the `produces` guard per member:

```
rule FlagOverdueAccounts {
    each FlaggedCustomer produces AccountFlag {
        AccountFlag for this flaggedOn: now
    }
} on Daily
```

No separate loop construct — `each` composes the original "for each" iteration idea with `produces`, applied to a filtered set instead of a single shape.

## 16. Schedule triggers (postfix `on`)

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

## 17. Inputs and Outputs

A shape can act as a function by declaring input properties and an `output`:

```
shape ApplyPayment {
    invoice: one Invoice
    payment: one Payment
    output: invoice with payments += payment
}
```

An "object" shape is a degenerate case of a "function" shape whose output is itself. There's no `return`/function-call model — invoking a shape like this produces (or updates) a shape, the same as any rule's effect.

## 18. Open / unresolved

- **Mapping** (shape-to-shape translation, e.g. API DTO → domain shape) — part of the original design goals, not yet exercised in a worked example.
- **Schedule definition** — `on Daily` (postfix) settles how a rule *references* a schedule; what actually defines `Daily` (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be a cron-like scheduling framework.
- **Reversal** — resolved as a non-issue for the language itself (it's a business-policy choice, expressed via which artifact shapes a human declares — see `example_invoice_payment.md` #5), but no single canonical pattern has been adopted yet; the consolidated top-of-file example still doesn't reflect a chosen policy.
- **Exit-trigger loose ends** — whether an undeclared mutation policy on evidence defaults to `stands` or is a compile error; the surface syntax of `compensate`'s desugared form (guarding an exit rule on the evidence's existence, and naming the matched evidence instance inside the compensating mapping); and whether a membership that begins and ends unobserved obligates the *entry* rule's effect at all — a business question the exit design sharpens but doesn't answer. See `investigate_time.md`.
- **Escape hatch / override syntax** — how a human marks part of a spec as intentionally hand-implemented/AI-implemented rather than declarative. Deferred; agreed to be a lesser concern until the core language settles.
- **Compiled guardrails** — the idea that the compiler/transpiler should structurally enforce best practices (e.g. forced prepared statements, automatic error-context capture, correctly evaluating self-referential shape/derived-property definitions, how deep narrowing analysis for `.`-vs-`?.` sees through nested expressions, erroring — not silently resolving — a bare unqualified name that doesn't exist in its innermost scope but would resolve unambiguously in exactly one enclosing scope, per `## Principles`'s compiling-as-validation rule: the fix is always an explicit `this.field`, never an inferred scope-walk that could silently start pointing elsewhere the moment an enclosing shape gains a same-named field; a field addition that creates a new type-match ambiguity for an existing bare `for` reference elsewhere in the spec, per §13, must be reported as one connected diagnostic naming both the declaration that introduced the ambiguity — e.g. `Referral` gaining a second `Customer`-typed field — and every reference it now makes ambiguous — e.g. `CustomerWhoReferred`'s `for this` — since the compiler's job is reporting an incoherence in the spec as a whole, not a syntax error in one isolated line, and a human should never have to search for why an untouched line stopped compiling) as a byproduct of codegen. A design principle, not yet a syntax construct.
- **`latest`/`first` ordering property** — selectors order by an explicit `Date`/`DateTime` property, not an implicit creation timestamp; how that property is identified is undecided. Likely the same pattern as `for` field-ambiguity: bare `latest(...)` legal only when the element shape has exactly one date property, an explicit form (e.g. `latest(payments by receivedOn)`) required otherwise — but no syntax is settled.
- **Exit from act-entered refinements** — a membership predicate of the form `exists ArchiveRequest for this` is monotone: facts persist, so nothing can ever leave `ArchivedInvoice`, and un-archiving is inexpressible. Exit requires either pairing occurrences in the predicate (`... and not exists Unarchival` newer than the matched request — which needs occurrence ordering/scoping vocabulary not yet designed) or a mutable field plus a declared mutation policy (`## Exit triggers`). Both are expressible; which is idiomatic is unsettled, and ties into occurrence reification (`investigate_time.md`).
- **State partition declaration** — refinement properties give states their data (`## Refinements`), reified acts give transitions their payloads, and mutation policies bound which transitions are legal — but nothing yet asserts that a set of refinements *partitions* a shape: mutually exclusive, jointly exhaustive ("an invoice is always in exactly one of Draft, Issued, Paid, Voided"). Candidate spelling `states of Invoice = Draft | Issued | Paid | Voided`, invoking the exhaustiveness/overlap check `## Refinements` already names as a compiler goal. The next investigation.
- **`why` / provenance** — a command to trace which rule/refinement produced a given piece of state, mapped back to Velle source. Agreed as a goal; no syntax proposed yet.
- **Derived-property value-expression grammar** — `## Derived properties`' arithmetic and conditional (`if`/`else`) forms have only ever been used by example, the same gap `## Predicate expressions` closed for boolean predicates. Not yet formalized.
