# OQ141 — Does a `?.` comparison that holds, or the right side of an `or`, show an optional step present?

**Status:** opened 2026-11-17 from a stress pass of a domain spec, re-verified at commit 76cff78. D1 decided 2026-11-17, build pending; D2 awaits Dave.

**In plain terms:** a rule says "a value whose attribute is a relation must name one of the attribute's relations", and another says "a value with units must have an attribute that has units". Both are refused, because each reads the attribute in a place where the attribute can only be present, but the compiler recognises only an `is some` or `is none` test as showing that. The author writes the longer spelling with the test added, or turns the `or` inside out.

**See:** Reference §10 "Reaching through relationships" (the tests that let a plain dot read through an optional step); `checks.md` V11; OQ125 and OQ128 in the settled table of `working-docs/QUESTIONS.md` (a test on a path shows every step present; a plain dot with no test is refused); `compiler/src/main/kotlin/velle/ExprTypes.kt` (`narrowingOf`, `untestedStep`), `compiler/src/main/kotlin/velle/Validator.kt` (`checkDotsLicensed`, `withPresence`); the survey files `working-docs/examples/survey/18-presence-*`.

---

## 1. What runs today

Both specs below are refused today. Every comment says what the compiler does with the line now.

`working-docs/examples/survey/18-presence-optional-comparison-refused.velle`:

```
act Attr { kind: text?, relations: many text, hasUnits: boolean }
                                                 -- an attribute; `kind` is optional
act Value { attr: one Attr?, x: text, units: text? }
                                                 -- a value; `attr` is optional
never (Value where attr?.kind == "relation" and x not in attr.relations)
                                                 -- `attr?.kind` reads absent when `attr` is absent, and an
                                                 -- absent value equals no text, so the left conjunct holds only
                                                 -- when `attr` is present. REFUSED today, V11: "'attr.relations'
                                                 -- reads through 'attr', which may be absent; test 'attr is some'
                                                 -- first, or write 'attr?.relations' to read absent when it is"
```

`working-docs/examples/survey/18-presence-or-right-side-refused.velle`:

```
act Attr { hasUnits: boolean }
act Value { attr: one Attr?, units: text? }
never (Value where units is some and (attr is none or not attr.hasUnits))
                                                 -- the right side of the `or` is read only when `attr is none`
                                                 -- failed, so `attr` is present there. REFUSED today, V11:
                                                 -- "'attr.hasUnits' reads through 'attr', which may be absent;
                                                 -- test 'attr is some' first, or write 'attr?.hasUnits' to read
                                                 -- absent when it is"
```

What validates today, `working-docs/examples/survey/18-presence-or-right-side-workaround.velle`:

```
never (Value where attr is some and attr.kind == "relation" and x not in attr.relations)
                                                 -- ACCEPTED: the `is some` test is written out
never (Value where units is some and not (attr is some and attr.hasUnits))
                                                 -- ACCEPTED: the `or` is turned into `not (... and ...)` so the
                                                 -- read sits in one conjunction with its test. It reads less
                                                 -- directly than the rule as a product owner says it.
```

Why: V11 shows a step present only by an `is some` or `is none` test in the same conjunction, in the governed branch of an `if`, in the refinement's own `where`, in a rule's inline `where`, or by a prohibition the rules cannot move (Reference §10, `checks.md` V11). A comparison is not read as a presence test, and the right side of an `or` is not read under the failure of its left side. The evaluator's `==` on an absent operand is false (`compiler/src/main/kotlin/velle/Evaluator.kt`, `valueEquals`), so the first spec's reasoning is sound at run time; it is the compiler that does not follow it.

## 2. What to decide

### D1. Whether `a?.f == <value>` holding shows `a` present · Decided 2026-11-17

Does `attr?.kind == "relation"` holding, in the same conjunction, let a later plain dot read `attr.relations`?

Recommendation: yes, for `==` and `in` with a `?.` path on one side. An absent value equals no value and is in no list (Reference §10; `Evaluator.kt`), so the comparison holding means every step of the `?.` path is present. This is OQ125's rule, "a test on a path shows every step of it present", with a comparison as the test. The spelling `attr?.kind == "relation" and x not in attr.relations` says the rule once, in the order a product owner says it.

