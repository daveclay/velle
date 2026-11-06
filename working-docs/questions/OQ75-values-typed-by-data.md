# OQ75 — Values whose type is data: reading text as a number or a date, and composing text

**Status:** opened 2026-11-06 from a stress pass over a system whose record types are data (`working-docs/examples/survey/`, the `05-text-as-number-*` and `06-text-composition-*` files). Not designed. Dave's direction: list and work through.
**In plain terms:** in a system where an admin defines record kinds and their fields at runtime, a field is a record and a value is text; the field says whether the text is a number, a date, or an option from a list. Velle can check the shape of the text (`matches`) and membership in the options, and nothing more: no builtin reads a text as a number or a date, so a number field's minimum and a date field compared to `today` cannot be written. Nor can a name be built from another: the unit half of a quantity is stored under `<name>_unit`, and `name + "_unit"` has no meaning in Velle, so the spec matches the name by prefix, suffix, and length. The compiler does not refuse either spelling today; both validate and fail at runtime, which is the bug item under "Expressions" in `QUESTIONS.md`.
**See:** Reference §5 "The built-in functions" (the registered functions Velle ships, `length`, `trim`, `lowercase`) · `wiki/Extension-functions.md` (how an engineer registers one) · `wiki/grammar.md` "Value expressions" (`+` as text concatenation "only if we ever admit it — currently not admitted") · OQ66's residue items under "Larger language questions" (the text operators as registered predicates) · OQ68 (builtin types through the public API) · OQ69 (a function's store side: what the pre-filter renders for it) · OQ72 (the one other kind of check a value may need, asking the world)

---

## 1. The two cases

Reading text as a number, `05-text-as-number-accepted.velle`:

```
act Field { name: text  type: text  min: decimal? }

shape Reading {
    fieldName: text
    value: text?
    field: one Field? = ...lookup by name...
}

never (Reading where field is some and field.min is some and value is some and value < field.min)
```

This validates. At runtime `compareValues` (Evaluator.kt) throws "cannot order" for a text against a number. The function the author reaches for, `number(value) < field.min`, is refused: no builtin or registered function is named `number`. The sound spelling checks the text's shape only, `value matches "-?[0-9]+([.][0-9]+)?"`, and the minimum is not checked.

Composing text, `06-text-composition-accepted.velle`:

```
act Field {
    name: text
    isQuantity: boolean
    unitName: text = name + "_unit"
}
```

This validates. At runtime `arith` has no text case. The sound spelling recognizes the unit reading by `this.fieldName starts with name and this.fieldName ends with "_unit" and length(this.fieldName) == length(name) + 5`.

## 2. Why these are one question

Both are what a spec needs when the type of a value lives in a record rather than in the shape. A conventional backend does the same: it coerces the text by the field's declared type and builds derived names by concatenation. Velle has the predicates to check text and nothing to turn it into anything else. The pattern check holds for format; it says nothing about magnitude, and nothing about one text being another plus a suffix.

## 3. What to decide

### D1. Conversions as registered functions

`number(text): decimal?`, `date(text): Date?`, `datetime(text): DateTime?`, absent when the text does not parse, registered as `length` and `trim` are (Reference §5). A function called on an absent value is absent, so `number(value) < field.min` holds of nothing when `value` is unparseable, which is the right reading for a prohibition (an unparseable number fails the `matches` check beside it). The store side (OQ69): a SQL rendering is a guarded `CAST`; a store that cannot render it filters in memory.

Open within D1: the accepted formats (a decimal point, a comma, a sign; ISO dates only?), and whether the format is a second argument.

### D2. Text composition

Either admit `+` on two texts, type-checked and rendered by every store (`||` in SQL), the grammar page's "only if we ever admit it"; or a `concat` registered function, which needs no grammar change and fits OQ66. Dave decides the spelling. Either way the validator must refuse what it does not mean: today it refuses neither `+` on texts nor a text against a number (the bug items).

### D3. Whether a derived record may exist

The unit half of a quantity is a value lab systems synthesize: for every quantity field, a second field exists that nothing declared. Velle has no derived records (a derived property is a value or a view, never a new instance), and this question does not propose one; the sound spelling is to recognize the name on the reading. Recorded here so the next system with synthesized records lands on this question rather than opening another.
