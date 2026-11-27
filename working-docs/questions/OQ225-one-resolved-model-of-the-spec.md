# OQ225 — Should the compiler resolve every path in a spec once, into one model that every check, generator, and the running system read?

**Status:** opened 2026-11-27 from a discussion of OQ222 (`working-docs/QUESTIONS.md`), checked against commit 4738255. D1 and D2 decided 2026-11-27. Not designed; the inventory in §1 is incomplete. Seven work items.

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
- Each later part resolves the path again. Those found so far, by the function that does it:
  - the read analysis behind `velle compare` and the diagrams, `collectPath` in `Model.kt` (line 1018);
  - the serialization-domain analysis, the `Walker` in `compiler/src/main/kotlin/velle/Domains.kt` (`walkPath`, line 754);
  - the type of an expression, `pathType` in `compiler/src/main/kotlin/velle/ExprTypes.kt` (line 109);
  - which way a commit can move a condition, `mono` and `dir` in `compiler/src/main/kotlin/velle/StateFlowGen.kt` (from line 418);
  - the sentence `velle describe` writes for a condition, `nameCondition` in `compiler/src/main/kotlin/velle/Sentences.kt` (line 94);
  - the query compiler, `compileBoolAtom` in `compiler/src/main/kotlin/velle/Query.kt` (line 174);
  - the running system's evaluator, `evalPath` in `compiler/src/main/kotlin/velle/Evaluator.kt` (line 156), and its relevance analysis, `ConditionWatcher` in `compiler/src/main/kotlin/velle/ConditionWatchers.kt`.
- 32 source files under `compiler/src/main/kotlin/velle/` match on `PathExpr`, and 39 call `membersOf`. The list above is not yet every one of them (§4).

### What it costs

One rule of the language is taught to each of those parts on its own, and they drift apart:

- OQ199 (settled table): "a field declared on a shape taken with `is` belongs to every shape that takes it" was fixed in `Model.kt`, `StateFlowGen.kt`, `ConditionWatchers.kt`, `validation/NeverObligationCheck.kt`, and `Domains.kt`.
- OQ222: the read analysis cannot tell a field read on the subject from one read through a reference.
- OQ223: the read analysis records a read of a taken shape under the name written, while the running system compares by the base shape.
- OQ224: the read analysis mishandles `this.branch` inside a nested filter.

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

Not designed. These hold for it, from D1:

- Each part is moved onto the model in a commit of its own, and that commit leaves every test passing and every example's generated output unchanged. A change in output is a bug in the move, unless it is one of the open OQs above becoming right, which is then that OQ's build and not this one's.
- The model is built after the validator has accepted the spec, so a part reading it never meets a path that does not resolve.

## 4. Work items

- [ ] List every part of the compiler and runtime that resolves a path or walks a condition, by file and function, and what each records about how a field is reached.
- [ ] List the facts about a path those parts compute differently from each other, each pinned by a test that shows the disagreement.
- [ ] Check which open OQs in `working-docs/QUESTIONS.md` besides OQ222, OQ223, and OQ224 the model would close or change.
- [ ] Design the model: what a resolved step records, including the ways of reaching a field listed in OQ222's D1, and where it is built.
- [ ] Write §3 as the plan: the order in which parts move onto the model.
- [ ] Move each part onto the model, one commit each, output unchanged.
- [ ] Build OQ222 on the model.
