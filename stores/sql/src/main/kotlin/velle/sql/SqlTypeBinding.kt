package velle.sql

import java.util.ServiceLoader

/**
 * How a SQL store holds one registered extension type (Reference §5; the wiki
 * guide `Extension-types`, "Holding a type in a store"): the [StorageClass] the
 * column has, the carrier value for a value of the type, the value back from a
 * carrier, and which comparisons the column answers with the type's own meaning.
 *
 * The binding names no database. It speaks in storage classes and carriers, and
 * every [SqlDialect] maps those to its own columns, so one binding serves every
 * SQL store Velle has. Written by whoever knows the type's encoding, as a library
 * of its own: Alice publishes `Money`, Bob publishes the SQL binding for it, Chris
 * uses both over whichever database he runs. The type itself says nothing about
 * storage.
 *
 * Registration: name the implementing class in
 * `META-INF/services/velle.sql.SqlTypeBinding` on the classpath; a store loads
 * every binding it finds, or takes an explicit list. A store built for a model
 * that stores a type it has no binding for refuses to start (E4), and so does one
 * whose binding does not round-trip the type's own samples through the dialect's
 * column.
 */
interface SqlTypeBinding<T : Any> {

    /** The type's qualified name, `namespace.name`: `velle.finance.Money`. */
    val type: String

    /** The kind of column the type is held in. */
    val storage: StorageClass

    /** The carrier of [storage] for [value]: a `Long` for [StorageClass.INTEGER], and so on. */
    fun encode(value: T): Any

    /** The value back from a carrier the column read. */
    fun decode(stored: Any): T

    /**
     * The comparison operators the encoding answers with the value's own meaning:
     * `==` and `!=` for any faithful encoding; `<`, `<=`, `>`, `>=` only when the
     * carrier orders as the type orders (an integer of cents does, a text of a
     * decimal does not). A comparison not listed here is left to the evaluator
     * under the superset contract, never answered wrongly. The dialect's own
     * column may narrow this further.
     */
    val answers: Set<String>

    @Suppress("UNCHECKED_CAST")
    fun encodeAny(value: Any): Any = encode(value as T)

    companion object {
        /** Every binding on the classpath. */
        fun load(loader: ClassLoader = SqlTypeBinding::class.java.classLoader): List<SqlTypeBinding<*>> =
            ServiceLoader.load(SqlTypeBinding::class.java, loader).toList()
    }
}
