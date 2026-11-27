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
    AuditEntry from { member: this, review: review, note: "account review opened for ${name}", loggedOn: now }
}
```

- `as review` names the record the first statement creates.
- `review: review` in the second statement points the audit entry at it. The name may stand wherever a reference is taken: a field of another creation, or the target of `for`.
- The second statement reads `review`, so it runs after the first. No `then` is needed ([§15](Reference-15-then)). The two records land in one commit.
- The name is the record and nothing more. The record has no fields until the commit lands, so `review.member` is not allowed. Read `this`, or the values the review was built from.

## Creating one record per element (`for each`)

A rule sometimes has to create many records from one: a well for every position on every container a design calls for. `for each` runs the statements inside its braces once per element of a collection, and once per combination when it names several collections. A range `from 1 to n` stands for the whole numbers one through `n`, both included, so a count stored on the record becomes a set to run over.

The name carries what its collection's filter shows about each element, as a refinement's `where` shows `this` ([§10](Reference-10-Predicate-expressions#reaching-through-relationships)). Inside `for each r in (rows where newKey is some) { Made from { key: r.newKey } }`, `r.newKey` is a text, not a text that may be absent, so it fills the required `key`; the same holds for the `where` of a refinement the name runs over, `for each r in (Keyed where upload == this)` with `shape Keyed = Row where newKey is some`. The facts end with the closing brace. A test the filter writes on `this`, the rule's subject, says nothing about the element.

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
- `for each condition in conditions, slot from 1 to wellsPerCondition` runs once per condition per slot in its block. `in` names a collection, `from ... to` names a range. `conditions` is the design's own collection. A `for each` may run over any collection the rule can reach: a `many` field of records or of values ([§5](Reference-05-Scalars#collections-of-values-many)), an inferred inverse, a filter such as `(Condition where design == this)`, or a shape name for every record of that shape. Over values that tolerate duplication, the block runs in the kept order.
- `container: container` points each well at the container the outer block just created. The name is bound each time the outer block runs, and only the statements inside that block may read it.
- `replicate`, `position`, `condition`, and `slot` are values inside their block. `condition.block` reads the condition's field. `slot` is a number and has no fields. Outside the block the names mean nothing.
- The rule fires once per design. Every container and every well it creates lands in the design's own commit, so a well a `never` refuses refuses the whole design ([§20](Reference-20-State-change-patterns)).

A block holds creations, other blocks, and assignments through its own name (the next section). To write one field of every member of a collection with one value, the assignment through the collection in the rule is shorter: `items.n = 1` writes `n` on every record in `items` ([§6](Reference-06-Relationships#committing-and-assigning-collections)). To delete every member, write a rule on the member shape that deletes `this` ([§12](Reference-12-Assignment), "Deletion"). A later name in one `for each` may use an earlier one, as in `for each n from 1 to lines, copy from 1 to n`. `then` inside a block orders the block's statements each time it runs ([§15](Reference-15-then)).

## Writing a field of each element

Inside a `for each` block, an assignment through the block's own name writes one field of each element, and its value may read that element. Use it when each record gets a value worked out from that record, or when a filter chooses which records to write.

"Each time a member asks to renew, count one more renewal on each of their late loans, and flag it," from `compiler/src/test/kotlin/velle/ForEachAssignmentTest.kt`:

```
act Member { name: text }

act Loan {
    member: one Member
    dueOn: Date
    renewals: integer initially 0
    flagged: boolean initially false
}

transient act Renew { member: one Member }

rule Extend when Renew {
    for each loan in (member.loans where dueOn < today) {
        loan.renewals = loan.renewals + 1
        loan.flagged = true
    }
}
```

- `for each loan in (member.loans where dueOn < today)` runs the block once for each of the member's loans that is late.
- `loan.renewals = loan.renewals + 1` writes each late loan's own count, one more than it was. A loan that is not late is not written.
- `loan.flagged = true` flags each late loan.
- The filter and every value are read as the state stood before the rule wrote anything. A block over `(member.loans where not flagged)` that sets `loan.flagged = true` writes each unflagged loan once.
- Every write lands in the request's own commit.

A block inside another block may assign too, when each inner record belongs to one outer record. "Label every book with the name of its shelf," from `compiler/src/test/kotlin/velle/ForEachAssignmentTest.kt`:

```
act Library { name: text }
act Shelf { library: one Library, name: text }
act Book { shelf: one Shelf, label: text initially "" }
transient act Relabel { library: one Library }

