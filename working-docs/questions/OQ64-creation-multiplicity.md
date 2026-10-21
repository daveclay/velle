# OQ64 — Many records from one request: a count, and a product of sets

**Status:** open (2026-10-20) — raised by Dave from a prior system (a science system: the user enters condition values, a container type, how many containers, and a number of replicates, and the software creates every container and every well: containers × wells per container × replicates wells, with the conditions spread across them); no design yet
**In plain terms:** a creation statement creates exactly one record, and a rule fires once per record that newly meets its condition. So the only sources of "many" are the records a client commits in one closure and the records already on the books. A request that should produce N records, where N is a number carried by the request, or a product of numbers, or one record per pair drawn from two sets, has no spelling. Nor has a tree of such records, where each created container needs its own created wells. The client has to compute the layout and commit every record; the spec can check the count afterwards but cannot produce it. The feature the product owner asked for lives outside Velle.
**See:** `grammar.md` (`creation`, `ruleBody`) · Reference §11 (a rule fires once per record) · Reference §14 (`for`, `from`) · Reference §6 ("Inline part creation": the closure is the one construct that creates several records in one commit) · Reference §12 (fan-out assignment: one write per existing member; assignment never creates) · Reference §22 ("`initially` generator vocabulary": sequence-flavored generators deferred) · `examples/peptides/peptides.velle` (`GreenhouseProtocol.plotsPerArm`, `AssayProtocol.replicates`, `LaidOutTrial`, `CompletedAssay`: counts the spec checks against, never produces)

---

## 1. The want

An experiment design names a list of conditions (values to test), a container type (a plate with a fixed number of wells per container), how many containers the design uses, and a replicate count. A replicate is a full copy of the container set, so the design produces containers × replicates physical containers, and each of those carries the container type's number of wells. The scientist submits the design once. The system creates every container and every well, assigns a condition to each well, and the wells are what the lab then measures. The sentence the owner said: "when a design is submitted, lay out every container and every well."

The counts, for a design of three containers of ninety-six wells in two replicates:

| created | how many |
|---|---|
| containers | 3 × 2 = 6 |
| wells | 6 × 96 = 576 |

The nearest spelling today:

```
act ContainerType {
    name: text
    wellsPerContainer: integer
}

act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
} with conditions

shape Condition {
    design: one ExperimentDesign
    value: decimal
}

shape Container {
    design: one ExperimentDesign
    replicate: integer
    position: integer                -- 1 to design.containerCount
}

shape Well {
    container: one Container
    position: integer                -- 1 to design.containerType.wellsPerContainer
    condition: one Condition
}
```

The conditions arrive as parts of the design's closure, so they are records and a rule can fire once per condition. The containers, the replicates, and the wells per container are integers, and no rule can fire on an integer. The creation is also two levels deep: each created container needs its own created wells, and a well points at a container that does not exist until the same rule creates it.

## 2. Where today's constructs stop

- **A creation statement is one record.** `rule LayOut when ExperimentDesign { Sample from { ... } }` creates one `Sample`. Nothing in `ruleBody` repeats a statement, and a rule body cannot name a set to create over.
- **A rule's multiplicity is the multiplicity of records that exist.** `rule PlaceCondition when Condition { Well for this ... }` gives one well per condition, which covers the one axis the client supplied as records. It cannot cover containers, replicates, or wells per container.
- **An integer is not a set.** `containerCount: integer`, `replicates: integer`, and `containerType.wellsPerContainer` yield nothing to fire on. There is no range, no sequence generator, and `initially` is a value for one record, not a source of records (Reference §22, "`initially` generator vocabulary").
- **There is no product.** With `WellPosition` as a reference table (ninety-six records, one per position on the container type), a rule can still fire on one shape only. There is no condition over pairs and no join-shaped trigger, so "one well per container per position" has no rule that fires once per pair. Fan-out assignment (`this.invoices.customer = ...`) writes once per existing member and cannot create.
- **Cascading does not help.** A chain of rules creates one record per firing at every link, and the number of firings is bounded by the records already on the books. No chain turns a count into that many records. A rule `when Container { Well for this ... }` would give one well per container once containers exist, which is the right shape for the second level, but still one well, not ninety-six, and the containers themselves have no source.

## 3. The spellings available today, and what each gives up

