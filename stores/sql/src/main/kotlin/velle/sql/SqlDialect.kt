package velle.sql

import velle.AdmissionKey
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration

/**
 * What one SQL database contributes to [SqlStore]: a column for each
 * [StorageClass], the key column a shape table mints keys in, and the one
 * operator the standard leaves out. Everything else a store does, the schema
 * derived from the model, the reads, the `WHERE` rendering, the commit, is the
 * layer's and the same over every dialect.
 *
 * A dialect also says how one unit of work (Hydration.kt, [velle.UnitOfWork])
 * maps onto the database: how a read's and a write's transaction begin, and how
 * the queue keys an envelope revolves around become locks the database holds
 * until the transaction ends. A dialect is stateless and shared; what it needs
 * on a connection it installs in [prepare], which the store calls on every
 * connection it takes.
 */
interface SqlDialect {

    /** The database's name, for messages: `SQLite`, `PostgreSQL`. */
    val name: String

    /**
     * Whether [lock] serializes envelopes sharing a queue key across every
     * connection and process on this database. False for a database with one
     * writer at a time, where the keys add nothing.
     */
    val serializesKeys: Boolean get() = false

    /** Installs what the dialect needs on a connection the store just took from its `DataSource`; idempotent. */
    fun prepare(conn: Connection) {}

    /**
     * Begins a read unit's transaction on [conn]: one consistent state for every
     * statement until the store rolls it back, a snapshot where the database has
     * one. The default is a plain transaction at the connection's isolation.
     */
    fun beginRead(conn: Connection) { conn.autoCommit = false }

    /**
     * Begins a write unit's transaction on [conn]. Read committed, not a snapshot:
     * the envelope's locks are taken after this begins, and what it then reads
     * must be the state behind those locks.
     */
    fun beginWrite(conn: Connection) { conn.autoCommit = false }

    /**
     * Takes one lock per key inside the write transaction begun on [conn], in the
     * given order, waiting at most [timeout] for each; the locks go when the
     * transaction ends. Throws when a lock could not be taken in time. The default
     * takes none, for a database that serializes writers itself.
     */
    fun lock(conn: Connection, keys: List<AdmissionKey>, timeout: Duration) {}

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
