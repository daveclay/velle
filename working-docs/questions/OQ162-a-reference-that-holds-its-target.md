# OQ162 — How a spec says a record cannot be deleted while another record points at it

**Status:** opened 2026-11-21 from Dave's remark on OQ160 D1: he had been weighing a shorthand for preventing a delete while a shape is referenced, and "this has made me think it's worthy of a language structure so the validator can read it more clearly". That a structure is wanted is Dave's. Where it is said and how it is spelled are proposals. D1 to D3 are decided. D4, added 2026-11-21 from OQ161's third case, awaits Dave. Nothing built; the plan in §3 does not yet hold D4's changes.

**In plain terms:** Most of the time a product owner does not want a record deleted while other records point at it. Velle has no way to say that directly today. The question is what the spec's author writes to say it, so that the compiler can read it as a statement and does not have to work it out from how a condition happens to be worded.

**See:** `checks.md` V24, the check that refuses a delete a required reference could be left pointing at nothing by. Reference §12, "Deletion". Reference §21, on a refusal. `working-docs/examples/survey/README.md`, the four `24-delete-pointed-at-` rows, which show what the compiler does today. `working-docs/QUESTIONS.md` OQ160, whose D1 proposed proving the same thing from a `never` and was answered by D2 here. `working-docs/QUESTIONS.md` OQ161, on requests that arrive together. `working-docs/QUESTIONS.md` OQ155, on foreign keys.

---

## Roster

- **D1** to **D4**: the decisions, in §2.
- **A required reference**: a `one` field with no `?`, such as `seller: one Seller`. It always points at a record.
- **Holds**: used in this file for what D1 proposes a required reference does to the record it points at. A listing holds its seller when the seller cannot be deleted while the listing points at it. It is not a Velle keyword unless D3 makes it one.
- **A foreign key**: a constraint declared on a column, shared among database engineers, saying its value must be the key of a row in another table.

## 1. What runs today

The compiler refuses a rule that deletes a record a required reference could point at, whatever else the spec says. `working-docs/examples/survey/24-delete-pointed-at-never-refused.velle` refuses the request `CloseAccount` with a `never` while a `Listing` points at the seller, and the rule `ApplyClose` is still refused with `[V24] rule 'ApplyClose' deletes a 'Seller' ('delete seller'), which strands 'Listing.seller: one Seller'`. `24-delete-pointed-at-condition-refused.velle` and `24-delete-pointed-at-undeletable-refused.velle` in the same folder say it two other ways and get the same error.

What passes today is `working-docs/examples/survey/24-delete-pointed-at-workaround.velle`: the field is weakened to `seller: one Seller? initially required`, which says a listing may come to have no seller, although the `never` beside it means that cannot happen.

`working-docs/QUESTIONS.md` OQ160 D1 proposes having V24 accept the three refused files by recognising `exists (Listing where seller == this.seller)` in a `never`, a rule's condition, or an `undeletable` refinement. Its costs are what led here: a spec that says the same thing in other words is refused, and every new shape pointing at a seller needs another condition written into the delete rule.

## 2. What to decide

### D1. A required reference holds the record it points at · Decided 2026-11-21

Does `seller: one Seller` on `Listing` itself say that a seller cannot be deleted while a listing points at it, with no further word on the field?

Recommendation: yes. The choice between refusing the delete and clearing the reference is made per reference, and the clearing answer is already said on the field, as `seller: one Seller? initially required`. A required field already promises that a listing always has a seller. Holding the seller is what keeping that promise takes.

```
act Seller {                                -- compiler: a persistent shape, so the store creates table "Seller"
    name: text                              -- compiler: a text column "name"
}

act Listing {                               -- compiler: a persistent shape, so the store creates table "Listing"
    title: text                             -- compiler: a text column "title"
    seller: one Seller                      -- compiler, with this decision: required, so this listing holds its seller
}

act Review {                                -- compiler: a persistent shape, so the store creates table "Review"
    body: text                              -- compiler: a text column "body"
    seller: one Seller? initially required  -- compiler: does not hold its seller; it reads as absent once the seller is deleted, as today
}
```

