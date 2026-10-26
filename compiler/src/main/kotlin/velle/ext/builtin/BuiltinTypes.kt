package velle.ext.builtin

import velle.ext.FunctionContext
import velle.ext.HasCalendarDate
import velle.ext.HasTimeOfDay
import velle.ext.LiteralRejected
import velle.ext.Literals
import velle.ext.Numeric
import velle.ext.Operator
import velle.ext.Ordered
import velle.ext.Samples
import velle.ext.Signed
import velle.ext.Summable
import velle.ext.VelleType
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The builtin scalar types (Reference §5) as implementations of the same
 * [VelleType] an engineer's type implements, in the `velle` namespace and
 * registered ahead of the classpath. They are the reference implementation of
 * every capability interface: what `Ordered` means is what `decimal` does with
 * it. A function over a capability (`max`, `year`) reaches a builtin and an
 * engineer's type through the one interface.
 *
 * The value class of each is the runtime's representation: every number is a
 * `BigDecimal`, a `DateTime` is an `Instant`, a duration is `java.time.Duration`.
 * The arithmetic, the literal forms `true`/`false` and `3 days`, and the
 * generated surface's classes stay in the language for now; nothing here
 * changes what a spec writes.
 */
object BuiltinTypes {
    val all: List<VelleType<*>> = listOf(
        TextType, IntegerType, LongType, DecimalType, DoubleType, BooleanType,
        DateType, DateTimeType, UuidType, DurationType, TimeType, BytesType,
    )
    val byName: Map<String, VelleType<*>> = all.associateBy { it.name }
}

/** A builtin: lowercase keyword names, the `velle` namespace, no operator lines yet. */
sealed class BuiltinType<T : Any>(override val name: String) : VelleType<T> {
    override val namespace = "velle"
    override val operators: List<Operator<*, *, *>> get() = emptyList()
    override fun toString() = name
}

