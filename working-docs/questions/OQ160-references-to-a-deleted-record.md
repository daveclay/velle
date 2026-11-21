# OQ160 — What happens to the records that point at a record when it is deleted

**Status:** opened 2026-11-21, split out of OQ155 at Dave's direction: "Leaving a reference to a deleted record is not how systems work". On 2026-11-21 Dave set the frame the file now follows: the product owner and the engineer choose, per case, between refusing the delete and clearing the references. D1 to D4 await Dave. Nothing built, and four things are not yet checked (§4).

**In plain terms:** A user asks to delete a record that other records point at. The product owner and the engineer have two answers to choose from: the user may not delete it while anything points at it, or it is deleted and the pointers are cleared. Most of the time the product owner wants the first, and the user must be told why in the words of the business, not by an error from the database. Velle can say the second today and cannot say the first.

**See:** Reference §12, "Deletion", and `checks.md` V24, which decide which references may survive a deletion. Reference §21, on a refusal and what it carries to the caller. `examples/moderation/moderation.velle`, the example of deletion. `working-docs/examples/survey/README.md`, the four `24-delete-pointed-at-` rows. `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `delete` (line 981), which removes the row. `wiki/Guides/Store-API.md`, the store contract. `working-docs/QUESTIONS.md` OQ155, on foreign keys, whose D1 and D4 depend on the answers here. `working-docs/QUESTIONS.md` OQ154, on rows removed outside Velle.

---

## Roster

- **D1** to **D4**: the decisions, in §2. D3 and D4 are about refusing the delete and come first in §2. D1 and D2 are about clearing the references. D1 was D2 of OQ155 until 2026-11-21.
- **Refusing the delete**: the first of the two answers. The request to delete is turned away while any record points at the record.
- **Clearing the references**: the second answer. The record is deleted, and every record that pointed at it reads as pointing at nothing.
- **A required reference**: a `one` field with no `?`, such as `seller: one Seller`. It always points at a record.
- **A union reference**: a field whose type is a shape declared as `A or B` (Reference §9). The store holds it as two columns, the key and the name of the shape.
- **A junction table**: the table, shared among database engineers as a term, that holds a `many` field. It has one row per member, with the owner's key and the member's key.
- **A foreign key**: a constraint declared on a column, shared among database engineers, saying its value must be the key of a row in another table.

## 1. What runs today

### Refusing the delete cannot be said while the reference is required

Check V24 (`checks.md`) refuses a rule that deletes a record a required reference could point at, unless the pointing record is deleted in the same commit. It does not read anything else the spec says. Three spellings of "a seller who still has listings cannot close their account" are each refused with the same `[V24]` error (`working-docs/examples/survey/README.md`):

- `working-docs/examples/survey/24-delete-pointed-at-never-refused.velle`: a `never` over the request `CloseAccount` turns it away while a listing points at the seller.
- `working-docs/examples/survey/24-delete-pointed-at-condition-refused.velle`: the rule `ApplyClose` deletes only when no listing points at the seller, and the rule `RefuseClose` records the request that was turned away.
- `working-docs/examples/survey/24-delete-pointed-at-undeletable-refused.velle`: the shape `SellerWithListings` is declared `undeletable`.

What an author writes today instead is `working-docs/examples/survey/24-delete-pointed-at-workaround.velle`, which passes: the same `never`, with the field weakened to `seller: one Seller? initially required`. The `never` means no listing's seller can go absent. The field's type says it may. Every rule that reads `listing.seller` must then answer for an absence that cannot happen, and the product owner reading the shape is told a listing may have no seller.

### Clearing the references is what an optional reference does, and the store keeps the dead key

A reference declared `?`, `? initially required`, or `many` survives the deletion of what it points at and reads as absent afterwards. No spelling says the author chose this for a plain `?` or a `many` field; it follows from the field's type.

`SqlStore.delete` removes the deleted record's row, the rows of its own `many` fields, and what its refinements remembered about it. It does not touch any other record's row. A row that pointed at the deleted record keeps the key, and the runtime reads that reference as absent (`compiler/src/main/kotlin/velle/Runtime.kt`, line 288). `SqlStoreConformance`, "deleting a row removes its collections and captures and leaves references at it reading absent", pins both halves: it asserts "the surviving row still holds the dead key" and that the reference reads as absent.

This is why the store contract forbids reusing a key (`wiki/Guides/Store-API.md`, "Keys are the store's and are never reused"): a new record given the dead key would be pointed at by rows that meant the old one.

## 2. What to decide

The two answers are chosen per reference, by the product owner and the engineer. D3 lets the spec say "refuse the delete". D4 asks whether a reference that is cleared must say so. D1 and D2 are about what the store holds after a reference is cleared.

### D3. A rule may delete a record that a required reference points at when the spec turns the request away while any record points at it

Should the compiler accept `working-docs/examples/survey/24-delete-pointed-at-never-refused.velle`, where the listing's seller is required and a `never` refuses the request to close an account while a listing points at the seller?

Recommendation: yes. This is the answer the product owner wants most of the time, and today it can only be written by declaring the reference as one that may go absent.

```
act Seller {                                -- compiler: a persistent shape, so the store creates table "Seller"
    name: text                              -- compiler: a text column "name"
}

