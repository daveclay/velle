# OQ63 — Supplying a reference: by handle, by value, or in the act

**Status:** open (2026-10-16) — found by the batches pass (`examples/batches/`, finding B2); no design yet. Reframed 2026-10-20: the tree rule is a limitation, not a principle (§3, "Trees"), so the question is how, not whether; and this question now owns the mechanism OQ64's nested creation needs (§5), so it is the one Dave is focusing on.
**In plain terms:** a peptide batch points at its provenance (`origin: one Origin`), and the two are one event in the lab: the batch and where it came from are known together. Today they are two commits, origin first, because the only construct that creates several records in one commit is the act's closure, and a closure creates parts that point *back* at the act. A `one` field on an act is a reference, and today the committer supplies it only one way: by handle, naming a record already on the books. Can the committer also supply it by value, with the pointed-at record nested inside this one, or in the act, pointing at a new record that arrives elsewhere in the same act, without giving up what the `one` field buys?
**See:** Reference §6 ("Inline part creation"; "Closures are trees") · Reference §4 (one commit, one statically-shaped closure) · `checks.md` V22 · `examples/batches/batches.velle` (the two-commit spelling, with `UnclaimedOrigin` as its residue) · `examples/batches/output/src/test/kotlin/velle/BatchesProbesTest.kt` (B2: the books rule that would close the gap refuses the origin's own commit)

---

## 1. The want

The business: a lab keeps peptides in batches. Every batch came from one of three places. It was fermented in-house from a bacteria strain, it was ordered from an outside vendor, or it was mixed from other batches already on the shelf. Where a batch came from is its *origin*, and a batch has exactly one.

The spec, from `examples/batches/batches.velle`, cut down to the records involved:

```
act Peptide { code: text }
act BacteriaStrain { name: text }
act FermenterRun { vessel: text, startedOn: Date }
act Vendor { name: text }

-- The three kinds of origin. Each is a record of its own, holding only what that kind knows.
act Fermentation  { strain: one BacteriaStrain, run: one FermenterRun }
act ExternalOrder { vendor: one Vendor, orderItem: text }
act Composition   { mixedOn: Date } with components

-- One name for "any of the three", so a field can point at whichever one applies.
shape Origin = Fermentation or ExternalOrder or Composition {
    peptideBatches: many PeptideBatch = (PeptideBatch where origin == this)
}

-- The batch. It points at its origin.
act PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin          -- must already exist when the batch is committed
}
```

In order:

- `Fermentation`, `ExternalOrder`, and `Composition` are the three kinds of origin. They share nothing but the fact that a batch can come from them.
- `Origin` is a static union: a name that means "a Fermentation, or an ExternalOrder, or a Composition" (Reference §9). Its body declares `peptideBatches`, the batches that point at this origin.
- `PeptideBatch.origin: one Origin` is the pointer. A `one` field holds exactly one value, so "a batch has exactly one origin" needs no rule; it is how the field works (Reference §6).

The pointer is what forces two commits. The batch's input must name an origin that exists, so the lab commits the fermentation first and the batch second, even though in the lab they are one event: the fermenter run finished and produced this batch. Between the two commits the fermentation is on the books with no batch. The spec can show that state (`shape UnclaimedOrigin = Origin where peptideBatches is empty`) and cannot forbid it: `never (Fermentation where peptideBatchs is empty)` is checked at the fermentation's own commit, when no batch can exist yet, so it refuses every fermentation (probe B2).

## 2. The two spellings available today, and what each gives up

- **Two commits, the reference supplied by handle.** What the example does. Keeps exclusivity structural. Costs: the unclaimed state exists and is observable; a caller that fails between the two commits leaves an orphan the business never meant; the batch's refusals (`never (PeptideBatch where count(origin.peptideBatches) > 1)`, the one-peptide rule) fire at the second commit, so a refused batch leaves the origin behind (`BatchesFlowTest`, "a mixture is of one peptide").
- **One commit, pointer reversed.** `Fermentation.batch: one PeptideBatch` and `act PeptideBatch { ... } with fermentations, externalOrders, compositions`. One closure, no unclaimed state. Costs: exclusivity degrades from a `one` field to a checked `never` over three inferred inverses ("exactly one of the three collections has one member"), which is the trade Reference §6 names as the reason the one-to-many has one spelling; the input type carries three optional part collections where the domain has one required choice; and `kind`, `Fermented`, and every narrowing become reads through collections instead of through a field.

Neither is the sentence the owner said: "a batch arrives with its provenance, and has exactly one."

## 3. What a construct would have to settle

- **Shape of the input.** A reference supplied by value means the act's input carries, for the field, *either* a handle to an existing record *or* a nested value creating one. For a union-typed field the nested value is one of three input types. Reference §6's "a `one` field on an act means a handle" would gain a declared exception, as `with` already is for inverse edges.
- **Trees.** Reference §6's rule that a part points only at the record it arrived under was not a design goal. Dave (2026-10-20): at the time there was no worked-out way to let a value in the input refer to another value in the same input, so the rule was stated as a limit. Do not defend it. A reference supplied by value still keeps the closure a tree (the nested record is created once, by the one field referencing it); no back-reference needs populating, the field itself is the edge.
- **Which commit the pointed-at record "is".** Today an inline part is an ordinary instance after the commit. An origin supplied by value would be one too; the trigger analyses (`when Fermentation`) fire once at the closure's commit as a part does now (Reference §6, the closure's entrants).
- **Prohibitions.** `never (Fermentation where peptideBatchs is empty)` becomes satisfiable at the closure's commit, since the batch lands in the same commit; the door check (Reference §21) sees both.
- **Interaction with OQ43.** A transient act with a closure is refused today; a record supplied by value under a transient act would be the materialize-then-decide pattern with the materialization inline.