rule LabelBooks when Relabel {
    for each shelf in library.shelfs {
        for each book in shelf.books {
            book.label = shelf.name
        }
    }
}
```

- `shelf.books` is the inferred inverse of `Book.shelf` ([§6](Reference-06-Relationships)): the books whose `shelf` is this shelf. A book has one shelf, so the inner block meets each book once.
- `book.label = shelf.name` writes each book's label. The inner block may run over a filter of `shelf.books` as well.

An assignment in a block is accepted when no record can be written twice by it:

- The left side is one stored field of the name of the block the assignment stands in, `loan.renewals`. A field of an outer block's name, of the rule's subject, or of a record reached through the name, `loan.member.name`, is written in the rule or in a block of its own.
- That block and every block around it has one name and runs over records. A block with two names, or over a range or values, meets a record more than once.
- A block inside another runs over the records that point at the outer name through a `one` field, as `shelf.books` does, filtered or not. A declared `many`, a derived collection, or a collection that does not start from the outer name can hold one record under two outer records.
- A field written in a block is written nowhere else in the same rule.

## What the compiler checks

- The compiler rejects a `from` block that omits a declared field, other than ones Velle fills itself such as `id` ([checks.md, F4](checks#f4--mapping-totality)).
- The compiler rejects a `from` block that gives a field a value of another type than the field's, `effective: "yes"` for a `boolean`, and one that gives a required field a value that may be absent by declaration, an optional field of the subject not tested with `is some`; a selection that matches nothing is the runtime's, below ([checks.md, F2](checks#f2--type-checking)).
- The compiler rejects `for` when more than one field matches the value's type ([checks.md, V13](checks#v13--ambiguity-is-a-connected-diagnostic)). A field typed with a union ([§9](Reference-09-Composing-refinements)) matches a record of any operand, so a singular reference `(Comment for this)` beside `target: one Content` and `post: one Post` is refused the same way, naming both fields.
- The compiler rejects an `as` name that is already a name in the rule, a read through the name, the name in an assignment or a `delete`, and two named creations pointing at each other ([checks.md, V33](checks#v33--a-creations-as-name)).
- The compiler rejects a `for each` whose source is not a collection or a range, a range bound that is not a number, a name already in the rule, a `delete` inside a block, an assignment inside a block that could write one record twice, and an `as` name read outside the block that binds it ([checks.md, V34](checks#v34--a-for-each-block)). The message names which of the conditions above the assignment breaks.
- The compiler rejects a rule that writes one field both in a block and elsewhere in its body, and two rules that write the same field and could fire in one commit, as it does for any assignment ([checks.md, V1](checks#v1--one-writer-disjointness)).

## A required field given an absent value

A `from` block may fill a field with a selection, `entry: first(Entry where key == this.entryKey by key)`, with a filter over a key, `entry: (Entry where key == this.entryKey)` when `Entry.key` is declared `unique` ([§10](Reference-10-Predicate-expressions); [§21, "Keys"](Reference-21-never#keys)), or with the one related record, `flag: (OpenDelinquencyFlag for this)` ([§10, "Reading the one related record"](Reference-10-Predicate-expressions#reading-the-one-related-record)). When the selection matches nothing its value is absent, and a required field cannot hold an absence. The firing then fails: the commit it belongs to is undone and the act comes back refused, with a reason naming the rule and the field, as when a selection does not single out a winner ([§10](Reference-10-Predicate-expressions)). Keep the case out at the door with a `never` on the act ([§21](Reference-21-never)). The worked spelling is [patterns, "An act that names a record by its values"](patterns#an-act-that-names-a-record-by-its-values), which also shows how to refuse the case in the spec's own words: the rule fires only when the selection matches, and a `never` forbids what is left over.

A field the `from` block leaves to its `initially` ([§5, "Starting values"](Reference-05-Scalars#starting-values-initially)) is held to the same rule. The compiler rejects a required `initially` that reads through a selection no test shows to match. If a starting value is absent for a required field all the same, the firing that creates the record fails, with a reason naming the rule, the field, and the `initially`.

A field declared with `?`, read off the selected record, is a different case. `label: first(upload.rows by key).newKey` with `newKey: text?` may be absent even when a row matched, so the compiler rejects it for a required `label`, as it rejects any optional value there ([§10, "A selection that may match nothing"](Reference-10-Predicate-expressions#a-selection-that-may-match-nothing)).

A rule that fires `after commit` or on a schedule ([§17](Reference-17-Schedule-triggers)) runs after the act's commit has been kept. A selection that matches nothing there refuses nothing: the record stays and the firing fails. The compiler warns when such a rule fills a required field with a selection that its condition does not show to match, and names the test to add to the condition, `exists (Entry where key == this.entryKey)` ([checks.md, A9](checks#a9--a-selection-that-may-miss-outside-the-acts-transaction)).

## Related pages

- [§10 Predicate expressions](Reference-10-Predicate-expressions): `for` inside a predicate.
- [§15 then](Reference-15-then): ordering statements that do not read each other.
- [§12 Assignment](Reference-12-Assignment): writing one field of every member, and deleting every member.
- [§6 Relationships](Reference-06-Relationships), "A reference supplied by value": the same want on the committer's side.
