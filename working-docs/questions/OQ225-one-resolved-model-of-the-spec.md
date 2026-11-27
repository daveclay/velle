# OQ225 — Should the compiler resolve every path in a spec once, into one model that every check, generator, and the running system read?

**Status:** opened 2026-11-27 from a discussion of OQ222 (`working-docs/QUESTIONS.md`), checked against commit 4738255. D1 and D2 decided 2026-11-27. Inventory taken; designed (§3); being built; moves 1 to 5 done, 6 and 7 in part, every disagreement in §1 resolved (§3). One work item.

**In plain terms:** when the compiler reads a condition such as "the loan's member is called late", it does not write down once what "the loan's member" means. Each part of the compiler that later reads the condition works it out again for itself, and they do not always agree. Several recent bugs came from one of them working it out differently from the others. The change is to work it out once, keep the answer, and have every part read that.

**See:** OQ199 in the settled table of `working-docs/QUESTIONS.md` (one fact fixed in five places); OQ222, OQ223, OQ224 in `working-docs/QUESTIONS.md` (the same kind of bug, still open); `compiler/CLAUDE.md` (the rules for compiler code); `compiler/src/main/kotlin/velle/Ast.kt` (what the parser produces).

---

## 1. What runs today

A path here means a chain of names in a condition or a rule body, such as `member.name` in `shape LoanOfLateMember = Loan where member.name == "late"` (`working-docs/examples/survey/63-compare-new-record-through-held-reference-accepted.velle`). Resolving a path means finding the declaration each name refers to: here, the field `member` declared on `Loan`, then the field `name` declared on `Member`.

### What is already a model

- The parser turns the spec into objects, one per declaration: `ShapeDecl`, `RefinementDecl`, `RuleDecl`, `NeverDecl` (`compiler/src/main/kotlin/velle/Ast.kt`).
- `Model` in `compiler/src/main/kotlin/velle/Model.kt` navigates the declarations: the fields of a shape (`membersOf`), the shapes a shape takes with `is` (`basesOf`).

### What is not

- A path is kept as names only. `member.name` is `PathExpr(root = "member", segs = [Seg("name", ...)])` (`Ast.kt`, line 381). It holds no link to the declaration of `Loan.member`, and no note of how the subject reaches it.
- The validator resolves each path to check that it is legal (`resolvePath` in `compiler/src/main/kotlin/velle/validation/PathResolution.kt`) and keeps no record of the answer.
- Each later part resolves the path again. The inventory, by file under `compiler/src/main/kotlin/velle/` and function, taken 2026-11-27 at commit fd7a12d:

| Part | Where | What it is for | What it records |
|---|---|---|---|
| Read analysis | `Model.kt`: `collectExpr`, `collectPath`, `walkSegs`, `collectCollection`, `collectFor`, `record` | about 25 callers: the V-checks that read a `ReadSummary`, `ConditionWatchers.kt`, `DiagramGen.kt`, `RuleGraphGen.kt`, `StateFlowGen.kt`, `Domains.kt` | (shape, field) pairs, shapes consulted, whether time is read |
| Type at a path's end | `Model.kt`: `pathEndType`, `pathElementShape`, `collectionElement`, `heldSubjectShape` | about 20 callers | a type or a shape name |
| Expression types | `ExprTypes.kt`: `of`, `pathType`, `along`, `recordElement`, `narrowingOf` | F2, V11, `AgreedSpelling.kt`, `validation/TransientFieldCheck.kt` | a type, and what a test narrows |
| Validator paths | `validation/PathResolution.kt`: `resolvePath`; `validation/Narrowing.kt`; the walk in `validation/ExpressionChecks.kt`, `CollectionChecks.kt`, `ForEachCheck.kt`, `RuleBodyChecks.kt` | F1, F2, V11 | messages only |
| Serialization domains | `Domains.kt`: `Walker` (`walkPath`, `hop`, `read`, `walkCollection`, `walkAssignment`), `corrExpr`, `bodyCorr`, `bodyPath` | the queue keys of OQ40 | queue keys, correlations |
| Running system | `Evaluator.kt`: `eval`, `evalPath`, `readMember`, `readResolved`, `declaredTypeOf`, `resolveTargets` | evaluating a condition | values |
| Store pre-filters | `Query.kt`: `resolveHops`, `compileBoolAtom`, `compileExists`, `backFieldOf` | the filter a store applies before the runtime | a filter tree |
| Direction | `StateFlowGen.kt`: `mono`, `dir`, `existsDir`, `pathDelta`, `staticPathSign`, `fieldAtBirth` | the state-flow diagram and `velle compare` | which way a commit moves a condition |
| Polarity | `validation/SignedConsults.kt`: `signedExpr`, `followPath` | V15 | a sign per shape consulted |
| Transient fields | `validation/TransientFieldCheck.kt`: `transientFieldOnPath`, `elementScopeOf` | V37 | the transient field read |
| Declarations before `Model` | `Keys.kt`: `KeyLookups`; `PresenceFacts.kt`: `factsOf`, `pathType` | V36, the presence facts of a prohibition | a declared type |
| Writes | `validation/Writes.kt`: `resolveWrite` | V1, fan-out | the field written |
| Aliases | `Kinds.kt`: `producedKinds`; `validation/RuleActorCheck.kt`: `ruleAliases`, `loopAliases`; `validation/CreationChecks.kt`: `instanceShapeOf` | the alias maps the read analysis is given | an alias map |
| Small ones | `validation/TextPatternChecks.kt`: `membersAlong`; `validation/DeleteStatementChecks.kt`: `pathHasCollectionHop`; `SpecGen.kt`: `traversesCollection`; `validation/SelfReadProblems.kt`: `visitOneStep`; `validation/OwnCollectionReadAdvisory.kt`; `StartingValues.kt`; `Validator.kt`: `checkQueryOrder` | one check or generator each | a yes or no, or members |

