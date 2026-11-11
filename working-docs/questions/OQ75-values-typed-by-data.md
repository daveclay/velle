# OQ75 — Values whose type is data: does Velle ever read a text as a number or a date, or join two texts?

**Status:** opened 2026-11-06 from a stress pass over a system whose record types are data (`working-docs/examples/survey/`, the `05-text-as-number-*` and `06-text-composition-*` files). Rewritten 2026-11-10 as two halves decided apart: §3 is what the compiler stops accepting, §4 is what an author writes instead. Settled with Dave 2026-11-11: D1 refuse, D2 keep the documented reading of a quoted text, D3 functions (after a first answer of no), D6 refuse, D7 ship the three readers as builtins; D4, joining texts, moved to OQ80. Every decision is answered. Nothing is built; §5 is the build, and this file stays until it lands.
**In plain terms:** in a system where an admin defines record kinds and their fields at runtime, a field is a record and a value is text; the field says whether the text is a number, a date, or an option from a list. A spec for that system wants to compare the text to the field's minimum, `value < field.min`, and to build one field name from another, `name + "_unit"`. The compiler accepts both today and both fail the first time they run. The Reference says the compiler rejects both. The first half of this question makes the compiler refuse what it already says it refuses, which takes spellings away from authors. The second half is what an author writes instead: a function that reads the text where the spec says so, or a shape that declares the value under its type.
**See:** Reference §5 "The built-in functions" and its "The compiler checks" list ("an operator or comparison on mismatched types") · `wiki/checks.md` F2 ("comparisons like-typed") · `wiki/grammar.md` "Value expressions" (`+` as text concatenation "currently **not** admitted") and the text literal ("Composing text out of data is presentation logic") · `wiki/Extension-functions.md` (a function is pure, called where the spec needs its value, absent when it has no answer) · OQ68 (builtin types through `VelleType`; D2's constant check rides it) · OQ69 (a function's store side) · OQ80 (joining texts) · the three items under "Expressions, refinements, and unions" in `QUESTIONS.md`, which carry §5.

---

## 1. What the compiler does today

Checked 2026-11-10 against the source at HEAD, with the compiler jar rebuilt from it. `working-docs/examples/survey/10-mixed-comparisons-accepted.velle` declares one field of each builtin scalar type on an act and compares every pair of different kinds, field against field and field against a constant of another kind, under `==` and under `<`. It validates. The table lists what each comparison does when it runs, read from `valueEquals` and `compareValues` in `compiler/src/main/kotlin/velle/Evaluator.kt`; `!=` is the opposite of `==`, so "always false" there is "always true". The validator looks at a comparison's operands only when an extension type takes part (`checkExtComparison`, `compiler/src/main/kotlin/velle/Validator.kt`) and refuses an ordered comparison on a `boolean` or a `uuid`; nothing else about the two sides is checked. The last column is what was settled.

