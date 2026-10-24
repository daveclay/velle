package velle.sqlite

import java.sql.ResultSet
import java.util.ServiceLoader

/**
 * How [SqliteStore] holds one registered extension type (Reference §5; OQ65 §2.5):
 * the column type, the value handed to JDBC, the read back, and which comparisons
 * the column answers faithfully for the pre-filter. Written by whoever knows the
 * store, as a library of its own: Alice publishes `Money`, Bob publishes the
 * SQLite binding for it, Chris uses both. The type itself says nothing about
 * storage.
 *
 * Registration: name the implementing class in
 * `META-INF/services/velle.sqlite.SqliteTypeBinding` on the classpath; the store
 * loads every binding it finds, or takes an explicit list. A store built for a
 * model that stores a type it has no binding for refuses to start (E4), and so
 * does one whose binding does not round-trip the type's own samples.
 */
interface SqliteTypeBinding<T : Any> {

    /** The type's qualified name, `namespace.name`: `velle.finance.Money`. */
    val type: String

    /** The SQLite column type: `TEXT`, `INTEGER`, or `REAL`. */
    val columnType: String

    /** The JDBC value for [value], as `PreparedStatement.setObject` takes it. */
    fun bind(value: T): Any

    /** The value back from [column] of the current row. */
    fun read(rs: ResultSet, column: String): T

    /**
     * The comparison operators the column's encoding answers with the value's own
     * meaning: `==` and `!=` for any faithful encoding; `<`, `<=`, `>`, `>=` only
     * when the column orders as the type orders (an INTEGER of cents does, a TEXT
     * of a decimal does not). A comparison not listed here is left to the
     * evaluator under the superset contract, never answered wrongly.
     */
    val answers: Set<String>

    @Suppress("UNCHECKED_CAST")
    fun bindAny(value: Any): Any = bind(value as T)

    companion object {
        /** Every binding on the classpath. */
        fun load(loader: ClassLoader = SqliteTypeBinding::class.java.classLoader): List<SqliteTypeBinding<*>> =
            ServiceLoader.load(SqliteTypeBinding::class.java, loader).toList()
    }
}
