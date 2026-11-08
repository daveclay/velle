# The survey examples: a system whose record types are data

Each `.velle` file here exercises one finding of a stress pass over a system
where an admin defines a *kind* of surveyed thing (site, plot, specimen) and
its *fields* at runtime; an *entry* is one surveyed thing; a *reading* is
one field's value, held as text because the field says what it means; a
*submission* carries a list of readings. The findings are listed in
`QUESTIONS.md` as OQ74, OQ75, OQ76, items under OQ43 and OQ53, and bug items under
"Expressions, refinements, and unions".

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
| `01-in-inside-exists-refused` | `r.value not in r.field.options` inside an `exists` over the submission's readings. The same test at the reading's own scope, in the same file, is accepted. | `[F2] 'r.value in r.field.options' — 'in' takes a list of values [a, b] or a many of values on the right` | Expressions item |
| `01-in-inside-exists-workaround` | the options searched with `not exists (r.field.options as o where o == r.value)` | passes | |
| `02-lookup-recursion-refused` | a kind's parent looked up by name, and `locationHolder` walking `parent?.locationHolder`; a `never (Kind where parent == this)` is present and does not help | `[V14] definition cycle: Kind.locationHolder -> Kind.locationHolder — no certificate from the whitelist applies ...` | OQ76, OQ78 |
| `02-lookup-recursion-stored-accepted` | the same walk with `parent: one Kind?` stored and the same `never` | passes | OQ76 |
| `02-lookup-recursion-workaround` | the walk unrolled to four levels; a fifth level is silently missed | passes | OQ76 |
| `03-assigned-field-refused` | a rule assigns `entry.label`; a `never` over the submission resolves a parent by label | `[V10] never over Entry.label is a guarantee ('ApplyRelabel' writes the field it reads) — v0 cannot yet prove it inductively over its writers; fail closed` | OQ53, V10 |
| `03-assigned-field-workaround` | the label derived from the latest `Outcome` record; nothing assigns it | passes | OQ53, V10 |
| `04-transient-with-parts-refused` | `transient act Submission ... with readings` | `[V17] 'Reading.submission' references transient act 'Submission'`, `[V18] a 'Submission' can arrive that nothing provably answers`, `[V22] transient act 'Submission' with a closure is refused — the combination is open (OQ43)` | OQ43 |
| `04-transient-with-parts-workaround` | the submission kept, an `Outcome` record, a rule on `not exists Outcome for this` | passes | OQ43 |
| `05-text-as-number-accepted` | `value < field.min` with `value: text?` and `min: decimal?` | passes validation; `compareValues` throws "cannot order" at runtime | OQ75, Expressions item |
| `05-text-as-number-function-refused` | `number(value) < field.min` | `[F2] 'number(value)': no builtin or registered function is named 'number'` | OQ75 |
| `05-text-as-number-workaround` | `value matches "-?[0-9]+([.][0-9]+)?"`; the minimum is not checked | passes | OQ75 |
| `06-text-composition-accepted` | `unitName: text = name + "_unit"` | passes validation; `arith` has no text case and fails at runtime | OQ75, Expressions item |
| `06-text-composition-workaround` | the unit reading matched by `starts with`, `ends with`, and `length(...) == length(name) + 5` | passes | OQ75 |
| `07-boolean-derivation-refused` | `isRoot: boolean = parentName is none or parentName == "survey"` | `expected LIDENT, found KW('is')` | Expressions item |
| `08-timestamp-field-name-refused` | a field named `timestamp` | `expected LIDENT, found KW('timestamp')` | Expressions item |
