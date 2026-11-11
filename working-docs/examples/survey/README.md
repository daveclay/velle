# The survey examples: a system whose record types are data

Each `.velle` file here exercises one finding of a stress pass over a system
where an admin defines a *kind* of surveyed thing (site, plot, specimen) and
its *fields* at runtime; an *entry* is one surveyed thing; a *reading* is
one field's value, held as text because the field says what it means; a
*submission* carries a list of readings. The findings are work items
in OQ43 and OQ53, and the OQs OQ80, OQ86, OQ89, and OQ90 under "Expressions,
refinements, and unions" in `QUESTIONS.md`; the lookup finding (OQ74, settled) became
the worked pattern "An act that names a record by its values" in `wiki/patterns.md`,
the walk finding (OQ76, settled) became Reference §7 "Walks", the text-as-data
finding (OQ75, settled) became the comparison rule in `wiki/checks.md` F2 and the
`number`, `date`, and `datetime` functions in Reference §5, and the uniqueness
finding (OQ77, settled) became the key, `unique`, in Reference §21 "Keys".

`./check.sh` compiles every file against the compiler jar (path inside the
script; build it with `./gradlew :compiler:jar --max-workers=1`) and prints
the diagnostics. Output lands in `out/`, which is ignored. The suffix says
what to expect:

- `-refused`: the compiler refuses it, and the diagnostic quoted below is
  what it says today.
- `-accepted`: the compiler accepts it although the Reference does not admit
  what it says; the file's comment names the code that fails at runtime.
  The runtime failure is read from `Evaluator.kt`, not run.
- `-workaround`: the spelling that validates, with what it gives up.
- `-stored-accepted`: the same construct over a stored reference, accepted.

