# OQ64 — Many records from one request: a count, and a product of sets

**Status:** open (2026-10-20) — raised by Dave from a prior system (a science system: the user enters condition values, a container type, how many containers, and a number of replicates, and the software creates every container and every well: containers × wells per container × replicates wells, with the conditions spread across them). Narrowed 2026-10-21: the references between created records were OQ63's part and are settled (`as` on a creation, a part pointing at any record in the same commit, a reference supplied by value). What remains here is the count and the product: no design yet.
**In plain terms:** a creation statement creates exactly one record, and a rule fires once per record that newly meets its condition. So the only sources of "many" are the records a client commits in one closure and the records already on the books. A request that should produce N records, where N is a number carried by the request, or a product of numbers, or one record per pair drawn from two sets, has no spelling. The client has to compute the layout and commit every record; the spec can check the count afterwards but cannot produce it. The feature the product owner asked for lives outside Velle. The tree part of the want (a created well points at a created container, a well in one branch points at a condition in another) is no longer the obstacle: `as` names the record a statement creates (Reference §14) and a part may point at any record arriving in the same commit (Reference §6).
**See:** `grammar.md` (`creation`, `ruleBody`) · Reference §11 (a rule fires once per record) · Reference §14 (`for`, `from`, `as`) · Reference §6 ("Inline part creation": the closure is the one construct that creates several records in one commit; "A reference to a record arriving elsewhere in the same commit") · Reference §12 (fan-out assignment: one write per existing member; assignment never creates) · Reference §22 ("`initially` generator vocabulary": sequence-flavored generators deferred) · `checks.md` V33 (the `as` name's scope) · `examples/peptides/peptides.velle` (`GreenhouseProtocol.plotsPerArm`, `AssayProtocol.replicates`, `LaidOutTrial`, `CompletedAssay`: counts the spec checks against, never produces) · `examples/membership/membership.velle` (`OpenAccountReview`: a rule creating two linked records with `as`)

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

The conditions arrive as parts of the design's closure, so they are records and a rule can fire once per condition. The containers, the replicates, and the wells per container are integers, and no rule can fire on an integer. The creation is two levels deep, which a rule can now spell: `Container from { ... } as container` and then `Well from { container: container, ... }` (Reference §14). What the rule cannot spell is doing that six times for the containers and ninety-six times under each.

## 2. Where today's constructs stop

- **A creation statement is one record.** `rule LayOut when ExperimentDesign { Sample from { ... } }` creates one `Sample`. Nothing in `ruleBody` repeats a statement, and a rule body cannot name a set to create over.
- **A rule's multiplicity is the multiplicity of records that exist.** `rule PlaceCondition when Condition { Well for this ... }` gives one well per condition, which covers the one axis the client supplied as records. It cannot cover containers, replicates, or wells per container.
- **An integer is not a set.** `containerCount: integer`, `replicates: integer`, and `containerType.wellsPerContainer` yield nothing to fire on. There is no range, no sequence generator, and `initially` is a value for one record, not a source of records (Reference §22, "`initially` generator vocabulary").
- **There is no product.** With `WellPosition` as a reference table (ninety-six records, one per position on the container type), a rule can still fire on one shape only. There is no condition over pairs and no join-shaped trigger, so "one well per container per position" has no rule that fires once per pair. Fan-out assignment (`this.invoices.customer = ...`) writes once per existing member and cannot create.
- **Cascading does not help.** A chain of rules creates one record per firing at every link, and the number of firings is bounded by the records already on the books. No chain turns a count into that many records. A rule `when Container { Well for this ... }` would give one well per container once containers exist, which is the right shape for the second level, but still one well, not ninety-six, and the containers themselves have no source.

## 3. The spellings available today, and what each gives up

- **The client expands the product.** `act ExperimentDesign { ... } with { conditions, containers with { wells } }`: the client computes every container and every well and sends them as a nested closure. The containers-with-wells tree is exactly what a closure can carry, and a well pointing at its condition is a reference into another branch of the closure, which Reference §6 allows ("A reference to a record arriving elsewhere in the same commit"): the client passes the same condition object wherever a well points at it, and the boundary resolves the reference by object identity (the Binding API guide). The spec can audit the counts with prohibitions over the design (`count(containers) == containerCount * replicates`) and over each container (`count(wells) == design.containerType.wellsPerContainer`). Costs: the layout algorithm, which is the feature, lives in the client, and every client reimplements it; the prohibitions name the symptom (a wrong count) and not the rule (one per position).
- **Reference-table positions, one rule per condition.** `WellPosition` as reference data and `rule when Condition { Well for this ... }`. Still one record per firing: it buys nothing beyond the conditions axis, and the container and position have to be chosen by the client per condition.
- **Hand-committed, counted back.** What peptides does. `GreenhouseProtocol.plotsPerArm` is a number; the scientists commit each `GreenhousePlot` themselves; `LaidOutTrial` is the state reached when the count is met. `AssayProtocol.replicates` likewise: each `Assay` plate is its own act, and `HitExperiment` compares `completedReplicates` against the protocol. This is a faithful model of a human laying out a bench. It is the wrong model when the software does the laying out, which is the case raised here.

None is the sentence the owner said.

## 4. What a construct would have to settle

- **Creation over a set.** A creation statement scoped by a collection, so that the number of records created is a function of the firing record's data: one statement, one record per element, nested for a product. One firing per entrant record is unchanged, so "running the rule twice creates nothing" still holds for free: the rule fires once per design, and the set is fixed by the design.
- **Creation of a tree.** The wells of a created container point at that container. Two spellings exist for the pointer and a spec picks per rule. The nested statement names the container the enclosing statement just created (`as <name>`, Reference §14), so one rule creates both levels. Or the second level is a separate rule `when Container` that creates that container's wells, so each level is one rule and the levels chain through ordinary firing. The second keeps each rule to one business sentence ("a design has its containers"; "a container has its wells"); it also means a container committed by hand gets its wells laid out the same way, which may or may not be wanted. What a loop adds: an `as` name bound inside a repeated block is bound once per element. Reading it outside its block would name several records, so the name's scope has to be the block (an extension of V33).
- **A set from a count.** The one place an integer becomes a set. Whether well positions are a count or a reference table is the domain's choice (real plate layouts name their wells A1 to H12, which is reference data), so the integer case is probably replicates alone. A range form, or a generator that yields a sequence, which Reference §22 names as deferred.
- **What the created records may point at.** Settled by OQ63. Each created well points at its container, which the same rule named with `as` or which is `this` in the chained rule, and at a condition, a part that landed in the design's closure. A rule-created record may point at any entrant of its transaction and at any record already on the books, and the ordering is the runtime's (`evaluation.md`, "Closure commits"). Nothing is left to settle here.
- **Validator obligations.** One-writer (V1): every created record is new, so no conflict. V14: no self-reference. Order-independence (OQ16): the created set is a function of the entrant's closure, so sibling order does not matter. The created records are entrants in the design's transaction, so `when Well` rules and `never (Well where ...)` fire per well at the door, and one refused well refuses the whole design (Reference §20, "All-or-nothing batches"). A product can be large: the worked design above is five hundred and seventy-six wells in one transaction, and a plate of 1536 wells in ten replicates is tens of thousands. Whether the compiler bounds it, or warns, is open.
- **Downstream surfaces.** The rule graph gains a one-to-many creation edge (`diagrams.md`); spec generation needs a case that asserts the count and one that refuses a cell (`testgen.md`, OQ56).

## 5. Candidate spellings

Both candidates add the same two things to the grammar, and differ only in whether one rule creates both levels. Neither adds anything for the pointers: `as` on a creation statement already exists (Reference §14).

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

**Candidate A: one rule, nested, naming the container.** The inner block needs the container the outer block just created, so the creation statement names it with `as` (Reference §14), bound once per element of the outer block.

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

**Candidate B: one rule per level, chained through ordinary firing.** No name: the second rule fires once per created container and `this` is the container.

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

- *Cross-pointers among records created by one firing.* Each replicate creates a treatment container, a control container, and a `Pairing` pointing at both. In A, two `as` names in one block give the pairing direct references, as `OpenAccountReview` in `examples/membership/membership.velle` does with no loop. In B, the rule on the container finds its partner by a lookup over the design and the replicate number; the lookup fills a `one` field, so the compiler must prove at most one match, and the at-most-one proof beyond the refinement slice is a listed gap (QUESTIONS.md, OQ53's V12 item). B works only if the validator accepts the lookup. A's names are lexical, so a record pointing at a *sibling iteration's* creation (container `p` at container `p - 1`) needs the same lookup in A.
- *Data that lives only in the loop.* A's inner block reads the outer block's names whether or not the container stores them. B's second rule sees only what the container stores and what its paths reach, so every datum the inner level needs becomes a stored field on the intermediate, even one the business does not consider a fact about a container.
- *Intermediates that arrive by other routes.* B's rule on the container fires for every container, however it came to exist. If containers also arrive by a client closure or from another rule, B needs a structural discriminator (a refinement over containers that have a design). A's block runs only for the design.

None is impossible in B; each is a proof, a stored field, or a refinement. A is B's block plus the name, and a spec picks per rule between one rule and a chain.

**What the compiler would check, for either.**

- Each `in` names a collection: a `many` field, an inferred inverse, a filter, or a range. A scalar is rejected.
- A bound name shadows nothing already in scope, and is not read outside its block. An `as` name given inside a block has the same scope (V33 today rejects a taken name and a read-through; the block scope is the addition).
- The statements inside a block are creations only. Assignment inside a block would write one field once per element, and the one-writer rule (V1) already refuses several writes to one field from one rule; deletion inside a block is the fan-out Reference §12 refuses for `delete`.
- `then` inside a block orders its statements per element, as it does today (Reference §15); a block is itself one statement to the `then` around it.
- The number of created records is not bounded by the grammar. Whether a product over three ranges earns an advisory (Reference §22, the advisory series) is open.

## 6. Questions for Dave

Every example below uses the shapes from §5 unless it says otherwise: a `ContainerType` with `wellsPerContainer`, an `ExperimentDesign` with `containerCount`, `replicates`, a derived `wellsPerBlock`, and its `conditions` parts, and a `Container` and `Well` created by rule.

### 6.1 Should the spec lay out the records at all?

**Context.** Today every "many" in Velle comes from records: the records a client commits in one closure, and the records already on the books. A rule never turns a number into that many records. The construct this question proposes would be the first place a spec creates records from a count, and it moves the layout algorithm from the client into the spec. The alternative is to leave the layout in the client and have the spec count it back, which is the pattern `examples/peptides/peptides.velle` uses for `GreenhouseProtocol.plotsPerArm`.

**Example.** The scientist submits one design. With the construct, the spec creates the six containers and the five hundred and seventy-six wells:

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
} with conditions

rule LayOutContainers when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containerCount {
        Container from { design: this, replicate: replicate, position: position }
    }
}
```

Without it, the client creates them and the spec checks the number:

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
} with { conditions, containers with { wells } }

never (ExperimentDesign where count(containers) != containerCount * replicates)
never (Container where count(wells) != design.containerType.wellsPerContainer)
```

