# OQ155 — Whether the tables Velle creates declare foreign keys

**Status:** opened 2026-11-20 from Dave's note while deciding OQ153 D4. D1, D2, D3, and D4 await Dave. On 2026-11-21 Dave cleared his answers to D1 and D2, D2 was rewritten at his direction, and D4 was added. Nothing built, and five things are not yet checked (§4).

**In plain terms:** When one record points at another, the database could be told so, and it would then refuse to remove a record that others still point at. Velle creates the tables and does not tell the database this today. The question is whether it should.

**See:** `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema` (line 401), which writes the tables. `working-docs/QUESTIONS.md` OQ153, whose D4 stops checking that a referenced record exists. `working-docs/QUESTIONS.md` OQ154, on what Velle reports after a row is removed outside Velle. `working-docs/QUESTIONS.md` OQ27 and OQ28, which also change rows that already exist.

---

## Roster

- **D1**, **D2**, **D3**, **D4**: the decisions, in §2.
- **A foreign key**: a constraint declared on a column, shared among database engineers, saying its value must be the key of a row in another table. The database refuses a write that would break it.
- **A union reference**: a field whose type is a shape declared as `A or B` (Reference §9). The store holds it as two columns, the key and the name of the shape.
- **A junction table**: the table, shared among database engineers as a term, that holds a `many` field. It has one row per member, with the owner's key and the member's key.

## 1. What runs today

`SqlStore.createSchema` is what creates the tables. It is called by `SqliteStore.attach` (`stores/sqlite/src/main/kotlin/velle/sqlite/SqliteStore.kt`, line 45), by the PostgreSQL store's `attach` (`stores/postgres/src/main/kotlin/velle/postgres/PostgresStore.kt`, line 30), and by the Spring starter when its `createSchema` setting is on (`apis/spring/src/main/kotlin/velle/api/spring/VelleAutoConfiguration.kt`, line 111).

It writes, per shape, `CREATE TABLE IF NOT EXISTS` with one column per field, and a `UNIQUE` constraint per declared key (`keyConstraints`, line 376). It writes no foreign key: neither `REFERENCES` nor `FOREIGN KEY` appears anywhere under `stores/`.

Three facts shape the answer:

- **A rule may delete a record that others point at.** The store removes the row and leaves the pointing rows as they are; the runtime reads such a reference as absent (`SqlStore.delete`, line 986, and its comment). A foreign key on such a column would make the database refuse the rule's own delete.
- **A union reference has no single table to point at.** Standard SQL cannot declare a foreign key for it.
- **`CREATE TABLE IF NOT EXISTS` leaves an existing table alone.** A database created before this change would gain nothing unless the store also altered tables.

## 2. What to decide

### D1. The tables Velle creates declare a foreign key for a reference to a shape no rule deletes

Should `createSchema` declare a foreign key on every column and junction table that holds a reference to one shape, when no rule in the spec deletes records of that shape?

Recommendation: yes, if the three checks in §4 come out clean. It turns OQ153 D4's assumption, that such a reference cannot point at nothing, into something the database enforces, which follows the principle that a wrong state which cannot be represented beats one that is checked.

Costs:

- Maintenance that removes such a row is refused by the database until the pointing rows are dealt with. That is the purpose, and it is also a limit on the maintenance OQ154 D1 says is the engineer's to do.
- A union reference gets no foreign key, so the protection is partial and the wiki must say which references have it.
- A spec that gains its first rule deleting a shape must lose the constraints on references to that shape. See D3.
- Each insert and each change of a reference costs the database one lookup.

In `examples/pharmacy/pharmacy.velle`, `Interaction` points at `Drug`, and no rule deletes a `Drug`, so the `Interaction` table's drug columns would each declare a foreign key to the `Drug` table. `PriceChange` is deleted by a rule, so a column pointing at a `PriceChange` would not.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema`: the constraint, for a reference whose shape is outside `Model.deletedShapes` and is not a union.
- `stores/sql/src/testFixtures/kotlin/velle/sql/SqlStoreConformance.kt`: a test that removing a pointed-at row with SQL is refused, and that every existing scenario still passes.
- `wiki/Guides/Store-API.md`: which references are constrained.

### D2. When a rule deletes a record, every stored reference to it is emptied

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
- Each deletion costs the store one statement per column and junction table that can point at the deleted shape. Who runs them is D4.
- A reference already left pointing at a deleted record in an existing database stays as it is and still reads as absent. Cleaning those up is migration (D3).
- A row removed outside Velle still leaves the references to it in place, unless D4 has the database refuse the removal. OQ154 D1 already makes an outside change the engineer's to answer for.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `delete` and its comment, and the comments on `onCommit` (line 843) and `SqlDialect.keyColumn`: references to the deleted row are emptied.
- `compiler/src/main/kotlin/velle/Hydration.kt`, the comment on `CommitSet.Deletion`: the store empties every reference to the deleted record.
- `stores/sql/src/testFixtures/kotlin/velle/sql/SqlStoreConformance.kt`, "deleting a row removes its collections and captures and leaves references at it reading absent": rewritten to assert the pointing column is empty, with a union reference and a `many` field added.
- `wiki/Guides/Store-API.md`, the store contract: a new line saying a deletion empties every reference to the record. The line "Keys are the store's and are never reused" keeps its rule and loses the sentence about a surviving reference to a deleted record.
- `wiki/Normative/checks.md`, V24: "no write anywhere" becomes "no rule writes it; the store empties it".

