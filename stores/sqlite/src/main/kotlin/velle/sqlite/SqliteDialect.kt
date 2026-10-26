package velle.sqlite

import velle.sql.ALL_COMPARISONS
import velle.sql.EQUALITY_ONLY
import velle.sql.SqlDialect
import velle.sql.StorageClass
import velle.sql.StorageColumn
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * SQLite's part of the SQL store: its column for each storage class, its key
 * column, and a `REGEXP` function installed on the connection.
 *
 * SQLite has five storage types, so most classes are held as TEXT or INTEGER:
 * integers and durations (whole seconds) as INTEGER, booleans as INTEGER 0/1,
 * decimals as TEXT (exact round-trip, but text does not order as a number, so a
 * decimal column answers no comparison in SQL), dates, times, datetimes and
 * uuids as ISO-8601 TEXT, bytes as BLOB.
 */
class SqliteDialect(conn: Connection) : SqlDialect {

    override val name = "SQLite"

    /** `AUTOINCREMENT` keeps a deleted row's key from ever being reused. */
    override val keyColumn = "INTEGER PRIMARY KEY AUTOINCREMENT"

    /** Whether `matches` (Reference §10) can run in the store: SQLite has no regular
     *  expressions of its own, so a `REGEXP` function is registered on the connection;
     *  a connection that refuses one (a pool's wrapper) leaves `matches` to the evaluator. */
    private val regexpRegistered: Boolean = runCatching {
        org.sqlite.Function.create(conn, "REGEXP", object : org.sqlite.Function() {
            private val cache = HashMap<String, java.util.regex.Pattern>()
            override fun xFunc() {
                // SQLite calls `regexp(pattern, text)` for `text REGEXP pattern`; the whole text must match
                val pattern = value_text(0); val text = value_text(1)
                if (pattern == null || text == null) { result(); return }
                val p = cache.getOrPut(pattern) { java.util.regex.Pattern.compile(pattern) }
                result(if (p.matcher(text).matches()) 1 else 0)
            }
        })
    }.isSuccess

    override fun regexMatch(column: String): String? = if (regexpRegistered) "$column REGEXP ?" else null

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
        StorageClass.INTEGER to Column("INTEGER", ALL_COMPARISONS, { it }, { rs, c -> rs.getLong(c) }),
        // exact as text; text does not order as the number does, nor equate `1.0` with `1.00`
        StorageClass.DECIMAL to Column("TEXT", emptySet(), { (it as BigDecimal).toPlainString() }, { rs, c -> BigDecimal(rs.getString(c)) }),
        StorageClass.DOUBLE to Column("REAL", ALL_COMPARISONS, { it }, { rs, c -> rs.getDouble(c) }),
        StorageClass.BOOLEAN to Column("INTEGER", EQUALITY_ONLY, { if (it as Boolean) 1 else 0 }, { rs, c -> rs.getInt(c) != 0 }),
        // ISO-8601 orders as the date does
        StorageClass.DATE to Column("TEXT", ALL_COMPARISONS, { it.toString() }, { rs, c -> LocalDate.parse(rs.getString(c)) }),
        // ISO-8601 with a varying number of fraction digits: neither orders nor equates as the instant does
        StorageClass.DATETIME to Column("TEXT", emptySet(), { it.toString() }, { rs, c -> Instant.parse(rs.getString(c)) }),
        StorageClass.TIME to Column("TEXT", ALL_COMPARISONS, { it.toString() }, { rs, c -> LocalTime.parse(rs.getString(c)) }),
        // whole seconds: orders as the value does
        StorageClass.DURATION to Column("INTEGER", ALL_COMPARISONS, { (it as Duration).seconds }, { rs, c -> Duration.ofSeconds(rs.getLong(c)) }),
        StorageClass.UUID to Column("TEXT", EQUALITY_ONLY, { it.toString() }, { rs, c -> UUID.fromString(rs.getString(c)) }),
        // compared byte by byte, as the runtime orders bytes
        StorageClass.BYTES to Column("BLOB", ALL_COMPARISONS, { it }, { rs, c -> rs.getBytes(c) }),
    )

    override fun column(storage: StorageClass): StorageColumn = columns.getValue(storage)
}