act Listing {                               -- compiler: a persistent shape, so the store creates table "Listing"
    title: text                             -- compiler: a text column "title"
    seller: one Seller                      -- compiler: a required reference; every Listing points at a Seller for as long as it exists
}

transient act CloseAccount {                -- compiler: transient, so no table; the request itself is not kept
    seller: one Seller                      -- compiler: the caller names one existing Seller
}

never (CloseAccount where exists (Listing where seller == this.seller))
                                            -- compiler: a refusal; a CloseAccount for a seller some Listing points at is turned away at the door
                                            -- compiler, with this decision: also the proof V24 asks for, that no Listing points at a seller ApplyClose deletes

rule ApplyClose when CloseAccount {         -- compiler: runs once per CloseAccount that was not refused
    delete seller                           -- compiler today: [V24] error, "strands 'Listing.seller: one Seller'"
                                            -- compiler, with this decision: accepted, because the `never` above covers Listing.seller
}
```

What the user is told is the refusal, not an error from the database. Reference §21 says a refusal "carries the act, the records that would be in the forbidden state, the record the act was about, and the evaluation that found it, and a readable message is rendered from that data". The user who asks to close seller 7 is told the `CloseAccount` was refused because a `Listing` points at that seller.

The author chooses how the refusal is kept, as for every `never`. `working-docs/examples/survey/24-delete-pointed-at-condition-refused.velle` says the same thing with the rule `ApplyClose` on the condition `not exists (Listing where seller == this.seller)` and the rule `RefuseClose` recording a `CloseRefusal`. `working-docs/examples/survey/24-delete-pointed-at-undeletable-refused.velle` says it with the shape `SellerWithListings` declared `undeletable`. A yes here accepts all three.

When the spec says neither answer, the compiler still refuses the delete, as today, and the V24 error names both: refuse the request while a record points at it, or declare the reference as one that goes absent.

Costs:

- The compiler must prove that the refusal covers the reference. It can do that when the condition is written as `exists (Listing where seller == ...)` naming the pointing field and the record the rule deletes. A spec that says the same thing another way, such as through a count the seller keeps of its listings, is refused although it is right. The author rewrites the condition.
- Every required reference to the deleted shape needs its own condition. A seller pointed at by `Listing.seller` and `Invoice.seller` needs both named, and adding a third shape that points at a seller makes the delete rule an error until its condition is added. That is the product owner being asked the question again for the new shape, which is the frame.
- The guarantee is checked, not built into the shapes: the compiler proves the rule cannot fire, and the runtime evaluates the `never` on each request. The principle that a wrong state which cannot be represented beats one that is checked is met in the database only if the column also carries a foreign key (`working-docs/QUESTIONS.md` OQ155). With this decision that foreign key can be declared on a required reference to a shape a rule deletes, and the database's own refusal is never what the user sees, because the `never` has answered first.
- A scheduled rule that deletes has no caller to refuse. It can only use the condition form, deleting the records nothing points at and leaving the rest.
- Two requests arriving together, one closing seller 7 and one creating a listing for seller 7, must take turns for the proof to hold at runtime. Not yet checked (§4).

#### Proposed changes

- `compiler/src/main/kotlin/velle/`, check V24: a required reference is resolved when a `never` over the triggering act, the deleting rule's own condition, or an `undeletable` refinement of the deleted shape excludes every record the reference points at. The error text names this resolution first.
- `wiki/Normative/checks.md`, V24, and Reference §12, "Deletion" and "What the compiler checks": the fourth resolution, with the seller and listing case.
- `examples/moderation/moderation.velle`: a second shape pointing at `Seller` through a required reference, with the refusal, beside the `Listing.seller` that goes absent, so the example shows both answers.
- `working-docs/examples/survey/`: the three `24-delete-pointed-at-*-refused` files become passing files and the workaround file is removed, with their README rows.

### D4. A plain optional reference and a `many` field are cleared without the spec saying so

When a rule deletes a record that a `?` reference or a `many` field points at, should the compiler go on accepting it with no statement from the author, as it does today?

Recommendation: yes, for now.

```
act Coupon {                                -- compiler: a persistent shape, so the store creates table "Coupon"
    code: text                              -- compiler: a text column "code"
}