| Compared | `==` today | `<` `<=` `>` `>=` today | Settled 2026-11-11 |
|---|---|---|---|
| text and number, field or constant on either side (`value < field.min`, `label == 5`, `amount < "5"`) | validates; always false | validates; runtime error "cannot order" | refused (D1); the text is read where the spec says so, `number(value)`, absent when it does not parse (D3), or the value is declared as a `decimal` on the shape |
| text and `Date` or `DateTime` (`due == "2026-01-01"`) | validates; always false, the text is not read as a date | validates; runtime error | refused, constant and field alike (D1); the reading of a quoted constant as a date was not taken (D2), so a fixed date is `today` plus a duration, an item under "Expressions" in `QUESTIONS.md`; a stored text is read by `date(value)` or `datetime(value)` (D3) |
| text and `duration` | validates; always false | validates; runtime error | refused (D1); a span is a duration literal, `30 days`, or a `duration` field |
| text and `time` (`opensAt == "09:00"`, `opensAt < label`) | validates; the text is read as a time, and one that does not parse is unequal | validates; the text is read as a time, and one that does not parse is a runtime error | a quoted constant stays, checked when the spec is compiled (D2); a field is refused and declared as a `time` (D1) |
| text and `uuid` (`code == "nope"`, `code == label`) | validates; the text is read as a uuid, and one that does not parse is unequal | refused: a uuid has no order | a quoted constant stays, checked when the spec is compiled (D2); a field is refused and declared as a `uuid` (D1) |
| text and `bytes` | validates; always false | validates; runtime error | refused (D1) |
| number and `Date`, `DateTime`, `duration`, `time`, or `bytes` (`due < 5`, `elapsed == 30`) | validates; always false | validates; runtime error | refused (D1); a span is a duration literal, `30 seconds`; a part of a date is `day(due)` |
| `Date` and `DateTime` (`due < shippedAt`) | validates; always false | validates; runtime error | refused (D6); no function gives a moment's date |
| `time` and `DateTime` (`cutoff < shippedAt`) | validates; always false | validates; runtime error | refused (D6); no function gives a moment's time of day |
| `Date` or `DateTime` and `duration` or `bytes`; `Date` and `time` | validates; always false | validates; runtime error | refused (D1) |
| `duration` and `time` or `bytes` | validates; always false | validates; runtime error | refused (D1) |
| `time` and `bytes` | validates; always false | validates; runtime error | refused (D1) |
| `boolean` and anything but a boolean | validates; always false | refused: a boolean has no order | refused (D1) |
| `uuid` and anything but a uuid or a text | validates; always false | refused: a uuid has no order | refused (D1) |

Every row is refused except the two quoted constants D2 keeps. Two rows carried a meaning worth more than a refusal, the quoted date constant (D2) and the date or time of day of a moment (D6); Dave took neither. Every other row is a comparison between kinds that have no common reading, where the author has written something they did not mean. The facts behind the first row and the `time` and `uuid` rows, and one that is not a comparison, follow.

**A text against a number validates and fails at runtime.** `working-docs/examples/survey/05-text-as-number-accepted.velle` writes `value < field.min` with `value: text?` and `min: decimal?`. Reference §5 and `checks.md` F2 both say the compiler rejects this. The compiler is behind its own documentation.

**`+` between two texts validates and fails at runtime.** Not a comparison, so not in the table. `working-docs/examples/survey/06-text-composition-accepted.velle` writes `unitName: text = name + "_unit"`. `scalarTypeOf` (`Validator.kt`) gives the sum no type and nothing checks the operands; `arith` (`Evaluator.kt`) has no text case and throws when the derivation is first read. `wiki/grammar.md` says the spelling is not admitted.

**A text beside a `uuid` or a `time` is read as one, at runtime, whether it is a quoted constant or a stored field.** Reference §5 documents this for a quoted text: `opensAt == "09:00"`. The evaluator does not know whether the text was a constant, so a stored text field beside a time or a uuid is parsed too. The validator checks neither the constant nor the field: a builtin type has no `literals` reader, so `checkConstantAs` runs for extension types only, and `opensAt < label` with `label: text` validates. A quoted text beside a `Date` or a `DateTime` gets no such reading: `due == "2026-01-01"` is always false.

## 2. What this question proposed

Two halves, each a decision of its own, both now settled.

**Take away (§3).** The compiler refuses every comparison in the §1 table, two builtin kinds that differ, and `+` between two texts (D1). It refuses a stored text beside a `uuid` or a `time` and keeps the documented reading of a quoted constant there (D2). It refuses a date or a time of day against a moment (D6). D1 removes nothing a running system can have, because every row of the §1 table fails or is always false at its first evaluation. D2 removes one spelling that runs today, a text field beside a `time` or a `uuid`.

**Add (§4).** Once the compiler refuses the spellings, an author who holds a value as text needs a way to say what they meant. Velle gives them functions that read a text as a number or a date where the spec calls them, absent when the text does not parse (D3). Joining texts is OQ80. The refusal messages of D1 name those spellings.