- Beside those, about 30 functions walk the expression tree by hand, each listing every form again: among them `ExpressionParts.kt` `subExpressionsOf`, `BodyOrder.kt` `childrenOf` (the same as `subExpressionsOf`), `FormsWithoutWords.kt` `childrenOf` (a third, which skips a path used as a collection source), `Printer.kt`, `Sentences.kt`, `AgreedSpelling.kt`, `validation/RefinementMentions.kt`. Splitting a condition at `and` is written again in twelve files.

### What it costs

One rule of the language is taught to each of those parts on its own, and they drift apart:

- OQ199 (settled table): "a field declared on a shape taken with `is` belongs to every shape that takes it" was fixed in `Model.kt`, `StateFlowGen.kt`, `ConditionWatchers.kt`, `validation/NeverObligationCheck.kt`, and `Domains.kt`.
- OQ222: the read analysis cannot tell a field read on the subject from one read through a reference.
- OQ223: the read analysis records a read of a taken shape under the name written, while the running system compares by the base shape.
- OQ224: the read analysis mishandles `this.branch` inside a nested filter.

### Where the parts disagree today

Each is pinned by a test in `compiler/src/test/kotlin/velle/PathResolutionDisagreementTest.kt` that holds today's answer and names the part that answers differently. The move that makes the two agree turns the test into one that holds the agreed answer.

1. Resolved in move 5. The validator read `this.target` and `target` as different paths when it narrowed, so `if this.target is Post then target.body` is refused with F1, while `ExprTypes.kt` types it ("a test spelled with this does not narrow a read spelled without it ...", and the reverse).
2. Resolved by moving the polarity walk (commit 412e448). `validation/SignedConsults.kt` read `this` inside a filter as the element, while the read analysis reads it as the subject ("the polarity walk reads this inside a filter as the element ...").
3. Resolved in move 4. `ExprTypes.kt` typed a bare collection or a `walk` inside a filter over another shape on the subject, so the validator refuses a valid `sum(lines, amount)` with F2, while the read analysis reads it on the element ("a bare collection inside a filter over another shape ...", "a walk inside a filter over another shape ...").
4. Resolved (commit 412e448). `validation/TransientFieldCheck.kt` read a bare name in a filter over two collections on the first, while everything else reads it on the last: V37 is raised for a stored field and missed for a transient one (the two tests "the transient check reads a bare name in a filter over two collections ...").
5. Resolved (commit 76c4ede), with `Model.recordsHolding` the one answer to which shapes a read or write of a taken field lands on. The serialization-domain analysis in `Domains.kt` gave a write to a field declared on a taken shape no queue key, so a rule the write should wake is missed: a soundness gap ("a write to a field declared on a taken shape wakes no rule ...").
6. Resolved (commit 8483cdf). `Domains.kt` `corrExpr` did not follow a refinement named inside a rule's `where`, while it follows one that is the condition's shape ("a refinement named inside a rule's where is not followed ...").
7. Resolved (commit 8483cdf), with `Model.namesBoundIn` the one answer to the names a rule binds. `Domains.kt` `bodyPath` gave a read through a `for each` name no route ("a read through a for each name gives the serialization domain no correlation route ...").
8. Resolved (commit d619acc), with `Model.referencesRecordsOf` the one answer. `Query.kt` compiled `exists ... for this` over a union-typed reference to no store pre-filter, while the running system matches it ("exists for this over a union-typed reference compiles to no store pre-filter ...").
9. Resolved (commit d619acc). `ReadConflicts.kt` did not count a read of a union-typed reference as a read of a reference to its operand, while `PossibleCommits.kt` does ("a read of a union-typed reference is no read of a reference to its operand ...").
10. Found and resolved in move 4 (commit eeb2af5): the read analysis ignored `this is RenameEntry` narrowing `this` itself, while `ExprTypes.kt` honoured it (`compiler/src/test/kotlin/velle/spec/PathResolverTest.kt`, "after this is loan a name only loan declares is looked up on loan"; `SharedShapeTest`, "a rule over a taken shape fires inside a transient taker's own commit ...").

