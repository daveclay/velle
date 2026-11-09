# 7. Derived properties

Some values in a system are never entered or set. An invoice's balance is its total minus what has been paid. A member's founding referrer is the first person up the referral chain with no referrer of their own. A derived property is how Velle expresses such a value: a formula over other data, computed from current records every time it is read, and never stored. This page shows how to declare one, how to name a walk along a relationship when the value lies several records away, and what the compiler checks.

## A formula over the record's data

A product owner says "the balance is what is owed less what has been paid". Nobody types the balance in, and no rule updates it. It is a fact that follows from other facts.

From `examples/billing/billing.velle`:

```
balance: decimal = total - sum(payments, amount)
```

- `= expression` after the type makes the property derived. Compare `applied: boolean initially false`, which stores a starting value a rule may later change ([§5](Reference-05-Scalars)).
- The value is recomputed from current data on every read. It is never stale and never cached.
- A rule cannot assign a derived property, and a committer never supplies one. On an act, a derived property is allowed but is not part of the generated input ([checks.md, V21](checks#v21--act-field-forms)).
- Because derived values track the data, a record can enter or leave a [refinement](Reference-08-Refinements) such as `Invoice where balance <= 0` without any rule acting.

A derived property may also be a collection, written as a filtered set: `recruits: many Member = (Member where referrer == this)` names the members who name this one as their referrer ([§6, "Renamed and derived collections"](Reference-06-Relationships#renamed-and-derived-collections)).

## Walks: a value found along a relationship

Some values are not on the record, and not one link away, but somewhere along a chain of records of the same kind. A member's referrer has a referrer of their own. A batch was mixed from batches that were themselves mixed from others. The business sentence is "everyone above me", "every batch behind this one", or "the first one up the chain that has a location". A **walk** is how Velle says it. You name the relationship, and Velle follows it from the record, step after step, visiting each record once. Because each record is visited once, a walk ends over any data, including data that loops back on itself, and there is nothing for you to prove.

A relationship name on its own is one step and gives the flat list. `walk` in front of the name is every step.

From `examples/loyalty/loyalty.velle`:

```
shape Member {
    name: text
    referrer: one Member?
    tier: text initially "basic"

    upline: many Member = walk referrer
    foundingReferrer: one Member? = walk referrer until referrer is none
    recruits: many Member = (Member where referrer == this)
    downline: many Member = walk recruits
    closeDownline: many Member = walk recruits for 2 steps
}
```

- `walk referrer` is every member reached by following `referrer` one or more times. The member themself is not in it. Its type is `many Member`: a collection like any other, so `count(upline)`, `(upline where tier == "gold")`, and a membership test with `in` all apply.
- `walk referrer until referrer is none` is the nearest-match form. Velle checks the condition at the member themself first, then at each member up the chain, and answers the first at which it holds. A founder has no referrer, so a founder is their own founding referrer. When the condition holds nowhere, the value is `none`, so the type is `one Member?`. Inside the condition, bare names and `this` name the record being looked at.
- `recruits` is an ordinary derived collection, one step down. `walk recruits` follows it every step. The relationship a walk follows may be stored or derived, `one` or `many`.
- `walk recruits for 2 steps` is the same set cut off after two steps: recruits and their recruits. The number is a business fact and part of the meaning.

To walk only some of a relationship's records, declare the filtered collection as a derived property and walk that. To keep only some of the records a walk reaches, filter its set: `(walk recruits where tier == "gold")`.

A walk's set may be read by a prohibition. From `examples/batches/batches.velle`, where a batch may be mixed from other batches:

```
    drawnFrom: many PeptideBatch = (PeptideBatch where exists (components where composition == this.origin))
    lineage: many PeptideBatch = walk drawnFrom
```

```
never (PeptideBatch where this in lineage) tolerates contention
```

- `drawnFrom` is the batches one step behind this one: those with a component in this batch's composition.
- `lineage` is every batch behind those, at any remove.
- The prohibition says a batch is never in its own lineage. `in` with a collection of records on the right asks whether the record on the left is one of them ([§10](Reference-10-Predicate-expressions)).
- A walk reads every record it can reach, so nothing ties the prohibition's read to one key and every commit on the shape takes turns. `tolerates contention` says the business accepts that ([§19, "A read over a whole shape"](Reference-19-Self-referential-folds-and-tolerates#a-read-over-a-whole-shape-tolerates-contention)).

A formula may also read the same property on a related record one link away, as `previous.streakAfter` does in [§19](Reference-19-Self-referential-folds-and-tolerates). The compiler accepts that only when it can see the chain ends. A walk is the spelling that needs no such proof.

## What the compiler checks

- The compiler rejects a walk whose relationship is not a member of the shape the walk is written on ([checks.md, F1](checks#f1--name-resolution-and-casing)), or is not a relationship that points back at that shape ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects `walk R until P` when `R` is a `many`. Two records at the same distance may both satisfy the condition, and "the nearest" has no single answer. The message says to walk a `one`, or to declare the set with `walk R` and select from it with `first(... by ...)` ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects `for 0 steps`, and warns on `for 1 steps`, which is the relationship itself ([checks.md, F2](checks#f2--type-checking); [A2](checks#a2--dead-machinery)).
- The compiler rejects a walk property declared with a type other than the walk's: `many S` for a set, `one S?` for `until` ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects `x in records` when `x` is a value rather than a record, or a record of another shape ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects a `never` that reads a field a rule assigns, through a walk as through any other read ([checks.md, V10](checks#v10--never-obligations-and-spend-tracking)). A prohibition over a walk is for a relationship that is fixed when the record is created.
- The compiler rejects a derived property defined in terms of itself on a related record unless the chain provably ends ([checks.md, V14](checks#v14--well-foundedness-coarse)). The message names the walk as the fix.
- The compiler warns when a prohibition or a rule reads a walk without `tolerates contention` ([checks.md, A5](checks#a5--contention-width)).

## Related pages

- [§6 Relationships](Reference-06-Relationships), for the relationships a walk follows and derived collections such as `recruits`.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for what a formula and a walk's condition may contain.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), for a running value each record derives from its predecessor's, and for `tolerates contention`.
- [§21 `never`](Reference-21-never), for the prohibition that reads a walk.