## 3. What the compiler stops accepting

### D1. Refuse a comparison between two builtin kinds that differ, and `+` between two texts

**Settled 2026-11-11 (Dave): refuse.** Every row of the §1 table except the quoted constant beside a `time` or a `uuid` (D2). `+` between texts is refused here; what an author writes to join texts is OQ80, where Dave expects an interpolation spelling, `"${foo}-${bar}"`, not `foo + "-" + bar`.

This is the rule Reference §5 and `checks.md` F2 already state and the two bug items under "Expressions, refinements, and unions" in `QUESTIONS.md`. Cost: none to any spec that runs. A spec containing any of these spellings fails or is always false the first time the expression is evaluated (§1), so no working system loses anything; an author who wrote one and never ran it is told at compile time instead of at the first commit.

The messages. A build of the compiler made on the morning of 2026-11-10 carried two refusals that no checked-in source has; they are the draft wording, revised for D3's answer:

```
[F2] 'value < field.min' — compares the text 'value' with a decimal; a text is not compared as a decimal. Write number(value), which is absent when the text is not a number, or declare the value as a decimal on a shape of its own
[F2] 'name + "_unit"' — '+' is not defined on a text; Velle does not join or compute with texts. A value made of parts is a shape with a field for each part, and a value that is a number or a date is declared as one
```

The second message names OQ80's spelling once that question settles; until then its second sentence stands as written. A row with no reading, text against bytes, a number against a date, gets the first message's first clause and nothing to write.

### D2. A quoted constant beside a `uuid` or a `time` stays; a stored text beside one is refused

**Settled 2026-11-11 (Dave): keep the documented reading of a quoted text.** The reading stays as Reference §5 states it, for a `uuid` and a `time`, and is checked when the spec is compiled. The extension to a `Date` and a `DateTime` below was not taken, so a fixed date in a spec is still written as `today` moved by a duration (an item under "Expressions" in `QUESTIONS.md`). A stored text beside a `uuid` or a `time` falls under D1.

Why keep the constant: Velle has no literal syntax for a time or a uuid, so `opensAt == "09:00"` is the only way to write one, and Reference §5 promises it. Why refuse the field: a stored text has no moment at which the compiler can see whether it parses, so today a malformed one is silently unequal under `==` and a runtime error under `<` (§1), which is the same hole D1 closes for numbers. Cost: a spec that compares a `time` or `uuid` field to a `text` field runs today when the text is well-formed and is refused after; the spelling it is told to write is to declare that field as a `time` or a `uuid`.

The `Date` and `DateTime` constant, which the table marks as not taken. The recommendation was to read a quoted constant beside a `Date` or a `DateTime` the same way, `due == "2026-01-01"`, since the grammar has no date literal and `today` plus a duration is not a fixed date. Dave kept the reading to what is documented. With D3, a stored date text is read by `date(value)`; a fixed date in a spec remains the open item.

Build: the constant check is a `literals` reader on the builtin `uuid` and `time` types, which is OQ68's next step (builtin types as `VelleType` objects with readers, as extension types have today), after which `checkExtComparison` covers them with no new branch. D2 pulls that step forward or waits for it; a hand-written check for two builtin names would be the parallel branch the design philosophy refuses. The evaluator's text-beside-time and text-beside-uuid cases then serve only a constant the compiler has already read and can become a coercion at compile time.

### D6. A `Date` or a `time` against a `DateTime`

**Settled 2026-11-11 (Dave): refuse.** No function was asked for. If a system needs a moment's date or time of day in the system zone, that is a new item then; the recommendation had been to give the meaning a function beside `year`, `month`, `day`, `hour`, and `minute`, so the zone choice is visible where the author writes it rather than picked quietly by a comparison.

## 4. What an author writes instead

### D3. Whether a stored text is ever read as a number or a date

