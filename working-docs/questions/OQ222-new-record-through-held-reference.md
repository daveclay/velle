# OQ222 — Should `velle compare` and the diagrams stop saying a new record can move a record that reaches it only through a reference it already holds?

**Status:** opened 2026-11-27 while building OQ199 (settled table of `working-docs/QUESTIONS.md`), checked against commit 4738255. D1 settled. The build waits for OQ225 (`working-docs/QUESTIONS.md`), which builds the model it is asked of. Nine work items.

**In plain terms:** the report on a changed spec says that adding a new member can make a loan start or stop belonging to a late member. It cannot: a loan already names its member, and a brand-new member is named by no loan yet. The report should only say that a new loan, or a rule moving a loan to another member, can do that.

**See:** `working-docs/examples/survey/63-compare-new-record-through-held-reference-accepted.velle` (the case, with the lines it prints); `compiler/src/main/kotlin/velle/Kinds.kt`, `KindCatalog.touches` (the cause); `wiki/Guides/Comparing-specs.md` (what `velle compare` reports); OQ223 in `working-docs/QUESTIONS.md` (the same function missing a new record of a taken shape, the opposite error in the same place).

---

## 1. What runs today

A condition here means the test after `where` in a refinement such as `shape LoanOfLateMember = Loan where ...`, or the test a rule waits on. Its subject is the shape it is written over, here `Loan`.

`working-docs/examples/survey/63-compare-new-record-through-held-reference-accepted.velle` holds the spec:

```velle
act Member { name: text }              -- a member, kept as a record

act Loan {                             -- a loan, kept as a record
    member: one Member                 -- each loan names exactly one member, fixed when it is made
    title: text
}

-- a loan is a LoanOfLateMember when the member it names is called "late";
-- the compiler records two reads: Loan's member, and Member's name
shape LoanOfLateMember = Loan where member.name == "late"
```

`velle compare` from an empty spec to that file prints, under "What that changes":

```
### Loan
- A new Member can now make a Loan become a LoanOfLateMember.
- A new Member can now make a Loan stop being a LoanOfLateMember.
```

Neither can happen. Only two things can move a loan into or out of LoanOfLateMember: a new loan, which can be one from the moment it is made, and a write to a loan's `member`, which here nothing does. A write to a member's `name` would be a third, and nothing does that either.

The lines print whether `member` is declared on `Loan` or on a shape `Loan` takes with `is` (`compiler/src/test/kotlin/velle/ComparisonDirectionTest.kt`, the spec `sharedBorrowing`).

OQ199's build named two more instances, and §4 has a work item to pin each one:

- `examples/peptides/peptides.velle`: `shape GreenhouseValidated = Peptide where exists (GreenhouseResult where effective and trial.peptide == this)`. The output says ApproveGreenhouseTrial, which creates a GreenhouseTrial, can move a Peptide either way through GreenhouseValidated. An existing GreenhouseResult names its trial already, so a new trial reaches no existing result.
- `examples/gallery/gallery.velle`: the sequence diagram says commitPost checks `never (Comment where target is Post and target.author == author)`. A new Post is the target of no existing Comment.

### The cause

`KindCatalog.touches` in `compiler/src/main/kotlin/velle/Kinds.kt` decides whether a kind of commit can change a condition. For a new record of shape S (the `CommitKind.Create` branch) it answers yes when S is consulted for its existence or as a collection, or when the condition reads any field of S at all:

```kotlin
is CommitKind.Create ->
    k.shape in s.existsShapes || k.shape in s.collShapes ||
        s.fields.any { it.first == k.shape } || s.collFields.any { it.first == k.shape }
```

The third test, `s.fields.any { it.first == k.shape }`, is what fires for the new Member: the condition reads `Member.name`. The test cannot tell the two ways a condition comes to read a field of S apart:

- **S is the subject.** `LoanOfLateMember` reads `Loan.member`, and a new Loan can be a LoanOfLateMember. This answer must stay.
- **S is reached through a `one` reference the subject holds.** `LoanOfLateMember` reads `Member.name` through `member`. A new Member is named by no existing loan, so it cannot move one. A loan that names the new Member is either a new Loan or a loan whose `member` a rule writes, and those two commits are each counted already, as a new Loan and as a write to `Loan.member`.

A reference reached through a collection (`exists (loans where ...)`, a `many` field) is a third way, and stays as it is: a new record can join a collection. That read is recorded in `collShapes`, not by the third test.

The running system makes the same over-count (`ConditionWatcher.of` in `compiler/src/main/kotlin/velle/ConditionWatchers.kt` reconsiders a condition when a record of a shape it reads is created). There it costs a wasted re-check and never a wrong answer, so it is outside this OQ.

## 2. What to decide

### D1. Stop saying a new record moves a record that reaches it only through a `one` reference · Decided 2026-11-27

Should `velle compare`, the state-flow diagram, the rule graph, and the sequence diagram stop saying that a new record of shape S can change a condition when the condition reaches S only through a `one` reference held by an existing record?

Recommendation: yes. The lines are false, and a product owner reading "a new Member can make a Loan stop being a LoanOfLateMember" will go looking for a way that happens.

**If we do it**
- Pro: the report and the diagrams stop listing causes that cannot happen. Today they say commitPost checks a prohibition that a new post can never break, in `examples/gallery/gallery.velle`.
- Pro: a cause that remains is one a reader can act on.
- Con: the compiler must keep track of how each field read was reached, as subject, through a `one` reference, or through a collection. Today a read is only a (shape, field) pair, and every consumer of `ReadSummary` in `compiler/src/main/kotlin/velle/Model.kt` reads it that way.
- Con: a mistake in that tracking hides a real cause, which is worse than listing a false one. The tests must pin both directions: the false line gone, and the new Loan and the write to `Loan.member` still listed.

