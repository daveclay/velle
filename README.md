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

The one way state ever changes is a **commit**: a discrete moment at which the black box accepts a change from outside — an external act submitting a new shape instance (committing an instance *is* persisting the record — `## Assignment (mutation in place)`, "No act-level sugar"), together with whatever consequences rules attach to it (`## rule`); or a scheduled tick, which is a commit whose changed datum is `today` (`## Schedule triggers`). A system never does anything on its own; every change to state traces to some commit.

The CRUD replacement reads off directly: "create" is committing a new instance, "update" is an assignment fired by a rule (`## Assignment (mutation in place)`), "read" is any predicate or derivation evaluated against current state, and "delete" has no primitive at all — a produced fact records something that happened in the world, and deleting the record would make the description lie (`## Exit triggers`); where the business needs reversal, that's a policy expressed as data, not an erasure (`example_invoice_payment.md` #5).

Because a commit is a discrete moment, pre-state and post-state are both well-defined at it — which is what lets entry into and exit from a refinement mean something precise, with no hidden bookkeeping (`## rule ... when ... on ...`). What exactly one commit encompasses — the atomicity of a rule firing with its effects, whether cascaded firings share their initiating commit, the moments `then` occupies — is deliberately still open (`investigate_state.md`, OQ6; the cascade-boundary thread specifically is `investigate_transactions.md`, OQ16–20).

## 5. Scalars

Property types seen so far: `text`, `integer`, `decimal`, `boolean`, `Date`, `Money`. A trailing `?` marks a property optional (`processedOn: Date?`); properties are required by default.

`initially` gives a stored property a starting value: `applied: boolean initially false`. The field stays stored and assignable (`## Assignment (mutation in place)`); the initializer is evaluated once, at the instance's creation commit — so `submittedOn: Date initially now` records commit time as model data. This is a third property kind, distinct from derivation: `applied: boolean = false` in shape-body position would mean *derived, always false* — unassignable (`## Derived properties`).

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
- **Captured** — marked with the leading keyword `captured`: evaluated once at the moment the current membership begins, fixed for the duration of that membership, absent before entry, retracted on exit, re-captured on re-entry. The marker is required because a bare `= expr` in body position is a live derivation — the two kinds must read differently. Capturing `today`/`now` anchors them to the entry moment: `archivedOn` above is the membership's start date, with no implicit system timestamp needed (the same stance `latest`/`first` already take).

**Every captured value traces to data.** There is no ambient execution context — no "current user", no request-scoped magic. `archivedBy` can only reach a `User` through the data graph, which forces the act carrying that data to be reified as a shape (`ArchiveRequest`) before the refinement can capture from it. That's a feature, not a workaround: reified acts are independently required for occurrence identity under re-entry, and they are what `why`/provenance will walk. (`(ArchiveRequest for this)` above is legal only while the spec proves at most one can exist per invoice — `## Predicate expressions`' `for`-query rule; the moment re-archival enters the model, the reference must become an ordered selection — see Open/unresolved.)

**Entry-evaluability guardrail.** A captured property's expression must be provably evaluable at the moment membership begins: every reference in it must be guaranteed by the refinement's own predicate, or be unconditionally present on the base shape. `(ArchiveRequest for this)` is legal above precisely because the predicate asserts `exists ArchiveRequest for this` — the predicate narrows the capture expression, the same machinery by which `is some` licenses `.`. A capture reading something its predicate doesn't guarantee is a compile error. A refinement whose captures need nothing beyond the base shape's own data (`captured balanceWhenOverdue: Money = balance` on `OverdueInvoice`) can be entered by drift; one whose predicate requires an act-fact can only be entered by that act occurring — the compiler derives which kind each refinement is from its predicate, the human never declares it.

