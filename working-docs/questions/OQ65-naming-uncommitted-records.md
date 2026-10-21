# OQ65 — A name for a record that has no `id` yet

**Status:** open (2026-10-20) — split out of OQ63 §6.2; settle after OQ63's 6.1 is built
**In plain terms:** a record gets its `id` when its commit lands. Until then nothing can point at it, because a reference is an `id`. That blocks two things. A rule cannot create a record and then create a second record that points at the first. A client cannot send a request in which one new record points at another new record in the same request, unless the second sits inside the first (OQ63's forward field). Both need the same thing: a local name that stands for a record before it exists, accepted wherever a reference is.
**See:** OQ63 §5 and §6 (the forward field closes the nested case without a name; this is the rest) · OQ64 §3 and §5 (the well-to-condition pointer; Candidate A) · Reference §6 ("Inline part creation": parts form a tree, the rule this relaxes) · Reference §14 (`from`, `for`) · Reference §15 (`then`; statements unordered unless one depends on another's result) · `examples/membership/membership.velle` (`OpenAccountReview`, the two-record rule the first example extends)

---

## 1. In a rule: `as <name>` on a creation statement

### 1.1 Two linked records, no loop

The business, from `examples/membership/membership.velle`: a member with three delinquency flags gets an account review, and the audit log records that the review was opened. Today the rule creates both, and the audit entry cannot say which review it is about, because the review has no `id` when the entry is written:

```
shape AuditEntry {
    member: one Member
    note: text
    loggedOn: DateTime
}

shape AccountReview {
    member: one Member
    openedOn: DateTime
}

rule OpenAccountReview
    when (ChronicDelinquent where not exists (AccountReview where member == this))
    on commit, Daily {
    AuditEntry from { member: this, note: "account review opened", loggedOn: now }
    then
    AccountReview from { member: this, openedOn: now }
}
```

With the name, the audit entry points at the review:

```
shape AuditEntry {
    member: one Member
    review: one AccountReview?
    note: text
    loggedOn: DateTime
}

rule OpenAccountReview
    when (ChronicDelinquent where not exists (AccountReview where member == this))
    on commit, Daily {
    AccountReview from { member: this, openedOn: now } as review
    AuditEntry from { member: this, review: review, note: "account review opened", loggedOn: now }
}
```

- `as review` names the record the statement creates. Inside the rule body, `review` is a value of type `AccountReview`, usable wherever a reference is.
- The `then` is gone. The audit entry reads `review`, so it depends on the review's statement, and Velle already orders statements by what they read (Reference §15). The name is a data dependency, nothing more.
- Both records are entrants of the one transaction, as today.

### 1.2 Nested creation: OQ64's Candidate A

The plate layout from OQ64 §5. A rule creates each container, then that container's wells, and the wells point at the container:

```
rule LayOut when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containers {
        Container from {
            design: this
            replicate: replicate
            position: position
        } as container
        for each condition in conditions, slot in 1..wellsPerBlock {
            Well from {
                container: container
                condition: condition
                position: (condition.block - 1) * wellsPerBlock + slot
            }
        }
    }
}
```

`container` is bound once per iteration of the outer block and read by every well created in the inner block. The `for each` block itself is OQ64's; this question supplies only the name.

### 1.3 What the compiler checks

- The compiler rejects a name that is already in scope: a field of `this`, a `for each` binding, or another `as` in an enclosing block.
- The compiler rejects a read of a name outside the block that declared it.
- A name read by another statement orders the two, as any read does (Reference §15). A cycle (`A ... as a` reading `b`, `B ... as b` reading `a`) is rejected: no record can be created first.
- One-writer (V1) is unchanged: a name is a reference to a new record, never a write to an existing one.

## 2. In a client request: a label on a nested value

### 2.1 The case

OQ64 §3's client-expanded closure. The design arrives with its conditions and with its containers, each container with its wells, and every well names its condition. The conditions and the containers are sibling branches of the closure, so a well's `condition` points across branches, which Reference §6 rejects today ("Parts form a tree"):

```
act ExperimentDesign {
    containerType: one ContainerType
    containers: integer
    replicates: integer
} with { conditions, containers with { wells } }

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
    condition: one Condition        -- a sibling branch of the same closure
}
```

Nothing new is written in Velle. The compiler can see from the `with` tree that `Well.condition` targets a shape that arrives elsewhere in the same closure, and that is what makes the field label-able.

### 2.2 The generated input

A nested value may carry a label, and a field that may point across branches accepts a label in place of a handle:

```kotlin
ExperimentDesignInput(
    containerType = containerTypeRef,
    containers = 1,
    replicates = 1,
    conditions = listOf(
        ConditionInput(label = ConditionLabel("low"),  block = 1, value = 0.1),
        ConditionInput(label = ConditionLabel("high"), block = 2, value = 1.0),
    ),
    containers = listOf(
        ContainerInput(replicate = 1, position = 1, wells = listOf(
            WellInput(position = 1, condition = ConditionArrival.Labelled(ConditionLabel("low"))),
            WellInput(position = 2, condition = ConditionArrival.Labelled(ConditionLabel("high"))),
        )),
    ),
)
```

- `label` is an optional field on every part input whose shape some field in the closure can point at. A part without a label is as today.
- `condition` is a choice, as a forward field is in OQ63 §6.1: `Existing(ref)` for a record already on the books, `Labelled(label)` for one arriving in this request.
- The label is typed per shape (`ConditionLabel`), so a well cannot name a container where a condition is wanted; that refusal is the client compiler's.
- The boundary reads the whole request, assigns ids, resolves every label, and refuses the request if a label is unknown or declared twice. Order inside the request does not matter: the request is one value, and resolution happens after all of it is read.

### 2.3 What the compiler checks

- A field may take a label only when its type is a shape that arrives in the same closure, read from the `with` tree. Any other `one` field keeps today's handle-only input.
- The generated boundary refuses an unknown label and a duplicate label as a rejection of the whole request, before the door check (Reference §21), since the request has no meaning until every reference resolves.
- Cycles are not possible: a part points at a part, and both are created in the one commit, so no order of creation is implied. (The closure is no longer a tree, but it is still one commit; the tree rule was a limitation, OQ63 §3.)

## 3. Open

- Whether the rule-side name and the client-side label are documented as one idea (Reference §6 and §14 both saying "a record may be named before it exists") or as two constructs that happen to share a purpose.
- Whether a label may also be used on the forward field of OQ63 §6.1, so a batch can name an origin that arrived as a sibling instead of nesting it. Probably not needed: nesting is the common case.
- The runtime: ids are assigned at the boundary today; confirm the label resolution can run before any row is written, so a refused label costs no store write.
