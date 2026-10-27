package velle.postgres

import velle.sql.ALL_COMPARISONS
import velle.sql.EQUALITY_ONLY
import velle.sql.SqlDialect
import velle.sql.StorageClass
import velle.sql.StorageColumn
import velle.AdmissionKey
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL's part of the SQL store: a native column for each storage class
 * and an identity key column. Every class has a column that orders as its
 * carrier does, so every comparison but boolean and uuid ordering (which the
 * language does not have) is pushed down.
 *
 * `matches` is left to the evaluator: PostgreSQL's regular expressions are
 * not Java's, and a pattern the database cannot read would fail the query
 * rather than over-return, which the superset contract does not allow
 * (the function's store side is OQ69's).
 *
 * A datetime is held as `TIMESTAMPTZ`, which keeps microseconds: an instant
 * with finer precision comes back rounded to the microsecond.
 *
 * Units of work: a read is a read-only `REPEATABLE READ` transaction, one
 * snapshot for every statement in it; a write is `READ COMMITTED`, and its queue
 * keys become transaction-scoped advisory locks (`pg_advisory_xact_lock` on the
 * key's hash), taken in sorted order under a `lock_timeout`, so two envelopes
 * sharing a key run one at a time across every connection and process on the
 * database, and a stuck lock fails the envelope instead of hanging it.
 */
object PostgresDialect : SqlDialect {

    override val name = "PostgreSQL"

    /** An identity column never hands out a deleted row's key again. */
    override val keyColumn = "BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY"

    override val serializesKeys: Boolean get() = true

    override fun beginRead(conn: Connection) {
        conn.autoCommit = false
        conn.isReadOnly = true
        conn.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    }

    override fun beginWrite(conn: Connection) {
        conn.autoCommit = false
        conn.isReadOnly = false
        conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
    }

    /** One advisory lock per key on the key text's hash; a collision only serializes two envelopes that need not be. */
    override fun lock(conn: Connection, keys: List<AdmissionKey>, timeout: Duration) {
        conn.createStatement().use { it.execute("SET LOCAL lock_timeout = '${timeout.toMillis()}ms'") }
        conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { ps ->
            for (k in keys) {
                ps.setString(1, k.text)
                ps.executeQuery().close()
            }
        }
    }

    override fun regexMatch(column: String): String? = null

    private class Column(
        override val columnType: String,
        override val answers: Set<String>,
        private val toJdbc: (Any) -> Any,
        private val fromJdbc: (ResultSet, String) -> Any,
    ) : StorageColumn {
        override fun bind(carrier: Any): Any = toJdbc(carrier)
        override fun read(rs: ResultSet, column: String): Any = fromJdbc(rs, column)
    }

    private val columns: Map<StorageClass, StorageColumn> = mapOf(
        StorageClass.TEXT to Column("TEXT", ALL_COMPARISONS, { it }, { rs, c -> rs.getString(c) }),
        StorageClass.INTEGER to Column("BIGINT", ALL_COMPARISONS, { it }, { rs, c -> rs.getLong(c) }),
        StorageClass.DECIMAL to Column("NUMERIC", ALL_COMPARISONS, { it }, { rs, c -> rs.getBigDecimal(c) }),
        StorageClass.DOUBLE to Column("DOUBLE PRECISION", ALL_COMPARISONS, { it }, { rs, c -> rs.getDouble(c) }),
        StorageClass.BOOLEAN to Column("BOOLEAN", EQUALITY_ONLY, { it }, { rs, c -> rs.getBoolean(c) }),
        StorageClass.DATE to Column("DATE", ALL_COMPARISONS, { it }, { rs, c -> rs.getObject(c, LocalDate::class.java) }),
        StorageClass.DATETIME to Column("TIMESTAMPTZ", ALL_COMPARISONS,
            { (it as Instant).atOffset(ZoneOffset.UTC) }, { rs, c -> rs.getObject(c, OffsetDateTime::class.java).toInstant() }),
        StorageClass.TIME to Column("TIME", ALL_COMPARISONS, { it }, { rs, c -> rs.getObject(c, LocalTime::class.java) }),
        // whole seconds: orders as the value does
        StorageClass.DURATION to Column("BIGINT", ALL_COMPARISONS, { (it as java.time.Duration).seconds }, { rs, c -> java.time.Duration.ofSeconds(rs.getLong(c)) }),
        StorageClass.UUID to Column("UUID", EQUALITY_ONLY, { it }, { rs, c -> rs.getObject(c, UUID::class.java) }),
        StorageClass.BYTES to Column("BYTEA", ALL_COMPARISONS, { it }, { rs, c -> rs.getBytes(c) }),
    )

    override fun column(storage: StorageClass): StorageColumn = columns.getValue(storage)
}
