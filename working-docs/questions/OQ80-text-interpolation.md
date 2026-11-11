# OQ80 — Joining texts: an interpolation spelling

**Status:** opened 2026-11-11 at Dave's direction when OQ75 D1 settled: the compiler refuses `+` between two texts, and the spelling for building one text from others is this question's. Dave's direction: likely an interpolation, `"${foo}-${bar}"`, rather than `foo + "-" + bar`. Not designed; D1 through D4 await Dave.
**In plain terms:** a spec sometimes needs a text made from other values: an invoice code `INV-` followed by a number, or, in a system whose record types are data, the name of the field that holds a quantity's unit, `<name>_unit`. Velle has no way to write that. `+` between texts validates today and fails at runtime; OQ75 D1 makes the compiler refuse it. This question decides what an author writes instead, and whether anything is needed at all.
**See:** OQ75 (settled: D1 the compiler refuses `+` between texts; D3 a text is read as a number or a date only by a function, `number(text)`, absent when it does not parse; D4 moved here) and the Expressions items in `QUESTIONS.md` that build it · `working-docs/examples/survey/06-text-composition-accepted.velle` and `06-text-composition-workaround.velle` · `wiki/grammar.md` "Value expressions" (`+` as text concatenation not admitted) and the text literal (`TextLiteral`, with the comment "Composing text out of data is presentation logic") · Reference §5 "The built-in functions" (`length`, `trim`, `lowercase`) · OQ69 (a function's store side) · OQ73 (Velle UI, where presentation lives)

---

## 1. The case, from OQ75 (settled)

`working-docs/examples/survey/06-text-composition-accepted.velle` writes what the author means:

```
act Field {
    name: text
    isQuantity: boolean
    unitName: text = name + "_unit"
}
```

This validates at HEAD and fails when `unitName` is first read (`arith` in `compiler/src/main/kotlin/velle/Evaluator.kt` has no text case). Once OQ75 D1 is built it is refused. `06-text-composition-workaround.velle` recognizes the unit reading by `this.fieldName starts with name and this.fieldName ends with "_unit" and length(this.fieldName) == length(name) + 5`, which is the shape of a text, not its meaning.

OQ75 settled that for this system the unit is data a reading carries, not a name built from a name:

```
act Field {
    name: text
    isQuantity: boolean
}

shape Reading {
    fieldName: text
    numberValue: decimal?
    unit: text?
    field: one Field? =
        if exists (Field where name == this.fieldName) then first(Field where name == this.fieldName by name)
        else none
}

never (Reading where field is some and field.isQuantity and numberValue is some and unit is none)
```

That spelling needs no joining of texts and stands whatever this question decides. The case this question is for is the one that spelling does not cover: a text whose business meaning is the joined form, such as a code an outside system expects, `INV-0042`.

## 2. What to decide

### D1. Whether a spec joins texts at all

The grammar page says at the text literal that "Composing text out of data is presentation logic", and OQ73 (Velle UI) is where presentation lives. Against that: a code another system keys on, `INV-0042`, is business data an act carries, and a spec that cannot say how it is formed cannot say it is unique or well-formed. Dave's direction is that there is a spelling; this decision records why.

### D2. The spelling

Dave's direction: interpolation inside a text literal, `"${foo}-${bar}"`, rather than `+` or a function. To settle: what may appear inside `${...}`, a path only or any value expression; whether a value that is not a text is rendered (a number as its digits, a date as ISO) or refused; OQ75 settled the other direction as a function, `number(text)`, which suggests `text(value)` or a rendering rule written once; the escape for a literal `${`; and how the lexer tells an interpolated literal from a plain one (the grammar's `TextLiteral` is a single line with `\` escapes).

The alternatives OQ75 weighed, kept so they are not weighed twice: `+` on two texts, against which `+` already means number, duration against a date, and collection union, and a fourth meaning raises what `name + 5` is; a registered function `concat(a, b, ...)` with `text(value)` for a number, which needs no grammar change and renders once per store under OQ69 but reads worse than what a product owner writes unprompted.

### D3. Where an interpolated text may be written

A derived property (`code: text = "INV-${number}"`), a creation's `from` block, a predicate's operand (`fieldName == "${name}_unit"`), a `matches` pattern. Each is a place the compiler types the pieces and the store may render them.

### D4. The store side

A derived text that a read filters on, `where code == "INV-0042"`, renders in SQL as `||` or `CONCAT` by dialect, or is filtered in memory, which OQ69 allows. A rendering of a non-text piece is the dialect's cast, which is the mirror of OQ75's `number(text)` with its absent case.

## 3. The build, once Dave decides

Grammar (`wiki/grammar.md`, `TextLiteral` and `primary`), the lexer and parser, `scalarTypeOf` and F2 in `Validator.kt`, the evaluator, the SQL dialects, Reference §5 or §10 (one section), `checks.md` F2, and `06-text-composition-workaround.velle` rewritten with the spelling. The OQ75 D1 message for `+` between texts then names it.
