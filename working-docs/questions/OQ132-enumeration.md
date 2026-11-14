# OQ132 — A field that takes one of a few words: does Velle get an enumeration?

**Status:** opened 2026-11-14 at Dave's request ("add enums to velle"). Every decision settled 2026-11-14; the build section awaits Dave's look, then the build.

**In plain terms:** many fields hold one of a few fixed words: a staff member's role is pharmacist, technician, or admin; a verification's outcome is approved or rejected; a survey field's type is number, date, or text. Today such a field is a text, and a prohibition lists the allowed words. The list binds what arrives at the door and nothing else: a rule or a refinement that misspells a word compiles, and the misspelt word is simply never equal to anything. The question is whether the words become a type of their own, declared once in the spec, so that a field of that type cannot hold any other word and a misspelt word in a rule is a compile error. Programmers call such a type an enumeration; this file uses the word in that sense.

**See:** Reference §5 (the scalar types; the reading of a quoted text beside a `uuid` or a `time`) · Reference §21 (`requires`; the fourth line of its first example is this workaround) · Reference §10 (`in [...]`) · `wiki/Extension-types.md` (the engineer's workaround: a Kotlin type with a text reader) · `checks.md` F2 (a constant the type rejects), V18 (the complement slice of coverage), V21 (what the boundary refuses) · OQ54 in `working-docs/QUESTIONS.md` (exhaustiveness; D5 below leans on it) · OQ68 (the builtins as registered types; an enumeration is a type the compiler would register per spec) · `working-docs/examples/survey/16-enumeration-*.velle`

---

## 1. What runs today

Nine `requires` lines across the examples spell "one of these words", each over a text field:

| File | Line |
|---|---|
| `examples/pharmacy/pharmacy.velle` | `Staff requires role == "pharmacist" or role == "technician" or role == "admin"` |
| `examples/pharmacy/pharmacy.velle` | `Interaction requires severity == "major" or severity == "moderate"` |
| `examples/pharmacy/pharmacy.velle` | `VerifyFill requires outcome == "approved" or outcome == "rejected"` |
| `examples/pharmacy/pharmacy.velle` | `Adjudication requires outcome == "paid" or outcome == "rejected"` |
| `examples/payments/payments.velle` | `ProcessorVerdict requires outcome == "approved" or outcome == "declined" or outcome == "error"` |
| `examples/membership/membership.velle` | `RaiseTicket requires priority == "low" or priority == "normal" or priority == "high"` |
| `examples/vendors/vendors.velle` | `Staff requires role == "staff" or role == "admin"` |
| `examples/peptides/peptides.velle` | `GreenhousePlot requires treatment == "peptide" or treatment == "control"` |
| `examples/peptides/peptides.velle` | `FieldPlot requires treatment == "peptide" or treatment == "control"` |

The shorter spelling, `requires type in ["number", "date", "text"]`, validates too (`working-docs/examples/survey/16-enumeration-workaround.velle`). Beside the ten, `examples/billing/billing.velle` computes `status: text` as one of `"paid"`, `"overdue"`, `"open"`, and `examples/loyalty/loyalty.velle` assigns `member.tier = "gold"` and `"advocate"` to a `tier: text initially "basic"` with no list anywhere.

What the workaround gives and what it leaves open:

- **It binds the door only.** A `requires` is a refusal over an act or a constraint over a shape (Reference §21). In the pharmacy, `VerifyFill requires outcome == "approved" or outcome == "rejected"` guards the request, and the kept `Verification.outcome: text`, which `RecordVerification` copies from it, has no list at all. It holds the right words today because one rule writes it; a second rule could write anything.
- **A misspelt word compiles.** `shape NumberField = Field where type == "numbr"` validates, and `NumberField` is empty forever (`working-docs/examples/survey/16-enumeration-misspelt-word-accepted.velle`). The same goes for a `from` block, an assignment, and a derived `if` chain: every place a word is written is a text the compiler compares with a text.
- **The reader learns the words from a prohibition, not from the field.** `role: text` says nothing; the reader finds the `requires` line, when there is one.
- **The engineer's spelling costs a library.** An engineer can write the words as an extension type today: a Kotlin class implementing `VelleType<Role>` with a `literals.fromText` reader that refuses any word not on the list, registered through `META-INF/services`, plus a store binding per store that holds it (`wiki/Extension-types.md`, "Writing a type" and "Holding a type in a store"). Then `role == "pharmcist"` is an F2 error at the constant, as `opensAt < "9 oclock"` is for a `time`. That is Kotlin and a library for a sentence the product owner said.

The proposed declaration is refused by the parser today, `expected a declaration (shape/act/rule/never/requires/visible/uses), found UIDENT('FieldType')` (`working-docs/examples/survey/16-enumeration-declared-refused.velle`).

Nothing is removed by this question. The `requires` spelling keeps working whatever is decided.

## 2. What to decide

### D1. Whether the words become a type of their own, declared in the spec · Decided 2026-11-14

Does a spec declare the words a field takes as a type, so the field is of that type and no other word can reach it?

Recommendation: yes. The principle "a design in which a wrong state cannot be represented beats one in which it is checked" decides this by default: `outcome: Outcome` cannot hold a word that is not an outcome, where `outcome: text` plus a `requires` is a check at one door. The survey system, `working-docs/examples/survey/16-enumeration-declared-refused.velle`, written with the declaration:

```
FieldType is one of "number", "date", "text"       -- declares a type named FieldType whose values are exactly these three words (spelling: D2)

act Field {
    name: text unique                                -- the field's key (Reference §21, "Keys")
    type: FieldType                                  -- holds one of the three words and nothing else; the door refuses any other word with the list
}

shape NumberField = Field where type == "number"     -- the quoted word is read as a FieldType when the spec is compiled; "numbr" is an F2 error here

shape Reading {
    fieldName: text
    numberValue: decimal?
    field: one Field? =
        if exists (Field where name == this.fieldName) then first(Field where name == this.fieldName by name)
        else none
}

never (Reading where field is some and field is NumberField and numberValue is none)   -- a constraint on the books, unchanged
```

The same spec today is `working-docs/examples/survey/16-enumeration-workaround.velle`: `type: text` and `Field requires type in ["number", "date", "text"]`, where `"numbr"` compiles.

Costs:

- A new declaration form and a new kind of type the compiler registers per spec, beside the builtins and the extension types. Through OQ68's lens it is a `VelleType` the compiler constructs from the declaration, with `fromText` as its one reader and `canonical` as the word, so every consumer that already dispatches on the registry gets it for nothing; the ones that still branch on `VType` (OQ68 §2) each gain a branch or move to the registry first.
- A stored word is held as its text; no store binding is needed, since the SQL stores already hold `text`. A store that someone wants to hold the word as a small integer writes a binding as for any type.
- The generated Kotlin surface gains a class per declaration (D6).
- The wiki: Reference §5 gains a section, Reference §21's first example loses its fourth line to the new form, `grammar.md` gains the production, `checks.md` F2 and V21 name the new case.
- Every example in §1 is rewritten to the declaration, and its output regenerated.

#### Proposed changes

- `compiler/`: the parser accepts the declaration (D2); the validator registers a type per declaration in the spec's type scope, reads a quoted constant beside it (D3), and refuses a word not on the list at the constant (F2); the boundary refuses a committed word not on the list, naming the list (V21); Codegen, StoreGen, SpecGen, and the SQL stores hold the word as text and emit the Kotlin class of D6.
- `wiki/Reference-05-Scalars.md`: a section "A field that takes one of a few words" with the declaration, what the compiler reads and refuses, `many` and `?` over it, and the nine examples' new lines.
- `wiki/Reference-21-never.md`: the first example's fourth line becomes the declaration, and the sentence "The fourth restricts a field to a list of values" goes.
- `wiki/grammar.md`: the declaration production and the contextual words it adds.
- `wiki/checks.md`: F2 names a word the type does not list; V21 names a committed word the type does not list.
- `examples/pharmacy`, `payments`, `membership`, `vendors`, `peptides`, `billing`, `loyalty`: each text field above declared with its type, the `requires` lines dropped, output regenerated.
- `working-docs/examples/survey/16-enumeration-declared-refused.velle` renamed to the accepted form and its README row updated.

**Decided:** yes, 2026-11-14. The words become a type declared in the spec.

### D2. The spelling of the declaration · Decided 2026-11-14

Is the declaration `FieldType is one of "number", "date", "text"`?

Recommendation: yes. It is the sentence the product owner says, "a field type is one of number, date, or text". `is` and `one` are keywords already, `of` becomes a contextual word that reads as part of the declaration only directly after `is one` at the top level, and the words stay quoted texts, so nothing new enters the identifier scope: a bare lowercase `pharmacist` keeps reading as a path, which matters because `examples/pharmacy/pharmacy.velle` already has a field named `pharmacist` beside a role word `"pharmacist"`. The declaration starts with an uppercase name, as `Staff requires ...` does, so the parser has the precedent.

The alternative, against the same spec:

```
FieldType = "number" or "date" or "text"           -- mirrors `shape Content = Post or Video`, the closed union of shapes (Reference §9)
```

It is shorter and parallels the union. Against it: `=` is derivation everywhere else in a spec, and a product owner reads `or` between quoted words as a predicate, not a list of members.

Costs of the recommendation:

- One more contextual word, `of`. A property named `of` stays legal, as `steps` and `minutes` do.
- A spec that declares a type and a shape under one name is refused, as for an extension type today (E1).

#### Proposed changes

- `compiler/src/main/kotlin/velle/Parser.kt`, `Tokens.kt`: the production `TypeName "is" "one" "of" textLiteral ("," textLiteral)+` at the top level.
- `wiki/grammar.md`: the production, and `of` in the list of contextual words.

**Decided:** yes, 2026-11-14. The declaration is `FieldType is one of "number", "date", "text"`.

### D3. Whether a word in a rule stays a quoted text, read as the type when the spec is compiled · Decided 2026-11-14

Does a word written in a rule, a refinement, a `from` block, an assignment, or a list after `in` stay a quoted text that the compiler reads as the type, so a word not on the list is an error where it is written?

Recommendation: yes. Reference §5 already has the rule for a `uuid` and a `time`: "a quoted text beside a `uuid` or a `time` is read when the spec is compiled, and a text the type cannot read is an error there". An enumeration is the third type with no literal of its own. `type == "numbr"` becomes `[F2] '"numbr"' — 'FieldType' rejects this constant: not one of "number", "date", "text"`, in the F2 shape `checkConstantAs` already prints. `"number" == type` reads the same way round. Two values compare with `==` and `!=`; `in ["number", "date"]` reads each listed word. A field of the type takes `?` and `many` as any scalar does. A word committed through the door that is not on the list is refused at the door, naming the field and the list, as a `time` that cannot be read is refused today (V21). At the boundary and in the read API the word arrives and leaves as its text.

The alternative is a bare name per word, `type == number`, as an enumeration is spelled in most programming languages. Against it: casing is load-bearing in Velle, a lowercase bare name is a path, and the words would need either a scope of their own or a qualified spelling, `FieldType.number`, which is code.

Costs:

- A text field and a word field cannot be compared: `reading.textValue == field.type` is an F2 error, "compares the text with a FieldType". A spec that holds the word as text on one side writes a reading function, which does not exist yet: `fieldType(value)` by analogy with `number(value)` (Reference §5), absent when the text is not one of the words. Whether such a function exists per declaration is a question for the build; the recommendation is that it does, under the type's name in lowercase, since every other reading of a text is a function with an absent result (design principle).
- A derived `if` chain over the type, `status: Status = if balance <= 0 then "paid" else if due < today then "overdue" else "open"`, reads each word; a branch that yields a text that is not a word is an F2 error.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Validator.kt`: the type reads a quoted constant through the existing `checkConstantAs` path; a `from` block value, an assignment, an `initially`, a derived property's branches, and a listed value after `in` go through it.
- `compiler/`: the boundary refuses a committed word not on the list (V21), the rejection naming the field and the list.
- `compiler/`: a reading function per declaration, `fieldType(text)`, absent when the text is not a word.
- `wiki/checks.md` F2, V21; `wiki/Reference-05-Scalars.md` as in D1.

**Decided:** yes, 2026-11-14. A word keeps its quotes, `role == "pharmacist"`, and the compiler checks it against the list; a bare `pharmacist` would collide with a field of that name.

### D4. Whether the words are ordered by the order they are declared · Decided 2026-11-14

Is a word field ordered, so that `priority >= "normal"` means "normal or high" when the declaration lists low, normal, high in that order?

Recommendation: no. A word field takes `==`, `!=`, and `in`, and nothing else, as a `uuid` does (Reference §5). An order that depends on the order of a list is a meaning an author easily does not intend: a list written alphabetically, or extended at the end with a word that belongs in the middle, silently changes every `>=` over it. A business that ranks its words says so with a number, `rank: integer = if priority == "low" then 1 else if priority == "normal" then 2 else 3`, where the ranking is visible.

Costs:

- `max`, `min`, a selector's `by`, and a query's order over a word field are refused (F2). The derived number is the spelling.

**Decided:** no, 2026-11-14. The words have no order; `==`, `!=`, and `in` only.

### D5. Whether a rule per word, together, answer a request · Decided 2026-11-14

When a transient act carries a word field and there is one answer per word, does the compiler prove the request is always answered?

Recommendation: yes, within V18's present slice. V18 proves coverage when two answers are syntactic complements, `P` and `not P` (`checks.md` V18). Over a type with exactly the words `"approved"` and `"rejected"`, `outcome == "approved"` and `outcome == "rejected"` are complements, and over three words three answers are. Today the same partition over a text field is rejected by V18, because a text has infinitely many other values, and the author writes the complement rule as one conditional. The general engine is OQ54's; this is the one case the declaration makes decidable by counting. From `examples/payments/payments.velle`, as it would read:

```
Verdict is one of "approved", "declined", "error"          -- three words, no others

transient act ProcessorVerdict {
    attempt: one ChargeAttempt
    outcome: Verdict                                        -- the request carries one of the three
}

rule RecordApproval when (ProcessorVerdict where outcome == "approved") { ... }   -- answers one word
rule RecordFailure  when (ProcessorVerdict where outcome in ["declined", "error"]) { ... }   -- answers the other two; together they cover the type, so V18 passes
```

Today `RecordVerdict when ProcessorVerdict` answers the bare act and the two paths split later in `SuccessfulCharge` and `FailedCharge`; nothing in the example needs to change for D5, and the example above is what D5 admits.

Costs:

- V18 gains a case: a set of answers each of which is `field == word` or `field in [words]` over one word field, with every word named once, is coverage. Anything more, a word test beside another condition, stays with OQ54.

#### Proposed changes

- `compiler/`: V18 accepts a set of answers whose word tests over one field of the act name every word of its type.
- `wiki/checks.md` V18: the sentence.
- `working-docs/questions/OQ54-exhaustiveness.md` §1: a pointer that the per-word slice is V18's.
- `compiler/`: when a word is not answered, V18 names it: `a 'Review' whose 'outcome' is "error" arrives and no rule or 'never' answers it ...; answer "error" in a rule, or refuse it with 'never (Review where outcome == "error")'`.
- `working-docs/examples/survey/16-enumeration-per-word-answers-refused.velle`: the probe that V18 refuses today over a text field; becomes the accepted spelling over the declared type, and its README row is updated.

**Decided:** yes, 2026-11-14. Shown first against the probe, which V18 refuses today over a text field, with the message the compiler would print when one word is left unanswered.

### D6. What the engineer's Kotlin sees · Decided 2026-11-14

Does the generated Kotlin surface hold a word field as a Kotlin `enum class` per declaration, or as a `String`?

Recommendation: an `enum class` named after the declaration, one entry per word, with the word as the entry's text. The input class of an act (`wiki/Act-API.md`) and the read API then refuse a wrong word at compile time in the engineer's code too, and a `when` over the field in Kotlin is exhaustive. A `String` would be the smaller change and leave the engineer where the spec was before D1.

Costs:

- A word that is not a legal Kotlin name, `"in-progress"` or `"n/a"`, needs an entry name. The build spells the entry by replacing every character that is not a letter or digit with an underscore, and the entry keeps the word as its text; two words that collapse to one entry name are refused at the declaration (F1).
- The store row keeps the text, so the generated store code converts between the entry and the word at the row.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Codegen.kt`, `StoreGen.kt`, `Read.kt`: an `enum class` per declaration in the generated package; the input class, the model class, the field handle, and the row conversion use it.
- `wiki/Act-API.md`: a sentence on the class.

**Decided:** a Kotlin `enum class`, 2026-11-14.

## 3. The build

In build order. Each line is from a decided decision's "Proposed changes".

**Compiler**

1. `compiler/src/main/kotlin/velle/Tokens.kt`, `Parser.kt`: the top-level production `TypeName "is" "one" "of" textLiteral ("," textLiteral)+`, with `of` contextual in that one position (D2). Two declarations of one name, or a word listed twice, is a parse-level error naming the line.
2. `compiler/src/main/kotlin/velle/Model.kt`, `TypeRegistry.kt`: a type per declaration, constructed by the compiler as a `VelleType<String>` in the spec's own type scope, with `fromText` as its one reader (refusing a text not on the list with the list as the reason), `canonical` the word itself, no ordering, no operators, no `sum` (D1, D4). A shape or an extension type in scope under the same name is refused as E1 refuses it today.
3. `compiler/src/main/kotlin/velle/Validator.kt`: the type in field position, with `?` and `many`; a quoted constant beside it read through `checkConstantAs` in every position a word can stand (comparison either way round, `in [...]`, a `from` block, an assignment, `initially`, a derived property's branches), so a word not on the list is F2 at the constant; `==`, `!=`, and `in` only, with `<`, `max`, `min`, `by`, and a query's order refused by F2; a word field beside a `text` field refused by F2 as two kinds that differ (D3, D4).
4. `compiler/`: a reading function per declaration under the type's name in lowercase, `fieldType(text)`, absent when the text is not a word (D3).
5. `compiler/`: V21, the boundary refuses a committed word not on the list, the rejection naming the field and the list (D3).
6. `compiler/`: V18 accepts a set of answers each `field == word` or `field in [words]` over one word field of the act whose words together name every word of the type; when one is missing, the message names the word, as D5 writes it (D5).
7. `compiler/src/main/kotlin/velle/Codegen.kt`, `StoreGen.kt`, `Read.kt`, `SpecGen.kt`, and the SQL stores: an `enum class` per declaration in the generated package, each entry named by the word with every character that is not a letter or digit replaced by an underscore, carrying the word as its text, two words that collapse to one entry refused (F1); the input class, the model class, the field handle, and the row conversion use it; the row holds the word as text; the test samples are the first word and none below or above (D6, D1).
8. `compiler/src/test/kotlin/velle/`: a test per item above: the parse, F2 at a misspelt word, F2 on an ordered comparison, V21 at the door, V18 coverage and the named missing word, the generated enum class and the round trip through a store.

**Wiki**

9. `wiki/Reference-05-Scalars.md`: a section "A field that takes one of a few words": the declaration, the quoted-word rule beside `uuid` and `time`, `?` and `many`, no order, the reading function, what the door refuses, and the examples' lines (D1 to D4).
10. `wiki/Reference-21-never.md`: the first example's fourth line becomes the declaration, and "The fourth restricts a field to a list of values" goes (D1).
11. `wiki/grammar.md`: the production and `of` among the contextual words (D2).
12. `wiki/checks.md`: F2 names a word not on the list and an ordered comparison on a word field; V21 names a committed word not on the list; V18 names the per-word coverage and the missing-word message (D3, D4, D5).
13. `wiki/Act-API.md`: a sentence on the generated `enum class` (D6).

**Examples**

14. `examples/pharmacy/pharmacy.velle` (`Staff.role`, `Interaction.severity`, `VerifyFill.outcome` and `Verification.outcome`, `Adjudication.outcome`), `examples/payments/payments.velle` (`ProcessorVerdict.outcome`, `ChargeResponse.outcome`, `CompletedAttempt.outcome`), `examples/membership/membership.velle` (`RaiseTicket.priority`, `Ticket.priority`), `examples/vendors/vendors.velle` (`Staff.role`), `examples/peptides/peptides.velle` (`GreenhousePlot.treatment`, `FieldPlot.treatment`), `examples/billing/billing.velle` (`Invoice.status`), `examples/loyalty/loyalty.velle` (`Member.tier`): each declared with its type, the nine `requires` lines dropped, output regenerated (D1).
15. `working-docs/examples/survey/16-enumeration-declared-refused.velle` and `16-enumeration-per-word-answers-refused.velle`: renamed to their accepted forms, `16-enumeration-misspelt-word-accepted.velle` renamed `-refused` with the F2 message, the README rows updated (D1, D3, D5).

**Settling**

16. The suite green; the settled row `| OQ132 — a field that takes one of a few words is a declared type | Reference §5 "A field that takes one of a few words"; checks F2, V18, V21 |` at the top of the table; this file deleted in the same commit; the OQ54 pointer in `working-docs/questions/OQ54-exhaustiveness.md` §1 (D5).

## 4. Work items

- [ ] Build items 1 to 8, the compiler and its tests.
- [ ] Build items 9 to 13, the wiki.
- [ ] Build items 14 and 15, the examples and the survey probes, and regenerate output.
- [ ] Land the settling commit, item 16.
