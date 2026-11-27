# The SQL store

Velle ships one [store](Store-API) for relational databases, in `stores/sql/`, with a small part per database: `stores/sqlite/` for SQLite and `stores/postgres/` for PostgreSQL. This page is about that one store. It says how to connect a system to a database through it, which tables and columns it creates from a spec, what the database itself enforces, and what is left to you when the spec changes. Nothing on this page is part of the Velle language or of the store contract. Another store keeps records its own way.

## The situation

The registrar's office runs the enrollment system in `examples/enrollment/enrollment.velle`. Its records have to live in the PostgreSQL database the office already backs up and reports from. The engineer wants the tables created for them, wants to read those tables with their own SQL for reports, and needs to know what to do when the spec gains a field next term.

## Connecting a system to a database

```kotlin
val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
val sys = EnrollmentSystem().also { SqliteStore.attach(it.system, conn) }
```

- `EnrollmentSystem()` is the system generated from the spec.
- `SqliteStore.attach` creates the tables the database does not have yet, then connects the system to the store. An in-memory SQLite database lives in its one connection, so the store takes the connection itself.
- Over a file or a server database the store takes a `DataSource` and borrows a connection for each read and each commit: `PostgresStore.attach(system, dataSource)`, as `examples/billing/output/src/test/kotlin/velle/GeneratedApiOverPostgresTest.kt` does.
- To create the tables without connecting, build the store and call `createSchema()`: `SqliteStore(model, conn).apply { createSchema() }`.
- A Spring Boot application calls none of these. The starter creates the tables at startup when `velle.store.create-schema` is true, which is the default ([Serving a system](Serving-a-system)).

## The tables it creates

From `examples/enrollment/enrollment.velle`:

```
act Advisor {
    name: text
    advisees: many Student = (Student where advisor == this)
}

act Course {
    title: text
    capacity: integer
    term: duration
    code: uuid initially randomUUID
    startsAt: time?
    syllabus: bytes?
}

act Student {
    name: text
    advisor: one Advisor?
    tags: many text
    notes: many text tolerates duplication
    courses: many Course
}
```

Over SQLite the store creates these tables for `Student`, among others. The text is what SQLite records, pinned by `EnrollmentSchemaOverSqliteTest` in `examples/enrollment/output/src/test/kotlin/velle/EnrollmentOverSqliteTest.kt`:

```sql
CREATE TABLE "Student" (id INTEGER PRIMARY KEY AUTOINCREMENT, "name" TEXT, "advisor" INTEGER, FOREIGN KEY ("advisor") REFERENCES "Advisor"(id))
CREATE TABLE "Student__tags" (owner INTEGER NOT NULL, pos INTEGER NOT NULL, value TEXT NOT NULL)
CREATE TABLE "Student__courses" (owner INTEGER NOT NULL, pos INTEGER NOT NULL, target INTEGER NOT NULL, FOREIGN KEY ("target") REFERENCES "Course"(id))
```

