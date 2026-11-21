# OQ155 — Whether the tables Velle creates declare foreign keys

**Status:** opened 2026-11-20 from Dave's note while deciding OQ153 D4. D1 and D2 decided; D3 awaits Dave. Nothing built, and three things are not yet checked (§4).

**In plain terms:** When one record points at another, the database could be told so, and it would then refuse to remove a record that others still point at. Velle creates the tables and does not tell the database this today. The question is whether it should.

**See:** `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema` (line 401), which writes the tables. `working-docs/QUESTIONS.md` OQ153, whose D4 stops checking that a referenced record exists. `working-docs/QUESTIONS.md` OQ154, on what Velle reports after a row is removed outside Velle. `working-docs/QUESTIONS.md` OQ27 and OQ28, which also change rows that already exist.

---

## Roster

- **D1**, **D2**, **D3**: the decisions, in §2.
- **A foreign key**: a constraint declared on a column, shared among database engineers, saying its value must be the key of a row in another table. The database refuses a write that would break it.
- **A union reference**: a field whose type is a shape declared as `A or B` (Reference §9). The store holds it as two columns, the key and the name of the shape.

## 1. What runs today

`SqlStore.createSchema` is what creates the tables. It is called by `SqliteStore.attach` (`stores/sqlite/src/main/kotlin/velle/sqlite/SqliteStore.kt`, line 45), by the PostgreSQL store's `attach` (`stores/postgres/src/main/kotlin/velle/postgres/PostgresStore.kt`, line 30), and by the Spring starter when its `createSchema` setting is on (`apis/spring/src/main/kotlin/velle/api/spring/VelleAutoConfiguration.kt`, line 111).

It writes, per shape, `CREATE TABLE IF NOT EXISTS` with one column per field, and a `UNIQUE` constraint per declared key (`keyConstraints`, line 376). It writes no foreign key: neither `REFERENCES` nor `FOREIGN KEY` appears anywhere under `stores/`.

Three facts shape the answer:

- **A rule may delete a record that others point at.** The store removes the row and leaves the pointing rows as they are; the runtime reads such a reference as absent (`SqlStore.delete`, line 986, and its comment). A foreign key on such a column would make the database refuse the rule's own delete.
- **A union reference has no single table to point at.** Standard SQL cannot declare a foreign key for it.
- **`CREATE TABLE IF NOT EXISTS` leaves an existing table alone.** A database created before this change would gain nothing unless the store also altered tables.

## 2. What to decide

### D1. The tables Velle creates declare a foreign key for a reference to a shape no rule deletes · Decided 2026-11-21

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

**Decided:** yes (Dave, 2026-11-21). The recommendation he answered was conditional on the three checks in §4, which are not yet run.

### D2. A reference to a shape some rule deletes gets no foreign key · Decided 2026-11-21

For a reference to a shape that a rule deletes, should the store declare nothing, as today, where the alternative is a foreign key that empties the pointing column when the row is removed?

Recommendation: yes, nothing. The alternative changes a surviving record's stored value as a side effect of another record's deletion. Today the reference is kept and read as absent, and whether it should instead be emptied belongs to the language, not to one store.

Costs:

- A row of such a shape removed outside Velle looks exactly like one a rule deleted, so nothing reports it. OQ154 D1 already accepts that.

**Decided:** yes, nothing (Dave, 2026-11-21).

### D3. A database that already exists is not altered

Should `createSchema` add foreign keys only to tables it creates, leaving the tables of an existing database as they are, and leaving the engineer to add or drop constraints when the spec changes?

Recommendation: yes for now. Altering an existing table can fail on the rows already in it, and can be slow on a large one; both are migration, which Velle does not do for any other change to a spec today.

Costs:

- Two databases running the same spec can differ in what they enforce, depending on when each was created.
- When a spec gains a rule that deletes a shape, an existing database keeps the foreign keys from D1 and refuses that rule's delete until the engineer drops them. The error must say so.

## 3. The build

Not written. Nothing is decided.

## 4. Work items

- [ ] Check, with a test, whether one commit that creates two records pointing at each other, or a record and the record it points at, writes rows in an order an immediate foreign key accepts, or needs the constraint deferred to the end of the transaction.
- [ ] Check, with a test, that SQLite enforces the constraint on the connections the store takes; SQLite ignores foreign keys unless each connection turns them on.
- [ ] Check which other paths remove or re-key rows (OQ27 erasure, seeds, capture tables) and would meet the constraint.