**Visibility and narrowing.** From the base shape, refinement properties are invisible: `invoice.archivedBy` is a compile error unless `invoice` has been narrowed by `is ArchivedInvoice` earlier in the same conjunction (or the corresponding branch of a conditional) — `is <Refinement>` narrows exactly the way `is some` does. A property whose formula reads properties of *two* refinements lives on their intersection, where both are in scope and provably present:

```
shape Reconciled = Quoted and Delivered {
    priceDrift: Money = billedTotal - quotedTotal
}
```

**Membership is unchanged.** A refinement with properties is still a pure predicate as to *membership* — properties change what a member *has*, never when membership *holds*. Captured properties are per-membership memory, state-layer through and through: they retract on exit. If the business cares about past memberships ("who archived it back in March, before it was unarchived?"), that was never a property — it's history, modeled as occurrence facts plus `latest(... by ...)`.

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

**Comparisons:** `==`, `!=`, `<`, `<=`, `>`, `>=`. `==` is the only spelling for value equality — `=` is reserved for shape definition (`shape X = Y where ...`).

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

Ordered by an explicit `Date`/`DateTime` property on the collection's element shape — there is no implicit system timestamp. How that property is identified when the shape has more than one (or none) is not yet settled — see Open/unresolved.

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
selectorCall   := ("latest" | "first") "(" collectionExpr ")"   -- ordered by an explicit Date/DateTime property
                                                                 -- of the element shape
collectionExpr := binding ("," binding)* ("where" predicate)?
binding        := path ("as" Identifier)?

duration       := IntegerLiteral ("seconds"|"minutes"|"hours"|"days"|"weeks")
```

See `example_predicates.md` for the worked derivation of every rule above.

## 11. `rule ... when ... on ...`

A top-level reaction attached to a refinement — replaces `if`/`else` branching and imperative "then do X" sequencing for state-driven behavior. The header separates the rule's *condition* from its *trigger source*, one keyword each:

```
rule <name> [when [leaving] <condition>] [on <trigger>, ...] { <effects> }
```

```
rule SendReceipt when SettledInvoice {
    Receipt for invoice sentOn: now
}
```

`when Refinement` names *what* the rule reacts to — the condition: a refinement, entered (or left — see Exit triggers, below). `on` names the *trigger source*: `on commit` — the rule evaluates as a consequence of any commit that can affect its condition — or a named schedule (`on Daily`, see Schedule triggers, below). `on commit` is the default when the clause is omitted (as above), the same single-well-defined-default category as properties being required unless marked `?`. The echo of BDD's given/when/then is deliberate: *given* the declared shapes, *when* the condition holds, *on* commit or tick, then the effects (`## then`).

Neither clause says *how* detection gets implemented. Whether the underlying mechanism is a check made at write-time, an event stream, a runtime data-structure instantiation, or some mix of these for the same rule is left open by the declaration itself; the only contract that has to hold regardless of mechanism is that the effect happens if and only if the subject is or becomes a member of the refinement, exactly once per newly-satisfying instance — "newly-satisfying" is commit-relative: the commit that makes the condition become true is the occurrence the rule fires for. Picking and implementing the actual detection mechanism is a compiling concern (`## 1. Principles`), not part of what the rule means.

### Rules ground in commits

**A system never does anything on its own** — it only reacts to an external event, a commit; a scheduled tick is just another commit, whose changed datum is `today` (`## Schedule triggers`). Every rule firing therefore traces to a commit — but `on commit` names no *particular* commits, and the condition can be a drift refinement (`Delinquent = Account where balance < 0`) whose data no act shape mentions. Which commits can fire it? The author never says — and doesn't have to, because **drift is always commit-mediated**: `balance < 0` becomes true only when some commit changes data the predicate reads. The static-path and one-writer requirements (`## Assignment (mutation in place)`) mean the compiler knows every assignment targeting `balance` and every act that feeds it, so the trigger set is derived: the author declares the **what** (`when` — the business condition) and the **source** (`on` — commit, ticks, or both); the compiler computes the **which** (the exact commits) and proves the rule **reachable**. A new writer of `balance` added next year automatically extends the trigger set with no edit to the rule — correct, because the business condition didn't change.

