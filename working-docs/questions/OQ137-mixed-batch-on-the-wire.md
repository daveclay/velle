# OQ137 — May a closure edge carry parts of several shapes that take one shape with `is`, the committer sending one list in which each part names its shape?

**Status:** opened 2026-11-16 as OQ136's residue. D1 awaits Dave; its costs were rewritten the same day as consequences for Velle after the reading side was pinned by a test.

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

### D1. Whether a closure edge may be the collection a taken shape's back-reference infers, its parts of every shape that takes it

Does a closure edge accept the collection a taken shape's back-reference infers on the act, whose parts are of every shape that takes it with `is`, the committer sending one list in which each part names its shape?

The question is about the input only. The act reads its parts as one collection already (§1), so saying yes changes what the author declares as an edge and what the committer sends, and nothing about what a rule can read.

Recommendation: no, at the language level. A client that holds a mixed list can be met in the API layer: the JSON codec accepts one tagged list under the inferred collection's name and splits it into the per-edge lists before the runtime is entered, as it already dispatches on `"@shape"` for a nested value at a union-typed field. The spec, the typed Kotlin surface, and the runtime are unchanged.

Consequences for Velle, which decide the recommendation:

- **What the compiler proves.** Today a shape that takes the shared shape cannot arrive unless the act names an edge for it: add a third taker, `DeleteRow is Row`, and the spec is refused (V17 names the taker's back-reference) until the act says what to do with it, and a rule over `createRows` has that kind's own fields in view with nothing to narrow. With one mixed edge, a rule that narrows `row is CreateRow` and treats everything else as an update silently takes the third kind down the `else` branch. Catching that needs an exhaustiveness check over `is` narrowing, which is OQ54's unbuilt engine. By the principle that a design in which a wrong state cannot be represented beats one in which it is checked, the per-shape spelling is the better default, and a mixed edge trades a guarantee the structure gives today for a check that does not exist yet.
- **What the product owner says.** One edge per shape says "two things arrive in one call". One mixed edge says "one thing, with a kind". Which is true is the business's answer, and the spelling should follow it. If the language offered both as equals, V22's message could no longer recommend, and the choice would be made by whatever the client happens to send rather than by the conversation Velle captures.
- **Order is neither a cost nor a benefit.** The note that opened OQ136 gave "keeps the rows' order" as the reason for one list. Velle reads no order of a collection: a `many` is a set, `for each` has no defined order, and OQ16 is about results independent of order. If the order of the parts carries business meaning, it is a field, `position: integer` on the part, that a rule reads by name. That motivation is dropped.
- **Where the input's shape belongs.** By the principle that arbitrary input from a form or a sync is not part of Velle and is pushed to the APIs, the shape of a client's list is the API layer's concern. That is why the recommendation meets the client there and not in the language.
- **Not at risk either way.** Describing the parts: the taken shape appears in generated code exactly where a static union does (OQ35 D4, settled table), so a mixed edge would be described as a list of `Row`, each a `CreateRow` or an `UpdateRow`. Totality (V18) and the door refusal are over the act and are the same either way. Serialization domains do not change, since it is one act.
- **The cost of no.** The act restates its takers as views, one per shape, which `is` has already said. That is a spelling cost and not a proof cost, since a taker without an edge is refused rather than silently dropped.

Spelled against the same spec, a yes would read:

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
        if row is CreateRow then ...                                      -- each kind narrowed where its own fields are read; a third kind falls into the else
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

For the recommendation, no at the language level and the tagged list met in the API layer:

- `apis/json`, `Wire.input`: for an act whose edges are views over the takers of one taken shape, a list under the inferred collection's name (`rows`) with `"@shape"` per element is split by shape into the per-edge lists; an element without a tag, or tagged with a shape that is not a taker with an edge, is refused naming the collection and the shapes.
- `apis/json`, `Describe`: the act's description names the inferred collection and the shapes it accepts beside the per-edge lists.
- `wiki/Guides/API-layer.md` (`Wire`): the tagged list as an input form; the Act API guide and the Reference unchanged.
- `TransientClosureTest.kt` or the JSON API's tests: a body with one tagged list commits as the per-edge lists do; an untagged element is refused.

A plain no, with nothing in the API layer, changes nothing.

If yes at the language level instead: `Model.kt` `resolveClosureEdge` resolves an inverse over a taken shape to one edge whose parts are every taker; `Codegen` emits the taken shape's `New` as a sealed type over the takers' `New` classes and types the input list by it; `Runtime.kt` creates each element as its tagged kind and refuses an untagged one; `checks.md` V22 drops "a closure edge's parts are of one shape" and its message offers both spellings; Reference §6 shows the example both ways; `Act-API.md` and `API-layer.md` follow; `TransientClosureTest.kt` commits one tagged list with values on each part.

## 3. Work items

- [ ] Decide D1.
