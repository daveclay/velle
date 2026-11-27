# OQ205 — Whether the SQL store names its tables and columns in snake case

**Status:** opened 2026-11-27 from Dave's question whether capitalized table names are conventional in PostgreSQL. D1 and D2 decided 2026-11-27; D3 and D4 await Dave. Nothing built.

**In plain terms:** The SQL store names a table `"Student"` and a column `"dueOn"`, exactly as the spec spells them. PostgreSQL users write `student` and `due_on`, and a name with capitals must be quoted in every query of their own. The store will use the lower-case names. Three questions follow: how a run of capitals converts, what happens when two names convert to one, and what happens to a database that already has the old names.

**See:** `wiki/Guides/SQL-store.md`, "The tables it creates", which documents today's names. `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`, `createSchema` and every statement that names a table or a column. `wiki/Normative/grammar.md`, `Identifier` and `ShapeName`, which allow letters and digits only. `working-docs/QUESTIONS.md` OQ155, whose D3 says the store never alters a table that already exists.

---

## Roster

- **D1** to **D4**: the decisions, in §2.
- **Snake case**: a name in lower-case words joined by `_`, such as `due_on`, shared among database engineers and the PostgreSQL convention.
- **A junction table**: the table, shared among database engineers as a term, that holds a `many` field, one row per member.
- **A capture table**: the table the store keeps for a refinement that remembers values with `captured`, one row per record in it.

## 1. What runs today

Every name the store writes is the spec's name, quoted, as pinned by `EnrollmentSchemaOverSqliteTest` in `examples/enrollment/output/src/test/kotlin/velle/EnrollmentOverSqliteTest.kt`:

```sql
CREATE TABLE "Student" (id INTEGER PRIMARY KEY AUTOINCREMENT, "name" TEXT, "advisor" INTEGER, FOREIGN KEY ("advisor") REFERENCES "Advisor"(id))
CREATE TABLE "Student__courses" (owner INTEGER NOT NULL, pos INTEGER NOT NULL, target INTEGER NOT NULL, FOREIGN KEY ("target") REFERENCES "Course"(id))
```

- A capture table is `capture_<Refinement>`, such as `capture_ArchivedInvoice`. The schedules' table is `velle_tick`.
- No two of these can collide today. A shape name starts with a capital, so `velle_tick` and every `capture_` table start in lower case and no shape's table does. A Velle name has no `_`, so a junction table's `__` names no shape.
- A Velle name is letters and digits only (`wiki/Normative/grammar.md`, `Identifier`, `ShapeName`). None of the systems under `examples/` has a name with two capitals in a row.
- Hand-written SQL naming these tables is in the tests of `stores/sql`, `stores/sqlite`, and four example modules (`billing`, `enrollment`, `membership`, `orders`), and in the tuned query of `wiki/Guides/Store-API.md`, "Tuning one question with hand-written SQL".

## 2. What to decide

### D1. The SQL store names its tables and columns in snake case · Decided 2026-11-27

Should the store write `student`, `student__courses`, and `due_on` instead of `"Student"`, `"Student__courses"`, and `"dueOn"`?

Recommendation: yes.

**If we do it**
- Pro: an engineer's own reports and migrations over PostgreSQL read as ordinary SQL, with no quotes.
- Con: two names the spec keeps apart can become one table name. See D3.
- Con: a database created with the old names no longer matches. See D4.

**If we don't**
- Pro: nothing changes, and no name can collide.
- Con: every query of the engineer's own over PostgreSQL quotes every table and every camel-case column.

The store keeps quoting every name it writes. A quoted lower-case name is the same name as the unquoted one, so the engineer may leave the quotes out, and a name that is an SQL keyword, such as `order` for `act Order` in `examples/orders/orders.velle`, still works.

#### Proposed changes

- `stores/sql/src/main/kotlin/velle/sql/SqlStore.kt`: one function turning a Velle name into its snake-case name, used for every table and every column the store writes. A shape's table is its name in snake case. A junction table is `<shape>__<field>`. A union reference's second column is `<field>__shape`. A capture table is `capture__<refinement>`. The schedules' table is `velle__tick`.
- Every test under `stores/` and `examples/*/output/src/test/` that names a table or a column in its own SQL.
- `wiki/Guides/SQL-store.md`, "The tables it creates", and the tuned query in `wiki/Guides/Store-API.md`.
- `EnrollmentSchemaOverSqliteTest`: the statements it pins.

**Decided:** yes, 2026-11-27 ("move to snake case").

### D2. A run of capitals is one word · Decided 2026-11-27

When a name has two capitals in a row, should the run be one word, so `HTTPServer` is `http_server`, rather than one word per capital, `h_t_t_p_server`?

```
act HTTPServer {           -- the compiler accepts the name: letters and digits
    hostName: text         -- a column, host_name either way
}
act HttpServer {           -- a second shape, a different name to the compiler
    port: integer
}
```

Under one word per run, both shapes are `http_server` (D3). Under one word per capital, they are `h_t_t_p_server` and `http_server`.

Recommendation: one word per run, the conversion engineers expect.

**If we do it**
- Pro: the names read as an engineer would write them.
- Con: two shapes the compiler keeps apart can share a table name, as above. D3 says what then happens.

**If we don't**
- Pro: no two Velle names can ever share a snake-case name. A name has no `_` of its own, so each `_` marks exactly one capital, and the name can be read back.
- Con: an acronym reads as `h_t_t_p_server`, which no engineer would write.

**Decided:** one word per run, 2026-11-27.

### D3. The store refuses to start when two names become one

Should the store refuse to start, naming both Velle names and the table or column they share, when two of them convert to one name?

This covers two shapes as in D2, two fields of one shape such as `urlPath` and `uRLPath`, and a shape whose table would be one of the store's own: `shape VelleTick` is `velle_tick`, which is why D1 moves the schedules' table to `velle__tick`, and a shape `Velle` with a `many` field `tick` would be `velle__tick`.

Recommendation: yes. The compiler cannot catch it, because a table name is the store's choice and another store names nothing (`compiler/CLAUDE.md`, "The store is not a database").

**If we do it**
- Pro: a spec never runs with two shapes writing one table.
- Con: a spec the compiler accepts can fail at startup over the SQL store, with a message from the store and not the compiler.

**If we don't**
- Pro: nothing more to check.
- Con: two shapes would share one table, and each would read the other's rows as its own.

### D4. A database with the old names

A database the store created before this change has `"Student"`. After it, the store looks for `student`, finds none, and creates an empty one beside the old table. What should the store do?

1. **Refuse to start** when it finds a table with the old name of a shape and none with the new name, naming each and the `ALTER TABLE "Student" RENAME TO student` statement that fixes it.
2. **Rename the tables itself** at startup.
3. **Nothing**: no database outside this repository's tests has been created by the store.

Recommendation: 3 if no database you care about runs on the SQL store today, otherwise 1. Do you have one?

**Option 1**
- Pro: the records are never silently hidden behind an empty table.
- Con: every startup looks for old names, a check that stays in the store for good.

**Option 2**
- Pro: nothing for the engineer to do.
- Con: it goes against OQ155 D3, which leaves a table that exists as it is, and a rename can fail halfway on a database the store does not own.

**Option 3**
- Pro: no code for a case that does not occur.
- Con: a database that does exist would start up empty, with its records still in the old tables.

## 3. The build

Not written. D3 and D4 await Dave.

## 4. Work items

None yet.
