# 14. `for`

A rule usually creates a record *about* another record: a welcome note for a member. `for` is the short way to say which record the new one is about. This page teaches both forms of creation, how to name the record a statement creates, and how to create one record per element of a collection.

"Every new member gets a welcome note," from `examples/membership/membership.velle`:

```
rule SendWelcome when Member {
    WelcomeNote for this sentOn: now
}
```

`WelcomeNote for this` creates a `WelcomeNote` with the current member in whichever field has type `Member`. Remaining fields follow as `name: value` pairs.

`for` matches a field by **type**. A static-union field matches any operand type ([§6](Reference-06-Relationships)). When two fields match, name the field: `Referral for referrer: this.referrer`.

The general form is `from`, which lists every field:

```
Referral from {
    referrer: this.referrer
    referee: this.referee
    referredOn: now
}
```

## Naming the record a statement creates (`as`)

A rule that creates two records, one pointing at the other, needs a name for the first. `as` gives it one. The name stands for the created record as a reference, for the other statements of the same body.

"Open an account review, and log an audit entry that names the review it opened," from `examples/membership/membership.velle`:

```
rule OpenAccountReview
    when (ChronicDelinquent where not exists (AccountReview where member == this))
    on commit, Daily {
    AccountReview from { member: this, openedOn: now } as review
    AuditEntry from { member: this, review: review, note: "account review opened", loggedOn: now }
}
```

- `as review` names the record the first statement creates.
- `review: review` in the second statement points the audit entry at it. The name may stand wherever a reference is taken: a field of another creation, or the target of `for`.
- The second statement reads `review`, so it runs after the first. No `then` is needed ([§15](Reference-15-then)). The two records land in one commit.
- The name is the record and nothing more. The record has no fields until the commit lands, so `review.member` is not allowed. Read `this`, or the values the review was built from.

## Creating one record per element (`for each`)

A rule sometimes has to create many records from one: a well for every position on every container a design calls for. `for each` runs the statements inside its braces once per element of a collection, and once per combination when it names several collections. A range `from 1 to n` stands for the whole numbers one through `n`, both included, so a count stored on the record becomes a set to run over.

"When a design is submitted, lay out every container and every well," from `examples/platelayout/platelayout.velle`. A design names a container type with a fixed number of wells, how many containers, how many replicates, and the conditions to test. Each condition takes a block of wells on every container.

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
    wellsPerCondition: integer
} with conditions

shape Condition {
    design: one ExperimentDesign
    block: integer
    value: decimal
}

shape Container {
    design: one ExperimentDesign
    replicate: integer
    position: integer
}

shape Well {
    container: one Container
    position: integer
    condition: one Condition
}

rule LayOut when ExperimentDesign {
    for each replicate from 1 to replicates, position from 1 to containerCount {
        Container from { design: this, replicate: replicate, position: position } as container
        for each condition in conditions, slot from 1 to wellsPerCondition {
            Well from {
                container: container
                condition: condition
                position: (condition.block - 1) * wellsPerCondition + slot
            }
        }
    }
}
```

- `for each replicate from 1 to replicates, position from 1 to containerCount` runs the block once per replicate per position. With two replicates and three containers, that is six times. `from 1 to replicates` is the whole numbers one through `replicates`, both included. When the second bound is below the first, the block runs zero times. A zero-based layout is `from 0 to containerCount - 1`. A range is allowed only here, as a `for each` source.
- `Container from { ... } as container` creates one container each time the outer block runs, and names it.
- `for each condition in conditions, slot from 1 to wellsPerCondition` runs once per condition per slot in its block. `in` names a collection, `from ... to` names a range. `conditions` is the design's own collection. A `for each` may run over any collection the rule can reach: a `many` field, an inferred inverse, a filter such as `(Condition where design == this)`, or a shape name for every record of that shape.
- `container: container` points each well at the container the outer block just created. The name is bound each time the outer block runs, and only the statements inside that block may read it.
- `replicate`, `position`, `condition`, and `slot` are values inside their block. `condition.block` reads the condition's field. `slot` is a number and has no fields. Outside the block the names mean nothing.
- The rule fires once per design. Every container and every well it creates lands in the design's own commit, so a well a `never` refuses refuses the whole design ([§20](Reference-20-State-change-patterns)).

A block holds creations and other blocks. To write one field of every member of a collection, assign through the collection ([§12](Reference-12-Assignment)). To delete every member, write a rule on the member shape that deletes `this` ([§12](Reference-12-Assignment), "Deletion"). A later name in one `for each` may use an earlier one, as in `for each n from 1 to lines, copy from 1 to n`. `then` inside a block orders the block's statements each time it runs ([§15](Reference-15-then)).

## What the compiler checks

- The compiler rejects a `from` block that omits a declared field, other than ones Velle fills itself such as `id` ([checks.md, F4](checks#f4--mapping-totality)).
- The compiler rejects `for` when more than one field matches the value's type ([checks.md, V13](checks#v13--ambiguity-is-a-connected-diagnostic)).
- The compiler rejects an `as` name that is already a name in the rule, a read through the name, the name in an assignment or a `delete`, and two named creations pointing at each other ([checks.md, V33](checks#v33--a-creations-as-name)).
- The compiler rejects a `for each` whose source is not a collection or a range, a range bound that is not a number, a name already in the rule, an assignment or `delete` inside a block, and an `as` name read outside the block that binds it ([checks.md, V34](checks#v34--a-for-each-block)).

## Related pages

- [§10 Predicate expressions](Reference-10-Predicate-expressions): `for` inside a predicate.
- [§15 then](Reference-15-then): ordering statements that do not read each other.
- [§12 Assignment](Reference-12-Assignment): writing one field of every member, and deleting every member.
- [§6 Relationships](Reference-06-Relationships), "A reference supplied by value": the same want on the committer's side.
