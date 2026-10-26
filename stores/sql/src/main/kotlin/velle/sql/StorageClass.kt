package velle.sql

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.reflect.KClass

/**
 * The kinds of column the SQL layer knows how to hold, named without reference
 * to any one database. A [SqlDialect] maps each to its own column type and
 * JDBC calls; a [SqlTypeBinding] picks one to hold an extension type in.
 *
 * Each class has one [carrier]: the Kotlin value a binding hands the layer for
 * a column of that class, and the value it gets back. Velle's builtin scalars
 * (Reference §5) each have a class of their own, with the value itself as the
 * carrier, so a builtin and an extension type travel through one path.
 */
enum class StorageClass(val carrier: KClass<*>) {
    /** Text of any length: `String`. */
    TEXT(String::class),
    /** A 64-bit integer: `Long`. */
    INTEGER(Long::class),
    /** An exact decimal: `BigDecimal`. */
    DECIMAL(BigDecimal::class),
    /** A floating-point number: `Double`. */
    DOUBLE(Double::class),
    /** `Boolean`. */
    BOOLEAN(Boolean::class),
    /** A calendar date: `java.time.LocalDate`. */
    DATE(LocalDate::class),
    /** A point in time: `java.time.Instant`. */
    DATETIME(Instant::class),
    /** A time of day: `java.time.LocalTime`. */
    TIME(LocalTime::class),
    /** A span of time: `java.time.Duration`. */
    DURATION(Duration::class),
    /** `java.util.UUID`. */
    UUID(java.util.UUID::class),
    /** Opaque binary data: `ByteArray`. */
    BYTES(ByteArray::class),
}

/** The comparison operators a column may answer (Reference §10). */
val ALL_COMPARISONS: Set<String> = setOf("==", "!=", "<", "<=", ">", ">=")

/** Equality and inequality only: for a column that equates faithfully but does not order as the value does. */
val EQUALITY_ONLY: Set<String> = setOf("==", "!=")
