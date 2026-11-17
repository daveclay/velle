# OQ137 — Does a batch whose rows come in several kinds arrive as one list per kind, or as one list of tagged rows?

**Status:** opened 2026-11-16 as OQ136's residue. D1 awaits Dave. Not designed beyond the question.

**In plain terms:** a client sends a batch of rows, some creating a record and some updating one. Today the spec declares one collection per kind on the batch, so the batch's input, in generated Kotlin and in JSON, is one list of create rows and one list of update rows. A client usually holds a mixed batch as one list, in the order the rows were written, each row saying which kind it is. The question is whether Velle should let the batch be declared, and so sent, that way.

**See:** Reference §6 "A request that is not kept, with its parts" (the batch example, with one view per kind); Reference §9 "Sharing a field across shapes" (`is`); `checks.md` V22 (a closure edge's parts are of one shape); `wiki/Guides/Act-API.md` "Inline parts" (one list per part collection) and `wiki/Guides/API-layer.md` (`Wire`, the `"@shape"` tag on a nested value at a union-typed field); `compiler/src/test/kotlin/velle/TransientClosureTest.kt` "the batch's fields read once"; `working-docs/examples/survey/04-transient-variants-read-batch.velle`.

---

## 1. What runs today

A closure edge's parts are of one shape. When a shape several part kinds take with `is` declares the back-reference, the collection it infers on the act holds every kind, and V22 refuses it as an edge and offers one view per kind. Since OQ136, that view compiles, and the batch is spelled as in `working-docs/examples/survey/04-transient-variants-read-batch.velle`:

```
transient act Batch {                                                     -- not kept; its rows and their values are removed with it
    note: text
    createRows: many CreateRow = (CreateRow where batch == this)          -- a view over one kind, read inside the batch's commit (V17)
    updateRows: many UpdateRow = (UpdateRow where batch == this)          -- the same for the other kind
} with { createRows with { rowValues }, updateRows with { rowValues } }   -- two closure edges, one per kind, each with its values (V22)

shape Row {                                                               -- the fields every row has; no records of its own (Reference §9)
    batch: one Batch                                                      -- declared once; infers `rows` on Batch, which holds both kinds
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
```

The committer's input follows the edges. In the generated Kotlin the batch's input class takes `createRows: List<CreateRow.New>` and `updateRows: List<UpdateRow.New>` (`wiki/Guides/Act-API.md`, "Inline parts"). In JSON the body is:

```
{ "note": "nightly",
  "createRows": [ { "identifier": "c1", "newKey": "N1", "rowValues": [ { "name": "a" } ] } ],
  "updateRows": [ { "identifier": "u1", "key": "T1",   "rowValues": [ { "name": "c" } ] } ] }
```

The order of the rows across the two kinds is not in the input. A rule that needs it has no way to recover it.

There is one place today where a nested value says which kind it is: a `one` field typed by a static union and named in `with` takes a nested value tagged `"@shape"` on the wire, and the generated input takes one of the operands' `New` classes (`wiki/Guides/API-layer.md`, `Wire.nested`; Reference §6 "A reference supplied by value"). That is a single value at a field, not a list of parts.

## 2. What to decide

### D1. Whether a closure edge may hold parts of several kinds, sent as one list of tagged rows

Does a closure edge over a collection whose parts come in several kinds, every kind taking one shape with `is`, become legal, so that the committer sends one list of rows each tagged with its kind?

Today's answer is no: V22 refuses `with { rows with { rowValues } }` and offers one view per kind. Keeping it costs nothing in the language and leaves the row order out of the input. Changing it would read, in the same spec:

```
transient act Batch {
    note: text
} with { rows with { rowValues } }                                        -- one edge over `rows`, which holds CreateRow and UpdateRow; refused by V22 today

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
    for each row in rows {                                                -- every row, in the order sent
        if row is CreateRow then ...                                      -- each kind narrowed where its own fields are read
    }
}
```

with the JSON body one list, each row tagged as a nested value at a union-typed field is today:

```
{ "note": "nightly",
  "rows": [ { "@shape": "CreateRow", "identifier": "c1", "newKey": "N1", "rowValues": [ { "name": "a" } ] },
            { "@shape": "UpdateRow", "identifier": "u1", "key": "T1",   "rowValues": [ { "name": "c" } ] } ] }
```

and the generated Kotlin taking `rows: List<Row.New>` where `Row.New` is sealed over `CreateRow.New` and `UpdateRow.New`, as the taken shape already appears in generated code where a static union does (OQ35 D4, settled table).

Recommendation: yes, as an addition. The spelling with one view per kind stays legal for an author who wants the kinds apart. Costs:

- A `for each` over `rows` reads only the shared fields until the row is narrowed with `is`, so a rule that touches each kind's own fields is a branch per kind. Today's spelling gives the author one loop per kind with the kind's fields in view.
- V22's message, which today offers the view per kind, would have to say both spellings are legal and when to pick each.
- The order of parts in a list is not something Velle reads today; a rule cannot ask for "the row before this one". Keeping the order on the wire only matters once a rule can read it, which is a question of its own if it comes up.
- A part shape that takes the shared shape and is also the target of its own view (`createRows`) and of the shared edge (`rows`) would arrive twice. The two spellings on one act would be refused, or `rows` would be the only edge.

#### Proposed changes

- `Model.kt`, `resolveClosureEdge`: an inverse whose shape is a taken shape resolves to one edge whose parts are every taker; `ClosureEdge` carries the set of part shapes, or one edge per taker under one name.
- `Codegen`: the taken shape's `New` class as a sealed type over the takers' `New` classes; the act's input list typed by it.
- `apis/json`, `Wire.nested`: the part list reads `"@shape"` per element, as a nested value at a union-typed field does; `Describe` lists the kinds an edge takes.
- `Runtime.kt`: the closure commit creates each element as its tagged kind; the refusal of an element without a tag, or with a tag that is not a taker, names the edge and the kinds.
- `checks.md` V22: the sentence "a closure edge's parts are of one shape" rewritten; the message no longer offers only the view per kind.
- Reference §6 "A request that is not kept, with its parts": the batch example shown both ways; `wiki/Guides/Act-API.md` "Inline parts" and `wiki/Guides/API-layer.md` (`Wire`).
- `TransientClosureTest.kt`: a commit of one tagged list with both kinds and values on each; a refusal of an untagged row; survey `04-transient-variants-read-batch.velle` gains the one-list spelling or a sibling file.

## 3. Work items

- [ ] Decide D1.
