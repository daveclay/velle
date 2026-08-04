# Velle Language Reference

Velle is a language intended to describe a system that is agnostic to how the system is executed at runtime.

The goal is that Velle captures discussions with a Product Owner without adding further complexity of how that system runs on a computer. Reading Velle describes the system - its conceptual models and behaviors - without introducing computer science terms and concepts. It abstracts away the stack, functions, variables, scope, closures in favor of shapes, relationships, and rules.

## 1. Principles

**Velle describes, it doesn't execute — and those are two separate phases, not one.** First, make declarative statements about a system's conceptual models: shapes, relationships, rules, behaviors. That description is complete, meaningful, and self-consistent entirely on its own. Velle is then "compiled" to make runtime decisions — storage strategy, caching, mutability, execution order, data structures, traditional computer-science machinery generally. Velle's own scope stops at *can this be declaratively stated*; it never extends to *how should this be executed*. If a design question can only be answered by talking about caching, mutable-vs-immutable storage, or when a computation runs, it has strayed into the second phase and doesn't belong in this document.

**"Compiling" means validating a coherent spec, not primarily building an executable.** The compiler's main job is checking that shapes, relationships, and rules form a strongly-typed, self-coherent system description — producing runnable code is a possible downstream output, not the defining purpose (the same instinct already behind refinement exhaustiveness/overlap checking, `## Refinements`, mapping totality checking, `## for`, and the one-writer and disarm proofs, `## Assignment (mutation in place)` and `## Run-once guards`). This gives ambiguity a home: a construct that would resolve one way today and a different way after some unrelated, later change to the spec (a field added to some other shape, say) is a compile error, not a runtime concern or a style nit — compiling *is* the later event that forces a human to notice and be explicit, rather than the moment a silent, schema-dependent resolution quietly starts pointing somewhere else.

Because ambiguity can be introduced by a change *anywhere* in the spec, "compiling" has to mean re-validating the whole spec as one coherent unit every time, not incrementally re-checking just the file or shape that changed — a traditional compiler's file-scoped, incrementally-cached model doesn't fit here, since the whole point is catching effects at a distance (a field added to `Referral` invalidating a `Customer` refinement that never mentions the change). Velle's "compiler" is closer to a global logic/consistency checker running over the whole spec as a single knowledge base — the same category of thing as a theorem prover or constraint solver checking a whole model for coherence — than to a traditional per-file compiler, even though it also happens to be the thing that may eventually emit code.

## 2. Philosophy

Computers execute in terms of stacks, registers, and addresses; humans don't think in those terms when solving problems. Conventional software engineering is the effortful, lossy act of translating a human problem into computer mechanics — functions, variables, scope, closures. Velle removes that translation step: it describes a system directly as shapes, relationships, and rules, and leaves the computer-mechanics translation to compilation.

Interactions are described as shapes with states, not function calls. Instead of a function taking parameters and returning a result or throwing an error, an interaction has an input state and several potential resulting states — success, error, retry, and so on — expressed as ordinary refinements (see the errors-are-refinements pattern in `example_invoice_payment.md`).

**Flexible, not restrictive.** Velle does not force one design pattern over another — ledger vs. in-place mutation, evidence shape vs. guard flag, classification vs. stored state are the author's calls, made per use case. Velle's job is to provide the validation tools that prove the system does what the author intended, whichever pattern they chose.

Velle separates human concerns from computer concerns. Capturing rules, data shapes, interactions, relationships, and conditions is human judgment about system design; "compiling" that judgment into running code is a mechanical, computer concern. Code itself is treated as fungible — a disposable, regenerable artifact of compiling the spec, not the durable source of truth. This was true even before AI (most software engineering is changing existing code to meet business change, not writing greenfield systems), and AI-generated code makes it more true: code changes faster, and the more of it AI writes, the less confidently engineers can say it does what's intended just by reading it.