**If we don't**
- Pro: no change to how reads are recorded, and no risk of hiding a real cause.
- Con: every condition that follows a `one` reference keeps a false "a new X can now" pair of lines in `velle compare`, and a false arrow in the state-flow diagram. Every spec with a reference has some.
- Con: the sequence diagram keeps showing checks a commit cannot fail.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Model.kt`: `ReadSummary` records, beside each field read, whether the subject reaches it only through `one` references, and the walker that fills it (`record` and its callers) sets that.
- `compiler/src/main/kotlin/velle/Kinds.kt`: the `CommitKind.Create` branch of `KindCatalog.touches` stops counting a field read reached only through `one` references. The `CommitKind.Delete` branch keeps counting it, since a deletion turns that reference absent (OQ37, settled table).
- `compiler/src/test/kotlin/velle/ComparisonDirectionTest.kt`: a test that a new Member no longer moves a Loan through LoanOfLateMember, and that a new Loan and MoveLoan still do.
- `examples/peptides/peptides.velle` and `examples/gallery/gallery.velle`: regenerate their output; ApproveGreenhouseTrial no longer moves a Peptide through GreenhouseValidated, and commitPost no longer checks the comment prohibition.
- `working-docs/examples/survey/63-compare-new-record-through-held-reference-accepted.velle`: delete it, with its README line, when the build lands.

**Decided:** yes, Dave, 2026-11-27. Narrowed the same day so a mistake cannot hide a real cause: a new record is still counted as able to move the condition, as today, except in one case the compiler recognises on sight. In that case the condition reads the field through a chain of `one` fields that starts at the subject and is written in the condition itself, such as `member.name` in `Loan where member.name == "late"`, and nothing else in the condition reads that shape. Every other way of reaching a field keeps today's answer, so a bug can only leave a false line in place and never removes a true one. These ways keep it:

- an inverse collection, such as `loans` in `Member where exists (loans where ...)`, which the compiler records as a read of `Loan.member`;
- the same field read a second way, such as `Member.name` in `Loan where member.name == "late" or exists (Member where name == "late")`;
- a refinement named inside the condition, such as `member is LateMember`;
- a derived field, a field typed with a union, and `this` inside a nested filter.

The cost is that some false lines stay, such as one reached through a refinement named inside the condition.

## 3. The build

Built on OQ225's model (`working-docs/QUESTIONS.md`), which records for each step of a path how it is reached (`velle.spec.Reach` in `compiler/src/main/kotlin/velle/spec/Paths.kt`).

1. `compiler/src/main/kotlin/velle/Model.kt`: the read analysis marks a shape as read only through held references when a path written in the condition itself, at its top and not inside a filter over another shape, reaches it by a chain of `one` fields from the subject, none typed with a union or a taken shape. Every other read of the shape, through a collection, an inverse, a derived field, a refinement named inside the condition, an alias, or a filter, marks it as read otherwise, and a shape read otherwise is counted as today.
2. `compiler/src/main/kotlin/velle/Kinds.kt`: the `CommitKind.Create` branch of `KindCatalog.touches` does not count a field read on a shape read only through held references. The `CommitKind.Delete` branch is unchanged.
3. `compiler/src/test/kotlin/velle/ComparisonDirectionTest.kt`: the survey spelling no longer says a new Member moves a Loan; a new Loan still does; a rule creating a member and a loan naming it in one commit is still listed; and one test per way of reaching a field listed in D1's `**Decided:**` paragraph shows the new record still counted.
4. The two other instances named in §1 stay as they are under D1's narrowed rule: `examples/peptides/peptides.velle` reads `trial.peptide` inside a filter over GreenhouseResult, and `examples/gallery/gallery.velle` reads `target`, typed with the union `Content`. Each is pinned as it stands, and extending the rule to them is residue with a tag of its own.
5. Regenerate the examples; delete survey file 63 and its README line; settle.

## 4. Work items

- [ ] Pin the peptides instance: a test that `velle compare` on `examples/peptides/peptides.velle` says ApproveGreenhouseTrial can move a Peptide through GreenhouseValidated, before the fix turns it into a test that it does not.
- [ ] Pin the gallery instance: a test that the sequence diagram of `examples/gallery/gallery.velle` shows commitPost checking `never (Comment where target is Post and target.author == author)`, before the fix turns it into a test that it does not.
- [ ] Find in `examples/` a condition that reaches a new record through a `one` reference in the same commit that creates it, and pin that its cause is still listed after the fix.
- [ ] Pin the survey spelling in `ComparisonDirectionTest`: a new Member moves a Loan through LoanOfLateMember today.
- [ ] Mark in `ReadSummary` a field read reached by a direct chain of `one` fields from the subject, with nothing else in the condition reaching that shape (`compiler/src/main/kotlin/velle/Model.kt`).
- [ ] Pin each way of reaching a field listed in D1's `**Decided:**` paragraph with a test in `ComparisonDirectionTest` that the new record is still counted.
- [ ] Stop the `CommitKind.Create` branch of `KindCatalog.touches` counting those reads (`compiler/src/main/kotlin/velle/Kinds.kt`).
- [ ] Regenerate the examples' output and account for every change.
- [ ] Check `wiki/Guides/Comparing-specs.md` and `wiki/Normative/diagrams.md` for the false claim, and land the settling commit.