This case is written for this decision and is in no example file. The product owner reads the two fields as the two answers: a seller with a listing cannot be deleted, and a seller with only reviews can, leaving the reviews without one.

A rule that deletes a seller and says nothing about held sellers is still a compile error, as today. The language does not turn the request away on its own, because the author chooses for each refusal whether it is refused at the door or recorded. How the rule answers is D2.

The alternative is a clause on the deleted shape, written where `undeletable` is written, saying a seller cannot be deleted while anything points at it. It covers every pointing shape with one line. It takes the choice away from the reference, so `Review.seller` above could not be cleared while `Listing.seller` holds.

Costs:

- The product owner is asked once per field, when the reference is declared required. They are not asked again when a rule that deletes the shape is written later.
- A required reference that should not stop the delete has only today's ways out: the pointing record is deleted in the same commit, or the field is declared `? initially required`.
- An optional reference never holds. A spec that wants a plain `?` reference to stop the delete writes its own `never`, which the compiler does not need for V24.
- It leans on the principle that a design in which a wrong state cannot be represented beats one in which it is checked: with a foreign key on the column (`working-docs/QUESTIONS.md` OQ155), the database cannot hold a listing whose seller is gone.

#### Proposed changes

- Reference §6 and Reference §12, "Deletion": a required reference holds its target.
- `wiki/Normative/checks.md`, V24: reworded from stranding to holding. What discharges it is D2.

**Decided:** yes, Dave, 2026-11-21.

### D2. A rule that deletes answers with a built-in test for "something holds this record" · Decided 2026-11-21

Should Velle have a built-in test that is true while a required reference points at a record?

A rule could then delete a record only where that test is false. Check V24 would read the test and nothing else.

Recommendation: yes.

```
act Seller {                                -- compiler: a persistent shape
    name: text                              -- compiler: a text column "name"
}

act Listing {                               -- compiler: a persistent shape
    title: text                             -- compiler: a text column "title"
    seller: one Seller                      -- compiler: required, so this listing holds its seller (D1)
}

transient act CloseAccount {                -- compiler: transient, so the request is not kept
    seller: one Seller                      -- compiler: the caller names one existing Seller
}

never (CloseAccount where seller is held)   -- compiler: a refusal; `is held` is true while a required reference points at the seller
                                            -- compiler: the spelling `is held` is a placeholder until D3

rule ApplyClose when CloseAccount {         -- compiler: runs once per CloseAccount that was not refused
    delete seller                           -- compiler: V24 passes, because the `never` excludes a held seller from every CloseAccount
}
```

This is `working-docs/examples/survey/24-delete-pointed-at-never-refused.velle` with the `never` rewritten. The author still chooses how the refusal is kept. The same test can be the condition of the deleting rule with a second rule recording the refusal on the opposite condition, as `24-delete-pointed-at-condition-refused.velle` does, or the predicate of an `undeletable` refinement, as `24-delete-pointed-at-undeletable-refused.velle` does. A scheduled rule, which has no caller to refuse, uses the condition.

The user is told the request was refused because the seller is held, and the refusal can name the listings holding it, since the compiler knows which fields the test stands for.

Costs:

- The test stands for every required reference to the shape at once. A spec cannot answer for `Listing.seller` and leave another required reference to `Seller` unanswered. That is the point, and it is also all or nothing.
- A new shape with a required reference to `Seller` changes what the test means without the delete rule changing. The compiler says nothing, where under OQ160 D1 it would report an error until the rule named the new shape.
- The test reads every shape that points at the record. On each request the runtime looks in each of those tables. Whether such a read makes requests wait on each other is `working-docs/QUESTIONS.md` OQ161.
- The hand-written `exists (Listing where seller == this.seller)` no longer satisfies V24. OQ160 D1 is then answered no.