The gap this is meant to close: existing test frameworks have no opinion about structuring around use cases rather than code (JUnit is code-centric; Cucumber/BDD gets closer but isn't structured enough). Velle aims to be a concise, use-case-oriented system design language humans can use to capture requirements and judgment without first translating them into computer-science concepts — then to extrapolate those requirements into executable tests, modular code, and tooling for organizing and reading AI- or human-generated code.

## 3. shape

A typed record — Velle's only structural noun. Replaces objects, functions, and constructors, since all three reduce to "a set of typed properties" — an interaction is a shape whose consequences are rules (`## rule`), not a function call.

```
shape Customer {
    name: text
    email: text
}
```

## 4. State and commits

Velle abstracts away the database. The system's **state** is a black box: the set of all shape instances and their stored properties. How that state is physically kept — tables, documents, caches, indexes — is a compiling concern (`## 1. Principles`); no CRUD vocabulary, storage API, or save/load call exists in the language.

The one way state ever changes is a **commit**: one mutation entering the state at a discrete moment — an external act submitting a new shape instance (committing an instance *is* persisting the record — `## Assignment (mutation in place)`, "No act-level sugar"), a scheduled tick, which is a commit whose changed datum is `today` (`## Schedule triggers`), or a rule firing's effects (`## rule`). A rule's body is exactly one commit, and a firing's effects are a *new* commit that may match further rules' conditions just as an act's commit would — rules react to conditions holding at commits, never to other rules; there is no call graph. A system never does anything on its own; every change to state traces to some commit.

The CRUD replacement reads off directly: "create" is committing a new instance, "update" is an assignment fired by a rule (`## Assignment (mutation in place)`), "read" is any predicate or derivation evaluated against current state, and "delete" has no primitive at all — a produced fact records something that happened in the world, and deleting the record would make the description lie (`## Exit triggers`); where the business needs reversal, that's a policy expressed as data, not an erasure (`example_invoice_payment.md` #5).

Because a commit is a discrete moment, pre-state and post-state are both well-defined at it — which is what lets entry into and exit from a refinement mean something precise, with no hidden bookkeeping (`## rule ... when ... on ...`). By default, an act's commit and every commit its consequences produce stand or fall together — one all-or-nothing envelope that a rule opts out of with `after commit` (`## rule`, "Transactions and `after commit`"). An act's commit carries exactly **one instance**: "several things arrive together" is not a special commit kind but an ordinary modeling call — a container shape whose single instance is the committed act, with the parts as related shapes. That is what lets "can these triggers coincide?" be answered from trigger shapes alone (`## Assignment (mutation in place)`, one-writer). Still open: the order-independence and quiescence proofs (OQ16) — `open_questions.md`.

## 5. Scalars

Property types seen so far: `text`, `integer`, `decimal`, `boolean`, `Date`, `Money`. A trailing `?` marks a property optional (`processedOn: Date?`); properties are required by default.

`initially` gives a stored property a starting value: `applied: boolean initially false`. The field stays stored and assignable (`## Assignment (mutation in place)`); the initializer is evaluated once, at the instance's creation commit — so `submittedOn: Date initially now` records commit time as model data. This is a third property kind, distinct from derivation: `applied: boolean = false` in shape-body position would mean *derived, always false* — unassignable (`## Derived properties`).

`initially` also accepts a **generator**: `processorKey: text initially randomUUID` mints a unique value at the creation commit — how a system-originated act gets an outward-facing key (`## rule`, "Transactions and `after commit`"). Like `now`, a generator is evaluated once, at the creation commit, and a rolled-back-and-retried transaction re-evaluates it. Which generators exist beyond `randomUUID` is open (`## Open / unresolved`); sequence-flavored generators are excluded deliberately — ordering comes from timestamps or declared fields, never minted values.

`timestamp` declares a property whose value is **commit metadata** — populated by the commit itself, never by a committer or a rule — with the persistence behavior part of the declaration:

```
shape Review {
    product: one Product
    stars: integer
    body: text
    createdAt: timestamp on create
    updatedAt: timestamp on update
}
```

`on create` fixes the value at the instance's creation commit; `on update` advances it at every commit that writes a stored field of the instance (creation included, so the field is never absent). The names are the author's — `correctedOn: timestamp on create` is fine. The values are never committer-suppliable (a fact about the commit can't be claimed by the client) and never assignable (`this.updatedAt = now` is a compile error) — which is the point: `updatedAt` maintained by hand would mean every writing rule also assigns it, boilerplate that lies the first time a rule forgets. Contrast `initially now`: also evaluated at the creation commit, but it declares ordinary *stored business data*, assignable ever after (a submission date an admin may later correct); `timestamp on create` declares unassignable bookkeeping fact. Choose by whether the moment is a business datum or a fact about the commit. A shape's declared creation timestamp is also what bare `latest(...)`/`first(...)` order by (`## Predicate expressions`).

Every instance carries **`id`** — identity as a readable value, present with no declaration. It is unique per instance, fixed at creation, never assignable, and supports `==` and nothing else: not ordered, not arithmetic, not parseable text, so a spec can never depend on representation — whether `id` is a UUID, an auto-increment, or an existing table's `some_unique_id` primary key supplied at an integration boundary is compilation's business (`## 1. Principles`). `id` is a reserved name; a domain whose business vocabulary includes an "id" (an externally meaningful account number) declares its own field for it — business identifiers are data, `id` is identity. Instance comparison was always identity comparison (`## Predicate expressions`); `id` is its readable form: `a == b` is `a.id == b.id`. One deliberate asymmetry with `timestamp` fields: those are declared surface with language-populated values, while `id` appears in no declaration — identity is the one fact every record carries whether or not the author writes it down.

## 6. Relationships (`one`, `many`)

```
shape Invoice {
    customer: one Customer
    payments: many Payment
}
```

`one`/`many` declare cardinality directly on a property. The inverse side of a relationship is inferred, not separately declared — e.g. `Customer` does not need its own `invoices: many Invoice` field for `Customer where count(invoices ...)` to work; it's derived from `Invoice.customer`.

## 7. Derived properties

A property can be defined as a computation over other properties instead of stored data:

```
balance: Money = amount - sum(payments, amount)
```

Derived properties are recomputed from current data, not cached/stored — they're the mechanism by which refinement membership can change without any explicit action (see `where`, below).

A derived property's formula may reference the same property one hop away through a relationship (self-reference) — e.g. `root: Foo? = none if parent is none else (parent if parent.root is none else parent.root)`. This needs no special syntax; correctly evaluating it is a compiler obligation (`## Principles`), not a language concern — see Predicate expressions, below.

## 8. Refinements (`where`)

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
- **Captured** — marked with the leading keyword `captured`: evaluated once at the moment the current membership begins, fixed for the duration of that membership, absent before entry, retracted on exit, re-captured on re-entry. The marker is required because a bare `= expr` in body position is a live derivation — the two kinds must read differently. Capturing `today`/`now` anchors them to the entry moment: `archivedOn` above is the membership's start date, recorded as model data.

**Every captured value traces to data.** There is no ambient execution context — no "current user", no request-scoped magic. `archivedBy` can only reach a `User` through the data graph, which forces the act carrying that data to be reified as a shape (`ArchiveRequest`) before the refinement can capture from it. That's a feature, not a workaround: reified acts are independently required for occurrence identity under re-entry, and they are what `why`/provenance will walk. (`(ArchiveRequest for this)` above is legal only while the spec proves at most one can exist per invoice — `## Predicate expressions`' `for`-query rule; the moment re-archival enters the model, the reference must become an ordered selection — see Open/unresolved.)

**Entry-evaluability guardrail.** A captured property's expression must be provably evaluable at the moment membership begins: every reference in it must be guaranteed by the refinement's own predicate, or be unconditionally present on the base shape. `(ArchiveRequest for this)` is legal above precisely because the predicate asserts `exists ArchiveRequest for this` — the predicate narrows the capture expression, the same machinery by which `is some` licenses `.`. A capture reading something its predicate doesn't guarantee is a compile error. A refinement whose captures need nothing beyond the base shape's own data (`captured balanceWhenOverdue: Money = balance` on `OverdueInvoice`) can be entered by drift; one whose predicate requires an act-fact can only be entered by that act occurring — the compiler derives which kind each refinement is from its predicate, the human never declares it.

**Visibility and narrowing.** From the base shape, refinement properties are invisible: `invoice.archivedBy` is a compile error unless `invoice` has been narrowed by `is ArchivedInvoice` earlier in the same conjunction (or the corresponding branch of a conditional) — `is <Refinement>` narrows exactly the way `is some` does. A property whose formula reads properties of *two* refinements lives on their intersection, where both are in scope and provably present:

```
shape Reconciled = Quoted and Delivered {
    priceDrift: Money = billedTotal - quotedTotal
}
```

**Membership is unchanged.** A refinement with properties is still a pure predicate as to *membership* — properties change what a member *has*, never when membership *holds*. Captured properties are per-membership memory, state-layer through and through: they retract on exit. If the business cares about past memberships ("who archived it back in March, before it was unarchived?"), that was never a property — it's history, modeled as occurrence facts plus `latest(... by ...)`.

### Frozen fields (`frozen`)

A refinement body may constrain as well as extend: a `frozen` clause names stored fields of the base shape that may not be written while membership holds — immutability scoped to a state:

```
shape IssuedInvoice = Invoice where exists Issuance for this {
    frozen lineItems, billingAddress
}
```

Immutability in Velle is state-scoped, never field-scoped: the same field is freely editable on a draft and frozen once issued, so a declaration-site marker (`final`, `readonly`) is the wrong granularity — the business sentence is about the *state*, and the refinement is the state's name. `frozen` is a **conditional write permission**, a new kind of statement in the same family as one-writer (`## Assignment (mutation in place)`): one-writer says who may write a field; `frozen` says when writing it is legal at all. A write to a frozen field is illegal at any commit where the instance is a member in the commit's **pre-state** — so the entering commit may still write (the instance is a member only in post-state: a rule reacting to the `Issuance` can normalize a field in the same commit that freezes it), and a non-monotone predicate thaws the freeze the moment membership ends. Bare `frozen` (no list) freezes every stored field of the base shape, auto-extending to fields added later (the same virtue as derived trigger sets — `## rule`); the listed form narrows deliberately. Only stored fields are eligible: derived properties were never assignable, and captured properties are fixed by definition. Composition (`## Composing refinements`) needs no extra rule — each refinement's freeze applies wherever its own membership holds.

**The check is static, whole-spec.** Every write to stored state is a rule assignment to a literal static path (`## Assignment (mutation in place)`), so the compiler knows every writer of every field; the freeze check is the one-writer disjointness question re-aimed — *can this writer's trigger coincide with membership in the freezing refinement?* — fail-closed, reported as one connected diagnostic naming writer and freeze. The fix is the rejection-as-data idiom: partition the act (`ApplicableEdit = LineItemEdit where not invoice is IssuedInvoice` / `RefusedEdit = ...`), hang the writing rule off the applicable subset — now provably disjoint — and let the refusal land as a fact the caller reads back (`open_questions.md`, "Validation rejection is data"). No commit is ever unwound: immutability needs no rejection machinery, only proofs and partitions. A freeze no rule could ever violate is advisory dead machinery ("serves no writer" — the dead-tolerance shape, `## Self-referential folds and tolerates`).

**Freeze depth is declared, not inferred.** "Can't edit line items" usually means the `LineItem` instances too; that's a conditioned refinement on the related shape, not transitive magic:

```
shape LockedLineItem = LineItem where invoice is IssuedInvoice {
    frozen price, quantity
}
```

Each hop of the freeze is its own visible business decision (issuing freezes the line item's price, not the product's name) — the same stance as "No guard sugar" (`## Run-once guards`).

**An evidence-held lien is this construct with the evidence in the predicate.** "The receipt freezes the invoice" is a freeze on an evidence-entered refinement, and lifting the lien is a non-monotone predicate — no lien vocabulary needed:

```
shape ReceiptedInvoice = Invoice where
    exists Receipt for this and not exists ReceiptVoid for this {
    frozen lineItems
}
```

Frozen while a receipt stands unvoided; thawed the moment the void lands. Provenance reads off the predicate (`why`: "frozen because a receipt stands, unvoided").

## 9. Composing refinements (`and`, `or`)

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

## 10. Predicate expressions

The expression language usable inside `where`, `requires`, and `visible to ... where`.

**Comparisons:** `==`, `!=`, `<`, `<=`, `>`, `>=`. `==` is the only spelling for value equality — `=` is reserved for shape definition (`shape X = Y where ...`). On shape instances, `==` compares identity — `a == b` is `a.id == b.id` (`## Scalars`) — never field-by-field structure.

**`is`** — one operator, three sanctioned right-hand forms:
- optionality: `assignee is none`, `response is some` — valid on an optional (`?`) field or to-one relationship
- collection emptiness: `corrections is not empty` — valid on a `many` relationship or collection expression
- refinement membership: `alert is UnacknowledgedAlert`, `this is FlaggedCustomer` — valid whenever the left side's shape shares a base with the named refinement; within a conjunction it also *narrows*, licensing access to the refinement's own properties (`## Refinements`), the same way `is some` licenses `.` on an optional

**`exists`** — `exists Receipt for this`, `not exists AccountFlagResolved for this` — tests whether any instance of a shape references the given subject via `for`.

**Bare boolean atoms** — a boolean-typed path is a complete predicate atom on its own: `where applied` and `where not applied` are grammatical, equivalent to `applied == true` / `applied == false` — so a flag refinement reads as the business phrase (`Deposit where not applied`).

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

**`for` as a query expression** (e.g. `(NurseVerification for this).nurse`) is legal only when the compiler can prove at most one matching instance exists *and* exactly one field matches by type — because a guard refinement elsewhere in the spec proves at most one can exist (the whole-spec singularity proof — `## Run-once guards` and "Episodes as data" in `## State-change patterns`), or the relationship is to-one from the other side. Otherwise, disambiguate with `latest`/`first` over a `where`-filtered collection instead — the same fallback as the field-ambiguity case above, not a second mechanism:

```
latest(Shape for expr)
first(Shape for expr)
latest(Referral where referrer == this).referee
```

Ordered by the element shape's declared creation timestamp (`timestamp on create`, `## Scalars`) when it declares exactly one — or by an author-named `Date`/`DateTime` property when the business ordering isn't creation order (backdated corrections, effective-dating) or the shape declares no creation timestamp. The syntax for naming the property is not yet settled (`latest(payments by receivedOn)` is the working sketch — see Open/unresolved).

**Duration arithmetic**: `today - 7 days`, `now + 14 days` — an integer literal plus a unit (`seconds`/`minutes`/`hours`/`days`/`weeks`), added to or subtracted from a `Date`/`DateTime`.

Grammar, informally:

```
predicate      := disjunction
disjunction    := conjunction ("or" conjunction)*
conjunction    := negation ("and" negation)*
negation       := "not"? atom
atom           := comparison | isExpr | existsExpr | booleanAtom | "(" predicate ")"

comparison     := expr ("==" | "!=" | "<" | "<=" | ">" | ">=") expr
isExpr         := expr "is" ("none" | "some" | "empty" | "not empty" | ShapeName)
existsExpr     := "exists" ShapeName "for" expr           -- sugar; legal only when exactly one field matches expr's type
               | "exists" "(" collectionExpr ")"          -- general form; required when more than one field
                                                           -- matches, or more than one condition is needed
booleanAtom    := path                                     -- legal when the path is boolean-typed:
                                                           -- "applied" ≡ applied == true

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
selectorCall   := ("latest" | "first") "(" collectionExpr ")"   -- ordered by the element shape's sole "timestamp on create"
                                                                 -- property; an author-named Date/DateTime property
                                                                 -- otherwise (naming syntax unsettled)
collectionExpr := binding ("," binding)* ("where" predicate)?
binding        := path ("as" Identifier)?

duration       := IntegerLiteral ("seconds"|"minutes"|"hours"|"days"|"weeks")
```

See `example_predicates.md` for the worked derivation of every rule above.

## 11. `rule ... when ... on ...`

A top-level reaction attached to a refinement — replaces `if`/`else` branching and imperative "then do X" sequencing for state-driven behavior. The header separates the rule's *condition* from its *trigger source*, one keyword each:

```
rule <name> when [leaving] <condition> [on|after <trigger>, ...] { <effects> }
```

```
rule SendReceipt when SettledInvoice {
    Receipt for invoice sentOn: now
}
```

`when Refinement` names *what* the rule reacts to — the condition: a refinement, entered (or left — see Exit triggers, below). `on` names the *trigger source*: `on commit` — the rule evaluates as a consequence of any commit that can affect its condition — or a named schedule (`on Daily`, see Schedule triggers, below). `after commit` swaps the preposition to start a new transaction (see "Transactions and `after commit`", below). `on commit` is the default when the clause is omitted (as above), the same single-well-defined-default category as properties being required unless marked `?`. The echo of BDD's given/when/then is deliberate: *given* the declared shapes, *when* the condition holds, *on* commit or tick, then the effects (`## then`).

Neither clause says *how* detection gets implemented. Whether the underlying mechanism is a check made at write-time, an event stream, a runtime data-structure instantiation, or some mix of these for the same rule is left open by the declaration itself; the only contract that has to hold regardless of mechanism is that the effect happens if and only if the subject is or becomes a member of the refinement, exactly once per newly-satisfying instance — "newly-satisfying" is commit-relative: the commit that makes the condition become true is the occurrence the rule fires for. The contract is per trigger source: from `on commit`, the firing subjects are the *entrants* — instances whose membership flips at the triggering commit; from a schedule source, the tick evaluates the condition against current state and every *current member* is a subject — re-checking, not transition-watching — which is why tick-cadence rules pair with guards: "already handled" across ticks must be data (`## Schedule triggers`, the tick law; `## Run-once guards`). Picking and implementing the actual detection mechanism is a compiling concern (`## 1. Principles`), not part of what the rule means.

### Rules ground in commits

**A system never does anything on its own** — it only reacts to an external event, a commit; a scheduled tick is just another commit, whose changed datum is `today` (`## Schedule triggers`). Every rule firing therefore traces to a commit — but `on commit` names no *particular* commits, and the condition can be a drift refinement (`Delinquent = Account where balance < 0`) whose data no act shape mentions. Which commits can fire it? The author never says — and doesn't have to, because **drift is always commit-mediated**: `balance < 0` becomes true only when some commit changes data the predicate reads. The static-path and one-writer requirements (`## Assignment (mutation in place)`) mean the compiler knows every assignment targeting `balance` and every act that feeds it, so the trigger set is derived: the author declares the **what** (`when` — the business condition) and the **source** (`on` — commit, ticks, or both); the compiler computes the **which** (the exact commits) and proves the rule **reachable**. A new writer of `balance` added next year automatically extends the trigger set with no edit to the rule — correct, because the business condition didn't change.

Two compiler obligations fall out. **The unfireable-rule error**: if a rule's trigger set is empty — no commit in the spec, and no tick in its `on` clause, can cause entry into its condition — the rule can never fire, and that's a whole-spec compile error, not a dead-code shrug. The sharp instance is time: `OverdueInvoice = Invoice where due < today` depends on `today`, which no act commit changes — a rule `when OverdueInvoice` with no schedule in its `on` clause observes entry at `Invoice` creation (committed already-overdue) but never entry *by aging*, and the diagnostic says exactly that: "entry into `OverdueInvoice` via the passage of time is unobserved — add a schedule to `on`, or this rule under-fires." **The PO-facing answer to "when does this run?"**: the derived trigger set is impact analysis read backward — forward, "if this commit lands, these rules may fire"; backward, "this rule fires as a consequence of: withdrawals, deposits, the daily tick."

### Entry and exit are commit-local transitions

"Newly-satisfying" is well-defined with no hidden bookkeeping, but only *per commit*: a commit is a discrete moment, and evaluating its consequences means pre-state and post-state are both transiently available. `when Delinquent` means *the commit that made it true* — false before, true after; `when leaving` (`## Exit triggers`) is the same transition reversed. Consequences:

- **Episodes are free at commit granularity.** September's re-entry into `Delinquent` is a new entering commit, so the rule fires again — once per episode, with no guard apparatus, and the outcomes accumulate as history. Guards are what you buy for *durability* (crash recovery) and *cross-tick memory*, not for entry itself (`## Run-once guards`).
- **Bare act triggers are sound.** `when CorrectEmail` fires once per correction commit — the commit is the unit of firing. The rule is never tick-evaluated, so no sweep can re-apply old corrections.
- **A tick is a commit whose changed datum is `today`.** So `on commit, Daily` is one mechanism, not two: an invoice *aging* into `OverdueInvoice` is a commit-local transition over the tick — the same entry semantics as a payment-reversal commit.

### Transactions and `after commit`

A rule's body is **exactly one commit** — every effect statement lands together or none do; a body is never partially applied. That makes guard soundness structural (`## Run-once guards`) and makes `then` a compilation ordering with no observable intermediate states (`## then`). A firing's effects are a *new* commit, which may match further rules' conditions the same way an external act's commit would.

By default, an act's commit and every commit its consequences produce share one all-or-nothing envelope — a *transaction*, a descriptive term, never a keyword: an unexpected error anywhere rolls back the whole set, the act included, and retrying the act re-produces the same commits. The grounding is **the transition law**, the tick law's sibling (`## Schedule triggers`): *a transition is not data — it exists only at the commit that caused it; a rule reacting to it either fires within that commit's transaction, or the obligation must first be reified as data (a guard); there is no third place for the trigger to live.* Current state can't reconstruct a past transition ("restored" and "never delinquent" are indistinguishable — `## Exit triggers`), so a rule outside the causing transaction that fails is unrecoverable: no sweep can find work whose trigger was never data. Sharing the transaction by default is the only reading under which a plain rule is reliable at all.

A rule opts out with a preposition — **`after commit`**: its firing becomes a new transaction, entered only after the triggering transaction has durably committed. The act stands even if the firing fails; the declared backstop heals the gap:

```
rule SyncToCrm when UnsyncedSignup after commit, Hourly {
    CrmSync from { signup: this, syncedOn: now }
}
```

What a PO reads off a header: `on commit` — part of the act; `after commit` — follows the act, healed on the stated cadence; `on Nightly` — follows by its nature. Boundaries arise only three ways: **declared** (`after commit`); **inherent** (schedule sources — a tick is a fresh commit and any originating act's transaction is long gone, so no preposition distinction exists for schedules); **forced** (external effects — an API call happens in the world, so a rule containing one can never share the triggering transaction, and writing it plain is a compile error demanding `after commit` plus the apparatus — or **`tolerates loss`** on the rule, the fire-and-forget escape for effects the business shrugs about (an analytics ping), signing the named hazard exactly as fold hazards are signed (`## Self-referential folds and tolerates`)). Boundaries also **nest**: an `after commit` firing's own consequences share *its* transaction by default, so a cascade is a tree of transactions — each subtree an all-or-nothing envelope rooted at a declared, inherent, or forced boundary, with the gaps between subtrees healed by each boundary's apparatus.

**A boundary obligates the apparatus, and the check runs both ways** (`## Run-once guards`): an `after commit` rule without a dischargeable guard and a backstop schedule is the stranding error — "this firing can be lost at the declared boundary, and its trigger is not data"; a guard-plus-backstop on a plain rule is dead machinery — "serves no boundary; did you mean `after commit`?" — since inside a transaction there is no gap for a firing to be lost in.

**The gap between the world and the state is irreducible — a business problem, not a language gap.** No system of any kind can call a charge-card API and be *guaranteed* the record of the attempt lands in the database — the call and its record live in different worlds, and no envelope contains both. Velle cannot prevent an author from writing a spec exposed to that gap; it can only make the exposure honest. The idiom is ordering, not atomicity: **commit the intent before the effect.**

```
shape ChargeIntent {
    order: one Order
    requestedOn: DateTime initially now
}

shape ChargeResolution {
    intent: one ChargeIntent
    resolvedOn: DateTime
}

shape UnchargedOrder   = Order where not exists ChargeIntent for this
shape UnresolvedCharge = ChargeIntent where not exists ChargeResolution for this

rule RecordChargeIntent when UnchargedOrder {         -- pure data — durable before any call
    ChargeIntent from { order: this }
}

rule ResolveCharge when UnresolvedCharge after commit, Hourly {
    -- the processor interaction happens here; what it learns lands as the resolving fact
    ChargeResolution from { intent: this, resolvedOn: now }
}
```

An unresolved intent is honest state: "a charge may have gone out; its outcome is unknown." The rule making the call cannot promise its own record lands after a successful call — nothing can — but the intent already stands, so a crash strands no knowledge: the backstop revisits the *intent*, and resolving it is a business process, not a blind retry — ask the processor what became of this attempt, record the outcome, or reverse the charge; which of those, and on what cadence, is the author's product decision. Nothing here is new machinery: the intent is a reified act, the unresolved state is a canonical guard (`## Run-once guards`), the resolution is evidence, and the sweep is a backstop schedule. What the pattern requires from the data is identity — recognizing *our* attempt among the processor's records — which the data carries: the intent's `id`, or a generated key (`processorKey: text initially randomUUID` — `## Scalars`). A retry budget — "retry, but give up after three and tell someone" — is memory across transactions, so it too is data: reify each try (`ResolutionAttempt`), express failure outcomes as refinements, guard the retry on `count(ResolutionAttempt for this) < 3`, and let a separate rule watch the exhausted state.

**Firing order within a transaction is never specified — and must provably not matter.** Velle states timeless facts, not call sequences: when one commit matches several conditions, the runtime may fire them in any order, or in parallel. Where data flows, the dependency graph orders firings — causality, not policy (`## then`). Where it doesn't, sibling firings must commute, and a spec whose outcome depends on an unstated order is *inconsistent* — a whole-spec compile error naming the rules involved, exactly like one-writer (write-write conflicts *are* one-writer at transaction scope; read-write conflicts and transition interference complete the check). The confluence and quiescence proofs backing this are open (`open_questions.md`, OQ16), as are rejection scope and the commit-refusal residue (OQ17, OQ20).

## 12. Assignment (mutation in place)

A rule body changes stored state with an assignment statement — mutating a field in place is allowed; Velle does not force the ledger pattern (below) onto a model that doesn't need history:

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
}

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}
```

- **`=` is positional.** In rule-body position it means *becomes, now*; in shape-body position it remains *is defined as, always* — a derivation (`## Derived properties`). One symbol, two positions — the same deliberate positional reuse the language already makes with `for` (association vs. query) and `?` (optional marker vs. `?.`). The two contexts never collide, because **rule bodies contain no definitions and shape bodies contain no effects** — a load-bearing invariant.
- **The target is a literal static path — a hard requirement.** Because `customer.email` is statically known, the whole-spec compiler knows every refinement predicate and derived property that reads `email`, and impact analysis falls out for free: which memberships may change, which entry/exit rules may consequently fire, which `frozen` declarations (`## Refinements`, "Frozen fields") the write must be provably disjoint from. A computed or reflective target would destroy this, so no mutation syntax may ever introduce one.
- **Targets are stored fields.** A derived property is a computation; a computation can't be assigned — `invoice.balance = 0` is a compile error when `balance` is derived. Assignment writes stored truth; derived properties recompute over it. The correct model commits data the derivation already reads (a `Payment`, or a stored `forgiven` field the formula consults).
- **Last-in-wins across commits.** Nothing in any computer system provides anything other than last-in-wins at the bottom, so Velle needs no ordering vocabulary for in-place mutation — a mutated field holds the last value committed. Within a single commit "last" is undefined, which is what the one-writer check (below) enforces.
- **A self-referential right-hand side is a fold.** An RHS that reads the field it assigns (`account.balance = account.balance + amount`) denormalizes a fold over the commit sequence and triggers its own analysis — `## Self-referential folds and tolerates`.