Two compiler obligations fall out. **The unfireable-rule error**: if a rule's trigger set is empty — no commit in the spec, and no tick in its `on` clause, can cause entry into its condition — the rule can never fire, and that's a whole-spec compile error, not a dead-code shrug. The sharp instance is time: `OverdueInvoice = Invoice where due < today` depends on `today`, which no act commit changes — a rule `when OverdueInvoice` with no schedule in its `on` clause observes entry at `Invoice` creation (committed already-overdue) but never entry *by aging*, and the diagnostic says exactly that: "entry into `OverdueInvoice` via the passage of time is unobserved — add a schedule to `on`, or this rule under-fires." **The PO-facing answer to "when does this run?"**: the derived trigger set is impact analysis read backward — forward, "if this commit lands, these rules may fire"; backward, "this rule fires as a consequence of: withdrawals, deposits, the daily tick."

### Entry and exit are commit-local transitions

"Newly-satisfying" is well-defined with no hidden bookkeeping, but only *per commit*: a commit is a discrete moment, and evaluating its consequences means pre-state and post-state are both transiently available. `when Delinquent` means *the commit that made it true* — false before, true after; `when leaving` (`## Exit triggers`) is the same transition reversed. Consequences:

- **Episodes are free at commit granularity.** September's re-entry into `Delinquent` is a new entering commit, so the rule fires again — once per episode, with no guard apparatus, and the outcomes accumulate as history. Guards are what you buy for *durability* (crash recovery) and *cross-tick memory*, not for entry itself (`## Run-once guards`).
- **Bare act triggers are sound.** `when CorrectEmail` fires once per correction commit — the commit is the unit of firing. The rule is never tick-evaluated, so no sweep can re-apply old corrections.
- **A tick is a commit whose changed datum is `today`.** So `on commit, Daily` is one mechanism, not two: an invoice *aging* into `OverdueInvoice` is a commit-local transition over the tick — the same entry semantics as a payment-reversal commit.

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
- **The target is a literal static path — a hard requirement.** Because `customer.email` is statically known, the whole-spec compiler knows every refinement predicate and derived property that reads `email`, and impact analysis falls out for free: which memberships may change, which entry/exit rules may consequently fire, which `forbidden` liens (`## Exit triggers`) could reject the commit. A computed or reflective target would destroy this, so no mutation syntax may ever introduce one.
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

A one-assignment rule stays a rule — there is no collapsed form letting a mutation shape carry its assignment in its own body (the territory earlier drafts' `output` construct was exploring, now retired):

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
    correctedOn: Date
}
```

Nothing here is new mechanism: `EmailCorrection` is a reified act like any other shape, `latest(...)` orders by `correctedOn` (the shape's only `Date` property), and the `exists` check narrows the then-branch so `.corrected` is provably evaluable. Commit means insert, the ledger is append-only, and the stored truth is never touched. The two patterns answer different business realities — "email changed" (in place) versus "email changed and the history matters" (ledger) — and the author picks per field.

## 13. Exit triggers (`when leaving`)

*Tentative in part — mutation policies are being re-derived under the commit-local transition model, and `compensate`'s desugaring is unsettled now that `produces` is retired (see `investigate_state.md`, OQ7).*

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

**Mutation policy on evidence.** The sharper use of `when leaving` is answering what happens to evidence when its premise is later falsified — the rule fired, the effect escaped, and then the instance left the refinement. The rule declares this as a clause:

```
rule SuspendService when Delinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
    when leaving Delinquent: compensate ServiceRestoration
}
```

Three policies, each an answer a Product Owner already gives in the wild:

- **`stands`** — the evidence is history and stays true on its own ("the quote is the quote; prices drift"). No reaction.
- **`forbidden`** — while the evidence exists, any change that would cause the exit is rejected ("you can't edit line items on an issued invoice"). Immutability in Velle is exactly this — not a property of a field, but a lien held by an effect that witnessed it, acquired when the evidence is produced and lifted if it's compensated away.
- **`compensate X`** — the exit produces a compensating fact ("invoices are never edited — voided and reissued"). Conceptually a dedicated `when leaving` rule that fires only for instances whose evidence exists and creates `X` scoped to that evidence; whether that survives as sugar (re-desugaring to a canonical-guarded exit rule) or falls to the no-sugar reasoning (`## Run-once guards`) is unsettled. Evidence scoping settles the edge cases: a membership too brief for the entry rule to fire has no evidence, so its exit compensates nothing; repeated exits are guarded per compensated evidence — granularity is the guard predicate's content, as always.