- **The client expands the product.** `act ExperimentDesign { ... } with { conditions, containers with { wells } }`: the client computes every container and every well and sends them as a nested closure. The containers-with-wells tree is exactly what a closure can carry. The spec can audit the counts with prohibitions over the design (`count(containers) == containerCount * replicates`) and over each container (`count(wells) == design.containerType.wellsPerContainer`). Costs: the layout algorithm, which is the feature, lives in the client, and every client reimplements it; the prohibitions name the symptom (a wrong count) and not the rule (one per position); and a well pointing at its condition is a part referring to a record in a sibling branch of the same closure, which Reference §6 rejects ("Parts form a tree"), so the condition must be denormalized onto the well as a value, or the conditions committed a step earlier (OQ63's window again).
- **Reference-table positions, one rule per condition.** `WellPosition` as reference data and `rule when Condition { Well for this ... }`. Still one record per firing: it buys nothing beyond the conditions axis, and the container and position have to be chosen by the client per condition.
- **Hand-committed, counted back.** What peptides does. `GreenhouseProtocol.plotsPerArm` is a number; the scientists commit each `GreenhousePlot` themselves; `LaidOutTrial` is the state reached when the count is met. `AssayProtocol.replicates` likewise: each `Assay` plate is its own act, and `HitExperiment` compares `completedReplicates` against the protocol. This is a faithful model of a human laying out a bench. It is the wrong model when the software does the laying out, which is the case raised here.

None is the sentence the owner said.

## 4. What a construct would have to settle

- **Creation over a set.** A creation statement scoped by a collection, so that the number of records created is a function of the firing record's data: one statement, one record per element, nested for a product. One firing per entrant record is unchanged, so "running the rule twice creates nothing" still holds for free: the rule fires once per design, and the set is fixed by the design.
- **Creation of a tree.** The wells of a created container point at that container. Either the nested statement can name the record the enclosing statement just created (a binding on a creation statement, which no statement has today), or the second level is a separate rule `when Container` that creates that container's wells, so each level is one rule and the levels chain through ordinary firing. The second keeps the grammar smaller and reads as two business sentences ("a design has its containers"; "a container has its wells"); it also means a container committed by hand gets its wells laid out the same way, which may or may not be wanted.
- **Naming a created record.** Needed for Candidate A and for any rule creating two linked records; owned by OQ63 (§6.2 there). Once it exists, the tree question above chooses per rule between a binding and a chain.
- **A set from a count.** The one place an integer becomes a set. Whether well positions are a count or a reference table is the domain's choice (real plate layouts name their wells A1 to H12, which is reference data), so the integer case is probably replicates alone. A range form, or a generator that yields a sequence, which Reference §22 names as deferred.
- **What the created records may point at.** Each created well points at its container (created by the same firing or the previous link) and at a condition (a part that landed in the design's closure). Rule-created records already point at entrants; pointing at two records of one closure is new only for parts, where Reference §6 forbids it. Settle whether the tree rule is about the input's serializability (then rule-created records are free of it) or about the commit (then it binds here too).
- **Validator obligations.** One-writer (V1): every created record is new, so no conflict. V14: no self-reference. Order-independence (OQ16): the created set is a function of the entrant's closure, so sibling order does not matter. The created records are entrants in the design's transaction, so `when Well` rules and `never (Well where ...)` fire per well at the door, and one refused well refuses the whole design (Reference §20, "All-or-nothing batches"). A product can be large: the worked design above is five hundred and seventy-six wells in one transaction, and a plate of 1536 wells in ten replicates is tens of thousands. Whether the compiler bounds it, or warns, is open.
- **Downstream surfaces.** The rule graph gains a one-to-many creation edge (`diagrams.md`); spec generation needs a case that asserts the count and one that refuses a cell (`testgen.md`, OQ56).

## 5. Candidate spellings

Both candidates add the same two things to the grammar, and differ only in whether one rule creates both levels.

- `for each <name> in <collection>, <name> in <collection> { statements }`: a block in a rule body that runs its statements once per element of each named collection, nested for a product. It starts with `for`, which a creation statement never does (a creation starts with a shape name), so the parse is unambiguous. Inside the block the names are ordinary values, usable in `from` fields.
- `1..n`: a value expression whose value is the collection of integers from one to `n`. The only place an integer becomes a set.

The shapes, with the condition assignment settled as a block per condition: the conditions arrive in client order, each with its block number, and each container holds every condition in a block of equal size.

```
act ContainerType {
    name: text
    wellsPerContainer: integer
}

act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
    wellsPerBlock: integer = containerType.wellsPerContainer / count(conditions)
} with conditions

shape Condition {
    design: one ExperimentDesign
    block: integer                   -- 1 to count(design.conditions), client-supplied order
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

never (ExperimentDesign where containerType.wellsPerContainer mod count(conditions) != 0)
    -- `mod` is not in the value vocabulary today (QUESTIONS.md, the vocabulary item); assumed here
```

**Candidate A: one rule, nested, with a binding on the container.** The inner block needs the container the outer block just created, so a creation statement gains `as <name>`, the alias keyword Velle already has.

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

One firing per design; the rule creates containers × replicates containers and, under each, wells per container wells. Running it again creates nothing, because the design does not enter twice.

**Candidate B: one rule per level, chained through ordinary firing.** No binding: the second rule fires once per created container and `this` is the container.

```
rule LayOutContainers when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containerCount {
        Container from { design: this, replicate: replicate, position: position }
    }
}

rule LayOutWells when Container {
    for each condition in design.conditions, slot in 1..design.wellsPerBlock {
        Well from {
            container: this
            condition: condition
            position: (condition.block - 1) * design.wellsPerBlock + slot
        }
    }
}
```

The containers are entrants of the design's transaction, so the second rule fires in the same transaction (Reference §6, the closure's entrants) and the whole layout lands or is refused as one. The cost is the one §4 named: a container committed by any other route also gets its wells laid out, which is either the point or a surprise.

**Is there anything only A can do?** No. Everything A does, B can do, at a cost in three cases:

- *Cross-pointers among records created by one firing.* Each replicate creates a treatment container, a control container, and a `Pairing` pointing at both. In A, two `as` bindings in one block give the pairing direct references. In B, the rule on the container finds its partner by a lookup over the design and the replicate number; the lookup fills a `one` field, so the compiler must prove at most one match, and the at-most-one proof beyond the refinement slice is a listed gap (QUESTIONS.md, OQ53's V12 item). B works only if the validator accepts the lookup. A's bindings are lexical, so a record pointing at a *sibling iteration's* creation (container `p` at container `p - 1`) needs the same lookup in A.
- *Data that lives only in the loop.* A's inner block reads the outer block's names whether or not the container stores them. B's second rule sees only what the container stores and what its paths reach, so every datum the inner level needs becomes a stored field on the intermediate, even one the business does not consider a fact about a container.
- *Intermediates that arrive by other routes.* B's rule on the container fires for every container, however it came to exist. If containers also arrive by a client closure or from another rule, B needs a structural discriminator (a refinement over containers that have a design). A's block runs only for the design.

None is impossible in B; each is a proof, a stored field, or a refinement.

**The binding is not A's feature.** Naming a created record so a later statement can point at it is needed with no loop at all, and today no rule can do it. That mechanism is OQ63's (its §6.2); this question keeps the loops and counts. With it, A is B's block plus the name, and a spec picks per rule between one rule and a chain.

**What the compiler would check, for either.**

- Each `in` names a collection: a `many` field, an inferred inverse, a filter, or a range. A scalar is rejected.
- A bound name shadows nothing already in scope, and is not read outside its block.
- The statements inside a block are creations only. Assignment inside a block would write one field once per element, and the one-writer rule (V1) already refuses several writes to one field from one rule; deletion inside a block is the fan-out Reference §12 refuses for `delete`.
- `then` inside a block orders its statements per element, as it does today (Reference §15); a block is itself one statement to the `then` around it.
- The number of created records is not bounded by the grammar. Whether a product over three ranges earns an advisory (Reference §22, the advisory series) is open.

## 6. Open

- Whether a derived collection on the design (the laid-out positions as values) should exist first, with creation reading it, or whether creation names the sets directly.
- How a condition is assigned to a well. The product gives every well; which condition each well holds is a second function of the design (round-robin across positions, a block per condition, a plate map the scientist supplies). The first two are arithmetic over the position, and the third is more client-supplied data. §5 takes the block per condition so the candidates can be written out; the choice is not made. Settle whether the assignment is the spec's or the client's, since it decides whether `condition` is created with the well or assigned after.
- Write the example: `examples/platelayout/` in the stress-pass style, the three spellings of §3 with what each refuses, so the gap has probes before it has a design.
