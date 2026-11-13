# The survey examples: a system whose record types are data

Each `.velle` file here exercises one finding of a stress pass over a system
where an admin defines a *kind* of surveyed thing (site, plot, specimen) and
its *fields* at runtime; an *entry* is one surveyed thing; a *reading* is
one field's value, held as text because the field says what it means; a
*submission* carries a list of readings. The findings are work items
in OQ53, and OQ90 under "Expressions,
refinements, and unions" in `QUESTIONS.md`; the transient-request finding (OQ43, settled) became
Reference §4 "Transient fields of a kept act" and Reference §6 "A request that is not kept, with its parts"; the lookup finding (OQ74, settled) became
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
| `02-lookup-recursion-stored-accepted` | the nearest kind with a location as `walk parent until hasLocation`, the ancestor set as `walk parent`, and a prohibition reading it, over a stored `parent`; `name: text unique` is the kind's key | passes | Reference §7 "Walks" (OQ76, settled); Reference §21 "Keys" (OQ77, settled) |
| `03-assigned-field-refused` | a rule assigns `entry.label`; a `never` over the submission resolves a parent by label | `[V10] never over Entry.label is a guarantee ('ApplyRelabel' writes the field it reads) — v0 cannot yet prove it inductively over its writers; fail closed` | OQ53, V10 |
| `03-assigned-field-workaround` | the label derived from the latest `Outcome` record; nothing assigns it | passes | OQ53, V10 |
| `04-transient-with-parts` | a submission that carries its readings, answered and not kept: `transient act Submission ... with readings`, the readings read at the commit and copied into an entry | passes | Reference §6, "A request that is not kept, with its parts" |
| `04-transient-field` | a kept sign-up whose invite code is checked, used to find the invite, and not kept: `transient inviteCode: text` | passes | Reference §4, "Transient fields of a kept act" |
| `04-transient-branch-fields` | a kept order with its lines, the card it is paid with as `transient cardNumber: text` and `transient cardholder: text` beside `with orderLines` | passes | Reference §4, "Transient fields of a kept act"; `examples/orders/orders.velle` |
| `05-text-as-number-refused` | `value < field.min` with `value: text?` and `min: decimal?` | `[F2] 'value < field.min' — compares the text 'value' with a decimal; a text is not compared as a decimal. Write number(value), which is absent when the text is not a number, or declare 'value' as a decimal on a shape of its own` | checks F2; Reference §5 |
| `05-text-as-number-function` | the settled spelling for a value that is text by the business's design: `number(value) is none` as the format check and `number(value) < field.min` as the minimum | passes | Reference §5 "The built-in functions" |
| `05-text-as-number-workaround` | the spelling for a shape that can declare the type: one optional typed field per kind (`numberValue: decimal?`, `dateValue: Date?`, `textValue: text?`) and a `never` per kind; gives up holding every value in one field | passes | Reference §5 |
| `08-timestamp-field-name-refused` | a field named `timestamp` | `expected LIDENT, found KW('timestamp')` | OQ90 |
| `09-lookup-in-rule-first` | the served spelling: `key: text unique` on the entry and `Arrival from { entry: (Entry where key == this.entryKey) }` in a run-once rule, with an existence `never` | passes; at runtime a good key pairs the submission with its entry and a bad key is refused at the door (`UniqueKeyTest`; `LookupProbeTest` runs the `first ... by` spelling for a shape without a key) | patterns, "An act that names a record by its values"; Reference §21 "Keys" |
| `09-lookup-in-rule-no-existence` | the same lookup with no existence `never` | passes validation; at runtime a key no entry has fails the firing, `'Arrival.entry' is required and 'first(...)' is absent: the selection matched nothing`, and the commit is undone (`LookupProbeTest`; before 2026-11-09 the `Arrival` was created with `entry` empty) | Reference §14 |
| `09-lookup-in-rule-for-refused` | the lookup as `(Entry for this.entryKey)`, a text subject | `[F2] '(Entry for this.entryKey)' — the subject after 'for' is a record, and 'this.entryKey' is a text; to find the record by a value, select it: first(Entry where <field> == this.entryKey by <field>)` (before 2026-11-09 it validated and failed at runtime) | checks F2 |
| `09-lookup-in-rule-for-one-text-refused` | the same with one text field on `Entry` | the same F2 | checks F2 |
| `09-lookup-in-never-no-writer` | the lookup inline in a `never` over standing readings, nothing assigning `Field.name`; `unique (kindName, name)` is the field's key | passes | patterns, "An act that names a record by its values"; Reference §21 "Keys" |
| `09-lookup-in-never-writer-refused` | the same `never` with a rule assigning `Field.name` | `[V10] never over Field.name is a guarantee ('ApplyRename' writes the field it reads) — v0 cannot yet prove it inductively over its writers; fail closed` | V10; the pairing record of the patterns page is the spelling |
| `10-mixed-comparisons-refused` | every comparison between two builtin scalar kinds that differ, field against field and field against a constant of another kind, under `==` and `<`; and, accepted in the same file, the one reading kept: a quoted text beside a `time` or a `uuid` | one `[F2]` per prohibition, `compares the <kind> '<field>' with a <kind>; a <kind> is not compared as a <kind>`, with `Write number(...)` / `date(...)` / `datetime(...)` or `Declare ... as a time` when a text field is one side; `'time' rejects this constant: 'abc' is not a time of day` for the unreadable constant | checks F2; Reference §5; `MixedKindComparisonTest` |
| `11-optional-key` | a key over an optional field, `key: text? unique`, and the lookup `(Entry where key == this.entryKey)` behind a refinement's `entryKey is some` | passes; at runtime two keyless entries coexist and a present key is refused the second time (`UniqueKeyTest`) | Reference §21 "Keys"; checks V36 |
| `12-declared-type-predicate-in-text-refused` | `label: text = (parentName is none)` | `[F2] 'Kind.label' is declared a text and computed as a boolean; declare it 'label: boolean', or compute a text` | checks F2; Reference §7 |
| `12-declared-type-number-in-text-refused` | `label: text = count * 2` | `[F2] 'Kind.label' is declared a text and computed as a decimal; declare it 'label: decimal', or compute a text` | checks F2 |
| `12-declared-type-moment-in-date-refused` | `seen: Date = now` | `[F2] 'Kind.seen' is declared a Date and computed as a DateTime; declare it 'seen: DateTime', or compute a Date` | checks F2 |
| `12-declared-type-absent-in-required-refused` | `parent: text = parentName` with `parentName: text?` | `[F2] 'Kind.parent' is declared a text and may be absent; declare it 'parent: text?', or test the value with 'is some' first` | checks F2; Reference §7 |
| `12-declared-type-one-in-many-refused` | `names: many text = name` | `[F2] 'Kind.names' is declared a many of text and computed as a text; declare it 'names: text', or compute a many of text` | checks F2 |
| `12-declared-type-branches-in-boolean-refused` | `flagged: boolean = if name == "x" then "yes" else "no"` | `[F2] 'Kind.flagged' is declared a boolean and computed as a text; declare it 'flagged: text', or compute a boolean` | checks F2 |
| `12-declared-type-decimal-in-integer-accepted` | `half: integer = total / 2` | validates; the read returns `1.5`, since the four number types are one kind until the numeric tower types arithmetic by its operands | OQ68 |
| `12-declared-type-initially-refused` | `applied: boolean initially 5` | `[F2] 'Kind.applied' is declared a boolean and starts as a decimal; declare it 'applied: decimal', or start it with a boolean` | checks F2; Reference §5 |
| `12-declared-type-from-block-refused` | `effective: "yes"` in a `from` block for a `boolean` field | `[F2] rule 'RecordVerdict' supplies 'Verdict.effective' — declared a boolean, given a text; supply a boolean` | checks F2; Reference §14 |
| `12-declared-type-assignment-refused` | `note.locked = 5` on a `boolean` field | `[F2] rule 'ApplyLock' assigns 'Note.locked' — declared a boolean, given a decimal; assign a boolean` | checks F2; Reference §12 |
| `12-declared-type-captured-refused` | `captured since: Date = now` | `[F2] 'ReadyKind.since' is declared a Date and computed as a DateTime; declare it 'since: DateTime', or compute a Date` | checks F2; Reference §8 |
| `12-declared-type-set-in-one-refused` | `entry: one Entry? = (Entry where name == this.wanted)`, no key on `Entry` | `[V36] (Entry where name == this.wanted) is a set of Entry where one record is expected, and Entry declares no key. ...` | checks V36 |
| `14-presence-through-path` | `siteName: text = if site?.name is some then site.name else "unnamed"`, a required declaration read through a path one test shows present | validates; the test shows `site` and `site.name` present, where before OQ125 settled only `site.name` was and the declaration was refused as possibly absent | Reference §10 "Reaching through relationships"; checks V11 |
