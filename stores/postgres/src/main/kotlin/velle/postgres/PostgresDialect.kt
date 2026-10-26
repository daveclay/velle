package velle.postgres

import velle.sql.ALL_COMPARISONS
import velle.sql.EQUALITY_ONLY
import velle.sql.SqlDialect
import velle.sql.StorageClass
import velle.sql.StorageColumn
import java.sql.ResultSet
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
 */
object PostgresDialect : SqlDialect {

    override val name = "PostgreSQL"

    /** An identity column never hands out a deleted row's key again. */
    override val keyColumn = "BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY"

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
