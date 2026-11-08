# OQ74 — A reference stored as a business key

**Status:** opened 2026-11-06 from a stress pass over a system whose record types are data (`working-docs/examples/survey/`). Reframed 2026-11-08 with Dave: a store question, not a language question. Not designed; D1–D5 await Dave.
**In plain terms:** a real system's table holds a reference to another record as that record's business key in a column: a submission's `entryKey`, a reading's `fieldName`, a kind's `parentName`. The engineer's code fetches the record by that value. In Velle the author describes the system's concepts and rules with no regard to how they are stored, so the relationship is written as a relationship, `entry: one Entry`, and how the store keeps it is the store's job. Today no store can be told that a reference column holds the target's business key rather than the store's own key. So the survey specs transcribed the engineer's lookup into the spec as a derived formula, an `exists` guard, a `first ... by`, and a `never` for uniqueness, once per relationship, and in one case the formula drew a refusal the stored spelling does not. The question is the store binding for a reference held by business key, and which facts of the real data it owns against which the spec states as prohibitions.
**See:** Reference §6 (relationships), §21 (`never`), §22 (reads; `Ref`) · wiki Store-API · OQ67 residue items (bindings) · OQ73 (the store configuration grammar) · OQ77 (`unique`) · OQ76 (the parent walk, which is not this question) · OQ78 (the V14 diagnostic) · `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt` (the mapping note)

---

## 1. The finding, in the survey files

The system holds three references by business key. The spec spelled each as a lookup.

A submission's entry, by `key` (`working-docs/examples/survey/03-assigned-field-workaround.velle`):

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

A reading's field, by two columns together (`01-in-inside-exists-workaround.velle`):

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

A kind's parent, by `name` (`02-lookup-recursion-refused.velle`). The same spelling, plus a derived walk up the parents that V14 refuses because the hop is a formula; the stored spelling in `02-lookup-recursion-stored-accepted.velle` passes. The walk and its certificate are OQ76 and are not this question. The parent is a self-relationship, and nothing here depends on that.

What the spec should say, for each: `entry: one Entry`, `field: one Field`, `parent: one Kind?`. The `exists`, the `first ... by`, and the uniqueness `never` are the engineer's fetch code, transcribed.

The same file shows the author stating a fact of the relationship as a prohibition, which is where such facts belong:

```
never (Submission where parentLabel is some and parent is none)
```

## 2. What a stored reference is today, and what the table holds

A stored `one X` is a column holding the store's own key for the target row, a `Long` in every generated store, and `Ref<X>` carries that key across the read API (`SqlStore.kt`, the mapping note; Reference §22). The store resolves a reference by that key and joins on it for the inferred inverse. Nothing lets a store say that the column for `Submission.entry` holds `Entry.key`, or that two columns together hold `Field`'s key. That is the whole gap. With it, the spec is the stored spelling, the compiler infers the inverse, certifies the walk under its prohibition, proves the singular read, and runs the deletion checks, all as it does today for a reference the store keys itself.

## 3. What to decide

### D1. The binding

How a store declaration says that a reference is held by business key, and where. Per shape, as the shape's identity in this store (`Entry` is keyed by `key`; `Field` by `kindName` and `name`), or per reference column (`Submission.entry` holds `Entry.key`). The per-shape form is smaller and makes every reference to the shape agree; the per-column form allows one table to hold the same target by different keys. It must handle a composite key. It lives in the store configuration, not the spec (OQ73's grammar; OQ67's binding items), since the spec says nothing about storage.

### D2. Uniqueness

A business key must be unique, or the reference has no single target. Whether that is the store's constraint only, declared once in the binding, or must also stand in the spec as the `never` of OQ77 so the compiler knows it for singular reads, or both with the compiler checking they agree.

### D3. A key that matches nothing

A reference in Velle cannot point at nothing. A row whose `entryKey` names no entry is refused on admission, as any reference supplied by a handle to a missing record is refused. Where the business allows a dangling key, the author keeps the field as text, and the relationship is a derived formula with an `exists` guard, which is the honest spelling for that business fact. The Reference would say which spelling means which. The prohibition quoted in §1 is the author stating the first choice for the parent label.

### D4. Renaming the target

When the key column of the target changes, the referencing rows hold the old value. Either the binding cascades the new key into them, or the key is never assigned by any rule, which the compiler can see. Whether the binding states this, the spec states it, or the Reference requires a key to be fixed at creation.

### D5. The generated API

`Ref<Entry>` and `read.entry(key)` carry the store's `Long` key. Whether a business key shows through them, so that a caller may fetch an entry by `key` and a `Ref` may be built from one, or the business key stays inside the store and the API keeps the store key.

## 4. Housekeeping when a decision lands

`02-lookup-recursion-refused.velle` and `02-lookup-recursion-workaround.velle` are wrong spellings and go; `02-lookup-recursion-stored-accepted.velle` is the spelling, and the lookups in the `01-` and `03-` files become stored references. The survey README's rows for `02-*` change accordingly. What left this question on the reframe: treating a derived `one X` as a relationship (dropped; a reference held by key is stored, not derived); accepting a derived hop in V14 (a candidate in OQ76 D3); documenting the fixed-depth workaround (gone).