### D3. A database that already exists is not altered

Should `createSchema` add foreign keys only to tables it creates, leaving the tables of an existing database as they are, and leaving the engineer to add or drop constraints when the spec changes?

Recommendation: yes for now. Altering an existing table can fail on the rows already in it, and can be slow on a large one; both are migration, which Velle does not do for any other change to a spec today.

Costs:

- Two databases running the same spec can differ in what they enforce, depending on when each was created.
- When a spec gains a rule that deletes a shape, an existing database keeps the foreign keys from D1 and refuses that rule's delete until the engineer drops them. The error must say so.

### D4. The store empties the references itself, and every reference to one shape declares the same plain foreign key

Given D2, should the store run the emptying statements itself before it removes the row, so that the foreign key D1 declares can be declared on every reference to one shape, whether or not a rule deletes that shape?

The alternative is to have the database do the emptying, with a foreign key that says so.

With this decision, the `Listing` table of `examples/moderation/moderation.velle` (declarations in D2) is created with a plain foreign key, and the rule `ApplyClose` deleting seller 7 makes the store run two statements:

```sql
-- in the CREATE TABLE of "Listing": the same clause D1 puts on a reference to a shape no rule deletes
FOREIGN KEY ("seller") REFERENCES "Seller"(id)

-- at the deletion, inside the commit's transaction
UPDATE "Listing" SET "seller" = NULL WHERE "seller" = 7;   -- the store empties the reference
DELETE FROM "Seller" WHERE id = 7;                        -- the database allows it: nothing points at row 7 now
```

With the alternative, the table says what the database does, and the store runs one statement:

```sql
-- in the CREATE TABLE of "Listing"
FOREIGN KEY ("seller") REFERENCES "Seller"(id) ON DELETE SET NULL

-- at the deletion
DELETE FROM "Seller" WHERE id = 7;                        -- the database empties "Listing"."seller" itself
```

Recommendation: yes, the store empties them itself.

- A union reference cannot have a foreign key, so the store must empty those with its own statements in either design. The alternative is two mechanisms; this is one.
- It works in a database created before this change, which has no foreign keys (D3). The alternative leaves that database with references to deleted records.
- The foreign key is the same for every reference to one shape. D1's test, "no rule in the spec deletes records of that shape", is no longer needed, and D1's protection widens to every shape. D1's third cost and D3's second cost go away: a spec that gains its first rule deleting a shape needs no constraint dropped.
- The database refuses a write that names a record which does not exist, for every single-shape reference.

Costs:

- This widens D1, which asks only about shapes no rule deletes. A yes here answers D1 with yes for every shape.
- A row of any shape removed outside Velle is refused by the database while another row points at it. With the alternative the database would empty the pointing rows and allow it. This limits the maintenance OQ154 D1 says is the engineer's to do, for every shape and not only the ones D1 named.
- The store runs one `UPDATE` per column and one `DELETE` per junction table that can point at the deleted shape. The store creates no index on a reference column today (no `CREATE INDEX` appears under `stores/`), so each such statement reads the whole pointing table. The alternative has the same cost inside the database.
- A union reference still has no foreign key, so the database does not refuse a write naming a missing record there, and an outside removal can leave one pointing at nothing.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `delete`: before the row is removed, one statement per column and junction table the model says can point at the shape.
- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema`: D1's foreign key on every reference to one shape, with no test of `Model.deletedShapes`.
- `wiki/Guides/Store-API.md`: every single-shape reference is constrained; a union reference is not.

## 3. The build

Not written. Nothing is decided.

## 4. Work items

- [ ] Check, with a test, whether one commit that creates two records pointing at each other, or a record and the record it points at, writes rows in an order an immediate foreign key accepts, or needs the constraint deferred to the end of the transaction.
- [ ] Check, with a test, that SQLite enforces the constraint on the connections the store takes; SQLite ignores foreign keys unless each connection turns them on.
- [ ] Check which other paths remove or re-key rows (OQ27 erasure, seeds, capture tables) and would meet the constraint.
- [ ] Check, with a test, a commit that deletes a record and also assigns a field of a record pointing at it: that the pointing row ends with the reference empty, and that no statement in the commit is refused by a foreign key.
- [ ] Check, with a test, a later commit that assigns another field of a record whose reference was emptied: that the store does not write the dead key back.