### One writer per field, per commit

More than one assignment to the same field of the same referenced shape is a compile error whenever the assignments' triggers can coincide — whenever a single commit could fire both. There is no `then`-ordered escape: sequencing two writes to one field inside a single firing is not a real business statement; one assignment whose RHS states the whole intent replaces it.

"Can the triggers coincide?" is answered statically from the declarations, never at runtime: same trigger shape → yes; a refinement and its base (or two overlapping refinements) → yes; unrelated shapes → provably never. If the compiler can't prove two triggers disjoint, it errors — uncertainty fails closed. The machinery is the refinement-overlap check `## Refinements` already names as a compiler goal, applied to trigger shapes, and it's a whole-spec check: a second rule assigning `customer.email`, added months later in another file, trips it, reported as one connected diagnostic naming both rules.

```
shape CorrectEmail  { customer: one Customer, corrected: text }
shape OverrideEmail { customer: one Customer, admin: one Admin, overridden: text }

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}

rule ApplyEmailOverride when OverrideEmail {
    customer.email = overridden          -- legal: an OverrideEmail is never a CorrectEmail,
                                         -- so these always fire from different commits
}

rule NormalizeEmail when CorrectEmail {
    customer.email = lowercase(corrected)     -- compile error: same trigger shape as
                                              -- ApplyEmailCorrection — one commit fires both
}
```

