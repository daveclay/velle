# OQ137 — May a closure edge carry parts of several shapes that take one shape with `is`, the committer sending one list in which each part names its shape?

**Status:** opened 2026-11-16 as OQ136's residue. D1 awaits Dave. Not designed beyond the question.

**In plain terms:** an act may arrive with records nested inside it, its parts, and the committer sends each list of parts as a list. Today every such list holds records of one shape. When the parts share their fields through one shape with `is` but come in several shapes, the author declares one list per shape and the committer sends one list per shape, so the order of the parts across the shapes is not in the input. The question is whether one list may hold parts of several shapes, each part saying which shape it is, as a nested value at a union-typed field already does.

**See:** Reference §6 "A request that is not kept, with its parts" (the batch example, with one view per kind); Reference §9 "Sharing a field across shapes" (`is`); `checks.md` V22 (a closure edge's parts are of one shape); `wiki/Guides/Act-API.md` "Inline parts" (one list per part collection) and `wiki/Guides/API-layer.md` (`Wire`, the `"@shape"` tag on a nested value at a union-typed field); `compiler/src/test/kotlin/velle/TransientClosureTest.kt` "the batch's fields read once"; `working-docs/examples/survey/04-transient-variants-read-batch.velle`.

---

## 1. What runs today

A closure edge's parts are of one shape (`checks.md` V22). The example throughout is the survey file named below, a request that is not kept arriving with rows of two shapes; the question is about the edge, not the example. When a shape several part kinds take with `is` declares the back-reference, the collection it infers on the act holds every kind, and V22 refuses it as an edge and offers one view per kind. Since OQ136, that view compiles, and the batch is spelled as in `working-docs/examples/survey/04-transient-variants-read-batch.velle`:

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

### D1. Whether a closure edge may be the collection a taken shape's back-reference infers, its parts of every shape that takes it

Does a closure edge accept the collection a taken shape's back-reference infers on the act, whose parts are of every shape that takes it with `is`, the committer sending one list in which each part names its shape?

Today's answer is no: V22 refuses such an edge and offers one view per taking shape, and the committer sends one list per edge. Keeping that costs nothing in the language and leaves the parts' order across shapes out of the input. Changing it would read, in the spec of §1:

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

with the JSON body one list, each part tagged as a nested value at a union-typed field is today:

```
{ "note": "nightly",
  "rows": [ { "@shape": "CreateRow", "identifier": "c1", "newKey": "N1", "rowValues": [ { "name": "a" } ] },
            { "@shape": "UpdateRow", "identifier": "u1", "key": "T1",   "rowValues": [ { "name": "c" } ] } ] }
```

and the generated Kotlin taking `rows: List<Row.New>` where `Row.New` is sealed over `CreateRow.New` and `UpdateRow.New`, as the taken shape already appears in generated code where a static union does (OQ35 D4, settled table).

Recommendation: yes, as an addition. The spelling with one view per kind stays legal for an author who wants the kinds apart. Costs:

- A `for each` over such an edge reads only the taken shape's fields until a part is narrowed with `is`, so a rule that touches each shape's own fields is a branch per shape. Today's spelling gives the author one loop per shape with that shape's fields in view.
- V22's message, which today offers the view per taking shape, would have to say both spellings are legal and when to pick each.
- The order of parts in a list is not something Velle reads today; a rule cannot ask for "the part before this one". Keeping the order on the wire only matters once a rule can read it, which is a question of its own if it comes up.
- A taking shape named by a view of its own and also reached through the taken shape's edge would arrive twice. The two spellings on one act would be refused, or the taken shape's edge would be the only one.

#### Proposed changes

- `Model.kt`, `resolveClosureEdge`: an inverse whose shape is a taken shape resolves to one edge whose parts are every taker; `ClosureEdge` carries the set of part shapes, or one edge per taker under one name.
- `Codegen`: the taken shape's `New` class as a sealed type over the takers' `New` classes; the act's input list typed by it.
- `apis/json`, `Wire.nested`: the part list reads `"@shape"` per element, as a nested value at a union-typed field does; `Describe` lists the kinds an edge takes.
- `Runtime.kt`: the closure commit creates each element as its tagged kind; the refusal of an element without a tag, or with a tag that is not a taker, names the edge and the kinds.
- `checks.md` V22: the sentence "a closure edge's parts are of one shape" rewritten; the message no longer offers only the view per kind.
- Reference §6 "A request that is not kept, with its parts": the batch example shown both ways; `wiki/Guides/Act-API.md` "Inline parts" and `wiki/Guides/API-layer.md` (`Wire`).
- `TransientClosureTest.kt`: a commit of one tagged list with parts of both shapes and values on each; a refusal of an untagged part; survey `04-transient-variants-read-batch.velle` gains the one-list spelling or a sibling file.

## 3. Work items

- [ ] Decide D1.