| File | Shows | Diagnostic today | Where |
|---|---|---|---|
| `01-in-inside-exists-refused` | `r.value not in r.field.options` inside an `exists` over the submission's readings. The same test at the reading's own scope, in the same file, is accepted. | `[F2] 'r.value in r.field.options' — 'in' takes a list of values [a, b] or a many of values on the right` | OQ86 |
| `01-in-inside-exists-workaround` | the options searched with `not exists (r.field.options as o where o == r.value)` | passes | |
| `02-lookup-recursion-stored-accepted` | the nearest kind with a location as `walk parent until hasLocation`, the ancestor set as `walk parent`, and a prohibition reading it, over a stored `parent`; `name: text unique` is the kind's key | passes | Reference §7 "Walks" (OQ76, settled); Reference §21 "Keys" (OQ77, settled) |
| `03-assigned-field-refused` | a rule assigns `entry.label`; a `never` over the submission resolves a parent by label | `[V10] never over Entry.label is a guarantee ('ApplyRelabel' writes the field it reads) — v0 cannot yet prove it inductively over its writers; fail closed` | OQ53, V10 |
| `03-assigned-field-workaround` | the label derived from the latest `Outcome` record; nothing assigns it | passes | OQ53, V10 |
| `04-transient-with-parts-refused` | `transient act Submission ... with readings` | `[V17] 'Reading.submission' references transient act 'Submission'`, `[V18] a 'Submission' can arrive that nothing provably answers`, `[V22] transient act 'Submission' with a closure is refused — the combination is open (OQ43)` | OQ43 |
| `04-transient-with-parts-workaround` | the submission kept, an `Outcome` record, a rule on `not exists Outcome for this` | passes | OQ43 |
| `05-text-as-number-refused` | `value < field.min` with `value: text?` and `min: decimal?` | `[F2] 'value < field.min' — compares the text 'value' with a decimal; a text is not compared as a decimal. Write number(value), which is absent when the text is not a number, or declare 'value' as a decimal on a shape of its own` | checks F2; Reference §5 |
| `05-text-as-number-function` | the settled spelling for a value that is text by the business's design: `number(value) is none` as the format check and `number(value) < field.min` as the minimum | passes | Reference §5 "The built-in functions" |
| `05-text-as-number-workaround` | the spelling for a shape that can declare the type: one optional typed field per kind (`numberValue: decimal?`, `dateValue: Date?`, `textValue: text?`) and a `never` per kind; gives up holding every value in one field | passes | Reference §5 |
| `06-text-composition-refused` | `unitName: text = name + "_unit"` | `[F2] 'name + "_unit"' — '+' is not defined on a text; Velle does not join or compute with texts. A value made of parts is a shape with a field for each part, and a value that is a number or a date is declared as one` | checks F2; the spelling for joining texts is OQ80 |
| `06-text-composition-workaround` | the unit reading matched by `starts with`, `ends with`, and `length(...) == length(name) + 5` | passes | OQ80 (the unit as a field of the reading, `unit: text?`, is OQ80 §1) |
| `07-boolean-derivation-refused` | `isRoot: boolean = parentName is none or parentName == "survey"` | `expected LIDENT, found KW('is')` | OQ89 |
| `08-timestamp-field-name-refused` | a field named `timestamp` | `expected LIDENT, found KW('timestamp')` | OQ90 |
| `09-lookup-in-rule-first` | the served spelling: `key: text unique` on the entry and `Arrival from { entry: (Entry where key == this.entryKey) }` in a run-once rule, with an existence `never` | passes; at runtime a good key pairs the submission with its entry and a bad key is refused at the door (`UniqueKeyTest`; `LookupProbeTest` runs the `first ... by` spelling for a shape without a key) | patterns, "An act that names a record by its values"; Reference §21 "Keys" |
| `09-lookup-in-rule-no-existence` | the same lookup with no existence `never` | passes validation; at runtime a key no entry has fails the firing, `'Arrival.entry' is required and 'first(...)' is absent: the selection matched nothing`, and the commit is undone (`LookupProbeTest`; before 2026-11-09 the `Arrival` was created with `entry` empty) | Reference §14 |
| `09-lookup-in-rule-for-refused` | the lookup as `(Entry for this.entryKey)`, a text subject | `[F2] '(Entry for this.entryKey)' — the subject after 'for' is a record, and 'this.entryKey' is a text; to find the record by a value, select it: first(Entry where <field> == this.entryKey by <field>)` (before 2026-11-09 it validated and failed at runtime) | checks F2 |
| `09-lookup-in-rule-for-one-text-refused` | the same with one text field on `Entry` | the same F2 | checks F2 |
| `09-lookup-in-never-no-writer` | the lookup inline in a `never` over standing readings, nothing assigning `Field.name`; `unique (kindName, name)` is the field's key | passes | patterns, "An act that names a record by its values"; Reference §21 "Keys" |
| `09-lookup-in-never-writer-refused` | the same `never` with a rule assigning `Field.name` | `[V10] never over Field.name is a guarantee ('ApplyRename' writes the field it reads) — v0 cannot yet prove it inductively over its writers; fail closed` | V10; the pairing record of the patterns page is the spelling |
| `10-mixed-comparisons-refused` | every comparison between two builtin scalar kinds that differ, field against field and field against a constant of another kind, under `==` and `<`; and, accepted in the same file, the one reading kept: a quoted text beside a `time` or a `uuid` | one `[F2]` per prohibition, `compares the <kind> '<field>' with a <kind>; a <kind> is not compared as a <kind>`, with `Write number(...)` / `date(...)` / `datetime(...)` or `Declare ... as a time` when a text field is one side; `'time' rejects this constant: 'abc' is not a time of day` for the unreadable constant | checks F2; Reference §5; `MixedKindComparisonTest` |
| `11-optional-key` | a key over an optional field, `key: text? unique`, and the lookup `(Entry where key == this.entryKey)` behind a refinement's `entryKey is some` | passes; at runtime two keyless entries coexist and a present key is refused the second time (`UniqueKeyTest`) | Reference §21 "Keys"; checks V36 |
| `09-lookup-in-never-value-in-refused` | `value in first(Field where ... by name).options` | `[F2] ... 'in' takes a list of values [a, b] or a many of values on the right` | OQ86 |