Deleting evidence is never one of the options — a produced fact records something that happened in the world (the email was sent), and deleting the record makes the description lie. Which policy applies when none is declared — default `stands`, or a compile error that forces the question — is unsettled; see Open / unresolved.

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

Creating an instance is an ordinary body statement, nothing more — no header annotation is involved (the retired `produces`, `## Run-once guards`). When the created shape serves as a guard witness, the connection to the trigger is the disarm proof's job, checked rather than spelled. `for` as a *query* expression (`(NurseVerification for this).nurse`, `exists Shape for expr`) is unaffected by this — see `## Predicate expressions`, above.

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

`then` and `from` don't compete: `then` orders *statements*, `from` is the *form* of one statement. There is no one-effect header collapse — a rule whose body is a single effect is simply a one-statement body (`## Assignment (mutation in place)`'s no-sugar stance extends here). What moments `then`'s intermediate states occupy relative to commit boundaries is part of the open definition of a commit (`investigate_state.md`, OQ6).

## 16. `each`

*Tentative in part — whether the disarm proof extends per iterated instance is under investigation (see `investigate_state.md`, OQ13).*

Applies a rule's effects across every member of a refined collection — the form every schedule-triggered rule takes, since a tick carries no subject of its own:

```
rule SuspendDelinquents on Nightly {
    each (Delinquent where not suspended) {
        this.suspended = true
    }
}
```

No separate loop construct — `each` quantifies over current state. When a sweep must not re-handle a member, the guard predicate lives inside the selector (`where not suspended`, above; `## Run-once guards`), so the run-once obligation is per iterated instance, not per rule firing.

## 17. Schedule triggers (`on <schedule>`)

A rule can be triggered by a named schedule instead of (or in addition to) `on commit`, by naming the schedule in the header's `on` clause:

```
rule RemindOverdue on Daily {
    each (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days)) {
        Reminder from { invoice: this, sentOn: today }
    }
}
```