Only the holding side carries the fact. `attr?.kind != "relation"` and `attr?.kind not in [...]` are true when `attr` is absent, so they show nothing, and the `else` branch of an `if` over `==` shows nothing either. Ordered comparisons, `attr?.weight > 3`, are false on an absent operand (Reference §10) and would carry the same fact; the recommendation includes them so the rule is "a comparison that holds shows the paths it reads present", with `!=` and `not in` the two exceptions.

Costs:

- The compiler reasons from the evaluator's treatment of an absent operand. That treatment is already stated in Reference §10 ("an absent operand satisfies no ordered comparison") for the ordered operators; the equality case is stated for `in` and must be stated for `==` in the same place. A design in which the wrong state cannot be represented is not at stake; the question is how much of a true fact the compiler reads.
- Nothing is removed. Every spelling accepted today is still accepted.
- A reader of the spec must know that `?.` on the tested side is what makes the fact available, as with `site?.region is some` today.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ExprTypes.kt` (`narrowingOf`): a comparison with `==`, `in`, `<`, `<=`, `>`, or `>=` whose operand is a path with `?.` yields the same presence facts as `is some` on that path, on its holding side only.
- `wiki/Reference/Reference-10-Predicate-expressions.md` "Reaching through relationships": one sentence after the `site?.region is some` paragraph, with the `attr?.kind == "relation"` example, and the statement that `==` on an absent operand is false.
- `wiki/Normative/checks.md` V11: the comparison added to the list of what shows a step present.
- `compiler/src/test/kotlin/velle/PresenceThroughPathTest.kt`: the first spec in §1 accepted; the same with `!=` still refused.
- `working-docs/examples/survey/18-presence-optional-comparison-refused.velle` renamed without the suffix and its README line updated.

**Decided:** yes, 2026-11-17. A comparison that holds shows the `?.` paths it reads present, on its holding side only; `!=` and `not in` show nothing.

### D2. Whether the right side of an `or` is read under the failure of its left side

Does `attr is none or not attr.hasUnits` let the right side read `attr.hasUnits` with a plain dot?

Recommendation: yes. `P or Q` evaluates `Q` only when `P` failed, so inside `Q` the facts that `not P` establishes hold. For `P` = `attr is none`, `not P` is `attr is some`. This is the mirror of today's conjunction rule, "a test earlier in the same `and` chain", and of the `else` branch of an `if`. OQ128 refused the plain dot with no test anywhere; here the test is present, on the other side of the `or`.

The alternative keeps today's rule and the `not (attr is some and attr.hasUnits)` spelling. Its cost is that the rule as spoken, "no attribute, or an attribute without units", cannot be written as spoken.

Costs:

- The narrowing engine reads `or`. Today it reads `and`, `if`, and `not` over a presence test; the `else` of `is none` is already the negation case, so the mechanism exists and gains one more site.
- Nothing is removed.
- The rule generalises: inside the right operand of `or`, every fact the negation of the left operand establishes holds, including a union narrowing from `x is not A or ...`. The proposal applies the same rule to both kinds of fact so there is one rule to state.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ExprTypes.kt` and `compiler/src/main/kotlin/velle/Validator.kt` (`withPresence`): the right operand of `or` is checked under the negation of the left operand's facts.
- `wiki/Reference/Reference-10-Predicate-expressions.md` "Reaching through relationships": the `or` case stated beside the `and` case.
- `wiki/Normative/checks.md` V11: the right side of an `or` added to the list.
- `compiler/src/test/kotlin/velle/PresenceThroughPathTest.kt`: the second spec in §1 accepted; `attr is some or attr.hasUnits` still refused, since the right side runs when `attr` is absent.
- `working-docs/examples/survey/18-presence-or-right-side-refused.velle` renamed without the suffix, the workaround file deleted, README lines updated.

## 3. Work items

- [ ] Decide D2.