11. Found in move 1 (commit c4b1734): the read analysis stopped at a step through a `many`, `kids.descendants`, and read anything, while the type reading followed it. It now follows it, so V14 sees a derived collection reading itself that way and fails closed, closing OQ53's B6 (`examples/batches/output/src/test/kotlin/velle/BatchesProbesTest.kt`, "B6 - a derived collection reading itself through a many hop draws V14").

### Open OQs it closes or changes

Read 2026-11-27 against every open line of `working-docs/QUESTIONS.md`.

- Closes, since each is two parts disagreeing about a path or one part resolving it wrongly: OQ222, OQ223, OQ224; OQ206 (the read analysis sees MoveLoan's write to `loan.member` for `exists Loan for this`, while the "entry is permanent" note in `StateFlowGen.kt` looks only at deletions); OQ55 §2 and §3 P2 and P3 (the `Walker` in `Domains.kt` resolving a second binding on the first binding's element, a `this.` path inside a `where` going opaque, and the condition side not following refinement filters or `is`).
- Changes the build of: OQ91 (a field read through a `many` step, which the model must define either way); OQ182 (direction lost through a refinement named in the condition); OQ172 (which edits a consequence points at); OQ194 (the nevers and rules that name a field, a reverse index over resolved steps); OQ186 (which record `this` is, in V13's message); OQ119 (a derived step in `Query.kt` `resolveHops`); OQ58 §1 (walking a condition's paths backward from a write); OQ55 S12 and P4; OQ53 B6; OQ214 D1 and D2, being built in another session.

## 2. What to decide

### D1. Do the refactor · Decided 2026-11-27

Should the compiler resolve every path once, into a model that records for each step the declaration it names and how it is reached, and should every check, generator, and the running system read that model and not resolve paths themselves?

Recommendation: yes.

**If we do it**
- Pro: a rule of the language, such as what a taken shape's field belongs to, is written once. A part cannot disagree with another about what a path means.
- Pro: questions such as OQ222's, "is this field reached only through `one` references from the subject", are asked of the model, not answered by a marker one part computes for itself.
- Con: a large change to the compiler's internals, spent instead of on open language questions.
- Con: while parts are being moved over, a mistake can change what a check accepts or what a report says. The safety net is that each move must leave every test passing and every example's output unchanged (§3).

**If we don't**
- Pro: no time spent on the compiler's internals.
- Con: every new rule about paths must be taught to each part, and each build of an OQ about them keeps producing new OQs like OQ222, OQ223, and OQ224.

**Decided:** yes, Dave, 2026-11-27. He expects it to reduce the risk of a buggy compiler when implementing OQ222, since many recent OQs came out of implementing earlier ones.

### D2. OQ222 waits for this refactor · Decided 2026-11-27

Is OQ222 built after this refactor, as a question asked of the new model, and not now as a marker in the read analysis?

Recommendation: wait.

**If it waits**
- Pro: OQ222 is built once, on the model, and the ways of reaching a field listed in its D1 are each a property of the model, not a case the read analysis must handle correctly.
- Con: `velle compare` and the diagrams keep printing OQ222's false lines until the refactor lands.

**If it is built now**
- Pro: the false lines go sooner.
- Con: a marker is added that the refactor removes, and it carries the risk OQ222's D1 was narrowed against.

**Decided:** wait, Dave, 2026-11-27, for the reason he gave for D1.

## 3. The build

Dave's direction, 2026-11-27: the model is a first-class API the compiler provides, and the validators are written against it as its first users. Other tools will be built on the same API, so it is designed to be read by someone who has not seen the compiler's internals: named for the language's own terms, documented, and stable. The more professional and clear it is, the easier those tools and later checks are to write.

These hold for it, from D1:

- Each part is moved onto the model in a commit of its own, and that commit leaves every test passing and every example's generated output unchanged. A change in output is a bug in the move, unless it is one of the disagreements in §1 becoming right, which then gets a test of its own in the same commit.
- Since the validators read the model, it is built before them and holds every path the author wrote, including one that does not resolve. Such a step is recorded as not resolving, with the name and the shape it was looked for on, and the check that reports an unknown name (F1) is written against that.

### The design

The API is the package `velle.spec`, in `compiler/src/main/kotlin/velle/spec/`.

- `Scope` (`Paths.kt`) is one point of a spec: the element, the shape a bare name is a member of; the subject, the shape `this` names; the names bound to a record and to a value; and the narrowing an `is` test gives. It is a value, never changed in place. Moving into a filter's `where` is `inside(element)`, binding a name is `withRecordName` or `withValueName`, and a governing test is `narrowedBy`.
- `PathResolver.resolve(path, scope)` (`Paths.kt`) answers with a `ResolvedPath`: what the path starts from (`PathStart`: the subject, the element, a record name, a value name, or a shape or refinement named on its own); each `Step`, holding the field's declaration, the shape it is looked up on after narrowing, and its `Reach` (from the start, through a reference to one record, or through a collection); and, when a name does not resolve, the `UnresolvedStep` with the shape it was looked for on.
- Still to come: a walk of a condition or rule body that hands each expression its `Scope`, so that which scope a nested filter, a `for each`, or an `is` test gives is written once; and one function for the parts of an expression, which the hand-written walks share.

### The order of moves

1. The resolver; the read analysis's `collectPath` and `pathEndType` in `Model.kt` read it. Done, commit c4b1734.
2. The rest of the read analysis in `Model.kt` (`walkSegs`, `forRecordShape`, `heldSubjectShape`), and its narrowing passed down as a `Scope` in place of the `narrowedScope` field. Done.
3. One computation of what an `is` test narrows, taken from `ExprTypes.kt` `narrowingOf`, which is the complete one, and read by the read analysis, the validator, and `ExprTypes.kt`. Done: `velle.spec.ShapeNarrowing`, read by all three.
4. `ExprTypes.kt` `pathType`, `recordElement`, and the type of a `walk`. Done.
5. The validator: `validation/PathResolution.kt` and `validation/Narrowing.kt`, with F1 reported from an `UnresolvedStep`. Done.
6. `Domains.kt`: the `Walker`, `corrExpr`, `bodyCorr`, `bodyPath`. `bodyCorr` and `bodyPath` done; the `Walker`'s read and write sides read `Model.recordsHolding`; the `Walker`'s own path walk is still to move.
7. `StateFlowGen.kt`, `validation/SignedConsults.kt`, `validation/TransientFieldCheck.kt`, `validation/Writes.kt`, `Query.kt`, `Evaluator.kt` `declaredTypeOf`, and the small ones in §1. `StateFlowGen.kt` (`pathDelta` and `staticPathSign`; `mono` and `dir` walk no path of their own), `SignedConsults.kt`, `TransientFieldCheck.kt`, `Writes.kt`, `Evaluator.kt` `declaredTypeOf`, `validation/TextPatternChecks.kt` `membersAlong`, `validation/DeleteStatementChecks.kt` `pathHasCollectionHop` and `SpecGen.kt` `traversesCollection` done; `validation/SelfReadProblems.kt`, `validation/OwnCollectionReadAdvisory.kt` and `Validator.checkQueryOrder` look up one member by name, which is no path, and keep `Model.membersOf`; `Query.kt` and the read conflicts read `Model.referencesRecordsOf`.
8. One function for the parts of an expression, and one for splitting at `and`, shared by the hand-written walks. Splitting done: `partsJoinedByAnd` and `partsJoinedByOr` in `compiler/src/main/kotlin/velle/spec/Conditions.kt` replace fourteen copies. The parts of an expression done: `operandsOf`, `collectionOf`, `sourcesAndFilterOf`, `subExpressionsOf` and `partsOf` in `compiler/src/main/kotlin/velle/spec/Expressions.kt`, read by every walk that took them from the validator or `StartingValues.kt`, and by `BodyOrder.kt` in place of its own copy.
9. OQ222, built on `Reach`. Done; OQ222 is in the settled table of `working-docs/QUESTIONS.md`.

`Keys.kt` and `PresenceFacts.kt` read declarations before `Model` exists, so they cannot read the resolver as it stands; they move when the model is built earlier, or stay, and the decision is recorded here when it is reached.

## 4. Work items

- [ ] Move each part onto the model, one commit each, output unchanged.