act Purchase {                              -- compiler: a persistent shape, so the store creates table "Purchase"
    amount: integer                         -- compiler: an integer column "amount"
    coupon: one Coupon?                     -- compiler: an optional reference; a Purchase may be made with no coupon
}

transient act RetireCoupon {                -- compiler: transient, so no table
    coupon: one Coupon                      -- compiler: the caller names one existing Coupon
}

rule ApplyRetire when RetireCoupon {        -- compiler: runs once per RetireCoupon
    delete coupon                           -- compiler: accepted with no diagnostic; every Purchase made with this coupon reads `coupon is none` afterwards
}
```

This case is written for this decision and is in no example file. After `ApplyRetire`, a purchase made with the coupon cannot be told from one made with none. The product owner was never asked. In the frame of this OQ that is the less common wish applied silently.

The alternative is an advisory on `delete coupon` naming `Purchase.coupon`, silenced by the refusal of D3 or by a spelling that says "cleared on purpose". For a `one` field that spelling could be `? initially required` only when the field is also required at creation, which `Purchase.coupon` is not. A new spelling would have to be designed, for `one` and for `many`.

Costs of yes:

- The product owner's choice is made by the field's type for these references, not stated. A reader of the spec cannot tell a chosen clearing from an unconsidered one.
- It leans against the principle that the compiler advises when a statement's meaning is defined but the author may not have intended it.

Costs of no:

- A new spelling, not designed here, on every optional reference and `many` field that points at a shape a rule deletes. In `examples/moderation/moderation.velle` every reference is already `? initially required` or required, so that file would not change.

### D1. When a rule deletes a record, every stored reference to it is emptied

When a rule deletes a record, should the store empty every stored reference to that record in the same commit, so that no row is left holding the key of a record that is gone?

Recommendation: yes. Reference §12 already says a deletion leaves "no reference that outlives its target". That is true of what Velle reads and false of what the store holds. The store leaves the key in place and the runtime reads it as absent (`SqlStore.delete`, `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt` line 981; pinned by `SqlStoreConformance`, "deleting a row removes its collections and captures and leaves references at it reading absent", which asserts "the surviving row still holds the dead key").

The compiler already makes sure every such reference can be emptied. Check V24 (`checks.md`) refuses a `delete` while a required `one` reference could point at the record, unless the pointing record is deleted in the same commit. Every reference that survives a deletion is therefore optional, declared `? initially required`, or a `many` field.

The case, from `examples/moderation/moderation.velle`, shapes `Seller` and `Listing`, rule `ApplyClose`:

```
act Seller {                                -- compiler: a persistent shape, so the store creates table "Seller"
    name: text                              -- compiler: a text column "name"
}

act Listing {                               -- compiler: a persistent shape, so the store creates table "Listing"
    title: text                             -- compiler: a text column "title"
    seller: one Seller? initially required  -- compiler: a column "seller" holding a Seller row's key; must be given at creation, may read as absent later
    isDraft: boolean initially true         -- compiler: a boolean column "isDraft"
}

transient act CloseAccount {                -- compiler: transient, so no table
    seller: one Seller                      -- compiler: the caller names one existing Seller
}

