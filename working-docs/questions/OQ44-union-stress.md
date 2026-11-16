# OQ44 — Stress-testing cross-base unions

**Status:** opened 2026-09-13 at OQ29's close. Reviewed against HEAD on 2026-11-16, after OQ35 landed `is`: every case in §1 has a verdict and a checked-in probe. D1 and D2 decided and built 2026-11-16; D3 awaits Dave. §4 is the build plan, with the D3 step left; §5 holds the work items still open. §4 holds the work items.

**In plain terms:** a static union, `shape Content = Post or Photo`, lets one rule, one field, or one prohibition speak about records of two unrelated kinds. Most of the compiler's checks were written for records of one kind. This question asks, case by case, where a union breaks a check, the runtime, the generated code, or the diagrams, and what to do about each.

**See:** Reference §6 (a static union as a field's type) · Reference §9 (`or` across unrelated shapes; the unchanged property rule; "Sharing a field across shapes") · Reference §10 (narrowing by `is <Operand>`, the `else` branch) · Reference §7 (walks) · Reference §14 (`for` with two matching fields) · `checks.md` F1, F3, V10, V11, V12, V13, V15, V24 · `compiler/src/test/kotlin/velle/UnionStressTest.kt` (one probe per case below, the gaps named `gap -`) · `compiler/src/test/kotlin/velle/UnionTest.kt` (the cooperative cases) · `examples/gallery/gallery.velle` (the cooperative spec) · OQ16 (B1, B4, S14, P8 in its calibration record)

---

## 1. What runs today

Checked against HEAD on 2026-11-16. Verdicts use the stress-pass words in `GLOSSARY.md`: HOLDS, the design handles the case; TAX, it handles it at a cost; GAP, it fails or a check skips it. Every row names the test that pins it; a test named `gap -` asserts today's wrong behaviour and is rewritten when the gap closes.

### 1.1 The cases known by inspection when the question was opened

| Case | What was asked | Verdict | Evidence |
|---|---|---|---|
| 1 | A union of refinements with no static union behind it, `shape Notable = LongPost or Captioned`, as a rule subject | TAX | It validates and generates; the composite gets no view of its own, and the generated rule spec checks membership by name. `UnionStressTest`, "a union of refinements generates no view of its own" |
| 2 | A union-typed edge set, `items: many Content` | HOLDS | Each operand holds the records, each operand gets the inverse, a handle outside the union is refused at the boundary. `UnionStressTest`, "a union-typed edge set holds records of each operand, and each operand gets the inverse" |
| 3 | A closure over the inverse of a union-typed field, `act Post { ... } with comments` where `Comment.target: one Content` | HOLDS | The part's `target` is the new post. `UnionStressTest`, "a closure over the inverse of a union-typed field fills the part's reference with the new operand". A union-typed field inside a nested part is not probed (work item) |
| 4 | Deletion against a union-typed referrer | HOLDS | `target: one Content? initially required` goes absent when the post is deleted, `when deleted Content` fires once, and a rule over `target is none` sweeps the comment. V24 sees a required `target: one Content` stranded by `delete post` (built 2026-11-16). `UnionStressTest`, "deleting an operand record absorbs through a union-typed reference and fires when deleted over the union" and "V24 sees a required union-typed referrer of a deleted operand" |
| 5 | `frozen`, `captured`, and `undeletable` on a refinement over a union | HOLDS | Each is refused by F3 with the per-operand spelling offered. `UnionTest`, "a union body admits derived properties only"; `checks.md` F3 |
| 6 | A `never` over a union | HOLDS for the writer check | `never (Content where views < 0)`, with `views` one declaration both operands take with `is`, draws V10 when a rule assigns `post.views` (built 2026-11-16). `UnionStressTest`, "V10 sees a rule writing on an operand what a never over a union reads". Whether such a prohibition is spendable by V1, V5, V15, and V16 is not probed |
| 7 | A singular reference over a refinement of a union, `(Popular for this)` | HOLDS | V12 refuses it over `Content` once per operand, as it refuses it over `Post` (built 2026-11-16). `UnionStressTest`, "V12 proves at-most-one for each operand of a refinement over a union" |
| 8 | A walk through a `one` typed with a union, `walk source` where `Trail = Alert or Escalation` and only `Escalation` has `source` | HOLDS | The walk ends at the alert, and `until this is Alert` finds it; `until source is Alert` is refused naming the declaration (D2, built 2026-11-16). `UnionStressTest`, "a walk through a union-typed reference ends at a record without the relationship, and until reads the record reached" |
| 9 | V15's third leg between a union rule and an operand rule flipping one refinement's membership | HOLDS by inspection | `checkTransitionInterference` in `compiler/src/main/kotlin/velle/Validator.kt` counts a creation or deletion of any operand as entering or leaving a refinement over the union (built 2026-11-16). No spec yet makes two co-firing rules flip such a refinement, so no test asserts the diagnostic |
| 10 | The predicate of a refinement or `never` over a union is not name-checked (OQ16 B1) | HOLDS | Both are refused, the separately declared field naming both declarations and offering narrowing and `is` (built 2026-11-16). `UnionStressTest`, "the predicate of a refinement or never over a union is name-checked, naming separate declarations"; `working-docs/examples/survey/17-shared-field-union-predicate-refused.velle` |

### 1.2 Narrowing

| Form | Verdict | Evidence |
|---|---|---|
| `if target is not Post then target.caption else target.body` | HOLDS | Built 2026-11-16. `UnionStressTest`, "narrowing by is not, and the residual else of a three-operand or nested union, read the rest of the union" |
| A narrowing inside a branch already narrowed | HOLDS | In `if target is Post then target.body else if target is Photo then target.caption else target.title`, the nested `target.caption` is licensed. Same test |
| The `else` of a three-operand union, and of a nested union (`Media = Content or Video`, `if about is Content then ... else about.title`) | HOLDS | The `else` narrows to the union of the operands not yet named, held by the model and never generated (built 2026-11-16). Same test |
| Narrowing inside a collection filter, `count(Comment where target is Post and target.body != "")` | HOLDS | `UnionStressTest`, "narrowing inside a collection filter and on a filter's source path is licensed" |
| Narrowing on a filter's source path and in a branch, `if target is Post then length(target.body) else 0` (OQ16 S14, B4) | HOLDS | The name check and the read-summary collector both carry it (built 2026-11-16). Same test |
| `Post or Post`, `Content or Post` | HOLDS by acceptance | Both validate and get a view (`twices()`, `mixeds()`). `UnionStressTest`, "a union of refinements generates no view of its own" |
| The element of a refinement-filtered collection, `sum(claims where PaidClaim, patientPays)` (OQ16 P8) | HOLDS | The filter's bare refinement names the element (built 2026-11-16). `UnionStressTest`, "the element of a refinement-filtered collection is the refinement, so its body properties can be summed" |

### 1.3 Downstream of the model

| Case | Verdict | Evidence |
|---|---|---|
| Hydration and the store | HOLDS at a cost the store pays | `examples/gallery/output/src/test/kotlin/velle/GalleryOverSqliteTest.kt`: a union-typed column keeps the concrete shape beside the key, since keys are unique per table; `stores/sqlite/` |
| Diagrams | HOLDS | The class diagram draws `Content` as a `<<union>>` box with an `or` arrow from each operand and attaches `Popular` to it; the state flow has a `Popular` axis under `Post` and under `Photo` (built 2026-11-16). `UnionStressTest`, "the diagrams draw a static union and a refinement over it" |
| `(Comment for this)` where `Comment` has `target: one Content` and `post: one Post`, read on a `Post` | HOLDS | Refused by V13 naming both fields and the filter form (built 2026-11-16). `UnionStressTest`, "a singular reference beside a union-typed and an operand-typed field is refused as ambiguous" |
| `Content from { ... }` in a rule | HOLDS | Refused naming the operands (built 2026-11-16). `UnionStressTest`, "creating a union is refused naming its operands" |
| `act Content { ... }` beside `shape Content = Post or Photo` | HOLDS | Refused as a duplicate name |
| Contention (A5) | not probed | A narrowed read is attributed to every operand that declares the name; a `never` reading through a union-typed field widens to system-wide |

## 2. What to decide

### D1. A check that skips a refinement over a union reasons over each operand instead · Decided 2026-11-16

Does every check that today skips a cross-base refinement (V10, V12, V15's third leg, V24) reason over each operand of the union and refuse when any operand fails?

The alternative is to refuse the construct where the check would skip: a `never` over a union, a singular reference over a refinement of a union, a required union-typed referrer of a deletable record. Reasoning per operand keeps every spelling §1 found working, and the design principle in `CLAUDE.md` decides it by default: the compiler errors when a statement's meaning is undefined, and these meanings are defined, operand by operand. The recommendation is yes.

Costs:

- One message per failing operand, naming the operand and the union, so an author sees which kind of record breaks the proof.
- V12 over a refinement of a union proves at-most-one for each operand's records; a union with one act operand is refused as the base is today.
- V24 over a required union-typed referrer offers the same three resolutions as over an operand-typed one.

From `examples/gallery/gallery.velle`, with the prohibition this review added:

```
shape Contribution {                       -- one declaration both operands take (Reference §9)
    author: one Member
}
act Post is Contribution { body: text }    -- a post has author
act Photo is Contribution { caption: text }
shape Content = Post or Photo              -- a record of either kind
act Comment { target: one Content }        -- target holds a post or a photo
shape Popular = Content where count(comments) >= 2   -- one refinement over both kinds

shape Spotlight = Member {
    featured: one Content? = (Popular for this)      -- today: validates, V12 silent; a member with two popular posts fails at the first read
}                                                    -- after D1: V12 refuses it, naming Post, as it does for `(Popular for this)` over Post today
```

#### Proposed changes

- `Validator.kt`: `checkNevers` matches a never's read of a field a taken shape declares against writes on every operand (V10); `checkSingularProofs` runs per operand of a cross-base refinement (V12); `checkTransitionInterference` runs per operand (V15); `checkDeleteStatements` counts a required union-typed field as a referrer of every operand (V24); each message names the operand.
- `checks.md` V10, V12, V15, V24: one sentence each saying the check reasons per operand of a union.
- `wiki/Normative/diagnostics.md`: one refused spec per new message under `compiler/src/test/resources/diagnostics/`.
- `UnionStressTest.kt`: the four `gap -` tests become assertions of the diagnostic.

**Decided:** yes, 2026-11-16. Dave: `(Popular for this)` names one of many and fails on its own terms, whatever the subject; the compiler says so at compile time over a union as it does over one shape.

### D2. A walk through a union-typed reference stops at a record without the relationship · Decided 2026-11-16

When a walk follows a `one` field typed with a union and reaches a record of an operand that does not declare the field, does the walk end there?

Today the walk validates and the first read fails at runtime, case 8 in §1. Two spellings are possible: the walk ends at such a record, so "everything above me" is every escalation up the chain and the alert at its root; or the compiler refuses a walk through a union-typed field unless every operand declares the field, which with `is` means one declaration. The recommendation is the first. Reference §7 says a walk follows the relationship "one or more times"; a record with no such relationship has nothing to follow, and the chain ends there, as it ends at an absent optional. The business sentence, "every escalation above this one and the alert that started it", is the first reading.

Costs:

- The `until` condition is evaluated on each record reached, so it is name-checked against the union: `until source is Alert` is refused, since `Alert` has no `source`, and the spelling is `until this is Alert`. Today that spelling is refused by F2 as `'this is Alert' — a 'Chain' can never be a member of 'Alert'`, because the check types `this` in a walk's condition as the declaring shape and not as the record reached. That is a work item under either answer.
- A walk's set is typed with the union, so reading it is narrowed per operand like any union-typed collection.

This example is not in the example files:

```
act Alert { title: text }                              -- the root of a chain; declares no source
act Escalation { source: one Trail, reason: text }     -- one step up, to an alert or to another escalation
shape Trail = Alert or Escalation                      -- what a step may reach

shape Chain = Escalation {
    above: many Trail = walk source                    -- today: validates; reading it fails when the chain reaches an alert
                                                       -- after D2: every escalation above and the alert; the alert ends the walk
    root: one Trail? = walk source until this is Alert -- today: refused by F2, `this` typed as Chain
                                                       -- after D2: the alert at the root, `this` being each record reached
}
```

#### Proposed changes

- `Evaluator.kt` (`evalWalk`): a record reached that does not declare the walked field ends the walk.
- `Validator.kt`: the `until` condition of a walk through a union-typed field is name-checked against the union, with `this` typed as the record reached.
- Reference §7, "Walks": one sentence saying where a walk through a union-typed field ends, and that `until` reads the record reached.
- `UnionStressTest.kt`: the walk test asserts the set and the root.

**Decided:** yes, 2026-11-16. The walk ends where the link ends; `this` in an `until` condition is the record reached, and the condition is checked against the union.

### D3. A union of refinements gets a view of its own

Does `shape Notable = LongPost or Captioned`, a union whose operands are refinements of different shapes, get a view in the generated code, as a static union does?

Today it is a legal rule subject and the runtime answers it, but the generated code has no `notables()`: a view is named after a base shape or a static union, and this composite has neither. The recommendation is yes. It is read exactly as `Content` is, as records of several shapes with the members they share, so the engineer's `read.notables()` and the GraphQL `notables` query should exist as `read.populars()` does for a refinement over a static union. The alternative refuses the composite as a rule subject and asks for a named static union with a `where`, `shape Notable = Content where (this is Post and length(body) > 10) or (this is Photo and caption != "")`, which says the same thing less plainly.

Costs:

- `Codegen.kt`, the GraphQL builder, and the store's typed reads treat a union of refinements as they treat a static union: an interface with the shared members, a query root, a membership flag on each operand's model object.
- Reference §9 says a union of refinements is read like a union of shapes.

This example is not in the example files:

```
act Member { name: text unique }
act Post { author: one Member, body: text }
act Photo { author: one Member, caption: text }
shape Content = Post or Photo                       -- a static union: read.contents() exists
shape LongPost = Post where length(body) > 10       -- a refinement of one shape: read.longPosts() exists
shape Captioned = Photo where caption != ""
shape Notable = LongPost or Captioned               -- a union of refinements: a rule subject today, no read.notables()
shape Notice { about: one Content }
rule Note when Notable { Notice from { about: this } }   -- fires for a long post and for a captioned photo
```

#### Proposed changes

- `Codegen.kt`: a cross-base refinement whose operands are refinements gets the union interface, the view, and the membership flags a static union gets.
- `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`: the same composite becomes a GraphQL union with a query root.
- Reference §9: one sentence saying a union of refinements is read as a union of their shapes.
- `UnionStressTest.kt`: "a union of refinements generates no view of its own" asserts the view.

## 3. Method

One adversarial spec per case, as a test in `compiler/src/test/kotlin/velle/UnionStressTest.kt` with the verdict recorded in §1. A test named `gap -` asserts today's behaviour and is rewritten when the gap closes. Rulings promote to the Reference and `checks.md` as they settle, and the work items in §4 close as the checks tighten.

## 4. The build

In build order, gathering the decided decisions' proposed changes and the work items that need no decision. Steps 1 to 5 landed 2026-11-16 for everything but D3; D3's changes join step 1 and step 3 when it is decided, and step 6 follows.

1. **Compiler.** `Validator.kt`: name-check the predicate of a refinement or `never` over a cross-base union (B1); V10, V12, V15, V24 reason per operand and name the operand (D1); V11 narrows on `x is not A` and on the residual `else` of a union with three or more operands and of a nested union; V13 counts a union-typed field as matching a `for`; the element of a refinement-filtered collection is the refinement (P8); a walk's `until` condition is checked against the union with `this` as the record reached (D2); creating a union is refused naming its operands; the F1 message for a field the operands declare separately names both declarations and offers narrowing or `is`. `Model.collectExpr` carries narrowing into the read summary. `Evaluator.kt`: a walk ends at a record that lacks the walked field (D2). `ClassDiagramGen.kt` and `StateFlowGen.kt`: a box for a static union with its shared members and an arrow from each operand; a state-flow axis for a refinement over a union.
2. **Regenerate** every example's output.
3. **Wiki.** `checks.md` V10, V11, V12, V13, V15, V24, F1; Reference §7 "Walks" (where a walk through a union-typed field ends, `until` reads the record reached); Reference §10 (the residual `else`, `is not`); Reference §9 (creating a union is refused naming the operands); `diagrams.md` (the union box and axis); `diagnostics.md` regenerated from one refused spec per new message.
4. **Examples.** `examples/gallery/gallery.velle` gains a walk through a union-typed field if a sentence of the gallery calls for one; otherwise the OQ's walk example moves into a new example. `working-docs/examples/survey/17-shared-field-union-predicate-accepted.velle` becomes `-refused` with its README row updated.
5. **Tests.** Each `gap -` test in `UnionStressTest.kt` becomes an assertion of the diagnostic or the behaviour; the probes in §5 that are still open (a nested part with a union-typed field, spendability of a `never` over a union, the contention width A5) are run and their verdicts recorded, or rehomed as residue.
6. **Settle.** The residue finds a home; the file is deleted in the settling commit.

## 5. Work items

- [ ] After D3: a union of refinements gets the view, the interface, the membership flags, and the GraphQL union a static union gets ("a union of refinements generates no view of its own").
- [ ] Probe a union-typed field inside a nested part of a closure, `act Post with { comments with { reactions } }` where a reaction points at a `Content`.
- [ ] Probe whether a `never` over a union is spendable by V1, V5, V15, and V16 (`checks.md` V10), and whether a narrowed read's contention width (A5) over every operand that declares the name is real.
- [ ] Let a `never` over a shape a transient act takes with `is` narrow to that act (`this is RenameEntry`): V17's expression check allows the narrowing in a rule or refinement over the taken shape (`Validator.transientSubjectsBySite`) and still refuses it in a `never`, whose site the walker does not name (Reference §9, "Sharing a field across shapes").