**Settled 2026-11-11 (Dave): yes, by a function, where the spec calls it.** Dave first answered no, then: "I still think functions serve a purpose here, the same way they do in predicates", pointing at `wiki/Extension-functions.md`. A function is pure, evaluated where a rule or a refinement needs its value, and gives an absent value when it has no answer, which the spec narrows as it narrows any optional. That is the shape of reading a text: explicit at its call, absent when the text does not parse, and nothing silent inside a comparison, which D1 refuses.

The functions: `number(text): decimal?`, `date(text): Date?`, `datetime(text): DateTime?`, each absent when the text does not parse. They are registered functions through `VelleFunction` with `optionalResult` (`compiler/src/main/kotlin/velle/ext/VelleFunction.kt`); no grammar change, no validator case. Whether Velle ships them in the `velle` namespace beside `length` and `trim`, or leaves each system to register its own, is D7: a builtin's name is reserved, so shipping `number` means no library may register a `number` of its own.

The survey spec under this answer, replacing `05-text-as-number-workaround.velle`:

```
act Field {
    name: text
    type: text
    min: decimal?
}

shape Reading {
    fieldName: text
    value: text?
    field: one Field? =
        if exists (Field where name == this.fieldName) then first(Field where name == this.fieldName by name)
        else none
}

never (Reading where field is some and field.type == "number" and value is some and number(value) is none)
never (Reading where field is some and field.min is some and value is some and number(value) < field.min)
```

The first prohibition is the format check; the regular expression of the old workaround goes away. The second holds of nothing when the text is unreadable, which is right because the first already refuses that record. One `number` function is enough: every number at runtime is one value kind (`VNum`, a `BigDecimal`), so `number(value) < field.min` compares against an `integer` or a `double` minimum as it does against a `decimal` one.

Formats: what the Kotlin counterpart's reader accepts and nothing else, `BigDecimal(text)` after trimming, `LocalDate.parse`, `Instant.parse`. Reference §5's rule "each type behaves as its Kotlin counterpart unless the Reference says otherwise" already covers it, so the Reference says one sentence per function. No format argument: a format language in a spec would be Java's pattern syntax, which is Kotlin leaking into Velle; a system whose data arrives in another format is residue, one item, when a system needs it. `datetime` of a text with no offset is absent, because a local time is not a moment; a system that stores local times stores them as `date` and `time` beside each other.

Store side (OQ69): the prohibition runs per record at the commit, in memory, so nothing is needed there for the two prohibitions above. A read that filters on `number(value)` has no sound SQL rendering by `CAST` alone: SQLite's `CAST` of a text that is not a number gives 0, PostgreSQL's throws. A dialect that renders it needs a guard of its own; until then the store filters in memory, which OQ69 already allows.

Names: `number`, `date`, `datetime` read as verbs, "number the value". Naming each after its type, `decimal(value)`, `Date(value)`, reads as a cast and leaves room for `integer(value)`; whether a type word may also be a function name is a lexer check nobody has made. The verbs are what §5 builds unless Dave says otherwise.

The other spelling, kept because it stands whatever D3 said: a shape that can declare the type declares it, one optional typed field per kind on the reading (`numberValue: decimal?`, `dateValue: Date?`, `textValue: text?`) with a `never` per kind tying the filled field to the field's declared kind, and the door reads the number as it reads any `decimal`. The functions are for a value that is text by the business's design.

### D7. Whether the three readers ship as builtins, reserving their names

**Settled 2026-11-11 (Dave): yes.** `number`, `date`, and `datetime` ship in the `velle` namespace, and their names are reserved from then on.

**Recommendation (as decided):** ship them, and state the reservation. `wiki/Extension-functions.md` says the compiler rejects "a registered function whose name is ... a builtin function's name", and a `uses velle.x.f as number` line is refused the same way (E1, `TypeRegistry.kt` and `Model.kt`, `BUILTIN_FUNCTION_NAMES`). So once `number`, `date`, and `datetime` ship, an engineer whose data arrives as `1.234,56` cannot register a `number` that reads it; they register `acme.euroNumber` and the spec calls that. The reservation is the same one `length` and `trim` already make, and a reader that accepts what the Kotlin counterpart accepts is the reading every other builtin already has, since Reference §5 grounds each type in its Kotlin class.