object TextType : BuiltinType<String>("text"), Ordered<String> {
    override val kotlinClass = String::class
    override val literals = Literals<String>(fromText = { it })
    override fun canonical(value: String) = value
    override val ordering: Comparator<String> = Comparator { a, b -> a.compareTo(b) }
    override val samples = Samples("sample", kotlinSource = { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
}

/** The four number types share one representation; `integer` and `long` refuse a fractional constant. */
sealed class NumberType(name: String, private val whole: Boolean) : BuiltinType<BigDecimal>(name),
    Ordered<BigDecimal>, Signed<BigDecimal>, Summable<BigDecimal>, Numeric<BigDecimal> {
    override fun toDecimal(v: BigDecimal): BigDecimal = v
    override fun fromDecimal(d: BigDecimal): BigDecimal = d
    override val kotlinClass = BigDecimal::class
    override val literals = Literals<BigDecimal>(fromNumber = { n ->
        if (whole && n.stripTrailingZeros().scale() > 0) throw LiteralRejected("$n is not a whole number")
        n
    })
    override fun equal(a: BigDecimal, b: BigDecimal) = a.compareTo(b) == 0
    override fun canonical(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    override val ordering: Comparator<BigDecimal> = Comparator { a, b -> a.compareTo(b) }
    override fun abs(v: BigDecimal): BigDecimal = v.abs()
    override val zero: BigDecimal = BigDecimal.ZERO
    override fun plus(a: BigDecimal, b: BigDecimal): BigDecimal = a.add(b)
    override val samples = Samples(
        sample = BigDecimal("2"),
        below = { it.subtract(BigDecimal.ONE) },
        above = { it.add(BigDecimal.ONE) },
        kotlinSource = { "java.math.BigDecimal(\"${it.toPlainString()}\")" },
    )
}

object IntegerType : NumberType("integer", whole = true)
object LongType : NumberType("long", whole = true)
object DecimalType : NumberType("decimal", whole = false)
object DoubleType : NumberType("double", whole = false)

object BooleanType : BuiltinType<Boolean>("boolean") {
    override val kotlinClass = Boolean::class
    override val literals = Literals.none<Boolean>()
    override fun canonical(value: Boolean) = value.toString()
    override val samples = Samples(false, kotlinSource = { it.toString() })
}

object DateType : BuiltinType<LocalDate>("Date"), Ordered<LocalDate>, HasCalendarDate<LocalDate> {
    override val kotlinClass = LocalDate::class
    override val literals = Literals.none<LocalDate>()
    override fun canonical(value: LocalDate) = value.toString()
    override val ordering: Comparator<LocalDate> = Comparator { a, b -> a.compareTo(b) }
    override fun calendarDate(v: LocalDate, context: FunctionContext): LocalDate = v
    override val samples = Samples(
        sample = LocalDate.of(2026, 1, 15),
        below = { it.minusDays(1) },
        above = { it.plusDays(1) },
        kotlinSource = { "java.time.LocalDate.parse(\"$it\")" },
    )
}

object DateTimeType : BuiltinType<Instant>("DateTime"), Ordered<Instant>, HasCalendarDate<Instant>, HasTimeOfDay<Instant> {
    override val kotlinClass = Instant::class
    override val literals = Literals.none<Instant>()
    override fun canonical(value: Instant) = value.toString()
    override val ordering: Comparator<Instant> = Comparator { a, b -> a.compareTo(b) }
    /** A moment's date and time are read in the system's zone, the one `today` uses. */
    override fun calendarDate(v: Instant, context: FunctionContext): LocalDate = v.atZone(context.zone).toLocalDate()
    override fun timeOfDay(v: Instant, context: FunctionContext): LocalTime = v.atZone(context.zone).toLocalTime()
    override val samples = Samples(
        sample = Instant.parse("2026-01-15T00:00:00Z"),
        below = { it.minusSeconds(1) },
        above = { it.plusSeconds(1) },
        kotlinSource = { "java.time.Instant.parse(\"$it\")" },
    )
}

object UuidType : BuiltinType<UUID>("uuid") {
    override val kotlinClass = UUID::class
    override val literals = Literals<UUID>(fromText = { t ->
        try { UUID.fromString(t) } catch (e: IllegalArgumentException) { throw LiteralRejected("'$t' is not a UUID") }
    })
    override fun canonical(value: UUID) = value.toString()
    override val samples = Samples(UUID.fromString("00000000-0000-4000-8000-000000000001"), kotlinSource = { "java.util.UUID.fromString(\"$it\")" })
}

object DurationType : BuiltinType<Duration>("duration"), Ordered<Duration>, Signed<Duration>, Summable<Duration> {
    override val kotlinClass = Duration::class
    override val literals = Literals.none<Duration>()
    override fun canonical(value: Duration) = value.toString()
    override val ordering: Comparator<Duration> = Comparator { a, b -> a.compareTo(b) }
    override fun abs(v: Duration): Duration = v.abs()
    override val zero: Duration = Duration.ZERO
    override fun plus(a: Duration, b: Duration): Duration = a.plus(b)
    override val samples = Samples(
        sample = Duration.ofDays(1),
        below = { it.minusSeconds(1) },
        above = { it.plusSeconds(1) },
        kotlinSource = { "java.time.Duration.parse(\"$it\")" },
    )
}

object TimeType : BuiltinType<LocalTime>("time"), Ordered<LocalTime>, HasTimeOfDay<LocalTime> {
    override val kotlinClass = LocalTime::class
    override val literals = Literals<LocalTime>(fromText = { t ->
        try { LocalTime.parse(t) } catch (e: java.time.format.DateTimeParseException) { throw LiteralRejected("'$t' is not a time of day such as \"09:30\"") }
    })
    override fun canonical(value: LocalTime) = value.toString()
    override val ordering: Comparator<LocalTime> = Comparator { a, b -> a.compareTo(b) }
    override fun timeOfDay(v: LocalTime, context: FunctionContext): LocalTime = v
    override val samples = Samples(
        sample = LocalTime.of(9, 30),
        below = { if (it == LocalTime.MIN) null else it.minusMinutes(1) },
        above = { if (it >= LocalTime.of(23, 59)) null else it.plusMinutes(1) },
        kotlinSource = { "java.time.LocalTime.parse(\"$it\")" },
    )
}

object BytesType : BuiltinType<ByteArray>("bytes"), Ordered<ByteArray> {
    override val kotlinClass = ByteArray::class
    override val literals = Literals.none<ByteArray>()
    override fun equal(a: ByteArray, b: ByteArray) = a.contentEquals(b)
    override fun canonical(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
    /** Byte by byte, unsigned, the shorter first when one is a prefix of the other. */
    override val ordering: Comparator<ByteArray> = Comparator { a, b ->
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
            if (c != 0) return@Comparator c
        }
        a.size.compareTo(b.size)
    }
    override val samples = Samples(byteArrayOf(1, 2), kotlinSource = { "byteArrayOf(${it.joinToString { b -> b.toString() }})" })
}
