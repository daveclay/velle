# OQ155 — Whether the tables Velle creates declare foreign keys

**Status:** opened 2026-11-20 from Dave's note while deciding OQ153 D4. D1 decided 2026-11-26; D3 and D4 await Dave. On 2026-11-21 Dave cleared his answers to D1 and D2, D2 moved to OQ160, and D4 was added. OQ160 was settled and built on 2026-11-22, so D4 no longer waits on it. Nothing built, and four things are not yet checked (§4).

**In plain terms:** When one record points at another, the database could be told so, and it would then refuse to remove a record that others still point at. Velle creates the tables and does not tell the database this today. The question is whether it should.

**See:** `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema` (line 401), which writes the tables. `wiki/Guides/Store-API.md`, "What a store must do", "A deletion empties every reference to the record", which is what OQ160 settled and what D4 rests on. `working-docs/QUESTIONS.md` OQ153, whose D4 stops checking that a referenced record exists. `working-docs/QUESTIONS.md` OQ154, on what Velle reports after a row is removed outside Velle. `working-docs/QUESTIONS.md` OQ27 and OQ28, which also change rows that already exist.

---

## Roster

- **D1**, **D3**, **D4**: the decisions, in §2. **D2** moved to OQ160, where it was D3, decided yes and built.
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

### D1. The tables Velle creates declare a foreign key for a reference to a shape no rule deletes · Decided 2026-11-26

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

**Decided:** yes, 2026-11-26. Whether the same foreign key goes on a reference to a shape some rule deletes is still D4.

### D2. Moved to OQ160

Whether a reference to a deleted record is emptied was OQ160 D3 from 2026-11-21. Dave decided yes on 2026-11-22 and it is built: the store empties every stored reference to a deleted record in the same commit (`wiki/Guides/Store-API.md`, "What a store must do"; `SqlStore.delete` in `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`). What foreign key a reference to a shape some rule deletes declares is D4.

### D3. A database that already exists is not altered

Should `createSchema` add foreign keys only to tables it creates, leaving the tables of an existing database as they are, and leaving the engineer to add or drop constraints when the spec changes?

Recommendation: yes for now. Altering an existing table can fail on the rows already in it, and can be slow on a large one; both are migration, which Velle does not do for any other change to a spec today.

Costs:

- Two databases running the same spec can differ in what they enforce, depending on when each was created.
- When a spec gains a rule that deletes a shape, an existing database keeps the foreign keys from D1 and refuses that rule's delete until the engineer drops them. The error must say so.

### D4. A reference to a shape some rule deletes declares the same foreign key as D1's

If OQ160 decides that the store empties every reference to a record before removing it (`working-docs/QUESTIONS.md` OQ160, D3 and D4), should every reference to one shape declare the plain foreign key of D1, whether or not a rule deletes that shape?

This decision waited on OQ160 until 2026-11-22. OQ160 decided that the store empties every reference before it removes the row, with its own statements, and that is built (`SqlStore.delete`, `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`; `SqlStoreConformance`, "deleting a row removes its collections and captures and empties every reference at it"). So the condition this question opens with holds.

In `examples/moderation/moderation.velle`, `Listing` has the field `seller: one Seller? initially required` and the rule `ApplyClose` deletes a `Seller`. With a yes here, the `Listing` table is created with:

```sql
FOREIGN KEY ("seller") REFERENCES "Seller"(id)   -- the same clause D1 puts on a reference to a shape no rule deletes
```

The rule's delete is allowed because the store has emptied `"Listing"."seller"` before it removes the `Seller` row.

Recommendation: yes.

- The foreign key is the same for every reference to one shape. D1's test, "no rule in the spec deletes records of that shape", is no longer needed. D1's third cost and D3's second cost go away: a spec that gains its first rule deleting a shape needs no constraint dropped.
- The database refuses a write that names a record which does not exist, for every single-shape reference.

Costs:

- This widens D1, which asks only about shapes no rule deletes. A yes here answers D1 with yes for every shape.
- A row of any shape removed outside Velle is refused by the database while another row points at it. This limits the maintenance OQ154 D1 says is the engineer's to do, for every shape and not only the ones D1 named.
- A union reference still has no foreign key, so the database does not refuse a write naming a missing record there, and an outside removal can leave one pointing at nothing.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema`: D1's foreign key on every reference to one shape, with no test of `Model.deletedShapes`.
- `wiki/Guides/Store-API.md`: every single-shape reference is constrained; a union reference is not.

## 3. The build

Not written. D1 is decided; D3 and D4 await Dave.

## 4. Work items

- [ ] Check, with a test, whether one commit that creates two records pointing at each other, or a record and the record it points at, writes rows in an order an immediate foreign key accepts, or needs the constraint deferred to the end of the transaction.
- [ ] Check, with a test, that SQLite enforces the constraint on the connections the store takes; SQLite ignores foreign keys unless each connection turns them on.
- [ ] Check which other paths remove or re-key rows (OQ27 erasure, seeds, capture tables) and would meet the constraint.
- [ ] Check, with a test, that no statement is refused by a foreign key in a commit that deletes a record and also assigns a field of a record pointing at it.
