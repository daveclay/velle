# OQ137 — May a closure edge carry parts of several shapes that take one shape with `is`, the committer sending one list in which each part names its shape?

**Status:** opened 2026-11-16 as OQ136's residue. D1 decided 2026-11-16; settled, build pending. The build plan is §3.

**In plain terms:** an act may arrive with records nested inside it, its parts, and the committer sends each list of parts as a list. Today every such list holds records of one shape. When the parts share their fields through one shape with `is` but come in several shapes, the author declares one list per shape and the committer sends one list per shape. The act itself already reads all of its parts as one collection. The question is whether the committer may also send them as one list, each part saying which shape it is, as a nested value at a union-typed field already does.

**See:** Reference §6 "A request that is not kept, with its parts" (the batch example, with one view per kind and the collection over every kind); Reference §9 "Sharing a field across shapes" (`is`); `checks.md` V22 (a closure edge's parts are of one shape) and V17 (the act reads its own parts inside its commit); `wiki/Guides/Act-API.md` "Inline parts" (one list per part collection) and `wiki/Guides/API-layer.md` (`Wire`, the `"@shape"` tag on a nested value at a union-typed field); `compiler/src/test/kotlin/velle/TransientClosureTest.kt` "the batch's fields read once" and "the act reads every kind of part through the inverse the shared shape infers"; `working-docs/examples/survey/04-transient-variants-read-batch.velle`; OQ54 (exhaustiveness, the engine a check over `is` narrowing would need); OQ16 (results independent of order).

---

## 1. What runs today

A closure edge's parts are of one shape (`checks.md` V22). The example throughout is the survey file named above, a request that is not kept arriving with rows of two shapes; the question is about the edge, not the example.

When a shape several part kinds take with `is` declares the back-reference, the collection it infers on the act holds every kind, and V22 refuses it as an edge and offers one view per kind. Since OQ136, that view compiles, and the act reads its parts both ways:

```
transient act Batch {                                                     -- not kept; its rows and their values are removed with it
    note: text
    createRows: many CreateRow = (CreateRow where batch == this)          -- a view over one kind, read inside the batch's commit (V17)
    updateRows: many UpdateRow = (UpdateRow where batch == this)          -- the same for the other kind
} with { createRows with { rowValues }, updateRows with { rowValues } }   -- two closure edges, one per kind, each with its values (V22)

shape Row {                                                               -- the fields every row has; no records of its own (Reference §9)
    batch: one Batch                                                      -- declared once; infers `rows` on Batch, holding both kinds
    identifier: text
    note: text = batch.note                                               -- reads the batch for a row of either kind
}

shape CreateRow is Row { newKey: text }                                   -- a create row: the shared fields plus its own
shape UpdateRow is Row { key: text }                                      -- an update row: the shared fields plus its own

shape RowValue {
    row: one Row                                                          -- points at either kind; allowed, the value is in the same closure
    name: text
    note: text = row.note                                                 -- reads the batch through the row without narrowing it
}

shape Seen { identifier: text, kind: text }

rule SeeEveryRow when Batch {
    for each row in rows {                                                -- every row of either kind, through the inferred collection
        Seen from { identifier: row.identifier,                           -- a shared field, read without narrowing
                    kind: if row is CreateRow then "create" else "update" }  -- a kind's own fields are read after narrowing with `is` (V17)
    }
}
```

So the reading side is complete: a rule over the act walks `rows` and narrows each part, or walks `createRows` and `updateRows` with each kind's own fields in view (`TransientClosureTest.kt`, the two tests named above). What the act cannot do is name `rows` as a closure edge. The committer's input therefore follows the two edges. In the generated Kotlin the act's input takes `createRows: List<CreateRow.New>` and `updateRows: List<UpdateRow.New>` (`wiki/Guides/Act-API.md`, "Inline parts"). In JSON the body is:

```
{ "note": "nightly",
  "createRows": [ { "identifier": "c1", "newKey": "N1", "rowValues": [ { "name": "a" } ] } ],
  "updateRows": [ { "identifier": "u1", "key": "T1",   "rowValues": [ { "name": "c" } ] } ] }
```

There is one place today where a nested value says which shape it is: a `one` field typed by a static union and named in `with` takes a nested value tagged `"@shape"` on the wire, and the generated input takes one of the operands' `New` classes (`wiki/Guides/API-layer.md`, `Wire.nested`; Reference §6 "A reference supplied by value"). That is a single value at a field, not a list of parts.

## 2. What to decide

### D1. Whether a closure edge may be the collection a taken shape's back-reference infers, its parts of every shape that takes it · Decided 2026-11-16

Does a closure edge accept the collection a taken shape's back-reference infers on the act, whose parts are of every shape that takes it with `is`, the committer sending one list in which each part names its shape?

The question is about the input only. The act reads its parts as one collection already (§1), so saying yes changes what the author declares as an edge and what the committer sends, and nothing about what a rule can read.

Recommendation: yes. The sentence in `checks.md` V22 that a closure edge's parts are of one shape is a limitation of how the closure is resolved, to be lifted, as the closure tree rule was; the per-shape views stay a legal spelling. The recommendation was no when this section was first written on 2026-11-16 and moved to yes the same day; both cases are kept below so the reversal can be weighed.

**The case for no, one edge per shape (the first recommendation):**

- **What the compiler proves.** Today a shape that takes the shared shape cannot arrive unless the act names an edge for it: add a third taker, `DeleteRow is Row`, and the spec is refused (V17 names the taker's back-reference) until the act says what to do with it, and a rule over `createRows` has that kind's own fields in view with nothing to narrow. With one mixed edge, a rule that narrows `row is CreateRow` and treats everything else as an update silently takes the third kind down the `else` branch. By the principle that a design in which a wrong state cannot be represented beats one in which it is checked, the per-shape spelling gives a guarantee by structure.
- **What the product owner says.** One edge per shape says "two things arrive in one call". One mixed edge says "one thing, with a kind". If the language offered both as equals, the choice would be made by whatever the client happens to send rather than by the conversation Velle captures.
- **Where the input's shape belongs.** By the principle that arbitrary input from a form or a sync is not part of Velle and is pushed to the APIs, the shape of a client's list is the API layer's concern; a client's mixed list could be split into the per-edge lists in the JSON codec, which already dispatches on `"@shape"` for a nested value at a union-typed field, with the spec and the typed surface unchanged.

**The case for yes, one edge over the taken shape (the recommendation):**

- **Consistency of what the language describes.** Velle already describes a collection of several shapes in three places: a `many` field typed by a static union or a taken shape; the inverse a taken shape's back-reference infers, which a transient act reads its own parts through (§1); and a `one` field typed by a union and named in `with`, whose nested value arrives as any operand, tagged with its shape on the wire, with each operand's own `with` children resolved per operand (Reference §6, "A reference supplied by value"). The closure edge over the inferred inverse is the one place where a mixed collection is refused: a single mixed nested value may arrive, a list of them may not. The cause is the compiler's data shape, a closure edge recording one part shape where the by-value edge already records a set of operands and an owner per child (`Model.kt`, `ClosureEdge`, `resolveByValueEdge`). That is a limitation of the same kind as the closure tree rule, which is not a principle.
- **The proof cost belongs to every mixed collection, not to this edge.** A rule over a union-typed `many` field, or over the inferred inverse, can narrow to one shape and let a new third shape fall into the `else` today. Catching that is exhaustiveness over `is` narrowing, OQ54's engine, owed to every mixed collection. The per-shape edges give the guarantee only as a side effect of the limitation, and an author who wants each kind's arrival spelled apart keeps the per-shape views, so a yes removes nothing. The point is filed as a work item in OQ54.
- **What the product owner says, read again.** "A batch arrives with its rows; a row creates a record or updates one" is one `with rows` and two shapes that take `Row`. The two views on the act restate what `is` already said and are the engineer's translation of the limitation. Both spellings being legal does not leave the choice to the client: when the author writes `with rows`, V22 has nothing to say; when the author writes the per-shape views, the same. Which is the business's answer stays the author's, as it is for a union-typed field today.
- **The binding is the smaller part, and follows.** Each element of the list carries its shape on the wire, as a nested value at a union-typed field does; the generated input list is typed by the taken shape's input class, sealed over the takers', which is where OQ35 D4 already puts a taken shape in generated code. The tag is a property of the wire, where a shape name must be said in text; nothing in the spec names a tag. The principle that input shape is pushed to the APIs therefore cuts the other way: the language says what arrives, and the binding says how it is spelled in JSON.

**Weighed either way:**

- **Order is neither a cost nor a benefit.** The note that opened OQ136 gave "keeps the rows' order" as the reason for one list. Velle reads no order of a collection: a `many` is a set, `for each` has no defined order, and OQ16 is about results independent of order. If the order of the parts carries business meaning, it is a field, `position: integer` on the part, that a rule reads by name. That motivation is dropped; Dave agreed on 2026-11-16 that order is not what is pertinent for a collection of mixed shapes.
- **Not at risk either way.** Describing the parts: the taken shape appears in generated code exactly where a static union does (OQ35 D4, settled table), so a mixed edge is described as a list of `Row`, each a `CreateRow` or an `UpdateRow`. Totality (V18) and the door refusal are over the act and are the same either way. Serialization domains do not change, since it is one act.
- **The cost of no.** The act restates its takers as views, one per shape, which `is` has already said. That is a spelling cost and not a proof cost, since a taker without an edge is refused rather than silently dropped.

**Why the recommendation moved.** The case for no rested on the proof point and on where input belongs. The proof point turned out to be the general cost of mixed collections, which Velle already carries and OQ54 owns, so the per-shape edges were buying a guarantee the language does not give anywhere else. And the input principle separates what arrives, which is the spec's, from how it is spelled on the wire, which is the binding's; the one-shape rule was deciding the first by the second.

Spelled against the same spec, the recommended spelling reads:

```
transient act Batch {
    note: text
} with { rows with { rowValues } }                                        -- one edge over `rows`, holding CreateRow and UpdateRow; refused by V22 today

shape Row {
    batch: one Batch                                                      -- infers `rows` on Batch
    identifier: text
    note: text = batch.note
}

shape CreateRow is Row { newKey: text }
shape UpdateRow is Row { key: text }

shape RowValue {
    row: one Row
    name: text
    note: text = row.note
}

rule HandleBatch when Batch {
    for each row in rows {                                                -- every row
        if row is CreateRow then ...                                      -- each kind narrowed where its own fields are read; a third kind falls into the else (OQ54)
    }
}
```

with the JSON body one list, each part tagged as a nested value at a union-typed field is today:

```
{ "note": "nightly",
  "rows": [ { "@shape": "CreateRow", "identifier": "c1", "newKey": "N1", "rowValues": [ { "name": "a" } ] },
            { "@shape": "UpdateRow", "identifier": "u1", "key": "T1",   "rowValues": [ { "name": "c" } ] } ] }
```

and the generated Kotlin taking `rows: List<Row.New>` where `Row.New` is sealed over `CreateRow.New` and `UpdateRow.New`.

#### Proposed changes

For the recommendation, yes at the language level:

- `Model.kt`, `resolveClosureEdge`: an inverse whose shape is a taken shape resolves to one edge whose parts are every taker, each taker's own `with` children resolved under it with its owner recorded, as `resolveByValueEdge` does for a union's operands; `ClosureEdge` carries the set of part shapes.
- `Codegen`: the taken shape's `New` class as a sealed type over the takers' `New` classes; the act's input list typed by it.
- `apis/json`, `Wire.nested`: the part list reads `"@shape"` per element, as a nested value at a union-typed field does; an element without a tag, or tagged with a shape that does not take the edge's shape, is refused naming the edge and the shapes. `Describe` lists the shapes an edge takes.
- `Runtime.kt`: the closure commit creates each element as its tagged shape.
- `checks.md` V22: the sentence "a closure edge's parts are of one shape" and the message offering a view per taker are removed; the entry says an edge over a taken shape's inverse carries parts of every taker.
- Reference §6 "A request that is not kept, with its parts": the example shown with `with rows`, the per-shape views noted as the spelling for an author who wants each kind's arrival apart; `wiki/Guides/Act-API.md` "Inline parts" and `wiki/Guides/API-layer.md` (`Wire`).
- `TransientClosureTest.kt`: a commit of one tagged list with parts of both shapes and values on each; a refusal of an untagged part. Survey `04-transient-variants-read-batch.velle` gains the `with rows` spelling, or a sibling file shows it.
- OQ54: a work item for exhaustiveness over `is` narrowing of a mixed collection (added 2026-11-16).

If no instead, with a client's mixed list met in the API layer: `apis/json` `Wire.input` splits a tagged list under the inferred collection's name into the per-edge lists and refuses an untagged element; `Describe` and `wiki/Guides/API-layer.md` say so; a test in the JSON API. A plain no changes nothing.

**Decided:** yes, 2026-11-16. Dave: submitting one collection of "upsert" objects that share their properties, where one kind carries fields the other does not, is a valid use case; it could be written as two collections, but the act being the one place that refuses a collection of mixed shapes is the greater inconsistency. The binding is less opinionated about its rules, and that is where the messiness of the real world adapts to Velle's explicit, consistent rules, so the language allows the mixed edge and the binding follows.

## 3. The build

D1's proposed changes in build order. The by-value edge over a union-typed `one` field (`Model.kt`, `resolveByValueEdge`) is the pattern for every step: parts of several shapes under one edge, each shape's own children resolved under it.

1. `Model.kt`: `ClosureEdge` carries the set of part shapes; `resolveClosureEdge` resolves an inverse whose shape is a taken shape to one edge over every taker, each taker's `with` children resolved under it with the owner recorded; `markTransientParts` marks every taker. The V22 message offering a view per taker goes.
2. `Codegen`: the taken shape's `New` class as a sealed type over the takers' `New` classes; the act's input list typed by it; the registry and the boundary's refusals for a sibling reference unchanged.
3. `Runtime.kt`: the closure commit creates each element of such an edge as its own shape; the input refusal of an element whose shape does not take the edge's shape names the edge and the shapes it accepts.
4. `apis/json`: `Wire.nested` reads `"@shape"` per element of such a list, refusing an element without one; `Describe` lists the shapes an edge takes.
5. Regenerate every example's output (`implement` skill §2).
6. Wiki: `checks.md` V22 loses "a closure edge's parts are of one shape" and says an edge over a taken shape's inverse carries parts of every taker; Reference §6 "A request that is not kept, with its parts" shows `with rows`, with the per-shape views as the spelling for an author who wants each kind's arrival apart; `wiki/Guides/Act-API.md` "Inline parts" and `wiki/Guides/API-layer.md` (`Wire`); `diagnostics.md` regenerated for the removed message.
7. Examples: survey `04-transient-variants-read-batch.velle` respelled to `with rows`, or a sibling file added, and the README line updated.
8. Tests: `TransientClosureTest.kt`, a commit of one tagged list with parts of both shapes and values on each, and a refusal of an element without a tag or of a shape that is not a taker; a JSON API test for the tagged list; `DiagnosticCatalogTest` for the removed message.
9. Settle: the row in the settled table, OQ54's item already filed, this file removed.

### 3.1 The committer's surface, today and after the build

Today, with one edge per shape, the generated input (`wiki/Guides/Act-API.md`, "Parts") is one list per edge, each element the part shape's own `New` class:

```kotlin
NewBatch(
    note = "nightly",
    createRows = listOf(NewCreateRow(identifier = "c1", newKey = "N1", rowValues = listOf(NewRowValue(name = "a")))),
    updateRows = listOf(NewUpdateRow(identifier = "u1", key = "T1", rowValues = listOf(NewRowValue(name = "c")))),
)
```

After the build, with `with rows`, the list is typed by the taken shape's input interface, which the generator already emits for a taken shape or a union in a creating position (`NewOrigin` in `examples/batches/output/src/main/kotlin/velle/generated/Batches.kt`), and each element is the concrete class for its shape. Nothing is tagged in Kotlin; the class is the shape:

```kotlin
sealed interface NewRow                                   // the taken shape's input interface; no input class of its own
data class NewCreateRow(identifier, newKey, rowValues) : NewRow, NewValue
data class NewUpdateRow(identifier, key, rowValues) : NewRow, NewValue
data class NewBatch(val note: String, val rows: List<NewRow> = emptyList())

NewBatch(
    note = "nightly",
    rows = listOf(
        NewCreateRow(identifier = "c1", newKey = "N1", rowValues = listOf(NewRowValue(name = "a"))),
        NewUpdateRow(identifier = "u1", key = "T1", rowValues = listOf(NewRowValue(name = "c"))),
    ),
)
```

In JSON each element says its shape, as a nested value at a union-typed field does today (`wiki/Guides/API-layer.md`, `Wire.nested`):

```
{ "note": "nightly",
  "rows": [ { "@shape": "CreateRow", "identifier": "c1", "newKey": "N1", "rowValues": [ { "name": "a" } ] },
            { "@shape": "UpdateRow", "identifier": "u1", "key": "T1",   "rowValues": [ { "name": "c" } ] } ] }
```

An element without `"@shape"`, or naming a shape that does not take `Row`, is refused at the door before the runtime is entered: `'rows' takes a CreateRow or an UpdateRow; say which with "@shape"`. The per-shape spelling keeps today's surface unchanged. How the GraphQL schema spells an input list over several shapes is checked in step 4 against what it does today for a union-typed nested value.

## 4. Work items

- [ ] Build steps 1 to 4 of §3 (model, codegen, runtime, JSON).
- [ ] Regenerate the examples' output.
- [ ] Update `checks.md` V22, Reference §6, the Act API and API layer guides, `diagnostics.md`.
- [ ] Respell or add the survey exhibit and its README line.
- [ ] Add the tests of §3 step 8 and run the suite.
- [ ] Land the settling commit.