#### Proposed changes

- `compiler/src/main/kotlin/velle/`: the test in predicate expressions, and V24 passing when the deleting rule's condition, a `never` over its trigger, or an `undeletable` refinement excludes it. The V24 error names the test.
- Reference §10 and Reference §12, `wiki/Normative/checks.md` V24, `wiki/Normative/grammar.md`.
- `examples/moderation/moderation.velle`: a required reference to `Seller` with the refusal, beside the `Listing.seller` that goes absent.
- `working-docs/examples/survey/`: the `24-delete-pointed-at-` files rewritten with the test, and their README rows.

**Decided:** yes, Dave, 2026-11-21.

### D3. How the test is spelled · Decided 2026-11-21

Given D2, is the test spelled `seller is held`?

Recommendation: undecided between two, leaning to `is held`.

- `seller is held`: says what D1 says a required reference does. It is a new word for the author to learn.
- `seller is referenced`: an everyday word among engineers. It reads as true when any reference points at the seller, including an optional one that is cleared and does not stop the delete, which is wrong.

The declarations are those of D2, where the line is `never (CloseAccount where seller is held)`.

**Decided:** `is held`, Dave, 2026-11-21.

### D4. A commit never both deletes a record and makes a required reference point at a record of that shape

Should the compiler refuse a rule, or two rules that can fire from one commit, that delete a record of a shape and also create or assign a required reference to that shape?

Recommendation: yes.

The gap this closes: `is held` is read when the request arrives. A record that the same commit makes point at the seller does not exist yet, so the test is false, the request is let in, and the seller is deleted while held.

```
act Seller {                                -- compiler: a persistent shape
    name: text                              -- compiler: a text column "name"
}

act Listing {                               -- compiler: a persistent shape
    title: text                             -- compiler: a text column "title"
    seller: one Seller                      -- compiler: required, so this listing holds its seller (D1)
}

transient act CloseAccount {                -- compiler: transient, so the request is not kept
    seller: one Seller                      -- compiler: the caller names one existing Seller
}

never (CloseAccount where seller is held)   -- compiler: a refusal, read when the request arrives; no listing made by this commit exists yet

rule ApplyClose when CloseAccount {         -- compiler: runs once per CloseAccount that was not refused
    Listing from { title: "Closed", seller: seller }
                                            -- compiler: creates a Listing with a required reference to a Seller
    delete seller                           -- compiler, with this decision: an error, because this commit also creates a required reference to a Seller
                                            -- compiler, without it: accepted, and the new listing is left holding a seller that is gone
}
```

This is `working-docs/examples/survey/24-delete-and-point-at-one-rule.velle` with the field made required and the `never` written with `is held`. That file passes today, with the field declared `? initially required`. The same commit written as two rules, `working-docs/examples/survey/24-delete-and-point-at-two-rules-refused.velle`, is refused today by check V15 for its unstated order, which is a different reason.

The check is by shape, not by record: a commit that deletes one seller may not create a listing for any seller. Check V23 already treats assigning a field and deleting the record this way across rules, with the reason "there is no business sentence 'change it and also remove it, at once'" (`checks.md` V23).

The alternative is to give `is held` its meaning at the end of the transaction, as `evaluation.md`, "The door's moment", does for a prohibition that reads what the request's handling moves. The `never` would then be read after the rules have run, and would count references to a record the commit has already deleted. A rule's condition is read when the rule fires and not at the end, so the condition and `undeletable` forms of D2 would still need this compile-time check.

Costs:

- "Close this account and hand its listings to another seller" cannot be one request. Moving the listings assigns `Listing.seller`, a required reference to `Seller`, in a commit that deletes a `Seller`. It is two requests: move the listings, then close the account. The alternative would allow it in one, for the `never` form only.
- The check is coarse. A commit that deletes seller A and creates a listing for seller B is refused although nothing is left pointing at a deleted record.
- It leans on the principle that a design in which a wrong state cannot be represented beats one in which it is checked.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Validator.kt`, check V24: a rule body, or two rules that can fire from one commit, that delete a record of a shape and create or assign a required reference to that shape is an error naming both statements.
- `wiki/Normative/checks.md`, V24, and Reference §12, "What you can and cannot write".
- `compiler/src/test/resources/diagnostics/`: one refused spec per message.
- `working-docs/examples/survey/24-delete-and-point-at-one-rule.velle` is rewritten with a required field and renamed `-refused`, with its README row.

## 3. The build

In build order. Each line is mirrored by a work item in §4. D4 is not decided, and its changes are not in this list yet.

1. **Grammar and parser.** `x is held` joins the `is` tests beside `is none`, `is some`, `is empty`, and `is <Refinement>` (`compiler/src/main/kotlin/velle/Ast.kt`, `IsExpr`, a new kind `"held"`). Its subject is a record of a stored shape. On a scalar, a collection, or a transient act it is an error. `x is not held` is its opposite.
2. **Runtime.** The test is true when a record of any shape points at the subject through a required `one` field, including a required field typed with a union that has the subject's shape as an operand. An optional field, a `? initially required` field, and a `many` field never count (D1).
3. **Check V24** (`compiler/src/main/kotlin/velle/Validator.kt`). A rule that deletes a record a required reference could point at passes when a `never` over its trigger, its own condition, or an `undeletable` refinement of the deleted shape excludes `is held` for the deleted record. The resolution "the pointing record is deleted in the same commit" stays. The error text names `is held` first and `? initially required` second.
4. **The refusal's message.** A refusal found by `is held` names the records holding the subject, by shape and field.
5. **Tests.** `compiler/src/test/kotlin/velle/DeleteValidatorTest.kt`: V24 passes for each of the three forms and fails without one, fails when the test is written on another record than the deleted one, and still fails for a hand-written `exists`. A runtime test: a held seller's `CloseAccount` is refused naming the listing, and the same request commits once the listing is gone.
6. **Regenerate** every example's output.
7. **Wiki.** Reference §6: a required reference holds its target. Reference §10: the `is held` test. Reference §12, "Deletion" and "What the compiler checks": the refusal as the first way to delete a held record. `wiki/Normative/checks.md` V24. `wiki/Normative/grammar.md`. `wiki/Normative/GLOSSARY.md`: "holds".
8. **Examples.** `examples/moderation/moderation.velle` gains a shape with a required reference to `Seller` and the refusal over `CloseAccount`, beside the `Listing.seller` that goes absent. The three `working-docs/examples/survey/24-delete-pointed-at-*-refused.velle` files are rewritten with `is held` and renamed without `-refused`; `24-delete-pointed-at-workaround.velle` is deleted; a new `24-delete-pointed-at-exists-refused.velle` keeps the hand-written `exists` that V24 does not read. The README rows follow.
9. **Settle.** The settled row in `working-docs/QUESTIONS.md`, OQ160's pointers to this OQ reworded to point at Reference §12, and this file deleted.

## 4. Work items

- [ ] Add `is held` to the grammar, the parser, and the type check of its subject.
- [ ] Evaluate `is held` in the runtime over every required `one` field that can point at the subject's shape.
- [ ] Make check V24 pass when a `never`, the rule's condition, or an `undeletable` refinement excludes `is held`, and reword its error.
- [ ] Name the holding records in the refusal's message.
- [ ] Write the validator tests and the runtime test.
- [ ] Regenerate every example's output and run the full suite.
- [ ] Write the wiki changes: Reference §6, §10, §12, `checks.md` V24, `grammar.md`, `GLOSSARY.md`.
- [ ] Grow `examples/moderation/moderation.velle` and rewrite the `24-delete-pointed-at-` survey files and README rows.
- [ ] Settle: the settled row, OQ160's pointers, and this file deleted.
