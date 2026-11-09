# OQ77 — A shorter spelling for "no two records share this value"

**Status:** opened 2026-11-08 by Dave from the OQ74 session, for the future. Not designed.
**In plain terms:** a spec that holds a relationship by name, or that treats any field as a business key, has to say that no two records share the value. Today that is a `never` with a count in it. Dave asks for a word on the field, `unique` or `distinct`, that says the same thing in one token. The question is the spelling, whether it covers a key made of several fields, and whether the compiler may use the declaration where it today asks for a `first ... by` or a proof that a selection yields one record.
**See:** Reference §21 (`never`), §5 (fields; `id`, `initially randomUUID`) · `singular_references.md` (V12) · `wiki/patterns.md` "An act that names a record by its values" (the lookup in a rule with `first ... by`; the uniqueness `never`, over one field or several, is one of the two prohibitions beside it) · OQ76 D1

---

## 1. The spelling today

`working-docs/examples/survey/02-lookup-recursion-refused.velle`:

```
never (Kind where count(Kind where name == this.name) > 1)
```

It is a refusal at the door: a commit that would give a second kind the same name is refused, so every kind's name is a key. A key made of two fields reads the same way with two conjuncts; the survey's fields are unique by kind and name together (`01-in-inside-exists-workaround.velle`, `Field` found by `kindName` and `name`).

The count reads every record of the shape, so this `never` is also a whole-shape read in the sense of A5 (`tolerates contention`, Reference §19), and every commit on `Kind` takes turns behind it. Whether the compiler today warns on this one is not checked.

## 2. What to decide

### D1. The spelling

On the field, `name: text unique`, for a one-field key. For a key of several fields the word has to sit on the shape, sketched as `unique (kindName, name)` inside the body. Dave's words were `distinct` or `unique`; nothing else is proposed. Whether a `unique` field may be optional (two absent values do not collide) and whether it may be assigned later by a rule (the refusal then fires at that write, not only at creation) are part of the spelling.

### D2. What it means

The same refusal the `never` is today, with the same rejection as data: nothing new at the boundary, one less line in the spec, and the intent named. The Reference would show the `never` as what the word stands for.

### D3. What the compiler may do with it

A `unique` field is a key, so a selection by it yields at most one record. The compiler could accept `first(Kind where name == this.parentName)` without `by`, or a `(Kind for ...)`-style singular read, on the strength of the declaration (`singular_references.md`, V12), and the lookup in a rule (`wiki/patterns.md`, "An act that names a record by its values") relies on it, together with an existence `never`, so that `first ... by` finds exactly one record. Whether the whole-shape read in §1 can be rendered as a store-side uniqueness constraint instead of a count at every commit is the store question (OQ69's store side).