Separate acts whose triggers can never coincide fire from different commits, where last-in-wins orders them — whichever act is committed last wins, precisely the business reality ("the admin's override stands until the customer corrects it again, and vice versa").

### No act-level sugar

A one-assignment rule stays a rule — there is no collapsed form letting a mutation shape carry its assignment in its own body:

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    customer.email = corrected      -- rejected
}
```

Two reasons. The positional meaning of `=` is load-bearing: an effect in shape-body position would spend the no-collision invariant to save four lines. And the sugar would mostly abbreviate a rarity: the common external-submission case ("save what the user sent") needs no rule at all, because **committing a shape instance is persisting the record** — a rule exists only when an act has a *consequence* beyond its own persistence, and naming that consequence (`rule ApplyEmailCorrection`) is the point, not overhead.

### The ledger alternative

The ledger is a *pattern* the author chooses when history is part of the model — not something Velle forces. Nothing is edited; each change is a new record — an ordinary shape instance — and the "current value" is a derived property selecting the latest one:

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
    correctedOn: timestamp on create
}
```

Nothing here is new mechanism: `EmailCorrection` is a reified act like any other shape, `latest(...)` orders by `correctedOn` — the shape's declared creation timestamp (`## Scalars`) — and the `exists` check narrows the then-branch so `.corrected` is provably evaluable. Commit means insert, the ledger is append-only, and the stored truth is never touched. The two patterns answer different business realities — "email changed" (in place) versus "email changed and the history matters" (ledger) — and the author picks per field.

