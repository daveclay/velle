# OQ160 — Whether a reference to a deleted record is emptied in the store

**Status:** opened 2026-11-21, split out of OQ155 at Dave's direction: "Leaving a reference to a deleted record is not how systems work", and it is a separate problem from generating foreign keys. D1 and D2 await Dave. Nothing built, and two things are not yet checked (§4).

**In plain terms:** When a record is deleted, other records that pointed at it still hold its number in the database, pointing at nothing. Velle reads those as empty, so nothing looks wrong from inside Velle, but the database itself is left holding pointers to records that are gone. The question is whether the pointer should be cleared when the record is deleted.

**See:** Reference §12, "Deletion", which says a deletion leaves no reference that outlives its target. `checks.md` V24, which decides which references may survive a deletion. `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `delete` (line 981), which removes the row. `wiki/Guides/Store-API.md`, the store contract. `working-docs/QUESTIONS.md` OQ155, on foreign keys, whose D4 depends on the answers here. `working-docs/QUESTIONS.md` OQ154, on rows removed outside Velle.

---

## Roster

- **D1**, **D2**: the decisions, in §2. D1 was D2 of OQ155 until 2026-11-21.
- **A union reference**: a field whose type is a shape declared as `A or B` (Reference §9). The store holds it as two columns, the key and the name of the shape.
- **A junction table**: the table, shared among database engineers as a term, that holds a `many` field. It has one row per member, with the owner's key and the member's key.
- **A foreign key**: a constraint declared on a column, shared among database engineers, saying its value must be the key of a row in another table.

## 1. What runs today

`SqlStore.delete` removes the deleted record's row, the rows of its own `many` fields, and what its refinements remembered about it. It does not touch any other record's row. A row that pointed at the deleted record keeps the key, and the runtime reads that reference as absent (`compiler/src/main/kotlin/velle/Runtime.kt`, line 288). `SqlStoreConformance`, "deleting a row removes its collections and captures and leaves references at it reading absent", pins both halves: it asserts "the surviving row still holds the dead key" and that the reference reads as absent.

This is why the store contract forbids reusing a key (`wiki/Guides/Store-API.md`, "Keys are the store's and are never reused"): a new record given the dead key would be pointed at by rows that meant the old one.

## 2. What to decide

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

- [ ] Check, with a test, a commit that deletes a record and also assigns a field of a record pointing at it: that the pointing row ends with the reference empty.
- [ ] Check, with a test, a later commit that assigns another field of a record whose reference was emptied: that the store does not write the dead key back.
