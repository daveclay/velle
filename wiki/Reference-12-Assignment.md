# 12. Assignment (mutation in place)

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

## One writer per field, per commit

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

## No act-level sugar

A one-assignment rule stays a rule — there is no collapsed form letting a mutation shape carry its assignment in its own body:

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    customer.email = corrected      -- rejected
}
```

Two reasons. The positional meaning of `=` is load-bearing: an effect in shape-body position would spend the no-collision invariant to save four lines. And the sugar would mostly abbreviate a rarity: the common external-submission case ("save what the user sent") needs no rule at all, because **committing a shape instance is persisting the record** — a rule exists only when an act has a *consequence* beyond its own persistence, and naming that consequence (`rule ApplyEmailCorrection`) is the point, not overhead. (A `transient` act inverts this — its commit persists only its consequences, so it does nothing without a rule, and [V18](checks#v18--transient-totality-every-request-gets-a-response-coarse) makes rule-less transience an error — `## State and commits`, "Transient acts".)

## The ledger alternative

The ledger is a *pattern* the author chooses when history is part of the model — not something Velle forces. Nothing is edited; each change is a new record — an ordinary shape instance — and the "current value" is a derived property selecting the latest one:

```
shape Customer {
    signupEmail: text
    email: text = if exists EmailCorrection for this
                  then latest(EmailCorrection for this by correctedOn).corrected
                  else signupEmail
}

shape EmailCorrection {
    customer: one Customer
    corrected: text
    correctedOn: timestamp on create
}
```

Nothing here is new mechanism: `EmailCorrection` is a reified act like any other shape, `latest(... by correctedOn)` names the shape's declared creation timestamp as its ordering datum (`## Scalars` — named, never implicit), and the `exists` check narrows the then-branch so `.corrected` is provably evaluable. Commit means insert, the ledger is append-only, and the stored truth is never touched. The two patterns answer different business realities — "email changed" (in place) versus "email changed and the history matters" (ledger) — and the author picks per field.

## Deletion (`delete`)

`delete <path>` is the third statement a rule body may contain, beside assignment and creation: it removes one instance from the state at the rule's commit. The target is a literal static to-one path — `delete this`, `delete listing` — never a collection; removing several records is one firing per record, from a rule whose subject is each of them. Like every write, a deletion is statically known, so the compiler sees every deleter of every shape exactly as it sees every writer of every field (one-writer, above), and one instance has at most one deleter per commit. A commit never both writes a field of an instance and deletes it: "change it and also remove it, at once" is not a business sentence, and the combination is refused rather than ordered. Reading the instance in the deleting commit, and creating other records there from what it says, stays legal — the deleting rule is one of its last readers.

Deletion is a mutation whose **legality is derived from what the spec proves using the instance's existence** — not a category the language bans or blesses. [§4](Reference-04-State-and-commits)'s stance that a produced fact is never deleted is an argument about occurrence facts, and it stands: a reified act or a piece of evidence records something that happened. Stored current-state truth is different: assignment already destroys the overwritten value unless the author chose the ledger, and deleting a `Customer` is that same choice one level up. What protects the spec is the set of obligations the compiler derives at every `delete`:

- **Referential completeness.** Every required `one` reference pointing at the deleted instance must be resolved: the referrer is deleted in the same commit, or it declares the reference `? initially required` — required when the referrer is created, absent once its target is gone, with no write anywhere — or the referrer keeps per-field copies of what must survive. Optional and `many` references simply absorb the deletion. There is no transitive cascade: each hop is the author's own visible declaration, the same stance as freeze depth (`## Refinements`, "Frozen fields").
- **Existence spent in proofs.** Where a guard reads `not exists` over the deleted shape, deletion can re-arm it; where a singular selection depends on the shape, deletion can strand an episode; where a `never` consults it, deletion is a state change the prohibition's inductive proof must range over. Each is checked, fail-closed, by the shared disjointness prover: the deleter's scope must provably exclude the instances those proofs depend on, and a disjointness that is true but unprovable is restructured into the predicates, never signed away.
- **The deletion gate.** A state may declare itself `undeletable` (`## Refinements`, "Frozen fields") — deletion permission scoped exactly as write permission.

Deletion is pure removal, with **no deletion memory**: no tombstone, no `is deleted` predicate, no reference that outlives its target. A business that must remember a deletion models the record that remembers it — an ordinary outcome shape the deleting rule creates, copying what must survive — and a business that must react to one uses the deletion-side exit trigger (`## Exit triggers`, "Deletion as a second exit cause"). The worked fixture is `examples/moderation/moderation.velle`:

```
act Seller {
    name: text
}

act Listing {
    title: text
    seller: one Seller? initially required    -- required at creation; absent once the seller is deleted
    isDraft: boolean initially true
}

shape Draft = Listing where isDraft

transient act DiscardDraft {
    listing: one Listing
    reason: text
}

shape DiscardRecord {
    listingTitle: text                        -- copied: the listing will not exist to reference
    reason: text
    discardedOn: timestamp on create
}

rule ApplyDiscard when (DiscardDraft where listing is Draft) {
    DiscardRecord from { listingTitle: listing.title, reason: reason }
    delete listing
}
```

The request is transient, so its durable trace is what was done; the `DiscardRecord` is the explicitly modeled deletion record, and what a deletion leaves behind is always the author's call.