## 13. Exit triggers (`when leaving`)

*Tentative in part — what an exit rule may read is being re-derived under the commit-local transition model (see `open_questions.md`, OQ7).*

`when R` reacts to an instance *entering* a refinement — becoming a member. `when leaving R` is its mirror: a reaction to an instance that was a member of R and stopped being one — the same commit-local transition reversed (`## rule`).

```
rule RestoreService when leaving Delinquent {
    ServiceRestoration from {
        account: this
        restoredOn: now
    }
}
```

**Not expressible as a complement.** Reacting to entering `Compliant` is not the same thing: a newly created, never-delinquent account also "enters" `Compliant` — *became compliant* and *was always compliant* are indistinguishable from current data alone. `leaving` needs no such reconstruction: only a member can leave, so the trigger is inherently transitional. This completes the condition vocabulary — `when` for entry, `when leaving` for exit; the trigger source (`on commit` / `on <schedule>`, see Schedule triggers below) is orthogonal to both. A run-once guard applies identically when durability demands one, and guard granularity — the guard predicate's content (`## Run-once guards`) — decides what a *repeated* exit means, exactly as it does for repeated entries.

**What an exit rule may read.** At the moment the rule fires, the instance is no longer a member of R, and everything membership implied is gone with it. The body may read the instance's current data and durable evidence produced while it was a member; it must not read anything only membership in R could supply — R's own captured properties above all, which retract at the very moment the rule fires — such a read can never be satisfied, and is a compile error. The discipline this enforces: a rule acting on a membership should record what it acted on in its evidence mapping, because evidence is the only thing that survives the exit.

**Evidence outlives its premise.** A rule fired, its evidence escaped, and then the instance left the refinement: what happens to evidence whose premise has been falsified? Nothing needs declaring — each case is expressed by machinery the language already has:

- **Evidence that is simply history** ("the quote is the quote; prices drift") needs nothing declared — silence is the behavior, and a spec that records evidence and reacts no further is complete and correct as written (the ledger stance, `## Assignment (mutation in place)`).
- **Immutability** ("you can't edit line items on an issued invoice") was never about the exit at all — it's a write-gate on a state, declared as `frozen` in the refinement's body (`## Refinements`, "Frozen fields"). The evidence-held lien is a freeze on an evidence-entered refinement, and lifting the lien is a non-monotone predicate.
- **Compensation** ("invoices are never edited — voided and reissued") is a pattern over the same machinery — next subsection.

Deleting evidence is never on the menu — a produced fact records something that happened in the world (the email was sent), and deleting the record makes the description lie.

### The compensation pattern

Compensation — the exit produces a compensating fact — is fully expressed by ordinary machinery once the rule's subject is the *evidence* rather than the instance that left:

```
shape UncompensatedSuspension = ServiceSuspension where not exists ServiceRestoration for this
shape RestorableSuspension    = UncompensatedSuspension where not account is Delinquent

rule CompensateSuspension when RestorableSuspension {
    ServiceRestoration from { suspension: this, restoredOn: now }
}
```

