# OQ170 — a tool that reads two versions of a spec and says what changed in Velle's words, not as changed lines of text

**Status:** opened 2026-11-23 from Dave's note. Investigated against HEAD the same day. D1, D2, D3, D4, D6, and D7 decided. D5, D8, D9, and D10 await Dave. Nothing built except the probe test.

**In plain terms:** a review of a change to a spec today means reading changed lines of text and working out what they do to the business. The compiler already knows what a spec means, so it could read the old spec and the new one and say "a line item can now clear an overdue invoice" instead of "line 87 was deleted".

**See:** `wiki/Normative/diagrams.md`, "The projection principle", which says today's answer is to read the changed lines of the generated diagrams. `compiler/src/test/kotlin/velle/SpecComparisonProbeTest.kt`, the seven cases below. `examples/billing/billing.velle`, the spec every case edits. OQ28, a new rule over records that already exist. OQ158, a proof the compiler lost, which a report would show. OQ56, generated cases.

---

## 1. What runs today

Nothing in the compiler reads two specs. `velle.GenerateKt` (`compiler/src/main/kotlin/velle/Generate.kt`) reads one spec and writes the runtime, the generated cases with their index `SPEC_INDEX-<System>.md`, and, with `--diagrams`, `DIAGRAMS-<System>.md`. Those files are checked in beside each example, so a change to a spec shows up in a pull request twice: as changed lines of the `.velle` file and as changed lines of the generated files. `wiki/Normative/diagrams.md` names that second set of changed lines as the review: "a spec edit surfaces as an artifact diff in the pull request".

The generated files do hold the change in behaviour. They hold it as lines of Mermaid, the diagram text format the diagrams are written in, and a reviewer reads lines again.

`SpecComparisonProbeTest` pins what a reviewer gets today. Each case makes one edit to `examples/billing/billing.velle`, checks that the edited spec still validates, and compares the generated lines before and after. The roster:

| Case | The edit to `examples/billing/billing.velle` | What the generated files show | What a reviewer needed to hear |
|---|---|---|---|
| A | The customer section (act `SignUp`, shape `Customer`, rule `AdmitCustomer`, act `CorrectEmail`, rule `ApplyEmailCorrection`) moved to the end of the file | The same lines in another order. A text comparison reports a change | Nothing changed |
| B | `never (LineItem where price < 0)` deleted | On shape `Invoice`, the arrow `otherwise --> OverdueInvoice : commitLineItem` becomes two arrows marked "may flip". Two generated cases disappear from the index | A line item with a negative price is now accepted, and a line item can now clear an overdue invoice. The second sentence is about `Invoice`, which nobody edited |
| C | Shape `ActionableOverdue` changed from `OverdueInvoice and not ArchivedInvoice` to `OverdueInvoice and not IssuedInvoice` | The arrow from `commitUnarchiveRequest` to rule `RemindOverdue` disappears. No arrow is added. No generated case changes | Archived invoices are now reminded, and issuing an invoice now stops its reminders. Neither sentence is drawn anywhere, because the rule graph leaves out an arrow the compiler proves cannot fire a rule |
| D | Shape `OverdueInvoice` changed from `due < today` to `due <= today` | One note changes. No arrow and no generated case changes | An invoice is now overdue on its due date, one day earlier |
| E | Shape `PaidInvoice` changed from `total > 0 and balance <= 0` to `balance <= 0 and 0 < total` | One note changes, exactly as in case D | Nothing changed |
| F | `Reminder` renamed to `Nudge` everywhere | Eleven changed lines and one changed generated case | One shape was renamed, and its stored records are kept under the old name |
| G | Rule `RemindOverdue` changed from `today - 7 days` to `today - 14 days` | One sentence changes in eight places. No generated case changes | An overdue invoice is now reminded at most once in fourteen days |

What the cases say:

- The compiler computes the answer in case B. The reviewer still has to find it among changed diagram lines and translate it.
- Cases A, F, and G are noise: more changed lines than changed meaning.
- Cases D and E look identical in the generated files. One is a change to the business and one is not. The compiler has no way to tell them apart today.
- Cases C, D, and G change what the system does and no generated case fails. That is a gap in the generated cases, not in this question, and is now a work item in OQ56.

## 2. What to decide

Decisions: D1 whether the tool exists and its form. D2 whether it reports consequences. D3 what it says about a reworded condition. D4 what it says about a rename. D5 whether it describes the stored records a change reaches. D6 a build question, how the tool gets its facts. D7 whether the report is written wholly in the business's words, with no Velle in it. D8 how the tool computes that two conditions mean the same. D9 whether the command counts affected records in a store. D10 what the report says where Velle does not define what happens to a stored record.

The sample reports below are my wording, to show the kind of sentence. No wording is decided by any of D1 to D6. D7 decides the wording.

### D1. Velle gets a command that reads two versions of a spec and reports the change in Velle's words · Decided 2026-11-23

Does Velle get a command that takes the old spec and the new spec and prints what changed, written in the words of the spec?

Recommendation: yes. The form is `velle.CompareKt <before.velle> <after.velle>`, beside `velle.GenerateKt`, printing Markdown. Both specs must validate. Putting its output on a pull request is a later work item, not part of this decision.

The spec, from `examples/billing/billing.velle`, with the edit of case B. Billing has no `uses` lines; `Money` comes from the `velle.finance` library on its classpath.

```velle
shape Invoice {
    customer: one Customer                          -- stored: the customer billed
    due: Date                                       -- stored
    total: Money = sum(lineItems, amount)           -- derived: recomputed from the line items pointing here
    balance: Money = total - sum(payments, amount)  -- derived: what is still owed
}

act LineItem {
    invoice: one Invoice           -- stored: the invoice this line bills
    price: Money                   -- stored: supplied by the caller
    quantity: integer              -- stored: supplied by the caller
    amount: Money = price * quantity   -- derived
}

never (LineItem where quantity <= 0)   -- refusal: checked at the door, the act is refused
never (LineItem where price < 0)       -- refusal: THE EDIT DELETES THIS LINE

shape OverdueInvoice = Invoice where balance > 0 and due < today
    -- refinement: with both refusals standing the compiler proves a line item's amount is never
    -- negative, so a line item can only raise balance, so it can move an invoice into
    -- OverdueInvoice and never out. With the second refusal deleted the proof is gone.
```

What the command would print for case B:

```
Edited
- Removed: never (LineItem where price < 0)

What that changes
- LineItem: a LineItem with a negative price is now accepted. It was refused.
- Invoice: a new LineItem can now move an Invoice out of OverdueInvoice.
  Before, it could only move one in.
- Generated cases: 2 removed, both about the refusal above.
```

And for case A, the reordered file: `No change.`

Costs:

- The report is a second place that must know every construct. A construct the tool has no sentence for must still be reported, as the old and new declarations printed in full through `Printer.kt`, and never dropped. A report that silently says less than the change is worse than no report.
- A reviewer who trusts the report stops reading the text. Anything the report cannot say (D2, D3, D5 name what) is then read by nobody. The report states its limits in its own first lines.
- Nothing changes in the language. A product owner writes nothing new.
- `wiki/Normative/diagrams.md` stops naming the changed diagram lines as the review.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Compare.kt`: the command, new.
- `wiki/Normative/diagrams.md`, "The projection principle" and "Notation": the sentences that name a diagram diff as the review point at the command instead.
- A new wiki guide page for the command, with the billing cases as its examples.
- `SpecComparisonProbeTest` is replaced by tests of the command over the same seven edits.

**Decided:** yes, Dave, 2026-11-23.

### D2. The report has two parts: what was edited, and what the edit changes for declarations nobody edited · Decided 2026-11-23

Does the report list the consequences the compiler derives, such as "a line item can now clear an overdue invoice", beyond the declarations that were edited?

Recommendation: yes. The first part alone is what any language could offer: a list of added, removed, and changed declarations. It already removes the noise of cases A and G, because order, comments, and layout are not in it. The second part is what only Velle can offer, because the compiler already derives it for the diagrams and the generated cases.

The facts the second part would compare, each already computed for one spec today:

| Fact | Where it is computed today | A sentence it would give |
|---|---|---|
| What an act is refused for at the door | `Prohibitions.kt`, the validator's door set | "A LineItem with a negative price is now accepted" |
| Which commit can move a record into or out of a refinement, and whether one direction is proven | `StateFlowGen.kt` | "A new LineItem can now move an Invoice out of OverdueInvoice" |
| Which commit can cause which rule to fire | `RuleGraphGen.kt` | "Unarchiving an invoice can no longer cause RemindOverdue" |
| Which acts wait in line behind one another | `Domains.kt`, `ContentionMapGen.kt` | "SubmitPayment now waits behind every other SubmitPayment, system-wide" |
| Advisories gained and lost | `Validator.kt` | "The change draws advisory A5 on rule X" |
| Generated cases gained and lost | `SpecGen.kt` | "2 generated cases removed" |

Costs:

- Every one of these facts is a "may": the compiler draws an arrow when it cannot prove the arrow impossible. The report can say "can now" and "can no longer". It can never say "will". A product owner must read it that way.
- The report can only say what the compiler derives. In case C the business consequence, archived invoices are now reminded, is derived nowhere, so the second part would report one lost arrow and miss the point. The first part still shows the edited refinement, old and new.
- A proof the compiler is missing shows up as a consequence that is not real. Under OQ158 the compiler already cannot prove a payment only lowers a balance, so a report over billing would say "may" where the business says "never". The report makes the validator's gaps louder. I count that as a benefit, and it will produce false alarms until those gaps close.
- It leans on the principle that rejections are as much the system's information as committed state: what an act is refused for is reported with the same weight as what a rule writes.

#### Proposed changes

- `Compare.kt` prints the second part under its own heading, one group per shape or act the consequence is about.
- The wiki guide page for the command states that every consequence is a "can", and lists the six facts.

**Decided:** yes, Dave, 2026-11-23. His reasoning: this is the whole point. The tool is not a text diff. It is Velle understanding the models that two specs describe, and explaining the changes to a product owner in terms of the business domain, not in terms of Velle and not in terms of text. What that reasoning asks of the report's wording is D7; it is not decided by this answer.

### D3. A condition rewritten in other words is reported as changed, with the old and new condition shown, and the report does not claim it means the same · Decided 2026-11-23

When a condition is rewritten and nothing the compiler derives from it changes, does the report show the old and new condition and say only that, without calling the change harmless?

D7 bears on this: if the report holds no Velle, the two conditions are shown as sentences, not as Velle text. The question here stays the same either way.

Recommendation: yes. Cases D and E are the reason. `due < today` to `due <= today` changes the business. `total > 0 and balance <= 0` to `balance <= 0 and 0 < total` does not. Every fact in D2's table is the same before and after in both cases, so the tool cannot tell them apart, and the one honest sentence covers both:

```
Edited
- Changed: shape OverdueInvoice
    was: Invoice where balance > 0 and due < today
    now: Invoice where balance > 0 and due <= today
  Nothing the compiler derives from it changed. Read the two conditions.