**Options.**

- Build the construct. The spec says "lay out every container and every well", which is the sentence the owner said.
- Leave the layout to the client. Document the count-back pattern as the spelling, and close this question.

**Recommendation.** Build it. The layout is the feature, and a feature that every client reimplements is the thing Velle exists to take out of clients. Counting back names the symptom, not the rule.

**Dave:** yes, build the construct (2026-10-21).

### 6.2 Where may a number become a set?

**Context.** Something has to turn `replicates: integer` into the values one through `replicates`. The spelling in §5 is a range, `1..n`. The question is whether a range is a value the spec can use anywhere a collection is allowed, or only the thing a `for each` runs over. If it is a value anywhere, a design could carry its positions as a derived field and the rule would read that field. If it is only a loop source, the range appears after `in` and nowhere else.

**Example.** A range as a derived field, read by the rule:

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
    positions: many integer = 1..containerCount
    replicateNumbers: many integer = 1..replicates
} with conditions

rule LayOutContainers when ExperimentDesign {
    for each replicate in replicateNumbers, position in positions {
        Container from { design: this, replicate: replicate, position: position }
    }
}
```

A range only after `in`:

```
rule LayOutContainers when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containerCount {
        Container from { design: this, replicate: replicate, position: position }
    }
}
```

**Options.**

- A range is a value. It can be a derived field, an argument to `count`, and a loop source. This adds a collection of plain integers to Velle, which has only collections of records today, so every construct that takes a collection (`count`, `all`, `exists`, filters, fan-out assignment) has to say what it does with integers.
- A range is a loop source only. It is legal after `in` in a `for each` and nowhere else. The grammar gains one production, and no other construct changes.

**Recommendation.** Loop source only. The one want is creation. A collection of integers as a field would be the first stored or derived collection that is not records, and nothing here needs it.

**Dave:** a range is a loop source only (2026-10-23). It is legal after `in` in a `for each` and nowhere else; no `many integer` field type.

### 6.3 May a block contain another block?

**Context.** Candidate A in §5 is one rule with a `for each` inside a `for each`: the outer block creates a container and names it with `as`, and the inner block creates that container's wells. Candidate B is two rules, one per level, chained through ordinary firing. If a block may not contain a block, Candidate A is not available and every tree is a chain. The product of two sets is a separate thing and is spelled with a comma in one `for each`, so this question is only about nesting a block that holds a creation between the levels.

**Example.** Nested, one rule:

```
rule LayOut when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containerCount {
        Container from { design: this, replicate: replicate, position: position } as container
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

Not nested, two rules:

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

The difference the author feels: the chained rule fires for every container, however it came to exist. If containers also arrive by a client closure, the chained rule lays out their wells too, and the author needs a refinement to stop it. The nested rule runs only for the design.

**Options.**

- Allow nesting. Both spellings exist, and the author picks per rule. The grammar gains nothing beyond the block itself, since a block is a statement and statements already nest in `then` groups.
- Forbid nesting. Every tree is a chain, each rule is one business sentence, and an `as` name is never bound inside a block.

**Recommendation.** Allow it. Forbidding it forces a stored field on the intermediate for every datum the inner level reads, and a refinement for every intermediate that can arrive by another route. Both are the costs §5 lists for Candidate B.

**Dave:**

### 6.4 Does the construct run over a part collection as well as a range?

**Context.** Which condition a well gets is the domain's choice, not Velle's. In §5 the spec computes it: the conditions arrive numbered, and each container holds a block of wells per condition. In a real lab the scientist often supplies a plate map instead, one entry per well position naming the condition for that position, and the software copies the map onto every container. Both wants exist. The plate map is a part collection on the design, so supporting it means a `for each` can run over a part collection and the created record reads fields off each element.

**Example.** The plate map as parts of the design:

```
act ExperimentDesign {
    containerType: one ContainerType
    containerCount: integer
    replicates: integer
} with { conditions, plateMap }

shape Condition {
    design: one ExperimentDesign
    value: decimal
}

shape PlateMapEntry {
    design: one ExperimentDesign
    position: integer
    condition: one Condition
}

rule LayOutWells when Container {
    for each entry in design.plateMap {
        Well from {
            container: this
            position: entry.position
            condition: entry.condition
        }
    }
}
```

The entry's `condition` is a reference into another branch of the design's closure, which Reference §6 allows.

**Options.**

- A `for each` runs over any collection the rule can reach (a `many` field, an inferred inverse, a filter) and over a range. The plate map spelling above is legal with nothing added.
- A `for each` runs over ranges only. Conditions are assigned by arithmetic, and a plate map is not expressible.

**Recommendation.** Any collection. Running over records is the ordinary case for every other construct in Velle, and the range is the addition. The question is only whether to say so in the grammar from the start.

**Dave:**

### 6.5 What may a block contain?

**Context.** The want is creation. A block could also hold an assignment, which would write one field once per element, or a deletion. Velle already has a spelling for writing one field on every member of a collection: fan-out assignment, `this.containers.status = "laid out"` (Reference §12). Deleting every member of a collection is the fan-out Reference §12 refuses for `delete`.

**Example.** The same write, two ways:

```
rule MarkLaidOut when LaidOutDesign {
    this.containers.status = "laid out"
}

rule MarkLaidOut when LaidOutDesign {
    for each container in containers {
        container.status = "laid out"
    }
}
```

**Options.**

- Creations and nested blocks only. Assignment keeps its one spelling, and the block never deletes.
- Any statement. Assignment in a block is a second spelling of fan-out, and deletion in a block is a new fan-out delete that Reference §12 refuses today.

**Recommendation.** Creations and nested blocks only. One spelling per want. If fan-out delete is ever wanted, it is its own question.

**Dave:**

### 6.6 Should the compiler say anything about a large product?

**Context.** A product over three ranges can be large. The worked design in §1 is five hundred and seventy-six wells in one transaction. A plate of 1536 wells in ten replicates is fifteen thousand three hundred and sixty. Every created record is an entrant of the design's transaction, so each one goes through the door, and one refused well refuses the whole design (Reference §20, "All-or-nothing batches"). Nothing in the grammar bounds the number.

**Example.**

```
rule LayOut when ExperimentDesign {
    for each replicate in 1..replicates, position in 1..containerCount, slot in 1..containerType.wellsPerContainer {
        Well from { ... }
    }
}
```

**Options.**

- Nothing. The size is the data's, and the store's problem to hold. All-or-nothing is what the owner asked for.
- An advisory when a `for each` multiplies three or more sources, naming the product as something the author should mean (Reference §22, the advisory series).
- A hard bound, refused at the door when the product exceeds it.

**Recommendation.** Nothing. A bound invents a number Velle has no basis for, and an advisory fires on the one case the construct exists for.

**Dave:**

## 7. Work left

- Write the example: `examples/platelayout/` in the stress-pass style, the three spellings of §3 with what each refuses, so the gap has probes before it has a design. The client-expands-the-product spelling can now be written in full, with wells pointing at conditions across the closure.
