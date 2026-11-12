# OQ82 — Does the compiler refuse a property whose expression is not of its declared type?

**Status:** opened 2026-11-11 as a one-line item from the survey pass; given this file 2026-11-12 after OQ89 showed `label: text = (a is none)` validating. D1 to D5 decided 2026-11-12; the build in §3 is for Dave to see before it starts. D5 was opened from Dave's answer to D1.

**In plain terms:** a product owner writes `label: text = ...` and the compiler takes the word `text` on trust. If the formula after `=` makes a yes-or-no, a number, or nothing at all, the compiler says nothing, and the mistake shows up later as a failed read, a failed commit, or a wrong value handed to whoever reads the record. The question is whether the compiler compares the declared type with what the formula makes, and refuses when they differ.

**See:** Reference §5 (the types table; "What the compiler checks", where a text literal with an optional piece in a `text` property is already refused) · Reference §7 ("What the compiler checks": a walk property declared with a type other than the walk's is already refused) · Reference §12 (assignment) · Reference §14 ("A required field given an absent value": a selection that matches nothing in a required `from` field fails the firing at runtime, by design) · `wiki/checks.md` F2 (type checking) and V36 (a filter over a key is one record) · `Validator.kt` `scalarTypeOf` (the type the validator reads off an expression) and `checkExtWrite` (the one place a declared type is compared with a value today, for extension types only) · `working-docs/examples/survey/12-*` (one file per spelling) · `compiler/src/test/kotlin/velle/DeclaredTypeProbeTest.kt` (what the runtime does with each accepted spelling) · OQ68 in `working-docs/QUESTIONS.md` (the numeric tower, where `integer` against `decimal` belongs).

---

## 1. What runs today

Checked against HEAD on 2026-11-12. The validator compares a declared type with an expression in two places only: a walk property against the walk's type (Reference §7), and a value written to a field of an extension type (`checkExtWrite`, `Validator.kt`). Everywhere else the declared type is a label the validator never reads back: a derived property, a `captured` property, an `initially`, a field in a `from` block, and the right side of an assignment all take any expression.

Each row is a file in `working-docs/examples/survey/`. "Validates" means the compiler reports nothing. The runtime column is `DeclaredTypeProbeTest`.

| file | the spelling | validator | runtime |
|---|---|---|---|
| `12-declared-type-predicate-in-text-accepted` | `label: text = (parentName is none)` | validates | the read returns a boolean |
| `12-declared-type-number-in-text-accepted` | `label: text = count * 2` | validates | the read returns a number |
| `12-declared-type-moment-in-date-accepted` | `seen: Date = now` | validates | the read returns a moment, not a date |
| `12-declared-type-absent-in-required-accepted` | `parent: text = parentName` with `parentName: text?` | validates | the read returns absent on a required property |
| `12-declared-type-one-in-many-accepted` | `names: many text = name` | validates | the read returns one text, not a collection |
| `12-declared-type-branches-in-boolean-accepted` | `flagged: boolean = if name == "x" then "yes" else "no"` | validates | the read returns a text |
| `12-declared-type-decimal-in-integer-accepted` | `half: integer = total / 2` | validates | the read returns `1.5` on an `integer` property |
| `12-declared-type-initially-accepted` | `applied: boolean initially 5` | validates | the read returns `true` |
| `12-declared-type-from-block-accepted` | `effective: "yes"` in a `from` block for a `boolean` field | validates | the read fails: `hydrating Verdict.effective: not a boolean (yes)` |
| `12-declared-type-assignment-accepted` | `note.locked = 5` on a `boolean` field | validates | the read returns `true`; with a refinement over the field that captures a value, the commit fails with an internal message, `non-boolean in predicate position: VNum(v=5)` |
| `12-declared-type-captured-accepted` | `captured since: Date = now` | validates | the capture holds a moment |
| `12-declared-type-set-in-one-refused` | `entry: one Entry? = (Entry where name == this.wanted)`, no key on `Entry` | refused by V36 since OQ77 | not reached |

The last row was this question's opening example. V36 now refuses it because the filter is not over a key; the declared type still plays no part, so `entry: one Entry? = (Entry where ...)` over a shape with a key is accepted for the right reason and `entry: text = (Entry where ...)` would be accepted for no reason.

What the validator can read off an expression today (`scalarTypeOf`, `Validator.kt`): a literal, a path to a member, arithmetic, a function call's result, `count` and `sum`, a walk. It reads nothing off a comparison, `is`, `exists`, `and`, `or`, `not`, a conditional, a set denotation, a selector such as `first`, or a `(Shape for ...)` read. Those return "unknown", and every check that uses the inference stays silent on unknown.

## 2. What to decide

### D1. Does the compiler refuse a property whose expression's type differs from its declared type? · Decided 2026-11-12

Does the compiler refuse a derived property, a `captured` property, an `initially`, a field in a `from` block, and an assignment when the type it reads off the expression is not the declared type?

Recommendation: yes, as a case of F2, type checking, which already holds "predicates boolean" and "comparisons like-typed". The meaning of `label: text = (parentName is none)` is undefined: the record has a text field that holds no text. That is an error by the design principle in the root `CLAUDE.md`, not an advisory. The diagnostic names the property, the declared type, and the type the expression makes, and offers both fixes:

```
[F2] 'Kind.label' is declared a text and computed as a boolean; declare it 'label: boolean', or compute a text
```

From `working-docs/examples/survey/12-declared-type-predicate-in-text-accepted.velle`:

```
act Kind {
    parentName: text?                     -- a stored text, may be absent
    label: text = (parentName is none)    -- declared text; the condition makes a boolean: refused under D1
}
```

The spelling that is accepted, against the same act:

```
act Kind {
    parentName: text?                     -- a stored text, may be absent
    isRoot: boolean = parentName is none  -- declared boolean; the condition makes a boolean (OQ89)
}
```

The same check, at each of the five places, from the survey files named in §1: `seen: Date = now` (a moment in a date), `names: many text = name` (one value in a collection), `flagged: boolean = if name == "x" then "yes" else "no"` (both branches text), `applied: boolean initially 5`, `effective: "yes"` in a `from` block, `note.locked = 5`.

Costs:

- A spec that leaned on the silence breaks at compile time. The examples under `examples/` are compiled at build; any that break are rewritten there.
- The inference must read a type off the forms it skips today (§1, last paragraph): a predicate is a boolean, a conditional is its branches' type, a set denotation is a collection of its shape. That is the build's main work and is listed in §3.

#### Proposed changes

- `Validator.kt`: a check comparing the declared type with the expression's type at the five places; `scalarTypeOf` reads a boolean off every predicate form, a conditional's type off its branches, and a collection off a set denotation.
- `wiki/checks.md` F2: a sentence for the declared type against the expression.
- Reference §7, §12, §14, §5 ("What the compiler checks" on each): one line each.
- `DeclaredTypeProbeTest` becomes `DeclaredTypeTest`, one case per survey file.
- The survey's `12-*-accepted` files become `12-*-refused`, with the README rows updated.

**Decided:** yes, 2026-11-12: a declared type that differs from the expression's is a validation failure. Dave added that it would be nicer for the type to be inferred so the mistake cannot be made at all; that is D5.

### D2. Are the whole-number and decimal types one kind for this check? · Decided 2026-11-12

Are `integer`, `long`, `decimal`, and `double` one kind for this check, so that `half: integer = total / 2` and `n: decimal = 5` both pass, and only a non-number in a number is refused?

Recommendation: yes, for now. The validator types every number literal and every arithmetic result as `decimal`, and F2's comparison rule already treats the four as one kind. Refusing `n: decimal = 5` because `5` is "an integer" would be wrong, and telling `total / 2` apart from `count * 2` needs the numeric tower, which is OQ68's open hole. The finding that `half: integer = total / 2` reads back `1.5` goes to OQ68 as a work item.

From `working-docs/examples/survey/12-declared-type-decimal-in-integer-accepted.velle`:

```
act Kind {
    total: decimal             -- a stored decimal
    half: integer = total / 2  -- declared integer; the division is typed decimal; passes under D2, 1.5 at the read
}
```

Costs:

- A wrong state stays representable: an `integer` property holding `1.5`. It is checked nowhere until OQ68.

#### Proposed changes

- OQ68 gains the work item: type arithmetic by its operands so that a `decimal` result in an `integer` property is refused.

**Decided:** yes, 2026-11-12, and OQ68 is to fix it: the four number types are one kind for this check until the numeric tower types arithmetic by its operands. The work item is in OQ68 §6.

### D3. Is an expression that may be absent refused in a required declaration? · Decided 2026-11-12

Is `parent: text = parentName`, with `parentName: text?`, refused because a required property is computed from a value that may be absent? The same for a conditional with a `none` branch in a required property, and for an optional path in a required `from` field or assignment.

Recommendation: yes, when the absence is declared: an optional path, a `none` branch, an optional function result such as `number(text)`. The precedent is Reference §5, where a text literal with an optional piece is refused in a `text` property and `text?` is named. The diagnostic names the `?` to add and the `is some` test that would narrow. A selection that may match nothing, `first(...)` or `(Shape for ...)`, stays the runtime failure Reference §14 decides, since its absence is data, not declaration. Narrowing is respected: `if parentName is some then parentName else "root"` is a text.

From `working-docs/examples/survey/12-declared-type-absent-in-required-accepted.velle`:

```
act Kind {
    parentName: text?           -- a stored text, may be absent
    parent: text = parentName   -- declared required; the path may be absent: refused under D3
}
```

The spellings that are accepted, against the same act:

```
act Kind {
    parentName: text?                                                   -- a stored text, may be absent
    parent: text? = parentName                                          -- the declaration says it may be absent
    parentOrRoot: text = if parentName is some then parentName else "root"  -- narrowed in the then branch, present in the else
}
```

Costs:

- Specs with a required derived property over an optional path must add `?` or a branch. The examples are compiled at build.
- The inference must carry optionality through a conditional and through narrowing (V11's branch-sensitive slice).

#### Proposed changes

- `Validator.kt`: the check of D1 compares optionality too; `scalarTypeOf` types a conditional with a `none` branch as optional and reads a narrowed path as present.
- `wiki/checks.md` F2 and Reference §7: the optional case, naming `text?`.
- `DeclaredTypeTest`: the optional cases, narrowed and not.

**Decided:** yes, 2026-11-12: a declared absence in a required declaration is refused; a selection that matches nothing stays the runtime failure of Reference §14.

### D4. Is an expression whose type the validator cannot read accepted in silence? · Decided 2026-11-12

When the validator reads no type off an expression, is the declaration accepted with no diagnostic?

Recommendation as first written: yes, since refusing on unknown would refuse every form the inference does not cover. Withdrawn in chat on 2026-11-12 when Dave asked why such a declaration should be valid: the gap is in the inference, not a reason to accept a spec. The example that showed it, a shape `Entry` with `name: text unique` and `weight: decimal`:

```
act Lookup {
    wanted: text                                                  -- a stored text
    found: boolean = (Entry where name == this.wanted) is some    -- a condition: typed boolean, checked under D1
    heaviest: text = first(Entry where weight > 0 by weight)      -- a selector: no type read off it today; an Entry or absent, never a text
}
```

Under silence the `heaviest` line validates and the mistake reaches the read. Under refusal with the inference as it is, the correct spelling `heaviest: one Entry? = first(...)` is refused too. The way out is neither: the inference covers every form of the grammar, and what it still cannot type is refused.

Costs:

- The build is larger: the inference gains the selector, the `(Shape for expr)` read, a field read through either, the set denotation, the condition, and the conditional.
- A form the inference misses fails an example's compile under `examples/`, which is the failure wanted.

#### Proposed changes

- `Validator.kt` (or `Model.kt` under D5): the type inference covers every expression form of `grammar.md`; a declaration whose expression it cannot type is F2, naming the expression; a registered function whose signature states no result type is F2, naming the function.
- `wiki/checks.md` F2: the refusal, and that the inference covers every form.
- `DeclaredTypeTest`: a selector, a `for` read, and a field read through each, typed and checked.

**Decided:** no, 2026-11-12: a declaration whose expression's type the compiler cannot read is refused, and the compiler reads a type off every form. Dave confirmed the plan fixes the compiler to do what it does not do today: type every form, and refuse the remainder.

### D5. May a derived property leave its type out and take the expression's? · Decided 2026-11-12

May a derived or `captured` property be declared without a type, `isRoot = parentName is none`, taking the type the validator reads off the expression, so that the mismatch of D1 cannot be written?

Recommendation: yes, for a derived and a `captured` property, when the validator reads a type off the expression. When it reads none, the compiler refuses the declaration and names the type to declare, so an untyped property is never a property of unknown type. A declared type stays allowed and is checked under D1; it is the author's word for the readers of the spec, and for a number it can say `integer` where the inference says `decimal` (D2). A stored property keeps its type, since nothing computes it; a `from` field and an assignment already take their type from the target.

From `working-docs/examples/survey/12-declared-type-predicate-in-text-accepted.velle`, rewritten:

```
act Kind {
    parentName: text?                               -- a stored text, may be absent
    count: integer                                  -- a stored whole number
    isRoot = parentName is none                     -- derived; the condition is a boolean, so isRoot is a boolean
    doubled = count * 2                             -- derived; arithmetic is a number, so doubled is a decimal (D2)
    parent = parentName                             -- derived; an optional text, so parent is a text? (D3)
    label: text = "kind ${count}"                   -- declared, and checked against the literal under D1
}
```

A declaration the compiler refuses, against the same act and a shape `Entry` with a `name: text` field:

```
    entry = first(Entry where name == this.parent by name)   -- no type read off a selector until the build covers it: refused
```

with the diagnostic naming the declaration to write, `entry: one Entry?`. Under D4 the forms the validator reads a type off are listed in `checks.md` F2; the same list says what may leave its type out.

Costs:

- The inference moves from the validator into the model, since the generated Kotlin, the spec generator, and the API description all read a derived property's type today (`Codegen.kt`, `SpecGen.kt`, `apis/json` `Describe.kt`). It is the largest part of the build.
- Two spellings for one declaration, typed and untyped. The typed one is documentation and the only one that says `integer`.
- A reader of the spec no longer sees the type on the line. The generated surfaces still carry it.
- The grammar changes: `derivedProp := Identifier (":" propType)? "=" valueExpr`, in `grammar.md` and Reference §7.

#### Proposed changes

- `Ast.kt`: `DerivedProp.type` becomes optional; `Parser.kt` accepts `name = expr` in a shape or refinement body.
- `Model.kt`: the expression type inference, moved from `Validator.kt`, resolves an untyped property's type once; a property with no readable type is F2.
- `Codegen.kt`, `SpecGen.kt`, `apis/json` `Describe.kt`, `Printer.kt`: read the resolved type.
- `wiki/grammar.md`, Reference §7, Reference §8 (`captured`), `wiki/checks.md` F2.
- `DeclaredTypeTest`: an untyped property of each readable form, and the refusal of an unreadable one.

**Decided:** yes, 2026-11-12, with one addition from Dave: two properties that refer to each other with neither declaring a type are refused. The resolution runs in dependency order, and a cycle among untyped properties is a diagnostic naming the properties and the type to declare on one of them.

## 3. The build

Each decision's proposed changes, in build order.

1. **The type inference becomes a model fact** (D4, D5). `scalarTypeOf` and its helpers (`operatorLine`, `typeNamed`, `valueCollectionType`, the function resolution it calls) move from `Validator.kt` into `Model.kt` as the one reading of an expression's type, over a scope and the aliases in it. It covers every form of `grammar.md`: a literal, a path, arithmetic, a call, `count` and `sum`, a walk (today); a condition, `is`, `exists`, `and`, `or`, `not` as `boolean`; a conditional as its branches' type, optional when a branch is `none` or optional, with a path narrowed by the condition read as present (D3); a set denotation as a collection of its shape; `first` and `latest` and `(Shape for expr)` as that shape, optional; a field read through any of them as the field's type; a text literal with pieces as `text`, optional when a piece is (Reference §5). A registered function whose signature states no result type reads as untyped.
2. **An untyped property resolves once** (D5). `Ast.kt`: `DerivedProp.type` becomes optional. `Parser.kt`: `name = expr` and `captured name = expr` in a shape or refinement body. `Model.kt`: each derived and `captured` property's type is its declaration's, or its expression's, resolved in dependency order over the untyped ones; a cycle among untyped properties is F2 naming each property in it and the type to declare on one; an untyped property whose expression reads as untyped is F2 naming the expression (D4). `Codegen.kt`, `SpecGen.kt`, `Printer.kt`, `apis/json` `Describe.kt`, and every other reader of `DerivedProp.type` read the resolved type from the model.
3. **The check** (D1, D2, D3, D4). `Validator.kt`, under F2, at five places: a derived property, a `captured` property, an `initially`, a field in a `from` block, the right side of an assignment. The declared type against the expression's: the four number types one kind (D2); a required declaration refuses an optional expression (D3), naming `?` and the `is some` test; an expression that reads as untyped is refused naming it (D4). The diagnostic: `'Kind.label' is declared a text and computed as a boolean; declare it 'label: boolean', or compute a text`.
4. **Regenerate** every module under `examples/` (the `implement` skill §2).
5. **Wiki.** `wiki/checks.md` F2: the declared type against the expression, the optional case, the untyped refusal, the cycle, and that the inference covers every form. Reference §7: the untyped spelling, `isRoot = parentName is none`, beside the typed one, and the cycle rule; "What the compiler checks" on §5, §7, §8 (`captured`), §12, §14: one line each. `wiki/grammar.md`: `derivedProp := Identifier (":" propType)? "=" valueExpr`, the same for `captured`.
6. **Examples.** `examples/loyalty/loyalty.velle`: `onTime` and one or two more derived properties written untyped, so the generated output exercises the resolved type. The survey's `12-declared-type-*-accepted` files become `-refused` with their README rows quoting the diagnostic; `12-declared-type-decimal-in-integer-accepted` stays accepted under D2 and its row points at OQ68.
7. **Tests.** `DeclaredTypeProbeTest` becomes `DeclaredTypeTest`: one case per survey file, the untyped spelling of each readable form, the cycle, the untyped-expression refusal, the narrowed conditional. The full suite against the baseline (the `implement` skill §3).
8. **Settle.** The OQ82 line leaves `working-docs/QUESTIONS.md`, a row goes to the top of the settled table, this file is deleted in the same commit.

## 4. Work items

- [ ] Move the expression type inference into `Model.kt` and cover every form of `grammar.md` (§3 step 1).
- [ ] Make `DerivedProp.type` optional, parse `name = expr`, resolve untyped properties in dependency order, refuse a cycle and an untyped expression (§3 step 2).
- [ ] Add the declared-type check at the five places under F2 (§3 step 3).
- [ ] Regenerate every example module (§3 step 4).
- [ ] Write `checks.md` F2, Reference §5, §7, §8, §12, §14, and `grammar.md` (§3 step 5).
- [ ] Rewrite `examples/loyalty/loyalty.velle` and the survey's `12-*` files with their README rows (§3 step 6).
- [ ] Turn `DeclaredTypeProbeTest` into `DeclaredTypeTest` and run the suite against the baseline (§3 step 7).
- [ ] Settle: the directory line, the settled row, this file deleted (§3 step 8).
