package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Runtime values. All numerics normalize to BigDecimal — a spike simplification. */
sealed interface Value {
    data class VNum(val v: BigDecimal) : Value
    data class VText(val v: String) : Value
    data class VBool(val v: Boolean) : Value
    data class VDate(val v: LocalDate) : Value
    data class VDateTime(val v: Instant) : Value
    data class VRef(val id: Long) : Value
    data class VColl(val ids: List<Long>, val shape: String) : Value
    /** An owned collection of scalar values (`many text`, Reference §5, "Collections of
     *  values"): each value once, or, when [ordered] (`tolerates duplication`), kept as
     *  given, duplicates and order included. The kind comes from the field's declared type. */
    data class VVals(val values: List<Value>, val ordered: Boolean = false) : Value
    /** The `empty` literal before its collection kind is known; coerced at the write site. */
    data object VEmpty : Value
    /** A closure part's back-reference before its target exists: index into the
     *  commit's creation list, resolved to a VRef as creations land (Reference §6,
     *  "Inline part creation"). Never escapes the commit ([CommitProcessor.apply]). */
    data class VPendingRef(val index: Int) : Value
    /** A `many` field some of whose elements are records this commit creates
     *  ([VPendingRef] among [VRef]s); resolved to a [VColl] as creations land. */
    data class VPendingColl(val items: List<Value>, val shape: String) : Value
    /** A span of time (Reference §5, `duration`): the literal's amount and unit, or a
     *  stored value read back in the largest unit that divides it evenly. */
    data class VDuration(val amount: Long, val unit: String) : Value {
        fun seconds(): Long = amount * unitSeconds(unit)
        fun toJava(): java.time.Duration = java.time.Duration.ofSeconds(seconds())
        companion object {
            /** [javaDuration] with any part of a second dropped toward zero: a span a store hands back, or the span between two moments. */
            fun ofJava(javaDuration: java.time.Duration): VDuration =
                ofSeconds(javaDuration.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).seconds)
            /** [javaDuration] when it is whole seconds, the only span a `duration` holds; null when it has a part of a second. */
            fun ofWholeSeconds(javaDuration: java.time.Duration): VDuration? =
                if (javaDuration.nano == 0) ofSeconds(javaDuration.seconds) else null
            /** Why [javaDuration], which has a part of a second, cannot be given to [fieldName]. */
            fun partOfASecond(fieldName: String, javaDuration: java.time.Duration): String =
                "'$fieldName' holds whole seconds; $javaDuration has a part of a second"
            fun ofSeconds(totalSeconds: Long): VDuration {
                for ((unit, secondsPerUnit) in UNITS) {
                    if (totalSeconds != 0L && totalSeconds % secondsPerUnit == 0L) return VDuration(totalSeconds / secondsPerUnit, unit)
                }
                return VDuration(totalSeconds, "seconds")
            }
            fun unitSeconds(unit: String): Long = UNITS.firstOrNull { (name, _) -> name == unit }?.second
                ?: throw VelleRuntimeError("unknown duration unit '$unit'")
            private val UNITS = listOf("weeks" to 604_800L, "days" to 86_400L, "hours" to 3_600L, "minutes" to 60L, "seconds" to 1L)
        }
    }
    /** A `uuid` value (Reference §5). */
    data class VUuid(val v: UUID) : Value
    /** A `time` value (Reference §5): a time of day. */
    data class VTime(val v: java.time.LocalTime) : Value
    /** A `bytes` value (Reference §5): equal by content, ordered byte by byte as SQLite orders a BLOB. */
    class VBytes(val v: ByteArray) : Value {
        override fun equals(other: Any?) = other is VBytes && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
        override fun toString() = "VBytes(${v.size} bytes)"
        fun compareTo(other: VBytes): Int {
            for (index in 0 until minOf(v.size, other.v.size)) {
                val comparison = unsigned(v[index]).compareTo(unsigned(other.v[index]))
                if (comparison != 0) return comparison
            }
            return v.size.compareTo(other.v.size)
        }
        private fun unsigned(byte: Byte): Int = byte.toInt() and 0xff
    }
    /** A value of a registered extension type (Reference §5; OQ65): the type's own Kotlin object. */
    data class VExt(val type: velle.ext.VelleType<*>, val v: Any) : Value {
        override fun equals(other: Any?): Boolean = other is VExt && other.type === type && type.equalAny(v, other.v)
        override fun hashCode(): Int = type.canonicalAny(v).hashCode()
        override fun toString(): String = "VExt(${type.name} ${type.canonicalAny(v)})"
    }
    data object VNone : Value

    companion object {
        fun num(number: Any): Value = VNum(
            when (number) {
                is BigDecimal -> number
                is Int -> BigDecimal(number)
                is Long -> BigDecimal(number)
                is Double -> BigDecimal.valueOf(number)
                else -> throw IllegalArgumentException("not numeric: $number")
            }
        )
    }
}

/** One record in the working set: its session handle, its shape, and its field values. */
class Instance(
    val id: Long,
    val shape: String,
    val seq: Long,
    val fields: MutableMap<String, Value>,
)

/** The handles a value refers to: the one record of a reference, the members of a collection, none otherwise. */
internal fun referencedHandles(value: Value?): List<Long> = when (value) {
    is Value.VRef -> listOf(value.id)
    is Value.VColl -> value.ids
    else -> emptyList()
}

/** A value as the engineer's Kotlin reads it: the scalar itself, a handle for a reference, a list for a collection. */
internal fun unwrapped(value: Value): Any? = when (value) {
    is Value.VNum -> value.v
    is Value.VText -> value.v
    is Value.VBool -> value.v
    is Value.VDate -> value.v
    is Value.VDateTime -> value.v
    is Value.VRef -> value.id
    is Value.VColl -> value.ids
    is Value.VVals -> value.values.map { element -> unwrapped(element) }
    Value.VEmpty -> emptyList<Any?>()
    is Value.VDuration -> value.toJava()
    is Value.VUuid -> value.v
    is Value.VTime -> value.v
    is Value.VBytes -> value.v
    is Value.VExt -> value.v
    is Value.VPendingRef, is Value.VPendingColl -> throw VelleRuntimeError("pending closure reference escaped its commit")
    Value.VNone -> null
}
