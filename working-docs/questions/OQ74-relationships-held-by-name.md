# OQ74 — An act's values that are meant to identify an existing record

**Status:** opened 2026-11-06 from a stress pass over a system whose record types are data (`working-docs/examples/survey/`). Reframed 2026-11-08 with Dave, twice. First as a store question. Then, the same day, to the premise below: the act carries values meant to pick out an existing record, and the question is how Velle resolves them. The parent example and the walk over it moved to OQ76. Not designed; D1 awaits Dave, D2–D6 are its parts.

**Two words kept apart on this page.** The *store key* is the value the store assigns to a row, a `Long` in every generated store today, carried by `Ref<Entry>` and `StoreKey`. The *identifying values* are values of the target record's own fields, supplied by a human, that pick out exactly one record: the key printed on an entry's tag, or a field's name together with its kind's name. A human never sees a store key. This page never uses "id" or "identifier" alone.

**The premise.** An act has a reference field, `entry: one Entry`. The only value the act's handle takes for it is the entry's store key. The human filling in the act does not have the store key. They have identifying values of the entry. So the act, as the human fills it in, carries values that are *intended* to identify an existing record, and Velle has no way to say so and no step that resolves them to the record. The survey specs worked around it by storing the values as text and searching for the record in a derived formula, which costs the relationship. The question is how Velle resolves an act's identifying values to the record they are meant for.

