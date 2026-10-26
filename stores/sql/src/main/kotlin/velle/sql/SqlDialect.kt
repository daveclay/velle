package velle.sql

import java.sql.ResultSet

/**
 * What one SQL database contributes to [SqlStore]: a column for each
 * [StorageClass], the key column a shape table mints keys in, and the one
 * operator the standard leaves out. Everything else a store does, the schema
 * derived from the model, the reads, the `WHERE` rendering, the commit, is the
 * layer's and the same over every dialect.
 *
 * A dialect is built per connection, since some (SQLite) install functions on it.
 */
interface SqlDialect {

    /** The database's name, for messages: `SQLite`, `PostgreSQL`. */
    val name: String

    /**
     * DDL of a shape table's key column, after `id`: the database mints the key
     * at insert and `INSERT ... RETURNING id` reports it. A deleted row's key is
     * never reused: a surviving row may still reference a deleted one (the
     * absorbing reference, Reference §12), which the runtime reads as absent,
     * and that reading is only sound while no new row can inherit the dead key.
     */
    val keyColumn: String

    /** The column this database holds one storage class in. */
    fun column(storage: StorageClass): StorageColumn

    /**
     * SQL testing [column] against a regular expression bound as the clause's
     * one parameter, matching the whole text (`matches`, Reference §10); null
     * when the database has no regular expressions, which leaves `matches` to
     * the evaluator.
     */
    fun regexMatch(column: String): String?
}

/**
 * One database's column for one [StorageClass]: its type name, the JDBC value
 * for a carrier, the carrier back from a result row, and which comparisons the
 * column answers with the carrier's own meaning.
 */
interface StorageColumn {

    /** The column type in `CREATE TABLE`: `INTEGER`, `BIGINT`, `TEXT`, ... */
    val columnType: String

    /** The value for `PreparedStatement.setObject`, from a carrier. */
    fun bind(carrier: Any): Any

    /** The carrier back from [column] of the current row. */
    fun read(rs: ResultSet, column: String): Any

    /**
     * The comparisons the column answers faithfully in `WHERE`: all six when the
     * column orders as the carrier does, equality only when it merely equates,
     * none when the stored form compares differently from the carrier (a decimal
     * held as text). The store degrades every other comparison to the evaluator.
     */
    val answers: Set<String>
}