rule ApplyClose when CloseAccount {         -- compiler: runs once per CloseAccount
    delete seller                           -- compiler: Seller joins the shapes a rule deletes; V24 passes because Listing.seller is `? initially required`
}
```

Before `CloseAccount` on seller 7:

| table | row |
|---|---|
| `Seller` | `id 7, name 'Acme'` |
| `Listing` | `id 31, title 'Lamp', seller 7, isDraft false` |

After, today:

| table | row |
|---|---|
| `Seller` | row 7 is gone |
| `Listing` | `id 31, title 'Lamp', seller 7, isDraft false` |

After, with this decision:

| table | row |
|---|---|
| `Seller` | row 7 is gone |
| `Listing` | `id 31, title 'Lamp', seller NULL, isDraft false` |

What "every stored reference" covers:

- A `one` field: its column is set to empty.
- A union reference: both of its columns, the key and the shape name, are set to empty.
- A `many` field: the member's row is removed from the junction table, so the record leaves the collection.

What Velle reads does not change. `seller is none` is true for listing 31 before and after this decision, so the shape `OrphanedListing` and the rule `SweepOrphanedDrafts` in the same file behave as they do today.

Costs:

- Which seller listing 31 had is no longer in the database after the deletion. Today the dead key 7 is still there for someone reading the tables with SQL. A spec that needs to remember it copies the fields, as Reference §12 already tells the author to.
- The store changes a row that no rule assigned. This is the store keeping what Reference §12 promises, not a change of meaning: V24's own text says the reference "goes absent at the target's deletion".
- It becomes part of the store contract, so every store must do it, not only the SQL store. This leans on the principle that a wrong state which cannot be represented beats one that is checked.
- Each deletion costs the store one statement per column and junction table that can point at the deleted shape. Who runs them is D2.
- A reference already left pointing at a deleted record in an existing database stays as it is and still reads as absent. Cleaning those up is migration, which Velle does not do for any change to a spec today.
- A row removed outside Velle still leaves the references to it in place, unless a foreign key has the database refuse the removal (`working-docs/QUESTIONS.md` OQ155). OQ154 D1 already makes an outside change the engineer's to answer for.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `delete` and its comment, and the comments on `onCommit` (line 843) and `SqlDialect.keyColumn`: references to the deleted row are emptied.
- `compiler/src/main/kotlin/velle/Hydration.kt`, the comment on `CommitSet.Deletion`: the store empties every reference to the deleted record.
- `stores/sql/src/testFixtures/kotlin/velle/sql/SqlStoreConformance.kt`, "deleting a row removes its collections and captures and leaves references at it reading absent": rewritten to assert the pointing column is empty, with a union reference and a `many` field added.
- `wiki/Guides/Store-API.md`, the store contract: a new line saying a deletion empties every reference to the record. The line "Keys are the store's and are never reused" keeps its rule and loses the sentence about a surviving reference to a deleted record.
- `wiki/Normative/checks.md`, V24: "no write anywhere" becomes "no rule writes it; the store empties it".

### D2. The SQL store empties the references with its own statements

Given D1, should the SQL store run the emptying statements itself, before it removes the row, where the alternative is to declare a foreign key that tells the database to do the emptying?

For the rule `ApplyClose` of `examples/moderation/moderation.velle` deleting seller 7 (declarations in D1), the store would run two statements inside the commit's transaction:

```sql
UPDATE "Listing" SET "seller" = NULL WHERE "seller" = 7;   -- the store empties the reference
DELETE FROM "Seller" WHERE id = 7;                        -- the store removes the row, as today
```

With the alternative, the table says what the database does, and the store runs one statement, as today:

```sql
-- in the CREATE TABLE of "Listing"
FOREIGN KEY ("seller") REFERENCES "Seller"(id) ON DELETE SET NULL

-- at the deletion
DELETE FROM "Seller" WHERE id = 7;                        -- the database empties "Listing"."seller" itself
```

Recommendation: yes, the store runs them itself.

- A union reference cannot have a foreign key, so the store must empty those with its own statements in either design. The alternative is two mechanisms; this is one.
- It works in a database that has no foreign keys, which is every database Velle has created so far.
- It does not wait on OQ155. Whether the tables declare foreign keys stays a separate decision.

Costs:

- The store runs one `UPDATE` per column and one `DELETE` per junction table that can point at the deleted shape. The store creates no index on a reference column today (no `CREATE INDEX` appears under `stores/`), so each such statement reads the whole pointing table. The alternative has the same cost inside the database.
- Nothing is emptied when a row is removed outside Velle. With the alternative the database would empty the pointing rows then too.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `delete`: before the row is removed, one statement per column and junction table the model says can point at the shape.

## 3. The build

Not written. Nothing is decided.

## 4. Work items

- [ ] Check, with a test, that a request deleting a record and a request creating a record that points at it, arriving together, take turns, so that the second is refused or the first is (D3).
- [ ] Check, with a test, a commit that creates a record pointing at a record and deletes that record: that the refusal of D3 sees the new record.
- [ ] Check, with a test, a commit that deletes a record and also assigns a field of a record pointing at it: that the pointing row ends with the reference empty.
- [ ] Check, with a test, a later commit that assigns another field of a record whose reference was emptied: that the store does not write the dead key back.
