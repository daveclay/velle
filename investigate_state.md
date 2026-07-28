# State Changes

Velle needs a way for product owners to describe how state changes occur in the system.

## Philosophy

Any system can be considered a black box with its persistent store as a single state. From Velle's perspective, mutations are committed to change this state, and the truth of the system must be consistent.

React is a similar analogy/approach with mutations committing to a single state tree. What I'm taking from that is the idea of a mutation being an arbitrary shape, and thinking of "commit" as an agnostic term for all underlying CRUD operations. The "state" is agnostic to the storage mechanism.

**The substrate is last-in-wins.** Nothing in any computer system provides anything other than last-in-wins; an engineer may design a system where concurrent writes are merged in more sophisticated ways, but always built from fundamental tools where last-in-wins (the database). Velle therefore needs no ordering vocabulary for in-place mutation — a mutated field holds the last value committed.

**Mutating a field in place is allowed.** Velle does not force the ledger pattern (below) onto a model that doesn't need history. The goal instead is that when a field is mutated, Velle can tell the product owner the impact — which refinements may change membership, which rules may consequently fire, which mutation policies could reject the change — and verify that relationships and behaviors stay consistent.

## Mutation in place

A mutation shape is an ordinary shape — nothing marks it as special. (Some way for an author to mark a shape as an "external input" may eventually be wanted, but that can be put off; for now a mutation is just another shape.)

```
shape Customer {
    email: text
}

shape CorrectEmail {
    customer: one Customer
    corrected: text
}
```

A rule is where a mutation's effect is described. The effect itself is an assignment statement:

```
rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}
```

The pieces:

- **`on CorrectEmail`** — a plain-shape trigger, already sanctioned (README §14's `rule InitiateCharge on Order`): the rule fires when an instance is committed.
- **`customer.email = corrected`** — assignment, a new effect-statement form alongside `X from { ... }` and `X for y ...`. `=` in rule-body position means *becomes, now*; in shape-body position it remains *is defined as, always* (derivation). One symbol, two positions — the same deliberate positional reuse the language already makes with `on` (prefix entry-trigger vs. postfix schedule), `for` (association vs. query), and `?` (optional marker vs. `?.`). The two contexts never collide: rule bodies contain no definitions, shape bodies contain no effects.
- **The target is a literal static path — a hard requirement.** Because `customer.email` is statically known, the whole-spec compiler knows every refinement predicate and derived property that reads `email`, and impact analysis falls out for free: which memberships may change, which entry/exit rules may consequently fire, which `forbidden` liens (README §12) could reject the commit. A computed or reflective target would destroy this, so no solution for mutation syntax may ever introduce one.

### One writer per field, per commit

More than one assignment to the same field of the same referenced shape is a compile error whenever the assignments' triggers can coincide — whenever a single commit could fire both. There is no `then`-ordered escape: sequencing two writes to one field inside a single firing is not a real business statement, and one assignment whose RHS states the whole intent replaces it. Within a single commit's rule firings "last" is undefined; last-in-wins is only real *across* commits, where "last" is a fact of the world.

"Can the triggers coincide?" is answered statically from the declarations, never at runtime: same trigger shape → yes; a refinement and its base (or two overlapping refinements) → yes; unrelated shapes → provably never. If the compiler can't prove two triggers disjoint, it errors — uncertainty fails closed. The machinery is the refinement-overlap check README §7 already names as a compiler goal, applied to trigger shapes. It's a whole-spec check: a second rule assigning `customer.email`, added months later in another file, trips it, reported as one connected diagnostic naming both rules. A spec that compiles has no ambiguous-write configuration reachable at runtime.

Legal — separate acts whose triggers can never coincide fire from different commits, where last-in-wins orders them:

```
shape CorrectEmail  { customer: one Customer, corrected: text }
shape OverrideEmail { customer: one Customer, admin: one Admin, overridden: text }

rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}

rule ApplyEmailOverride on OverrideEmail {
    customer.email = overridden          -- legal: an OverrideEmail is never a CorrectEmail,
                                         -- so these always fire from different commits
}
```

Whichever act is committed last wins — precisely the business reality ("the admin's override stands until the customer corrects it again, and vice versa").

Errors — triggers that can coincide:

```
-- same trigger shape: one CorrectEmail commit fires both
rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}
rule NormalizeEmail on CorrectEmail {
    customer.email = lowercase(corrected)     -- compile error
}
```

```
-- a refinement and its base: a TrustedCorrection IS a CorrectEmail,
-- so one commit fires both rules
shape TrustedCorrection = CorrectEmail where customer is VerifiedCustomer

rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}
rule ApplyTrustedCorrection on TrustedCorrection {
    customer.email = corrected                -- compile error
}
```

## Mutation with ledger design

The ledger is an alternative *pattern* the author chooses when history is part of the model — not something Velle forces. Nothing is ever edited; each change is a new record — an ordinary shape instance — and the "current value" is a derived property that selects the latest one:

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

The ledger case needs no connect-statement at all: the connection *is* the derived property, declared on `Customer` itself. Commit means insert, the ledger is append-only, and the stored truth (`signupEmail`, plus the correction history) is never touched. The two patterns answer different business realities — "email changed" (in place) versus "email changed and the history matters" (ledger) — and the author picks per field.

## Open questions

(OQ1, concurrent assignments to one field, is settled — see "One writer per field, per commit" above. Retired tags are not reused.)

### OQ2. Exactly-once, and what an assignment's RHS may read

README §10's contract is "exactly once per newly-satisfying instance," guaranteed by `produces` evidence — but a mutation rule produces nothing, so nothing witnesses that it ran. There's a clean static split between two kinds of RHS:

```
rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected               -- reads only the act's own data: idempotent
}

rule ApplyDeposit on Deposit {
    account.balance = account.balance + amount   -- reads the state being mutated: NOT idempotent
}
```

The first is harmless to re-fire — exactly-once degrades gracefully to at-least-once. The second applied twice is a double deposit; it is non-idempotent and order-sensitive, and the compiler can tell the two apart statically (does the RHS traverse into mutable stored state, or only into the triggering act?).

Whether the non-idempotent kind is (a) forbidden outright, (b) legal only with a `produces` guard supplying run-once evidence, or (c) legal and the product owner's problem, is undecided. Current instinct: (b) —

```
rule ApplyDeposit on Deposit produces DepositApplication {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

— which makes "not idempotent, so prove it happened once" a structural requirement instead of a bug class.

### OQ3. Assignment targets must be stored fields

A derived property is a computation; a computation can't be assigned:

```
shape Invoice {
    amount: Money
    payments: many Payment
    balance: Money = amount - sum(payments, amount)
}

rule Forgive on ForgivenessGrant {
    invoice.balance = 0        -- compile error: balance is derived
}
```

Assignment writes stored truth; derived properties recompute over it. The correct model commits data the derivation already reads (a `Payment`, a stored `forgiven` field the formula consults). Trivial check, needs stating in the reference.

### OQ4. Act-level sugar for the one-assignment case

`produces X for field from { mapping }` collapses a one-effect rule onto its header (README §14). The same collapse could let a mutation shape carry its single assignment directly — the territory README §17's provisional `output` was exploring — rather than requiring a separately-named rule:

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    customer.email = corrected      -- hypothetical collapsed form; syntax unsettled
}
```

Whether that sugar should exist, what it looks like, and how it stays visually distinct from a derived-property declaration in the same body position (README §7's `captured` faced the same must-read-differently problem) is unexplored.

### OQ5. Marking a shape as "external input"

Mutation shapes arrive from outside the system — a user action, an API call — and nothing in the spec currently says so:

```
shape CorrectEmail {        -- committed by whom? a user? another rule? an API?
    customer: one Customer
    corrected: text
}
```

An author may eventually want to declare the distinction (e.g. only externally-committed shapes cross a trust/validation boundary; a `visible to`-style clause may want to constrain who can commit one). Deferred — for now a mutation is just another shape.

### OQ6. Commit granularity

The one-writer check's "unrelated shapes can never coincide" holds only if a commit is a single act instance — two different acts can't enter the state as one commit. Nothing states this anywhere yet, and README §14's `then` (ordering effects *within* one rule firing) implies observable intermediate moments whose relationship to commit boundaries is also undefined. If multi-instance commits turn out to exist, "can these triggers coincide?" needs a broader definition than trigger-shape overlap. Needs pinning down before the one-writer check can be specified precisely.