An ordinary guarded entry rule — no `when leaving` anywhere. (Note the `ServiceRestoration` here references its *suspension*, not just the account as the section-top `RestoreService` example's did — the evidence linkage is exactly what the pattern is about.) The account's exit from `Delinquent` is observed as the suspension's *entry* into `RestorableSuspension`, at the same balance-changing commit (drift is commit-mediated — `## rule`, "Rules ground in commits"). Everything compensation requires, the pattern provides: evidence scoping is by construction (no suspension → no subject → no firing — a membership too brief for the entry rule to observe compensates nothing); once-per-evidence is the canonical guard with the disarm proof (`## Run-once guards` — the body produces the `ServiceRestoration` that falsifies `UncompensatedSuspension`); there is no singular selection and no at-most-one proof, because `this` *is* the evidence; and durability composes on demand (`when RestorableSuspension after commit, Hourly`), which no transition-triggered rule can have — a transition is not data (`## rule`, "Transactions and `after commit`"). The correlation decisions — per-suspension? per-account-ever? time-windowed? — are visibly the refinement predicates, where granularity always lives (`## Run-once guards`, "No guard sugar"). The pattern is also *more* correct than any exit-scoped spelling: evidence that lands after the exit (a tick-cadence or `after commit` entry rule, the account recovering in the gap) is born a member of `RestorableSuspension` and compensates immediately, where an exit-scoped rule — its transition already past when the evidence landed — would strand it uncompensated forever.

Compensation therefore never needs `when leaving`: uncompensated evidence is durable memory that the membership happened, so exactly where there is something to compensate, the exit is reconstructible from current state. `when leaving` remains irreducible only for reactions that carry no evidence (`RestoreService` at the top of this section) — which is exactly where there is nothing to compensate.

## 14. `for`

Associates a newly created shape instance with the subject it's about:

```
Receipt for invoice sentOn: now
```

`for <expr>` matches by **type**, not name: whichever field on the created shape has a type matching `<expr>`'s type is the one populated. When a shape has more than one field of that type, name the field explicitly: `Referral for referrer: this.referrer`.

Inside a rule body, `from { field: value, ... }` is the general, clearer way to write this — every field as an ordinary, totality-checked mapping entry, with no field singled out syntactically:

```
Referral from {
    referrer: this.referrer
    referee: this.referee
    referredOn: now
}
```

Creating an instance is an ordinary body statement, nothing more — no header annotation is involved. When the created shape serves as a guard witness, the connection to the trigger is the disarm proof's job, checked rather than spelled. `for` as a *query* expression (`(NurseVerification for this).nurse`, `exists Shape for expr`) is unaffected by this — see `## Predicate expressions`, above.

## 15. `then`

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

## 16. Sweeps: rules fire per record

There is no loop construct. A rule fires once per matching record — from `on commit`, once per entrant at the triggering commit; from a schedule source, once per *current member* of its condition at the tick (`## rule`). Transaction scope follows the source. A schedule firing is its own transaction (`## Schedule triggers`): one record's failure never unwinds another's handling — a poison record blocks only itself, everything else stands and disarms its guard, and the next tick retries exactly the stragglers. Commit-source firings are consequences of the triggering commit and share its transaction (`## rule`, "Transactions and `after commit`") — which is what makes a fan-out atomic when the business wants all-or-nothing: N records entering a condition at one commit yield N firings that stand or fall together with the act. A sweep is therefore just a schedule-triggered rule whose condition selects the records needing work:

```
rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}
```

`this` is the matched record. A guard against re-handling is part of the condition's own predicate (`where not suspended`, above — inline or as a named refinement; `## Run-once guards`), so the run-once obligation is per record — and because a schedule source quantifies current members, the sweep is self-healing: a record whose handling was lost is simply still a member at the next tick. Two consequences: every rule has a `when` condition — subjects come from the condition, so there are no subjectless rules — and a batch of records never needs an envelope of its own (`## State-change patterns`, "All-or-nothing batches").

## 17. Schedule triggers (`on <schedule>`)

A rule can be triggered by a named schedule instead of (or in addition to) `on commit`, by naming the schedule in the header's `on` clause:

```
rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }
}
```

`on` accepts a comma-separated list (`on Daily, Hourly`) for a rule that needs to run on more than one cadence — including mixed with commit (`on commit, Daily`: fire the moment the condition is entered, *and* re-check on the cadence). The list is distributive — each entry independently triggers the rule: `after commit, Hourly` declares two triggers, *fire after the commit* and *fire at the Hourly tick*. The `on`/`after` preposition is meaningful only for the `commit` entry, the one trigger source where sharing the triggering transaction is possible at all; a schedule entry's firings inherently begin their own transactions either way (`## rule`, "Transactions and `after commit`"). `Daily` is a placeholder name, not a built-in or sugar for a specific interval — what actually defines a schedule (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be provided by some cron-like scheduling framework. Only the rule-side trigger syntax is settled.

A schedule trigger is the *only* way a purely time-dependent refinement (like `OverdueInvoice`, which depends on `today`) gets re-checked — nothing in Velle executes purely on the passage of time by default. A scheduled tick is conceptually a commit like any other (the same category as a `Payment` arriving or a `ChargeResponse` coming back — a tick is a commit whose changed datum is `today`), but it's referenced by name in `on`, not declared inline as a custom shape. A tick's firings inherently begin new transactions — by the time a schedule fires, any originating act's transaction is long gone (`## rule`, "Transactions and `after commit`") — and each firing at a tick is its **own** transaction: a tick is a moment, not an envelope, so one firing's failure at the nightly tick never unwinds another's work, whether the firings belong to different rules or to different records of one rule (`## Sweeps`), and each firing's own consequences share that firing's transaction in the ordinary way.

**The tick law: cross-tick memory must be data.** A tick-triggered rule sees only current state — "what changed since last tick" would require persistent memory between ticks, and memory must be data. Sweeps carry their memory as witnesses, flags, or evidence: the example's "at most every 3 days" lives in the `Reminder` evidence itself, with no hidden last-run timestamp anywhere.

**Transient membership is a policy, stated in the header.** An account goes negative Monday 09:00 and recovers at 17:00. Under `when Delinquent on commit`, suspension fires at 09:00 and restoration at 17:00 — the blip was real, service was off for eight hours. Under an `on Nightly` sweep, the check sees a positive balance — the blip never mattered. Neither is wrong: commit-triggered observes every membership the commit stream produces; tick-triggered observes what persists to the tick. The choice is business-visible in one clause, answered per rule by the author rather than globally by the language.

## 18. Run-once guards

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

### No guard sugar

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

## 19. Self-referential folds and `tolerates`

Static analysis splits assignment right-hand sides into three classes; only the last needs machinery.

**1. Act-only** — reads nothing but the triggering act (`customer.email = corrected`). Idempotent; re-firing is harmless.

**2. Recomputing** — reads state, but *not the target itself*: a deterministic formula over current data. Convergent — same inputs, same value — so exactly-once is moot on any trigger. Per-commit recompute is an incrementally maintained materialization (semantically a derived property that happens to be stored); tick recompute is a *snapshot* — "the score as of last night," deliberately stable between ticks, a business concept live derivation can't express. Both valid, no guard:

```
-- "every commit, update this value": materialized derivation
rule UpdateRating when Review {
    product.rating = sum(product.reviews, stars) / count(product.reviews)
}

-- "every night, recalculate this value": snapshot
rule RecalculateRiskScore when Account on Nightly {
    this.riskScore = <formula over payments, balance, history>
}
```

Where a fold is expressible as a recompute, the incremental spelling (`rating = (rating * n + stars) / (n + 1)`) and the recompute spelling are the same description with different *execution strategies* — and incremental maintenance is what `## Principles` assigns to compilation, not description. Both spellings stay legal (flexible, not restrictive); surfacing the twin as guidance is rung recognition's advisory job (`## State-change patterns`).

**3. Self-referential — a denormalized fold.** The RHS reads the target's own current value, so the next value is a function of the previous one (`value′ = f(value, commit data)`): the stored field denormalizes a *fold over the commit sequence*. `balance = balance + amount` is only the simplest instance — streaks, peaks, and moving averages are all folds.

**The compiler never determines the algebra of `f` — it doesn't need to.** Two decidable facts replace it:

- **Self-reference is syntactic.** The RHS reads the field it assigns — reliably decidable; this is what triggers the analysis.
- **Exposure is structural, per hazard.** *Duplication exposure*: an unguarded fold can be applied twice for one commit (the crash/replay window, and trivially under a `, Hourly` backstop). A fold gated on a dischargeable state cannot. *Reordering exposure*: a commit-cadence rule folding one act at a time cannot reorder — commits are serialized, so fold order *is* commit order; a tick-cadence rule's pending records fire separately at the tick (`## Sweeps`) with no defined order among the firings.
- **Insensitivity is a fail-closed whitelist, per axis** — the compiler proves safety only for known forms, along the two axes independently:

| fold | duplication-safe? | order-safe? |
|---|---|---|
| `max(x, amount)`, `min`, set-union, boolean-or | provably yes | provably yes |
| `x + amount` (`sum`, `count`) | no | provably yes (commutative) |
| streak, moving average, "reset on miss" | no | no |

**An obligation exists exactly where a rule is exposed to a hazard its fold is not provably insensitive to.** An undischarged obligation is a compile error — fail-closed, mandated. The diagnostic is a demand for a stated policy, not an accusation of a bug: "this value's correctness depends on each qualifying commit being folded exactly once — state how that's ensured." Legitimate use cases carry the obligation *correctly* — a streak really does break under replay — so legit-but-unprovable and dangerous-but-unprovable get the same diagnostic; the difference is only that the legit author has an answer to give. Four ways to give it:

1. **Guard** — make exactly-once true (`## Run-once guards`).
2. **Derivation twin / recompute** — make it irrelevant: the value re-derives from source records.
3. **Reconciliation sweep** — bound the drift: the fold stays cheap and unguarded on the hot path; a periodic recompute (`viewCount = count(PageView for this)` nightly) restores truth on a cadence (`## State-change patterns`' reconciliation rung).
4. **`tolerates <hazard>`** — accept the named risk, declared where the risk lives (the field for fold hazards; the rule for `loss`):

```
shape Article {
    viewCount: Number tolerates duplication
}
```

The vocabulary is closed and mirrors the hazards one-for-one — `tolerates duplication` and `tolerates reordering` on fields; `tolerates loss` on a rule whose external effect may be dropped (`## rule`, "Transactions and `after commit`") — so checking is mechanical set-coverage: exposed hazards, minus those discharged by a guard, an ordering, a reconciliation, or a tolerance, must be empty. A dead tolerance is itself a diagnostic ("this rule cannot experience reordering — did you mean duplication?"): the author never needs the exposure model in their head; the compiler names the specific risk, and the author fixes it or signs it by name. The declaration is self-vetting because it *is* the business claim — `balance: Money tolerates duplication` is visibly absurd in exactly the way that forces the author to confront what they're signing.

**The showcase trio** — same trigger, same cadence, three different verdicts:

```
-- error: the canonical guard example minus its guard. The Hourly tick re-folds
-- every Deposit that exists — the happy path double-counts by construction.
rule ApplyDeposit when Deposit on commit, Hourly {
    account.balance = account.balance + amount
}
-- "correctness depends on each Deposit being folded exactly once, and nothing
--  ensures it. Gate the rule on a dischargeable state (see UnappliedDeposit),
--  define balance as a derivation over deposits, add a reconciliation sweep,
--  or declare `tolerates duplication` on the field."

-- silent: whitelisted fold — fold it twice, ten times, out of order; same value
rule TrackLargest when Deposit on commit, Hourly {
    account.largestDeposit = max(account.largestDeposit, amount)
}

-- legit and unprovable: the diagnostic fires correctly on code the author is
-- right to want. Commit cadence → reordering exposure is zero by construction;
-- the fold owes duplication only, and a guard serves it fully.
rule TrackStreak when Payment {
    account.streak = if onTime then account.streak + 1 else 0
}
```

**Residual gap.** A tick-cadence order-dependent fold (a nightly streak sweep) is exposed to reordering with no honest discharge yet: nothing orders one tick's firings, and the derivation grammar has no ordered folds — declared tolerance is currently the only spelling, which is wrong for a streak. Until one exists, commit-cadence is the only fully-served spelling for order-dependent folds (`open_questions.md`, OQ15).

## 20. State-change patterns

The same business sentence — "suspend delinquent accounts" — has several valid designs, chosen by what the business actually needs (flexible, not restrictive — `## Philosophy`). The spectrum runs **derivation → reconciliation → exactly-once events**, and the guard question only exists at the far end.

**1. Pure classification — no rule at all.** If "suspended" causes no external effect — it's a status the system reports — it isn't state, it's a predicate: `shape Suspended = Account where balance < 0`. Zero machinery, always exactly current, nothing to guard. The question that selects this rung: *is suspension a fact you compute, or an action you take?*

**2. Reconciliation — idempotent sweep + resettable current-state flag.** When suspension must be *stored* (independently meaningful: manual overrides, grace periods, an external system reads it) but only its current value matters:

```
shape Account {
    balance: Money
    suspended: boolean initially false
}

shape Delinquent = Account where balance < 0

rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}

rule RestoreService when (Account where suspended and balance >= 0) on Nightly {
    this.suspended = false
}
```

`this.suspended = true` writes a constant — idempotent, so exactly-once is moot: the sweep can run twice a night or crash mid-run and rerun; it converges. The answer here is not *guard the firing* but *make firing harmless* — a reconciliation loop, desired state enforced by an idempotent sweep. The flag is a *current-state* guard and it resets (`RestoreService` re-arms it), so re-entry is ordinary behavior. Latency = cadence, and transient blips are deliberately unobserved (`## Schedule triggers`).

**3. Exactly-once events.** When the moment matters or the record does:

```
rule SuspendService when Delinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
}
```

Commit-local entry makes re-entry free — September's second delinquency is a new entering commit, so the rule fires again, and the `ServiceSuspension`s accumulate as per-episode history. Add a guard (`## Run-once guards`) when the firing must be durable.

Off the end of the scale, the deposit case done reconciliation-style is `balance: Money = openingBalance + sum(...)` — the derived property, i.e., the ledger. The guard isn't the answer to mutation safety in general; it's the answer for the subset of designs where the author has chosen occurrence semantics, and the language's job is to make every rung expressible and validate whichever one the author picked — which the compiler does by classifying rungs (below).

### Episodes as data

Commit-triggered rules get per-episode *firing* free, but a tick rule that needs "this episode was handled" across ticks, or a business that quantifies over episodes ("third delinquency this year," episode duration), needs the episode reified — the tick law again: cross-tick memory must be data (`## Schedule triggers`). The pattern is a flag/resolution pair:

```
shape DelinquencyFlag {
    account: one Account
    flaggedOn: Date
}

shape DelinquencyResolution {
    flag: one DelinquencyFlag
    resolvedOn: Date
}

shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

-- a delinquent account with no open flag starts a new episode
rule OpenDelinquencyEpisode
    when (Delinquent where not exists (OpenDelinquencyFlag where account == this)) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- leaving Delinquent closes the open episode
rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag where account == this)
        resolvedOn: today
    }
}
```

Now `count(DelinquencyFlag where account == this) >= 3` is expressible, and any rule can guard per episode (`DelinquencyFlag where not exists ServiceSuspension for this`). Noteworthy in passing: the exit rule's singular reference `(OpenDelinquencyFlag where account == this)` is provably at-most-one *because of the entry rule's own guard* — a whole-spec singularity proof (`## Predicate expressions`' `for`-query rule, discharged by a guard elsewhere in the spec). The honest cost: three shapes and two rules of completely mechanical pattern — accepted, not sugared (`## Run-once guards`).

### All-or-nothing batches

There is no batch-transaction construct — and no business case has survived scrutiny that needs one. "Handle these records together, all or none" always dissolves into machinery already present, by one of these routes:

- **A static multi-record effect is one body — already atomic.** Double-entry bookkeeping: debit and credit are two statements of one body — one commit, both-or-neither (`## rule`):

```
rule PostTransfer when Transfer {
    LedgerEntry from { account: from, amount: 0 - amount }
    LedgerEntry from { account: to,   amount: amount }
}
```

- **A dynamic per-member effect is a fan-out — atomic when commit-driven.** "When an order is paid, reserve stock for every line item, all or none": the gate is an aggregate refinement on the parent, and the effect is a per-record rule the entering commit fires N times — every firing standing or falling with the act, because commit-source consequences share its transaction (`## Sweeps`):

```
shape FulfillableOrder = PaidOrder where count(lineItems where not InStock) == 0

rule ReserveStock
    when (LineItem where order is FulfillableOrder and not exists Reservation for this) {
    Reservation from { lineItem: this }
}
```

- **"All at once" over N records is one fact wearing N disguises.** "Apply the new rate to every subscription at midnight" isn't a batch: commit one `PriceSchedule` record that price derivations read, and every price changes at that commit by construction. Likewise a batch-wide status: a per-entry `submittedOn` that is equal across all entries by definition is the *batch's* one fact, derived per entry (`submittedOn: Date? = (BatchSubmission for batch)?.submittedOn`) — all N memberships flip at the witness commit.
- **A set that must succeed or fail together is a business object.** Settlement batches, payroll runs: whenever a business says "together," the together-thing has a name — reify it (`## State and commits`' container idiom), and its handling is one record's firing: one transaction, one external act, one `id` to reconcile (intent-before-effect, `## rule`).
- **N distinct facts arriving incrementally get a completeness gate, not an envelope.** Per-entry results from a clearing house land one commit at a time; downstream rules hang off `CompleteResponse = BatchResponse where count(results) == count(batch.entries)` — conditioned acceptance: the partial state is honest and unobserved.

The principle underneath: **batches demand atomic *observation*, not atomic *writing*** — and observation is a predicate over a commit's post-state, which is already atomic. Where records "must change together," the shared thing is one fact and the N views are derivations; where facts "arrive together," a completeness refinement keeps observers off the partial set. What a direct "per member of the collection" spelling would add is ergonomics over the fan-out — sugar territory for the mapping construct (`## Open / unresolved`), not transaction machinery.

### The compiler recognizes the rungs

The spectrum is not just documentation — the compiler classifies which rung each rule sits on and validates it accordingly. The classification is *derived* from what's written, never declared. Much of the machinery is already settled elsewhere: the RHS three-class split is the rung classification for assignments (`## Self-referential folds and tolerates`), obligations arise only where non-idempotence demands one, and a reconciliation sweep is recognized as a discharge. Recognition adds on top:

- **Rung-specific proofs.** A sweep serving as reconciliation must be provably convergent and idempotent — re-running it against unchanged data changes nothing. A latch pair (set-rule / reset-sweep) must actually cover both directions, or the flag drifts from the condition it mirrors.
- **Advisory, not policing.** "This stored flag could have been a pure classification — no rule needed" is guidance the author may ignore; the *required* diagnostics remain only the fail-closed ones (one-writer, disarm, fold obligations).
- **Classification feeds the analyses.** Impact analysis and `why` can name the rung — "this value is a nightly snapshot," "this flag is reconciled, drift bounded by the sweep cadence" — so a reader learns the design intent from the tooling, not from convention.

How this behaves against realistic specs is untested — the classification boundaries and the advisory/required line are expected to be calibrated when realistic Velle examples get written; the principle is settled, the calibration isn't.

## 21. `never`

A top-level declaration asserting that a configuration of state is impossible — a refinement that is empty, always. It takes the ordinary predicate grammar (`## Predicate expressions`), or a named refinement:

```
never (Account where balance >= 0 and suspended)    -- "an account in good standing is never suspended"
never (Customer where referrer == this)             -- "no customer refers themselves"
never SuspendedInGoodStanding                       -- the same statement over a named refinement
```

**Enforced at transaction end.** A `never` constrains the *settled* world — every transaction's final state — never the intermediate commits inside the envelope. A $50 deposit lands against a −$20 suspended account: at the deposit's commit the account transiently *is* `balance >= 0 and suspended`, and the restoring rule, firing as a consequence within the same transaction, settles it clean — valid. Per-commit enforcement would reject the deposit, or make its legality depend on sibling firing order, which must provably never matter (`## rule`, "Transactions and `after commit`"). The invariant promises what the world settles to, not the path: a transition-watching rule may observe a mid-transaction pass through the configuration.

**Enforcement follows who can violate.** An invariant whose predicate reads only rule-written data is **rule-maintained**: the compiler proves it inductively over the statically-known writers — whole-spec, fail-closed — and a writer that can end a transaction inside the configuration is one connected diagnostic naming both sides: "`SuspendManually` can end a transaction violating `never (Account where balance >= 0 and suspended)` — condition the act, restore within the transaction, or retract the invariant." An invariant violable only by external acts is **input-constrained**: no static analysis stops tomorrow's request, so its enforcement is *compiled into the boundary* — the transpiled code validates external data against the declared `never`s and rejects a violating act before it ever becomes a commit. That rejection lands below the language, alongside data that can't inhabit the shape's type at all; the language declares the business fact, compilation emits the machinery (`## Open / unresolved`, compiled guardrails). Data arriving through a legacy mapping wasn't born behind the guardrail — a `never` over mapped shapes carries a validation obligation at the mapping.

**An established `never` is a proof input.** Beyond being checked, it is *spent*: `never (Customer where referrer == this)` is what lets the one-writer disjointness analysis prove that writes reached by different paths (`customer.tier` and `customer.referrer.tier`, fired by one commit) can never collide. The author states the invariant; the prover uses it. "Established" means every derived obligation is discharged — `never` follows the same declare-once playbook as `frozen` (`## Refinements`, "Frozen fields"): the compiler derives an obligation per potential violator — author-side fixes for rule-maintained writers, emitted guardrails at the boundary, validation at legacy mappings — and only a fully-discharged invariant is spendable, so it is never merely checked and never merely assumed. How coarse the provers can be while spending invariants rides with the confluence calibration (`open_questions.md`, OQ16).

## 22. Open / unresolved

- **State change & rule mechanics — remaining frontier.** The core is settled — assignment and one-writer (§12), run-once guards with no sugar (§18), folds and `tolerates` (§19), the pattern spectrum and rung recognition (§20), and "what is one commit": a commit is exactly one act instance (a container shape models multi-part acts), a body is one commit, consequences share the act's transaction by default, `after commit` declares a boundary, and the irreducible world/state gap at an external effect is answered by the intent-before-effect pattern (§4, `## rule` "Transactions and `after commit`"); commit timestamps are author-declared, language-populated fields (`timestamp on create` / `on update`, §5); every instance carries an opaque, readable `id` (§5); a rule fires once per matching record — no loop construct, transaction scope following the trigger source (§16, §17); all-or-nothing batches need no envelope construct — they dissolve into one-body effects, commit-shared fan-out, one-fact-many-readers, containers, and completeness gates (§20, "All-or-nothing batches"); cascades nest as trees of transactions; `tolerates loss` signs fire-and-forget external effects (§11, §19); and `never` declares impossible configurations — transaction-end enforced, proven for rule-maintained invariants, compiled into the boundary for input-constrained ones (§21). Still open in `open_questions.md`: the external-input boundary — spelling adopted as `expose ... using` (see below), with committer-supplied vs. internal fields (including supplied `id`s at trust/legacy boundaries) and who-may-commit remaining (OQ5); the order-independence/confluence and quiescence proofs, rejection scope, and the commit-refusal residue (OQ16–17, OQ20); remaining rule-anatomy threads including what an exit rule may read (OQ7); whether the canonical guard form is pleasant enough to be what fold diagnostics ask authors to write (OQ14); ordered folds and firing order at a tick (OQ15). §13 carries a tentative marker accordingly.
- **Mapping** (shape-to-shape translation, e.g. API DTO → domain shape) — part of the original design goals, not yet exercised in a worked example. Now also the natural home for a direct per-member effect spelling — "one `Reservation` per line item" — as sugar over the guard-correlated fan-out (`## State-change patterns`, "All-or-nothing batches"), whose inverted spelling gets unpleasant as correlation deepens.
- **Schedule definition** — `on Daily` (the header's `on` clause) settles how a rule *references* a schedule; what actually defines `Daily` (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be a cron-like scheduling framework.
- **External input mechanisms (`expose ... using`)** — Velle implements no transport: REST APIs, GraphQL, socket connections to a message bus are traditional-code territory it doesn't compete in; what it owns is the contract between the spec and the shape of the transpiled code. The adopted direction: an external mechanism is a *plugin* — traditional code wired into the transpilation step — configured and named (`configure DefaultRestAPI using REST { ... }`), then referenced per shape — standalone (`expose CorrectEmail using DefaultRestAPI`) or inline at the shape's own declaration (`expose shape CorrectEmail { ... } using DefaultRestAPI`). An exposed shape is externally committable via the named mechanism; an unexposed shape enters state only as a rule's effect — which makes the set of `expose` declarations the trust boundary where input-constrained `never` guardrails (§21) compile to. Plugin configuration vocabulary, the plugin API, per-exposure field policy, and whether `expose` also names a read surface remain open (OQ5).
- **Reversal** — resolved as a non-issue for the language itself (it's a business-policy choice, expressed via which artifact shapes a human declares — see `example_invoice_payment.md` #5), but no single canonical pattern has been adopted yet; the consolidated top-of-file example still doesn't reflect a chosen policy.
- **Escape hatch / override syntax** — how a human marks part of a spec as intentionally hand-implemented/AI-implemented rather than declarative. Deferred; agreed to be a lesser concern until the core language settles.
- **Compiled guardrails** — the idea that the compiler/transpiler should structurally enforce best practices (e.g. forced prepared statements, automatic error-context capture, correctly evaluating self-referential shape/derived-property definitions, how deep narrowing analysis for `.`-vs-`?.` sees through nested expressions, erroring — not silently resolving — a bare unqualified name that doesn't exist in its innermost scope but would resolve unambiguously in exactly one enclosing scope, per `## Principles`'s compiling-as-validation rule: the fix is always an explicit `this.field`, never an inferred scope-walk that could silently start pointing elsewhere the moment an enclosing shape gains a same-named field; a field addition that creates a new type-match ambiguity for an existing bare `for` reference elsewhere in the spec, per §14, must be reported as one connected diagnostic naming both the declaration that introduced the ambiguity — e.g. `Referral` gaining a second `Customer`-typed field — and every reference it now makes ambiguous — e.g. `CustomerWhoReferred`'s `for this` — since the compiler's job is reporting an incoherence in the spec as a whole, not a syntax error in one isolated line, and a human should never have to search for why an untouched line stopped compiling) as a byproduct of codegen. A design principle, not yet a syntax construct — with one member now sourced by syntax: runtime boundary validation derived from input-constrained `never` invariants (§21).
- **`latest`/`first` explicit-ordering syntax** — the ordering source is settled: the element shape's declared creation timestamp (`timestamp on create`, `## Scalars`) by default, an author-named `Date`/`DateTime` property when the business ordering isn't creation order (`## Predicate expressions`). Unsettled: the surface syntax for naming the property (`latest(payments by receivedOn)` is the working sketch).
- **`initially` generator vocabulary** — `randomUUID` is the only generator so far (`## Scalars`); whether others belong, and which, is undecided. Sequence-flavored generators stay excluded on principle — ordering comes from timestamps or declared fields, never minted values.
- **Exit from act-entered refinements** — a membership predicate of the form `exists ArchiveRequest for this` is monotone: facts persist, so nothing can ever leave `ArchivedInvoice`, and un-archiving is inexpressible. Exit requires either pairing occurrences in the predicate — the simple unordered pairing (`exists Receipt for this and not exists ReceiptVoid for this`) is expressible today and is how a `frozen` state thaws (§8, "Frozen fields"), while `... and not exists Unarchival` *newer than the matched request*, needed the moment re-archival enters the model, requires occurrence-comparison vocabulary not yet designed (its ordering source is settled — the occurrence shape's declared creation timestamp, or an author-named field — but the comparison/scoping spelling isn't) — or a mutable field the predicate reads. Both are expressible; which is idiomatic is unsettled.
- **State partition declaration** — refinement properties give states their data (`## Refinements`), `frozen` gives states their write permissions (`## Refinements`, "Frozen fields"), and reified acts give transitions their payloads — but nothing yet asserts that a set of refinements *partitions* a shape: mutually exclusive, jointly exhaustive ("an invoice is always in exactly one of Draft, Issued, Paid, Voided"). Candidate spelling `states of Invoice = Draft | Issued | Paid | Voided`, invoking the exhaustiveness/overlap check `## Refinements` already names as a compiler goal — and the natural long-term home for per-state write permissions beside legal transitions (where "this field is frozen in every state" becomes the never-writable diagnostic). `never` (§21) is its natural primitive: pairwise disjointness is `never (A and B)`, leaving only the coverage check. The next investigation.
- **`why` / provenance** — a command to trace which rule/refinement produced a given piece of state, mapped back to Velle source. Agreed as a goal; no syntax proposed yet.
- **Derived-property value-expression grammar** — `## Derived properties`' arithmetic and conditional (`if`/`else`) forms have only ever been used by example, the same gap `## Predicate expressions` closed for boolean predicates. Not yet formalized.