## 4. Open

- ~~Whether this is a `with` extension (the `one` field named in the clause) or a property of the field's declaration.~~ Proposed as the `with` extension, §6 (2026-10-20).
- ~~Whether the want is common enough to carry a construct.~~ Yes (Dave, 2026-10-20). The gap itself was never in question: a record that must point at another record cannot be committed with it, and no spelling closes the window (Dave, 2026-10-16: the pass is about gaps in Velle, not about whether a domain could live with the window).
- ~~The act's generated Kotlin input class: a sealed type per `one` field named in `with` (`ByHandle(ref)` / `ByValue(value)`), and how the boundary's type-level refusal reads when the nested value is of a shape outside the union.~~ Proposed in §6.

## 5. The same mechanism inside a rule

OQ64's nested layout needs the same thing from the other side. A rule creates a container, then creates that container's wells, and the wells must point at the container, which has no `id` until the commit:

```
Container from { design: this, replicate: replicate, position: position } as container
Well from { container: container, ... }
```

Today no rule can do even the two-statement case: a creation statement cannot be named, so a second statement has nothing to refer to (no example does it; `OpenAccountReview` in `examples/membership/membership.velle` and `AskPrescriber` in `examples/pharmacy/pharmacy.velle` each create two unrelated records). Whatever this question settles for a value inside an act's input, a creation statement inside a rule wants the same: a local name that stands for a record before it exists, usable wherever a reference is. Settle the mechanism here; OQ64 keeps the loops and counts.

## 6. Proposal (2026-10-20, read right by Dave)

Two constructs, settled in this order. The first closes this question's own example and needs no name. The second is what OQ64's Candidate A waits on.

### 6.1 A reference supplied by value: the `one` field is named in `with`

Today `with` names inverse collections: parts that point back at the act. Let it also name a `one` field, meaning the reference may be supplied by value, with the pointed-at record nested inside this one:

```
act PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin
} with origin
```

It reads as the business sentence: "a batch arrives with its origin." The compiler tells the two cases apart by what the name resolves to on the act. An inverse collection (or a recognized-form view) means parts, as today. A `one` field means a reference that may be supplied by value.