- **One table per shape**, named as the shape is, with the same capitals. A [transient act](Reference-04-State-and-commits#transient-acts-transient-act) has no table, since it is never kept.
- **`id` is the record's key.** The database assigns it when the row is written and never gives a deleted row's key to a later row: SQLite's `AUTOINCREMENT`, PostgreSQL's identity column.
- **One column per stored field**, named as the field is. A field computed with `=` has no column, and neither has a transient field. A `timestamp on create` field and the actor of a kept act each have one.
- **A column may hold `NULL`** even for a field the spec requires. The spec's rules decide what is required, and the store does not repeat it.
- **A `one` field holds the key of the record it points at.** `advisor` holds an `Advisor`'s `id`.
- **A `one` field typed with a union has a second column** naming the shape, since a key is unique only within one table. In `examples/gallery/gallery.velle`, `Comment.target: one Content`, where `shape Content = Post or Photo`, is stored as `target` and `target__shape`.
- **Each `many` field is a table of its own**, `<Shape>__<field>`, with one row per member. `owner` is the key of the record the field belongs to, and `pos` is the member's place in the list, so the order is kept. A `many` of values holds the value in `value`. A `many` of records holds the member's key in `target`, and a member typed with a union adds `target_shape`.
- **A key the shape declares** ([§21, "Keys"](Reference-21-never#keys)) is a `UNIQUE` declaration over the key's columns.
- **A refinement that remembers values** with `captured` has a table `capture_<Refinement>`, one row per record currently in it. In `examples/billing/billing.velle`, `ArchivedInvoice` remembers `archivedOn`, so its table is `capture_ArchivedInvoice` with the columns `id` and `archivedOn`.
- **`velle_tick`** holds the moment each [schedule](Reference-17-Schedule-triggers) last ran.
- **A shape other shapes take with `is`** ([§9](Reference-09-Composing-refinements)) has no table. Its fields are columns on each taker's table.

Each field's type has a column type per database:

| Velle type | SQLite | PostgreSQL |
|---|---|---|
| `text` | `TEXT` | `TEXT` |
| `integer`, `long` | `INTEGER` | `BIGINT` |
| `decimal`, `double`, and every other number type | `TEXT` | `NUMERIC` |
| `boolean` | `INTEGER`, 0 or 1 | `BOOLEAN` |
| `Date` | `TEXT` | `DATE` |
| `DateTime`, `timestamp` | `TEXT` | `TIMESTAMPTZ` |
| `time` | `TEXT` | `TIME` |
| `duration` | `INTEGER`, whole seconds | `BIGINT`, whole seconds |
| `uuid` | `TEXT` | `UUID` |
| `bytes` | `BLOB` | `BYTEA` |
| a reference | `INTEGER` | `BIGINT` |

SQLite holds dates, times, and uuids as ISO-8601 text, and decimals as text so they come back exact. An [extension type](Extension-types) is held in the column its binding chooses, and the store refuses to start without a binding for each extension type the spec stores.

## What the database enforces

- **A reference must name a row that is there.** Each field that points at records of one shape gets a [foreign key](GLOSSARY#foreign-key), and so does each `many` field of records of one shape. The database refuses a write naming a record that is not there, and refuses to remove a row a record still points at. A field typed with a union points at rows of several tables and gets none.
- **A rule's `delete` is never refused by it.** When a commit deletes a record, the store first empties every reference to it: a `one` column is set to `NULL`, with its shape column when it has one, and the member's row leaves each `many` table. Then the row goes ([§12, "Deleting a record that others point at"](Reference-12-Assignment#deleting-a-record-that-others-point-at)).
- **Two records may not share a declared key.** The `UNIQUE` declaration keeps two commits from separate processes from both landing.
- **SQLite enforces foreign keys only on a connection that turns them on.** The store turns them on for every connection it takes. A connection of your own enforces them only when you turn them on too, with `PRAGMA foreign_keys = ON` or `foreign_keys=true` on the URL.

## Your own SQL beside the store

Reading the tables with your own SQL is always safe, for reports or exports. Writing to them is not. A hand-run `UPDATE` or `DELETE` changes records with no rule fired and no prohibition checked ([Store API](Store-API), "What a store must do"). Your own tables beside Velle's are yours.

## When the spec changes

The store creates only the tables the database does not have. A table that already exists is left exactly as it is. A field added to a shape gains no column, a field removed keeps its column, and a database created before foreign keys were declared keeps running without them. Changing an existing table is a migration, and it is yours to write with the tools you already use.

## Related pages

- [Store API](Store-API), the contract every store meets, this one included, and how to write a store of your own.
- [Serving a system](Serving-a-system), the Spring Boot starter that connects this store to an application's database.
- [Extension types](Extension-types), "the piece a store needs to hold it".
- [Extension functions](Extension-functions), "Holding a function in a store", how a function's call is run in the database.
