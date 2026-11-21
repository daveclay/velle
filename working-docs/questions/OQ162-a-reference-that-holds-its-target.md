# OQ162 — How a spec says a record cannot be deleted while another record points at it

**Status:** opened 2026-11-21 from Dave's remark on OQ160 D1: he had been weighing a shorthand for preventing a delete while a shape is referenced, and "this has made me think it's worthy of a language structure so the validator can read it more clearly". That a structure is wanted is Dave's. Where it is said and how it is spelled are proposals. D1 to D3 are decided. D4, added 2026-11-21 from OQ161's third case and rewritten the same day around a case Dave asked for, awaits Dave. Nothing built; the plan in §3 does not yet hold D4's changes.

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

### D4. A refusal written with `is held` is read when the commit ends

Dave, 2026-11-21: "an act that creates a reference to a record that the act deletes is definitely invalid and nonsensical." That is taken as given. The decision is how Velle enforces it.

Should `never (... where x is held)` be read over the state the commit leaves, after its rules have run, so that it refuses exactly the request that would leave a required reference pointing at a record the request deleted?

Recommendation: yes.

The case, "an employee leaves and another takes over their shop", as one request:

```
act Employee {                              -- compiler: a persistent shape
    name: text                              -- compiler: a text column "name"
}

act Shop {                                  -- compiler: a persistent shape
    name: text                              -- compiler: a text column "name"
    manager: one Employee                   -- compiler: required, so this shop holds its manager (D1)
}

transient act ReplaceManager {              -- compiler: transient, so the request is not kept
    shop: one Shop                          -- compiler: the caller names one existing Shop
    departing: one Employee                 -- compiler: the caller names the Employee who leaves
    successor: one Employee                 -- compiler: the caller names the Employee who takes over
}

never (ReplaceManager where departing is held)
                                            -- compiler: a refusal; the rule below assigns Shop.manager, which this test reads,
                                            -- compiler: so it is read when the commit ends and not when the request arrives

rule ApplyReplace when ReplaceManager {     -- compiler: runs once per ReplaceManager
    shop.manager = successor                -- compiler: points the shop at the successor
    delete departing                        -- compiler: V24 passes, because the `never` refuses any commit that ends with `departing` held
}
```

This is `working-docs/examples/survey/24-delete-and-hand-over-one-request.velle` with the field made required and the `never` added. That file passes today, with the field declared `manager: one Employee? initially required`.

What each request gets:

| request | state the commit would leave | result |
|---|---|---|
| shop 3, departing Ann, successor Bo | shop 3 points at Bo; nothing points at Ann | committed |
| shop 3, departing Ann, successor Ann | shop 3 points at Ann, who is deleted | refused: Ann is held by shop 3 |
| shop 3, departing Ann, successor Bo, and shop 4 is also Ann's | shop 4 points at Ann, who is deleted | refused: Ann is held by shop 4 |

Read when the request arrives, the test would refuse all three, because shop 3 points at Ann at that moment. The one-request handover could not be said.

This is not a new rule of evaluation. `evaluation.md`, "The door's moment", already says a prohibition's meaning is the settled transaction, and that one reading what the request's handling changes is evaluated after the handling. What is new is that `is held` on a record the commit deleted counts the required references still pointing at it.

A rule's condition is different: it is read when the rule fires. So for the two other forms of D2, the deleting rule's condition and the `undeletable` refinement, the compiler refuses a commit that deletes a record of a shape and also creates or assigns a required reference to that shape, and the error names the `never` form as the way to say it. `working-docs/examples/survey/24-delete-and-point-at-one-rule.velle` is the plainly wrong case: one rule creates a listing for the seller it deletes. With a `never`, that request is always refused when its commit ends.

Costs:

- The rules of a refused request have run before it is refused, and are rolled back. A rule in the same commit that reads `shop.manager` after the delete could meet a record that is gone. Not yet checked.
- The compiler does not report the plainly wrong rule, one that always creates a reference to the record it deletes, when the refusal is a `never`. Every request to it is refused at run time. An advisory could say so; it is not proposed here.
- The handover cannot be written with the condition or `undeletable` forms. Those authors are told to use the `never`.
- It leans on the principle that a rejection must reach a human with what was rejected and why: the refusal names the record still holding the deleted one.

#### Proposed changes

- `compiler/src/main/kotlin/velle/`: `is held` on a record the commit deleted counts the required references pointing at it in the settled transaction. A `never` using it is evaluated at the end of the commit whenever a rule of that commit creates, assigns, or deletes what it reads.
- `compiler/src/main/kotlin/velle/Validator.kt`, check V24: for the condition and `undeletable` forms, a commit that deletes a record of a shape and creates or assigns a required reference to that shape is an error naming the `never` form.
- `wiki/Normative/evaluation.md`, "The door's moment"; `wiki/Normative/checks.md` V24; Reference §12, with the manager case.
- `working-docs/examples/survey/`: `24-delete-and-hand-over-one-request.velle` rewritten with the required field and the `never`; `24-delete-and-point-at-one-rule.velle` kept as the request that is always refused; their README rows.

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