**Not this question:** a relationship to the own shape and any walk along it (OQ76; the survey's `02-lookup-recursion-*` files are its). The V14 diagnostic (OQ78). A short spelling for uniqueness (OQ77, which D3 uses).
**See:** Reference §6 (relationships), §21 (`never`), §22 (reads; `Ref`) · wiki Act-API (what a commit's input carries) · wiki Store-API · `compiler/src/main/kotlin/velle/Hydration.kt` (`Ref`, `StoreKey`, `StateResolver`) · OQ67 residue items (bindings) · OQ73 (the store configuration grammar) · OQ77 (`unique`)

---

## 1. The use case

A field team surveys things in the world: sites, plots, specimens. An administrator decides what kinds of things there are and, for each kind, which fields a surveyor fills in. A plot might have a soil type field with a fixed list of allowed answers. Each surveyed thing is an entry, and everyone knows an entry by the key printed on its tag, something like PLOT-0417. A surveyor's submission is about one entry and carries one reading per field: the field's name and the value they wrote down.

When the office reviews a reading, they need to know which field it answers, so they can check the value against that field's allowed answers. The reading gives the field's name and the submission gives the kind. Together those pick out exactly one field, because an administrator never gives two fields of one kind the same name. In the same way the submission gives the entry's key, and that picks out exactly one entry, because no two tags carry the same key.

Nobody in the office thinks of any of this as a search. The reading is for a field. The submission is about an entry. The kind and name, or the key, are just how people say which one they mean.

## 2. The Velle written so far

The survey specs modeled the reading's field and the submission's entry as follows.

The reading's field (`working-docs/examples/survey/01-in-inside-exists-workaround.velle`):

```
act Field {
    kindName: text
    name: text
    options: many text
}

act Submission {
    kindName: text
    readings: many Reading = (Reading where submission == this)
}

shape Reading {
    submission: one Submission
    fieldName: text
    value: text?
    field: one Field? =
        if exists (Field where kindName == this.submission.kindName and name == this.fieldName) then first(Field where kindName == this.submission.kindName and name == this.fieldName by name)
        else none
}
```

The submission's entry (`working-docs/examples/survey/03-assigned-field-workaround.velle`):

```
act Entry {
    key: text
    ...
}

act Submission {
    entryKey: text
    ...
    entry: one Entry? =
        if exists (Entry where key == this.entryKey) then first(Entry where key == this.entryKey by key)
        else none
}
```

This is the engineer's fetch transcribed into the spec. The identifying values the human supplied, `fieldName` and `entryKey`, are kept as text fields on the act. The relationship is a derived formula that searches the target records for a match, guarded by `exists` so a value matching nothing yields `none`. A `never` for uniqueness stands beside each formula so that `first` is honest.

What that spelling costs. The relationship is derived, so the compiler infers no inverse for it and runs no deletion check through it. The reference is optional, so a value that matches nothing is recorded instead of refused, which is not what the office means. The identifying values stay on the act as text beside the relationship they were meant to establish.

The office's view is `field: one Field` on the reading and `entry: one Entry` on the submission, with the two facts from section 1 as prohibitions: no two fields of one kind share a name, and no two entries share a key. That is what the spec should say. What it cannot say today is that an act's `entry` may be given as the entry's key.

## 3. What Velle does today when an act references a record

A commit's input carries each `one Entry` field as a `Ref<Entry>`, which holds the entry's store key (`Hydration.kt`, `Ref` and `StoreKey`; wiki Act-API). At admission the runtime resolves it by asking the store for the row with that store key, `StateResolver.fetchByKey`. The commit does not stand when there is no such row. The stored column for the reference holds the store key, and the store joins on it for the inferred inverse.

That is the only form a reference takes at the door. The store's read API has no question of the form "the entry whose key is PLOT-0417" other than the scan read, `fetchAll` or its filtered form, which is what the derived formula in section 2 compiles down to. The spec has no way to declare which fields of a record identify it. The store configuration has no way to say how to find a row by them. The generated API has no way to build a `Ref<Entry>` from them.

So the step that turns a human's identifying values into the referenced record has nowhere to live in Velle except as a search written in the spec.

## 4. What to decide

### D1. The decision: the spec declares which fields identify a record

The spec declares, on the target shape, which of its fields identify a record: an entry is identified by `key`; a field by `kindName` and `name`. The declaration may name several fields together.

This is the one decision. With it, the compiler knows at every act that references the shape that the reference may be given as those values, and everything in section 3 that has nowhere to live gets a place: the act carries the values, admission resolves them, a value that matches nothing is refused, the generated API builds a reference from them. Items D2 to D6 are the parts of that answer that still need their spelling, not separate choices.

The alternative, declaring the identifying fields only in the store configuration (OQ73's grammar), does not answer the question. The spec still cannot say that `entry` may be given as the entry's key, the act's handle still takes only a store key, and the resolution still happens before the handle is called, in the engineer's code or in a search written into the spec as the survey did. That alternative changes the question to how a store finds a row by its columns, which is this OQ's earlier framing, and leaves the premise unsolved. It is listed here so it is not reopened.

The declaration sits beside the fact that makes the fields identifying, the `never` against two records sharing them (D3). Every act that references the shape agrees on how, and every store does too.

### The parts of the answer

#### D2. How the act carries the values

An act's reference field accepts either the store key, as today, or the target's identifying values. For `Submission.entry`, the human's key is the value of `entry`, not a separate `entryKey` text field. The spelling, in the spec and in the input class the Act-API generates, is to be chosen.

The reading's field raises one more point. The reading supplies the field's name. The kind's name is on the submission the reading belongs to, which is in the same act. So the identifying values of one target may be spread over the act and the records it already points at. Whether an act may draw a part of the values from a related record, or must carry every part itself, is to be decided.

#### D3. Uniqueness

The identifying values pick out at most one record, and the spec says so with a `never`, in OQ77's short spelling if that lands. For several fields, the combination is unique, not each field. The compiler requires the `never` wherever D1's declaration stands. Whether the store also enforces it as its own constraint is a store-side item (OQ69).

#### D4. Values that match no record

Admission refuses the act, as a reference given by a store key that names no row is refused today. The reason names the values that matched nothing. Where the business allows a reading whose field is unknown, the author keeps the value as text and writes the derived formula of section 2, which is the honest spelling for that fact. The Reference says which spelling means which.

#### D5. How the store keeps the reference

The store keeps the reference as the store key, as today, and resolves the identifying values to it once, at admission, by asking the store for the row that has them. Renaming an entry's key then changes nothing in the rows that reference it, and the inverse and deletion checks run as they do for any reference. The store's read API needs the question "the row of this shape with these field values", which it does not have today beyond the scan read.

A table that already exists and holds the identifying values in its own columns, with no store key in the referencing row, needs a store binding that reads the reference from those columns. That binding is not this question; it is a store binding item (OQ67's residue), to be opened only if such a table needs it, and D1's declaration is what it would bind to.

#### D6. The generated API

`Ref<Entry>` can be built from the identifying values as well as from the store key, and the read API answers `read.entry(key)` for the identifying values as well as for the store key. Over the JSON API a reference in a request body may be given either way. The cost is a second form on every surface that takes a reference, and a resolution step on the read side that today exists only on the write side.

## 5. Housekeeping when a decision lands

The lookups in the `01-` and `03-` survey files become references given by identifying values, the `entryKey` and `fieldName` text fields go, and the survey README's rows for them change accordingly. What left this question on the reframes: treating a derived `one X` as a relationship (dropped; a reference resolved at admission is stored, not derived); the parent example, its walk, and the fixed-depth workaround (OQ76); the store binding as the whole question (now a store-side item named under D5).
