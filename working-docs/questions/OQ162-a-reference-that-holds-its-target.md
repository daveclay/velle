# OQ162 — How a spec says a record cannot be deleted while another record points at it

**Status:** opened 2026-11-21 from Dave's remark on OQ160 D1: he had been weighing a shorthand for preventing a delete while a shape is referenced, and "this has made me think it's worthy of a language structure so the validator can read it more clearly". That a structure is wanted is Dave's. Where it is said and how it is spelled are proposals. D1 to D3 await Dave. Nothing built. OQ160 D1 waits on this.

**In plain terms:** Most of the time a product owner does not want a record deleted while other records point at it. Velle has no way to say that directly today. The question is what the spec's author writes to say it, so that the compiler can read it as a statement and does not have to work it out from how a condition happens to be worded.

**See:** `checks.md` V24, the check that refuses a delete a required reference could be left pointing at nothing by. Reference §12, "Deletion". Reference §21, on a refusal. `working-docs/examples/survey/README.md`, the four `24-delete-pointed-at-` rows, which show what the compiler does today. `working-docs/QUESTIONS.md` OQ160, whose D1 proposes proving the same thing from a `never` and waits on this. `working-docs/QUESTIONS.md` OQ161, on requests that arrive together. `working-docs/QUESTIONS.md` OQ155, on foreign keys.

---

## Roster

- **D1**, **D2**, **D3**: the decisions, in §2.
- **A required reference**: a `one` field with no `?`, such as `seller: one Seller`. It always points at a record.
- **Holds**: used in this file for what D1 proposes a required reference does to the record it points at. A listing holds its seller when the seller cannot be deleted while the listing points at it. It is not a Velle keyword unless D3 makes it one.
- **A foreign key**: a constraint declared on a column, shared among database engineers, saying its value must be the key of a row in another table.

## 1. What runs today

The compiler refuses a rule that deletes a record a required reference could point at, whatever else the spec says. `working-docs/examples/survey/24-delete-pointed-at-never-refused.velle` refuses the request `CloseAccount` with a `never` while a `Listing` points at the seller, and the rule `ApplyClose` is still refused with `[V24] rule 'ApplyClose' deletes a 'Seller' ('delete seller'), which strands 'Listing.seller: one Seller'`. `24-delete-pointed-at-condition-refused.velle` and `24-delete-pointed-at-undeletable-refused.velle` in the same folder say it two other ways and get the same error.

What passes today is `working-docs/examples/survey/24-delete-pointed-at-workaround.velle`: the field is weakened to `seller: one Seller? initially required`, which says a listing may come to have no seller, although the `never` beside it means that cannot happen.

`working-docs/QUESTIONS.md` OQ160 D1 proposes having V24 accept the three refused files by recognising `exists (Listing where seller == this.seller)` in a `never`, a rule's condition, or an `undeletable` refinement. Its costs are what led here: a spec that says the same thing in other words is refused, and every new shape pointing at a seller needs another condition written into the delete rule.

## 2. What to decide

### D1. A required reference holds the record it points at

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

### D2. A rule that deletes answers with a built-in test for "something holds this record"

Should Velle have a test, written on a record, that is true while any required reference points at it, and should check V24 pass when every way of reaching the `delete` excludes it?

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

### D3. How the test is spelled

Given D2, is the test spelled `seller is held`?

Recommendation: undecided between two, leaning to `is held`.

- `seller is held`: says what D1 says a required reference does. It is a new word for the author to learn.
- `seller is referenced`: an everyday word among engineers. It reads as true when any reference points at the seller, including an optional one that is cleared and does not stop the delete, which is wrong.

The declarations are those of D2, where the line is `never (CloseAccount where seller is held)`.

## 3. The build

Not written. Nothing is decided.

## 4. Work items

None yet.