```

The alternative is to teach the tool that some rewrites mean the same: the two sides of `and` swapped, a comparison turned around. Case E would then report `No change.`

Costs of the recommendation:

- The reviewer reads two conditions as text, which is the thing this question set out to remove. It is one condition, named, not a page of lines.
- Costs of the alternative: every rewrite the tool calls "the same" is a claim the compiler must be right about, and a wrong one hides a change to the business. The compiler proves two conditions cannot both hold in some cases today (`StateFlowGen.kt` groups `PaidInvoice` and `OverdueInvoice` on one axis that way). It has no proof that two conditions are equal.

#### Proposed changes

- `Compare.kt` prints the old and new form of every changed condition through `Printer.kt`, and the sentence above when no derived fact changed.

**Decided:** no, Dave, 2026-11-23. He took the alternative: whether two conditions mean the same is computed. His words: "for some operators that will work, but presumably not for extension functions". A rewrite the tool cannot compute is still reported as the recommendation says, old and new shown and no claim made. Which rewrites are computed is D8.

### D4. A declaration that disappears under one name and appears under another with the same body is reported as renamed · Decided 2026-11-23

Does the report say "Reminder was renamed to Nudge" when the only difference between a removed declaration and an added one is the name?

Recommendation: yes, worded as what the tool inferred, with one warning attached.

```
Edited
- Renamed: shape Reminder is now shape Nudge. Nothing else about it changed.
  Records already stored as Reminder are not carried over by this change.
```

Costs:

- Two files cannot tell a rename from deleting one shape and creating another that happens to match. The report says "renamed" on the evidence of the body alone.
- A rename is not free for a running system. The SQL store names a table after its shape (`stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, the `CREATE TABLE IF NOT EXISTS "$shape"` statement), and the generated read and act API is named after it too. So the warning line is part of the rename sentence, not an extra.
- A rename together with an edit to the body is reported as one removal and one addition. The tool does not guess further.

#### Proposed changes

- `Compare.kt` pairs a removed and an added declaration whose printed forms are equal once the name is replaced, for shapes, acts, rules, fields, and refinements.

**Decided:** yes, Dave, 2026-11-23.

### D5. The report says which records already stored are affected, described from the two specs alone

Does the first build say which stored records a change reaches, as a description worked out from the two specs with no store read?

Rewritten 2026-11-24. The first draft proposed silence about stored records. Dave's concern: without this the comparison is very limited. He is right for an edit to a refinement's condition or to a constraint over a stored shape, where most of what the change means is what it does to records already there. The first draft also put three things in one decision. They are now three: this one, D9 (counting the records in a real store), and D10 (what Velle does with them when the new spec starts).

Recommendation: yes. When a refinement's condition changes, the records that enter it are the ones that meet the new condition and not the old, and the records that leave are the ones that meet the old and not the new. Both are conditions the tool can write down from the two specs, using the reasoning of D8 to simplify them.

From `examples/billing/billing.velle`, with the edit of case C. Billing has no `uses` lines.

```velle
shape Invoice {
    customer: one Customer    -- stored
    due: Date                 -- stored
    balance: Money = total - sum(payments, amount)   -- derived: what is still owed
}

shape OverdueInvoice = Invoice where balance > 0 and due < today
    -- refinement: an invoice is a member whenever the condition holds

shape IssuedInvoice = Invoice where exists Issuance for this
    -- refinement: a member once an Issuance points at the invoice

shape ArchivedInvoice = Invoice where
    exists ArchiveRequest for this and not exists UnarchiveRequest for this
    -- refinement: archived and not since unarchived

shape ActionableOverdue = OverdueInvoice and not ArchivedInvoice
    -- refinement composed of two others. THE EDIT changes it to:
    --     OverdueInvoice and not IssuedInvoice

rule RemindOverdue
    when (ActionableOverdue where
          not exists (Reminder where invoice == this and sentOn > today - 7 days))
    on Weekly {                                       -- runs at each Weekly tick, once per member
    Reminder from { invoice: this, sentOn: today }    -- creates a Reminder for that invoice
}
```

What the report would add for case C, in the wording D7 asks about:

```
Invoices already on the books
- Every invoice that is overdue and archived, and not issued, becomes an actionable overdue invoice.
  At the next Weekly tick each one gets a reminder, unless it had one in the last 7 days.
- Every invoice that is overdue and issued, and not archived, stops being an actionable overdue invoice.
  It gets no more reminders.
```

The second sentence of the first item can be said because a rule on a schedule runs over whoever is a member at the tick (Reference §16). What cannot be said is D10.

Costs:

- The description is only as plain as the tool can make the two conditions. "Meets the new condition and not the old" for conditions with counts, walks, or extension functions may not simplify, and the report then prints both conditions and says the affected records are those that meet one and not the other.
- It describes which records. It does not say how many, and "every invoice that is overdue and archived" may be none. That is D9.
- A reviewer reads these sentences as what will happen when the change ships. They are that only for rules on a schedule. For everything else the report must say what D10 decides, in the same place, or the sentences mislead.
- It leans against one design principle, stated here so it is not missed: a system's state changes only on a tick or an incoming act. Replacing the spec moves records into and out of refinements with neither. The report would be the first place Velle says so out loud.

#### Proposed changes

- `Compare.kt` prints, for each changed refinement condition and each added or changed constraint over a stored shape, the records that enter and the records that leave, and for each rule on a schedule over that refinement, what its next tick does with them.
- The first line of the report no longer says it is silent about stored records.

### D6. The tool compares facts, and the diagram generators are split so that the diagrams and the tool read the same facts · Decided 2026-11-23

Do `StateFlowGen.kt` and `RuleGraphGen.kt` get split into a step that works out the facts and a step that draws them, so the comparison reads the facts and not the drawn lines?

Recommendation: yes. This is a build question with no effect on what a spec means. Today each generator works out a fact and writes its Mermaid line in one pass. The other way to build the tool is the way the probe test works, comparing drawn lines, and cases A, F, and G show what that gives: order changes, one sentence repeated eight times, a rename as eleven lines.

Costs:

- `StateFlowGen.kt` is 796 lines and `RuleGraphGen.kt` 226, both changed without changing one generated line. `StateFlowGenTest`, `RuleGraphGenTest`, and every example's checked-in `DIAGRAMS-<system>.md` pin that.
- Until it is done, the tool can be built with its first part only (D2), which needs nothing from the diagram generators.

#### Proposed changes

- `StateFlowGen.kt`, `RuleGraphGen.kt`, `ContentionMapGen.kt`: each returns a list of facts, and a separate function draws them. No generated file changes.

**Decided:** yes, Dave, 2026-11-23.

### D7. The report is written wholly in the business's words: the names the spec gives things, and everyday words, with no Velle keyword and no Velle text · Decided 2026-11-24

Does the report hold no Velle at all, so that even the part saying what was edited is sentences and never a printed declaration?

This question comes from Dave's reasoning on D2 (2026-11-23): the report explains the change "in terms of the business domain, not the velle domain or text". My samples in D1, D3, and D4 do not meet that. They print `never (LineItem where price < 0)` and `Invoice where balance > 0 and due <= today`, which is Velle text, and they say "shape" and "move an Invoice out of OverdueInvoice", which is how Velle talks about a business, not how the business talks.

Recommendation: yes. The names in a spec (`Invoice`, `OverdueInvoice`, `LineItem`, `price`) are the business's own words and stay. Everything around them is everyday words. The compiler already writes conditions as sentences for the generated cases: `examples/billing/output/SPEC_INDEX-Billing.md` says "a LineItem where price below 0.00 is refused" for `never (LineItem where price < 0)`, written by `SpecGen.kt`.

Case B, as D1 shows it and as this decision would have it:

```
As D1 shows it                                    With no Velle in it

Edited                                            A line item with a price below 0.00 is no longer refused.
- Removed: never (LineItem where price < 0)
                                                  Because of that:
What that changes                                 - A new line item can now make an overdue invoice
- LineItem: a LineItem with a negative price        stop being overdue. Before, a line item could only
  is now accepted. It was refused.                  make an invoice overdue.
- Invoice: a new LineItem can now move an
  Invoice out of OverdueInvoice. Before, it
  could only move one in.
```

Case D, the condition of D3, both ways:

```
As D3 shows it                                    With no Velle in it

- Changed: shape OverdueInvoice                   An invoice is overdue when its balance is above 0 and its
    was: Invoice where balance > 0                due is on or before today. Before: when its balance is
         and due < today                          above 0 and its due is before today.
    now: Invoice where balance > 0
         and due <= today
```

Costs:

- Every construct needs a sentence form, and a condition can be anything a spec can say: a count, a walk, a selection, a nested `exists`. `SpecGen.kt` has sentences for comparisons only; a `never` with any other condition gets no generated case today (a work item in OQ56). Until a construct has a sentence, the report must fall back to printing its Velle, and say that it is doing so, or it drops the change. So "no Velle" is a goal the first build reaches for the common forms only.
- A sentence can be read two ways where Velle text cannot. "Its balance is above 0 and its due is on or before today" is clear. A condition with `and`, `or`, and `not` together is not, in a sentence. The engineer reviewing the same change loses the exact text and goes to the `.velle` file for it.
- The business's names are spelled as the spec spells them, `OverdueInvoice` and `LineItem`. Writing "overdue invoice" and "line item" means the tool splits names into words, which is a guess about names like `SKU` or `B2BOrder`. The recommendation is to split, since the generated diagrams are for the same reader. Dave may prefer the names untouched.
- The engineer is the second reader of every review. If the report serves only the product owner, the engineer reads the text diff beside it. That is the same two-reader split Velle itself is built on, so one report may not serve both.

#### Proposed changes

- `Compare.kt` writes every line as a sentence. A construct with no sentence form is printed as Velle under a line saying the tool has no words for it yet.
- The sentence forms for conditions move out of `SpecGen.kt` to one place that `SpecGen.kt` and `Compare.kt` both read.
- Added with the answer, 2026-11-24: every sentence ends with a pointer to where in the spec it comes from: the file, the line, and the declaration as written. For a consequence, the pointer is the edited declaration that causes it, since that is where the change would be undone. For a removed declaration, it is the line in the old spec.
- Added with the answer, 2026-11-24: each declaration in `compiler/src/main/kotlin/velle/Ast.kt` keeps the line it starts on. `Lexer.kt` counts lines for its tokens today, and `Parser.kt` reports them only in a syntax error.

**Decided:** both, Dave, 2026-11-24. The report reads in plain English, so that a person who is not technical can understand which business rules changed. When that person decides against a change, they must be able to tell the engineer where in the spec to make it, so each sentence also points at the place in the Velle that says it. His words: "it needs to read in plain english enough for non-tech humans to understand what business rules have changed. But, in the case where the human decides _against_ some behavior, they need to communicate where in velle to make that change." The pointer is the one place Velle text appears, and it is a reference for the engineer, not something the product owner reads. Names are split into words, as recommended; he did not ask for them untouched.

### D8. Each operator states facts about itself, and the tool computes "means the same" by reasoning from those facts, never from a list of operator names

Does each operator carry its own facts, such as "my two sides may be swapped", so that the tool works out whether two conditions mean the same from the facts of the operators in them?

This is the build question D3's answer leaves. Rewritten 2026-11-23 after Dave asked whether Velle can understand the operators as objects and reason about what they mean. My first draft was a fixed list of rewrites keyed on operator names. That is a hard-coded branch per builtin, which `compiler/CLAUDE.md` rules out ("Every builtin goes through the extension interfaces"), and it left every extension type out.

Recommendation: yes. An operator is already an object that states facts about itself. `compiler/src/main/kotlin/velle/ext/VelleType.kt`, class `Operator`, carries `foldStepsCommute` and `foldIdempotent`, which check V8 reads to accept a fold, and which are "checked on the type's samples (E2) and otherwise trusted". This decision adds facts of the same kind and one reader of them.

The facts, and who states them:

| Fact | Stated by | What the tool concludes |
|---|---|---|
| The two sides may be swapped: `a op b` equals `b op a` | Each `Operator`, builtin or extension, as a new member beside `foldStepsCommute`. True of `+` and `*` on decimals, and of `Money + Money` if `velle.finance` says so. False of `-` | `price * quantity` and `quantity * price` are the same |
| Grouping does not matter: `(a op b) op c` equals `a op (b op c)` | Each `Operator`, a new member | `a + b + c` in any order and grouping is the same, together with the fact above |
| An ordering is one comparator | Already stated: a type that implements `Ordered` (`compiler/src/main/kotlin/velle/ext/Capabilities.kt`, `Ordered.ordering`) | `total > 0` and `0 < total` are the same, for any ordered type |
| Equality is one function | Already stated: `VelleType.equal` | `a == b` and `b == a` are the same |
| A function gives the same answer for the same arguments | Already stated: `compiler/src/main/kotlin/velle/ext/VelleFunction.kt`, the interface comment | The same call on the same arguments is the same |
| A function's arguments may be given in any order | Each function, a new member. True of `max` and `min` | `max(a, b)` and `max(b, a)` are the same |
| `and`, `or`, `not` | The language. These are not operators of any type | The sides of `and` and of `or` may be swapped; `not not P` is `P`; `not (P and Q)` is `not P or not Q`. A condition writes nothing and is true or false, never unknown |
| A refinement's name stands for its condition | The language | `invoice is PaidInvoice` and the condition of `PaidInvoice` written out are the same |

So an extension function is not wholly closed, which softens what D3 presumed. Velle still cannot read what a function does. It can read what the function's author states about it.

One conclusion the facts do not give, though an engineer would expect it: `not (due < today)` against `due >= today`. An absent operand satisfies no ordered comparison (`Evaluator.kt`, the `"<", "<=", ">", ">="` branch; Reference §10). With `due` absent the first is true and the second is false. The tool concludes they are the same only where the compiler proves both sides present (`PresenceFacts.kt`).

Case E, `total > 0 and balance <= 0` against `balance <= 0 and 0 < total`, follows from the ordering fact and the `and` fact, and reports `No change.` Case D, `due < today` against `due <= today`, follows from no fact and is reported as changed.

Costs:

- A fact is a claim by whoever wrote the operator. Velle checks it on the type's samples, as check E2 does for the fold facts, and otherwise trusts it. A wrong fact makes the report call a real change "No change", which is the worst thing the report can do. The same trust already decides whether a fold is accepted.
- The extension API grows. Each new fact defaults to "not stated", so an existing library such as `extensions/finance` keeps working and gets false alarms until it states its facts. A false alarm is a rewrite reported as changed that means the same. It is never a hidden change.
- The facts are limited to those that give one agreed spelling of a condition: sort the sides, flatten the grouping, turn every comparison one way. Two conditions are the same when their agreed spellings are equal. Facts that relate two operators, such as `a * (b + c)` against `a * b + a * c`, have no such spelling and no end to the reasoning they need. They are left out, and such a rewrite is reported as changed.
- Nothing is concluded from values. `balance > 0` against `balance >= 0.01` is the same for `Money` and different for a decimal. Reported as changed.
- The compiler gains a notion it does not have today, two conditions being equal. `StateFlowGen.kt` (`complementary`) and `validation/RuleDisjointness.kt` prove two conditions cannot both hold by comparing their written form, and `StateFlowGen.signDelta` names `max` and `min` by hand (OQ116). All three could read the same facts. That is OQ53 and OQ116, and not proposed here.
- It leans on two principles in the root `CLAUDE.md`: Velle codes to its own extension interfaces, and custom functions outrank custom types.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ext/VelleType.kt`, class `Operator`: two new members, for swapped sides and for grouping, each defaulting to not stated.
- `compiler/src/main/kotlin/velle/ext/VelleFunction.kt`: one new member, arguments in any order, defaulting to not stated.
- `compiler/src/main/kotlin/velle/ext/builtin/BuiltinTypes.kt` and `BuiltinFunctions.kt`: the builtins state their facts through those members.
- Check E2 in `wiki/Normative/checks.md` tests each new fact on the type's samples.
- A new file under `compiler/src/main/kotlin/velle/` that writes a condition in its agreed spelling from the facts.
- `Compare.kt` compares agreed spellings, and reports a declaration as changed only when they differ.
- `wiki/Guides/Extension-types.md` and `wiki/Guides/Extension-functions.md`: the new members.
- A test per fact, evaluating both spellings over records with present and absent values.

### D9. The command can be pointed at a store, and then counts the records D5 describes

Does the command take a store to read, so that "every invoice that is overdue and archived, and not issued" becomes "37 invoices"?

Recommendation: yes, as a later build than D5, and optional each time the command runs. The description of D5 is a condition over a stored shape, which is what the read API already runs (Reference §22; `compiler/src/main/kotlin/velle/Query.kt`). The store API says nothing of how records are kept, so the count works over any store.

Costs:

- The count is of one store at one moment: a test system, or the live one. The report must name which and when, and a count from a test system says little about the live one.
- A condition that reads `today` is counted as of the day the command runs, not the day the change ships.
- The command becomes something that connects to a running system's records. Who may run it, and against what, is the engineer's to arrange. Velle would only read.
- The new condition is evaluated over records stored under the old spec. Where the change also adds or removes a field, some of those records cannot be read as the new spec describes them. That case is D10's.

#### Proposed changes

- `Compare.kt` takes an optional store connection and prints a count beside each description.
- The wiki guide page for the command: how to point it at a store.

### D10. Where Velle does not define what happens to a stored record when the spec changes, the report says exactly that, and the definition is OQ28's

Does the report say "Velle does not define what happens to these records" at each place the language is silent, with OQ28 widened to answer them?

Recommendation: yes. The tool cannot report a behaviour the language has not decided, and a report that passes over these places would read as "nothing happens". The places found so far:

| Label | The change | What is not defined |
|---|---|---|
| U1 | A refinement's condition is rewritten and a rule fires when a record enters it, as rule `SendReceipt` does on `PaidInvoice` in `examples/billing/billing.velle` | Whether the rule ever fires for a record that became a member by the rewrite. The runtime looks for a record entering a refinement only when a commit changes data the condition reads (`compiler/src/main/kotlin/velle/ConditionWatchers.kt`, `canChangeUnder`; Reference §11). No commit is the entry here. No test pins what happens at such a record's next commit |
| U2 | The same, with a rule that fires when a record leaves the refinement, as rule `NoteUnarchival` does on `ArchivedInvoice` | The same question for leaving, and what a value captured at entry holds for a record that never entered by a commit |
| U3 | A constraint is added over a stored shape, and records already stored break it | Whether those records are found, and whether the next commit that touches one is refused |
| U4 | A required field is added to a stored shape | What value the records already stored have for it. OQ154 covers a record the runtime cannot read, for a row changed outside Velle; this is the same record reached by a change of spec |
| U5 | A new rule is added over records that already meet its condition | This is OQ28 as it stands today |

A rule on a schedule is the one case that is defined: its next tick runs over whoever is a member (Reference §16).

Costs:

- The report will say "not defined" often, on the edits that matter most. That is the true state of the language, and it puts a price on leaving OQ28 undesigned.
- OQ28 grows from one question (U5) to five. It is the larger piece of work behind this tool, and this tool cannot finish without it.
- U1 to U4 are stated from reading the source, not from running it. A test that starts a system on one spec, stores records, restarts it on the edited spec, and commits, would pin what the runtime does today. That is a work item below.

#### Proposed changes

- `Compare.kt` prints "Velle does not define what happens to these records" with the reason, at each of U1 to U5.
- OQ28's file gains U1 to U4, and its title widens from a new rule to any change of spec over records that already exist.

## 3. The build

Waits on D5, D8, D9, and D10.

## 4. Work items

- [ ] Write a test that starts a system on `examples/billing/billing.velle`, stores an archived overdue invoice and a paid invoice, restarts it over the same store on the spec edited as in case C and with the condition of `PaidInvoice` rewritten, and commits to each record, pinning what the runtime does today for U1 and U2 of D10.
- [ ] Write the same kind of test for U3 and U4 of D10: a constraint added over records that break it, and a required field added to a stored shape.

The gap that cases C, D, and G show in the generated cases is a work item in OQ56.