Cost: three more reserved names, chosen now; a system with another format writes a longer name for its reader. The §4 names (`number`, `date`, `datetime`) are the ones reserved, so D7 is also where a different spelling (`decimal(value)`, `Date(value)`) would be chosen.

The alternatives. Ship nothing and document the pattern in `wiki/Extension-functions.md`, one `VelleFunction` an engineer copies: no name reserved, but every system that holds a value as text writes the same forty lines of Kotlin, and the survey spec cannot be written against Velle alone. Or ship them and let a registered function shadow a builtin by name through a `uses` line: that changes E1 for every builtin, which is a question about builtins at large, not about these three.

### D4. Whether Velle joins two texts

Moved to OQ80 (Dave, 2026-11-11): the compiler refuses `+` between texts under D1, and the spelling for joining texts, which Dave expects to be interpolation, `"${foo}-${bar}"`, is designed there. The survey's unit case, the one-field-per-kind spelling for it (`unit: text?` on the reading), and the `concat` alternative with its costs are carried in OQ80 §1 and §2.

### D5. Derived records

Not a decision: a note, kept so the next system with synthesized records lands here. For every quantity field the lab system synthesizes a second field that nothing declared. Velle has no derived records (a derived property is a value or a view, never a new instance) and this question does not propose one. Under OQ80's one-field-per-kind spelling the unit is a field of the reading; under an interpolation it is recognized by name on the reading. Neither makes a record.

## 5. The build

The same list as the three items under "Expressions, refinements, and unions" in `QUESTIONS.md`; this is the readable form, the items are the ones ticked.

1. `Validator.kt`: refuse a comparison, ordered or equality, between two builtin kinds that differ (every row of the §1 table, D1 and D6); `+` between two texts (D1); a text field beside a `uuid` or a `time` (D2). The messages of D1. The place is `checkExtComparison`, which today returns before looking when no extension type takes part, and the `+` case in `scalarTypeOf`, which today gives the sum no type and says nothing.
2. OQ68's next step for D2: `literals` readers on the builtin `uuid` and `time` types, the quoted constant read when the spec is compiled; the evaluator's text-beside-time and text-beside-uuid cases reduced to the constant.
3. `BuiltinFunctions`: `number`, `date`, `datetime`, one signature each over builtin text, `optionalResult = true`, `null` when the reader throws (D3, D7); their names join `BUILTIN_FUNCTION_NAMES` and are reserved from then on, which `wiki/Extension-functions.md` lists with the other builtins. `ExtensionFunctionTest` cases for a readable text, an unreadable one, and an absent argument.
4. `wiki/checks.md` F2 and Reference §5, one sentence each: a comparison never reads a text as another type; a quoted constant beside a `uuid` or a `time` is read as one when the spec is compiled; `number`, `date`, and `datetime` are where a text is read. Reference §5 "The built-in functions": one line per function. `wiki/Extension-functions.md` names them among the builtins. `wiki/grammar.md`: the "only if we ever admit it" clause becomes "not admitted; joining texts is OQ80".
5. `working-docs/examples/survey/`: `05-text-as-number-accepted`, `06-text-composition-accepted`, and `10-mixed-comparisons-accepted` become `-refused` files quoting the diagnostics; `05-text-as-number-function-refused` becomes a passing file, since `number` exists; `05-text-as-number-workaround` becomes the D3 spec above, with the one-typed-field-per-kind spelling beside it; `06-text-composition-workaround` is OQ80's; `README.md` rows updated.
6. Residue, as items when a system needs them: a format argument for the three functions; a SQL rendering of `number` under OQ69.

When the build lands, this file is deleted in that commit and its settled row goes into the table at the bottom of `QUESTIONS.md`.