`on` accepts a comma-separated list (`on Daily, Hourly`) for a rule that needs to run on more than one cadence — including mixed with commit (`on commit, Daily`: fire the moment the condition is entered, *and* re-check on the cadence). `Daily` is a placeholder name, not a built-in or sugar for a specific interval — what actually defines a schedule (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be provided by some cron-like scheduling framework. Only the rule-side trigger syntax is settled.

A schedule trigger is the *only* way a purely time-dependent refinement (like `OverdueInvoice`, which depends on `today`) gets re-checked — nothing in Velle executes purely on the passage of time by default. A scheduled tick is conceptually a commit like any other (the same category as a `Payment` arriving or a `ChargeResponse` coming back — a tick is a commit whose changed datum is `today`), but it's referenced by name in `on`, not declared inline as a custom shape the way earlier drafts of this doc did.

**The tick law: cross-tick memory must be data.** A tick-triggered rule sees only current state — "what changed since last tick" would require persistent memory between ticks, and memory must be data. Sweeps carry their memory as witnesses, flags, or evidence: the example's "at most every 3 days" lives in the `Reminder` evidence itself, with no hidden last-run timestamp anywhere.

**Transient membership is a policy, stated in the header.** An account goes negative Monday 09:00 and recovers at 17:00. Under `when Delinquent on commit`, suspension fires at 09:00 and restoration at 17:00 — the blip was real, service was off for eight hours. Under an `on Nightly` sweep, the check sees a positive balance — the blip never mattered. Neither is wrong: commit-triggered observes every membership the commit stream produces; tick-triggered observes what persists to the tick. The choice is business-visible in one clause, answered per rule by the author rather than globally by the language.

## 18. Run-once guards

Commit-triggered rules already fire exactly once per commit (`## rule`), so an ordinary rule needs no guard. A guard earns its place in exactly two situations: **durability** — the firing must survive a crash and be provable afterward — and **cross-tick memory** — a tick rule needs "already handled" as data (`## Schedule triggers`). The canonical form — and the only form; there is no guard sugar (below) — is a named refinement whose predicate the rule's own body falsifies:

```
shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit on commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

`on commit` gives latency; `on Hourly` is a reconciliation backstop that catches anything a crash dropped — safe to add precisely because the guard makes re-evaluation harmless. One line reads: *immediately, and self-healing hourly*.

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

rule ApplyDeposit when UnappliedDeposit {
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

### `produces` is retired

Earlier drafts guarded a rule with a header keyword (`rule SendReceipt when SettledInvoice produces Receipt`). It read like a *data* concept but behaved like a *guard*, and both jobs are now done by settled machinery. Its guard job — a compiler-derived implicit "hasn't happened yet" gate — is exactly what "No guard sugar" rejects: a header form whose correlation is *assumed* (field-type matching, `for <field>` as the patch when the guess was wrong); the granularity trap it carried — a witness silently keyed to the flag vs. the customer — is the hidden-join problem by name. Its data job — the rule creates an instance — is an ordinary body statement (`## for`). A checked header summary of a rule's outputs is a *derived* fact, and derived facts aren't declared — tooling can display what a rule produces; syntax shouldn't demand it. Singularity licensing for `for`-queries comes from *any* guard establishing at-most-one — a whole-spec proof ("Episodes as data", `## State-change patterns`) — not from a keyword. Exactly-once external effects are unchanged: an API call is an outcome commit whose only db-visible trace is its witness — which is *why* the witness must exist.

Guard soundness assumes the mutation and its witness enter the state together — atomicity, part of the open definition of a commit (`investigate_state.md`, OQ6).

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
rule RecalculateRiskScore on Nightly {
    each Account {
        this.riskScore = <formula over payments, balance, history>
    }
}
```

Where a fold is expressible as a recompute, the incremental spelling (`rating = (rating * n + stars) / (n + 1)`) and the recompute spelling are the same description with different *execution strategies* — and incremental maintenance is what `## Principles` assigns to compilation, not description. Both spellings stay legal (flexible, not restrictive); surfacing the twin as guidance is rung recognition's advisory job (`## State-change patterns`).

**3. Self-referential — a denormalized fold.** The RHS reads the target's own current value, so the next value is a function of the previous one (`value′ = f(value, commit data)`): the stored field denormalizes a *fold over the commit sequence*. `balance = balance + amount` is only the simplest instance — streaks, peaks, and moving averages are all folds.

**The compiler never determines the algebra of `f` — it doesn't need to.** Two decidable facts replace it:

- **Self-reference is syntactic.** The RHS reads the field it assigns — reliably decidable; this is what triggers the analysis.
- **Exposure is structural, per hazard.** *Duplication exposure*: an unguarded fold can be applied twice for one commit (the crash/replay window, and trivially under a `, Hourly` backstop). A fold gated on a dischargeable state cannot. *Reordering exposure*: a commit-cadence rule folding one act at a time cannot reorder — commits are serialized, so fold order *is* commit order; a tick-cadence rule batch-folding several pending items in one firing has no defined iteration order.
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
4. **`tolerates <hazard>`** — accept the named risk, declared on the field:

```
shape Article {
    viewCount: Number tolerates duplication
}
```

The vocabulary is closed and mirrors the hazards one-for-one — `tolerates duplication`, `tolerates reordering` — so checking is mechanical set-coverage: exposed hazards, minus those discharged by a guard, an ordering, a reconciliation, or a tolerance, must be empty. A dead tolerance is itself a diagnostic ("this rule cannot experience reordering — did you mean duplication?"): the author never needs the exposure model in their head; the compiler names the specific risk, and the author fixes it or signs it by name. The declaration is self-vetting because it *is* the business claim — `balance: Money tolerates duplication` is visibly absurd in exactly the way that forces the author to confront what they're signing.

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

**Residual gap.** A *batched* order-dependent fold (a nightly streak sweep) is exposed to reordering with no honest discharge yet: no clause gives a batch an iteration order, and the derivation grammar has no ordered folds — declared tolerance is currently the only spelling, which is wrong for a streak. Until one exists, commit-cadence is the only fully-served spelling for order-dependent folds (`investigate_state.md`, OQ15).

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

rule SuspendDelinquents on Nightly {
    each (Delinquent where not suspended) {
        this.suspended = true
    }
}

rule RestoreService on Nightly {
    each (Account where suspended and balance >= 0) {
        this.suspended = false
    }
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

### The compiler recognizes the rungs

The spectrum is not just documentation — the compiler classifies which rung each rule sits on and validates it accordingly. The classification is *derived* from what's written, never declared. Much of the machinery is already settled elsewhere: the RHS three-class split is the rung classification for assignments (`## Self-referential folds and tolerates`), obligations arise only where non-idempotence demands one, and a reconciliation sweep is recognized as a discharge. Recognition adds on top:

- **Rung-specific proofs.** A sweep serving as reconciliation must be provably convergent and idempotent — re-running it against unchanged data changes nothing. A latch pair (set-rule / reset-sweep) must actually cover both directions, or the flag drifts from the condition it mirrors.
- **Advisory, not policing.** "This stored flag could have been a pure classification — no rule needed" is guidance the author may ignore; the *required* diagnostics remain only the fail-closed ones (one-writer, disarm, fold obligations).
- **Classification feeds the analyses.** Impact analysis and `why` can name the rung — "this value is a nightly snapshot," "this flag is reconciled, drift bounded by the sweep cadence" — so a reader learns the design intent from the tooling, not from convention.

How this behaves against realistic specs is untested — the classification boundaries and the advisory/required line are expected to be calibrated when realistic Velle examples get written; the principle is settled, the calibration isn't.

## 21. Open / unresolved

- **State change & rule mechanics — remaining frontier.** The core is settled — assignment and one-writer (§12), run-once guards with no sugar and `produces` retired (§18), folds and `tolerates` (§19), the pattern spectrum and rung recognition (§20). Still open in `investigate_state.md`: marking shapes as external input, act identity, and commit-metadata readability — `createdAt`/`updatedAt` are commit metadata, readable never writable, spelling unexplored (OQ5); what exactly one commit is — atomicity of a firing, cascades, `then`'s intermediate moments — the load-bearing question one-writer, guard soundness, and derived trigger sets all lean on (OQ6; cascades and transaction boundaries now have their own investigation, `investigate_transactions.md`, OQ16–20); remaining rule-anatomy threads including re-deriving §13's mutation policies under commit-local transitions (OQ7); the `each`/multi-schedule disarm-proof pass (OQ13); whether the canonical guard form is pleasant enough to be what fold diagnostics ask authors to write (OQ14); ordered folds and batch ordering (OQ15). §§13 and 16 carry tentative markers accordingly.
- **Mapping** (shape-to-shape translation, e.g. API DTO → domain shape) — part of the original design goals, not yet exercised in a worked example.
- **Schedule definition** — `on Daily` (the header's `on` clause) settles how a rule *references* a schedule; what actually defines `Daily` (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be a cron-like scheduling framework.
- **Reversal** — resolved as a non-issue for the language itself (it's a business-policy choice, expressed via which artifact shapes a human declares — see `example_invoice_payment.md` #5), but no single canonical pattern has been adopted yet; the consolidated top-of-file example still doesn't reflect a chosen policy.
- **Exit-trigger loose ends** — whether an undeclared mutation policy on evidence defaults to `stands` or is a compile error; and `compensate`'s form now that `produces` is retired — it must either re-desugar to a canonical-guarded exit rule (guarding on the evidence's existence, naming the matched evidence instance inside the compensating mapping) or fall to the no-sugar reasoning (§18). The transient-membership question is answered: it's a per-rule policy, visible in the header's `on` clause (§17). See `investigate_state.md`, OQ7.
- **Escape hatch / override syntax** — how a human marks part of a spec as intentionally hand-implemented/AI-implemented rather than declarative. Deferred; agreed to be a lesser concern until the core language settles.
- **Compiled guardrails** — the idea that the compiler/transpiler should structurally enforce best practices (e.g. forced prepared statements, automatic error-context capture, correctly evaluating self-referential shape/derived-property definitions, how deep narrowing analysis for `.`-vs-`?.` sees through nested expressions, erroring — not silently resolving — a bare unqualified name that doesn't exist in its innermost scope but would resolve unambiguously in exactly one enclosing scope, per `## Principles`'s compiling-as-validation rule: the fix is always an explicit `this.field`, never an inferred scope-walk that could silently start pointing elsewhere the moment an enclosing shape gains a same-named field; a field addition that creates a new type-match ambiguity for an existing bare `for` reference elsewhere in the spec, per §14, must be reported as one connected diagnostic naming both the declaration that introduced the ambiguity — e.g. `Referral` gaining a second `Customer`-typed field — and every reference it now makes ambiguous — e.g. `CustomerWhoReferred`'s `for this` — since the compiler's job is reporting an incoherence in the spec as a whole, not a syntax error in one isolated line, and a human should never have to search for why an untouched line stopped compiling) as a byproduct of codegen. A design principle, not yet a syntax construct.
- **`latest`/`first` ordering property** — selectors order by an explicit `Date`/`DateTime` property, not an implicit creation timestamp; how that property is identified is undecided. Likely the same pattern as `for` field-ambiguity: bare `latest(...)` legal only when the element shape has exactly one date property, an explicit form (e.g. `latest(payments by receivedOn)`) required otherwise — but no syntax is settled.
- **Exit from act-entered refinements** — a membership predicate of the form `exists ArchiveRequest for this` is monotone: facts persist, so nothing can ever leave `ArchivedInvoice`, and un-archiving is inexpressible. Exit requires either pairing occurrences in the predicate (`... and not exists Unarchival` newer than the matched request — which needs occurrence ordering/scoping vocabulary not yet designed) or a mutable field plus a declared mutation policy (`## Exit triggers`). Both are expressible; which is idiomatic is unsettled, and ties into occurrence reification (`investigate_state.md`).
- **State partition declaration** — refinement properties give states their data (`## Refinements`), reified acts give transitions their payloads, and mutation policies bound which transitions are legal — but nothing yet asserts that a set of refinements *partitions* a shape: mutually exclusive, jointly exhaustive ("an invoice is always in exactly one of Draft, Issued, Paid, Voided"). Candidate spelling `states of Invoice = Draft | Issued | Paid | Voided`, invoking the exhaustiveness/overlap check `## Refinements` already names as a compiler goal. The next investigation.
- **`why` / provenance** — a command to trace which rule/refinement produced a given piece of state, mapped back to Velle source. Agreed as a goal; no syntax proposed yet.
- **Derived-property value-expression grammar** — `## Derived properties`' arithmetic and conditional (`if`/`else`) forms have only ever been used by example, the same gap `## Predicate expressions` closed for boolean predicates. Not yet formalized.