- The pointed-at record is created once, by the one field pointing at it, so the closure is still a tree and V22's reading of the closure from the declaration is unchanged.
- The batch and its origin land or are refused as one. `never (Fermentation where peptideBatchs is empty)` becomes satisfiable at the closure's commit, since the door check sees both (Reference §21). `UnclaimedOrigin` has no members on this route.
- The record supplied by value is an ordinary instance after the commit, and `when Fermentation` fires once for it in the batch's transaction, as a part's rule does (Reference §6, the closure's entrants).
- A `one` field whose type is a base shape works the same way with one input type instead of a choice among operands.
- Nesting composes: the nested record's own `with` clause applies to it (`with { origin with { components } }` if a composition arrives with its components), read against the value's shape as nested parts are today.

**The Kotlin input class the compiler generates for the act.** This is the class the engineer's transport code fills and hands to the act's commit function (Reference §22, "External input"); below, "the input class". For a `one` field named in `with`, the input class carries a choice: the reference supplied by handle, naming an existing record, or by value, creating one. For a union-typed field the value is one of the union's input types.

```kotlin
// in NewPeptideBatch, the act's input class as the generator names it today
val origin: OriginReference

sealed interface OriginReference {
    data class ByHandle(val ref: Ref<Origin>) : OriginReference
    data class ByValue(val value: NewOrigin) : OriginReference
}
sealed interface NewOrigin   // NewFermentation, NewExternalOrder, NewComposition implement it
```

A value of a shape outside the union has no type here, so the client cannot write it and the boundary never sees it. The refusal is the compiler's, in the client's language, not a runtime rejection.

**Checks.** V22 gains the by-value case: a `with` entry that resolves to a `one` field on the enclosing level. An entry that resolves to anything else keeps today's error. A `transient act` keeps today's refusal of `with` (OQ43). The one-writer analysis (V1) treats the record supplied by value as an entrant, as it treats a part.

**What this does not need.** No name mechanism. The origin value sits inside the batch's input at the field, so nothing refers to it. The batches case is closed by 6.1 alone.

### 6.2 A name for a record that has no `id` yet

A record gets its `id` when its commit lands. Until then nothing can point at it, because a reference is an `id`. That blocks two things 6.1 does not cover: a rule cannot create a record and then a second record that points at the first, and a client cannot commit an act in which one new record references another new record that is neither nested inside it nor its container. Both need the same thing: a local name that stands for a record before it exists, accepted wherever a reference is. Settle 6.2 after 6.1 is built.

### 6.2.1 In a rule: `as <name>` on a creation statement

#### 6.2.1.1 Two linked records, no loop

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

#### 6.2.1.2 Nested creation: OQ64's Candidate A

The plate layout from OQ64 §5. A rule creates each container, then that container's wells, and the wells point at the container:

```
rule LayOut when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containerCount {
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

`container` is bound once per iteration of the outer block and read by every well created in the inner block. The `for each` block itself is OQ64's; this section supplies only the name.

#### 6.2.1.3 What the compiler checks

- The compiler rejects a name that is already in scope: a field of `this`, a `for each` binding, or another `as` in an enclosing block.
- The compiler rejects a read of a name outside the block that declared it.
- A name read by another statement orders the two, as any read does (Reference §15). A cycle (`A ... as a` reading `b`, `B ... as b` reading `a`) is rejected: no record can be created first.
- One-writer (V1) is unchanged: a name is a reference to a new record, never a write to an existing one.

### 6.2.2 In an act: a reference to another new value in the same act

#### 6.2.2.1 The case

OQ64 §3's client-expanded closure. The design arrives with its conditions and with its containers, each container with its wells, and every well names its condition. The conditions and the containers are sibling branches of the closure, so a well's `condition` points across branches, which Reference §6 rejects today ("Parts form a tree"):

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
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

Nothing new is written in Velle. The compiler can see from the `with` tree that `Well.condition` targets a shape that arrives elsewhere in the same closure, and that is what lets the reference be supplied in the act.

#### 6.2.2.2 The Kotlin input class the compiler generates for the act

The client already holds the new condition as a Kotlin object, and an object reference is a reference. A field that may point across branches accepts the object itself in place of a handle:

```kotlin
val low  = NewCondition(block = 1, value = 0.1)
val high = NewCondition(block = 2, value = 1.0)

NewExperimentDesign(
    containerType = containerTypeRef,
    containerCount = 1,
    replicates = 1,
    conditions = listOf(low, high),
    containers = listOf(
        NewContainer(replicate = 1, position = 1, wells = listOf(
            NewWell(position = 1, condition = ConditionReference.InAct(low)),
            NewWell(position = 2, condition = ConditionReference.InAct(high)),
        )),
    ),
)
```

- `condition` is a choice, as in 6.1: `ByHandle(Ref<Condition>)` for a record already on the books, or `InAct(NewCondition)` for one arriving elsewhere in this act.
- `InAct` takes a `NewCondition`, so a well cannot hand it a `NewContainer`; that refusal is the client compiler's.
- The boundary reads the whole act, assigns each part its id, and resolves every `InAct` by object identity: the same `NewCondition` instance must appear among the act's parts, and its id is substituted. Order inside the act does not matter, because resolution happens after all of it is read.
- Nothing is declared on the part. There is no label to invent, spell twice, or mistype.

#### 6.2.2.3 What the compiler checks

- A field's input gains the `InAct` case only when its type is a shape that arrives in the same closure, read from the `with` tree. Any other `one` field keeps today's handle-only input.
- The generated boundary refuses an `InAct` object that is not among the act's parts, and a part object that appears in two creating positions (one record claimed twice), as a type-level refusal of the whole act before the door check (Reference §21), since the act has no meaning until every reference resolves.
- Cycles are not possible: a part points at a part, and both are created in the one commit, so no order of creation is implied. (The closure is no longer a tree, but it is still one commit; the tree rule was a limitation, §3.)

### 6.2.3 Open points for 6.2

Each is one decision. The decision is stated first, the context and the costs after, and a line for the answer.

#### 6.2.3.1 Are the rule-side name and the act-side `InAct` documented as one idea or two?

**Decision:** whether the wiki presents "a record may be referenced before it exists" once, with `as` and `InAct` as its two faces, or presents `as` on Reference §14 and `InAct` on Reference §6 as unrelated constructs that mention each other.

**Context.** A Velle author types `as review` in a rule body:

```
rule OpenAccountReview
    when (ChronicDelinquent where not exists (AccountReview where member == this))
    on commit, Daily {
    AccountReview from { member: this, openedOn: now } as review
    AuditEntry from { member: this, review: review, note: "account review opened", loggedOn: now }
}
```

The same author never types `InAct`. It lives in the generated Kotlin input class, and the Velle source for the plate design is unchanged by it:

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
} with { conditions, containers with { wells } }

shape Well {
    container: one Container
    position: integer
    condition: one Condition        -- points across branches; the client supplies the object
}
```

**Options.**

- *One idea.* A GLOSSARY entry and one paragraph, linked from §6 and §14. Cost: the wiki rules say every sentence must help the reader write Velle today, and `InAct` is not Velle; a reader of §14 is told about Kotlin they will not type.
- *Two constructs.* §14 documents `as`; §6 documents, under "Inline part creation", that a part may point at a part in another branch and that the act's generated Kotlin input class takes the object for it. Each page has one sentence pointing at the other. Cost: the shared reason (no `id` yet) is said twice.

**Recommendation:** two constructs. The reader of each page gets what they type.

**Dave:**

#### 6.2.3.2 May a `one` field named in `with` (6.1) also be supplied in the act, so a record can name a sibling instead of nesting it?

**Decision:** whether `origin: OriginReference`, the field's type in the Kotlin input class the compiler generates for the act, has a third case, `InAct`, beside `ByHandle` and `ByValue`.

**Context.** With 6.1 the origin nests inside the batch, one origin inside one batch:

```
act PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin
} with origin
```

Supplying that reference in the act would let two new records in one act point at one new sibling without nesting. The batches spec forbids that case (`never (PeptideBatch where count(origin.peptideBatches) > 1)`), so the example below is a variant of it, not the shipped spec.

The business: a fermenter run finishes and the lab splits its product into two containers at once. Each container is a batch, both batches came from the one fermentation, and the lab records the whole thing as one act: the split, the fermentation it came from, and the two batches. The records, with the shipped batches spec's reference data and the one-origin-one-batch rule dropped:

```
act Peptide { code: text }
act BacteriaStrain { name: text }
act FermenterRun { vessel: text, startedOn: Date }

-- One kind of origin is enough for the example.
act Fermentation { strain: one BacteriaStrain, run: one FermenterRun }
shape Origin = Fermentation {
    peptideBatches: many PeptideBatch = (PeptideBatch where origin == this)
}

-- The act: a split. It references the fermentation it splits,
-- and the batches it produces arrive as its parts.
act SplitFermentation {
    fermentation: one Fermentation
    splitOn: Date
} with { fermentation, batches }

-- A batch. It points at its origin, and at the split that produced it, if any.
shape PeptideBatch {
    peptide: one Peptide
    quantityMg: decimal
    origin: one Origin
    split: one SplitFermentation?
}
```

In order:

- `SplitFermentation.fermentation: one Fermentation` is a reference, and `with { fermentation, ... }` says it may be supplied by value, the fermentation nested inside the split (6.1).
- `PeptideBatch.split: one SplitFermentation?` gives the split an inferred inverse, `batches`, and `with { ..., batches }` says the batches arrive as the split's parts (Reference §6).
- `PeptideBatch.origin: one Origin` is the pointer every batch has. In this act each batch's origin is the fermentation nested inside the split.

The fermentation and the batches are sibling branches of the split's closure. Each batch's `origin` must point at the fermentation in the other branch. In the batch's generated Kotlin input class, `origin` is, under 6.1, `ByHandle(ref)` or `ByValue(value)`; neither names a sibling. Without `InAct` on `origin`, the batches cannot point at the new fermentation, and the lab is back to two commits: the fermentation first, the split with its batches second.

**Options.**

- *By value and in the act never on one field.* A reference supplied by value is for one-to-one arrival; a shared new target arrives as a part of a common container, and the references to it are supplied in the act as in 6.2.2. The split example is then respelled so the fermentation is a part of the split rather than nested at its `one` field:

  ```
  act SplitFermentation {
      splitOn: Date
  } with { fermentations, batches }

  act Fermentation {
      strain: one BacteriaStrain
      run: one FermenterRun
      split: one SplitFermentation?       -- gives the split its inferred `fermentations`
  }
  ```

  The fermentation and the batches are both parts, the batches' `origin` points across branches with `InAct` (6.2.2), and supplying by value is not used for the shared case at all. Cost: a `many` (`fermentations`) stands where the business has exactly one, and "a split is of one fermentation" becomes a `never` over the count instead of a `one` field.
- *In the act everywhere.* Any field that targets a shape arriving anywhere in the closure, nested or part, may be supplied in the act. Cost: in the generated Kotlin input class, the choice type gains a case on every `one` field named in `with` whether or not the spec needs it, and the boundary must check that an object referenced with `InAct` is not also supplied by value somewhere (two creators for one record).

**Recommendation:** by value and in the act never on one field. One rule for the reader: a record that one new record points at nests inside it; a record that several new records point at arrives as a part of their common container, and they reference it.

**Dave:**

#### 6.2.3.3 Where are in-act references resolved: in the generated boundary before the runtime sees the act, or inside the runtime's transaction?

**Decision:** whether `InAct` is a boundary-only notion, translated to session handles before any `Mutation.Create` is built, or a runtime notion that `applyCreate` resolves as it assigns ids.

**Context.** The runtime assigns a session-local id in memory at `applyCreate` (`compiler/src/main/kotlin/velle/Runtime.kt`, `nextId++`), and the store is written at transaction close; a refused transaction is rolled back before any row exists. So either placement resolves in-act references before a store write. The difference is who knows about them.

**Options.**

- *Boundary.* The generated commit function walks the act, gives every part object a session handle, rewrites every `InAct(object)` to the handle of that same object, and refuses an object not among the parts, or a part in two creating positions, with a type-level `Refused` before the runtime is entered. The runtime, the door check, and the rules see handles only. Cost: the boundary gains a pass over the act; the refusal carries no `Rejection` data (there is no `never`), which matches a type-level refusal today.
- *Runtime.* `Mutation.Create` carries object identities and in-act references; `applyCreate` records the id under the identity and later creates look it up. Cost: every store binding and the runtime's mutation model learn a notion that exists only to be erased; a rule body's `as` name would then be the same mechanism, which is tidy, but the rule-side name is already resolved by the compiler when it orders statements, so nothing is gained.

**Recommendation:** boundary. `InAct` is a serialization affordance and never reaches the runtime; `as` is a compile-time binding and never reaches it either.

**Dave:**
