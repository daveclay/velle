package velle.ext.builtin

import velle.ext.Accepts
import velle.ext.HasCalendarDate
import velle.ext.HasTimeOfDay
import velle.ext.LiteralRejected
import velle.ext.Ordered
import velle.ext.Parameter
import velle.ext.Returns
import velle.ext.Signature
import velle.ext.Signed
import velle.ext.VelleFunction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.sign
import kotlin.reflect.KClass

/**
 * The builtin functions (Reference §5) as implementations of the same
 * [VelleFunction] an engineer's function implements, in the `velle` namespace
 * and registered ahead of the classpath. Each is written against a capability
 * interface or a builtin type's class, never against a Kotlin class of a value,
 * so an engineer's type that implements the interface is accepted by the same
 * function.
 */
object BuiltinFunctions {
    val all: List<VelleFunction> = listOf(
        Lowercase, Uppercase, Trim, Length, Abs,
        Year, Month, Day, DayOfWeek, Hour, Minute,
        Weeks, Days, Hours, Minutes, Seconds,
        WeeksStarted, DaysStarted, HoursStarted, MinutesStarted,
        Max, Min,
        ReadNumber, ReadDate, ReadDateTime, ReadRegex,
    )
}

sealed class BuiltinFunction(override val name: String) : VelleFunction {
    override val namespace = "velle"
    override fun toString() = name
}

/** One text in, one value out. */
sealed class TextFunction(name: String, result: Returns, body: (String) -> Any) : BuiltinFunction(name) {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("text", Accepts.Is(TextType::class), takes = "text")),
        result = result,
        apply = { _, args -> body(args[0].value as String) },
    ))
}

object Lowercase : TextFunction("lowercase", Returns.Is(TextType::class), { it.lowercase() })
object Uppercase : TextFunction("uppercase", Returns.Is(TextType::class), { it.uppercase() })
object Trim : TextFunction("trim", Returns.Is(TextType::class), { it.trim() })
object Length : TextFunction("length", Returns.Is(IntegerType::class), { BigDecimal(it.length) })

/**
 * A text read as a value of one type, where the spec calls it (Reference §5): absent
 * when the text is not one. A comparison never reads a text; this is the one place a
 * stored text becomes a number or a date. The form read is what the type's Kotlin
 * counterpart reads and nothing else.
 */
sealed class TextReader(name: String, result: KClass<*>, private val read: (String) -> Any) : BuiltinFunction(name) {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("text", Accepts.Is(TextType::class), takes = "text")),
        result = Returns.Is(result),
        optionalResult = true,
        apply = { _, args -> readOrNull(args[0].value as String) },
    ))
    private fun readOrNull(text: String): Any? =
        try { read(text) }
        catch (error: NumberFormatException) { null }
        catch (error: DateTimeParseException) { null }
        catch (error: LiteralRejected) { null }
}

/** `number`: `BigDecimal` of the trimmed text. */
object ReadNumber : TextReader("number", DecimalType::class, { BigDecimal(it.trim()) })
/** `date`: a calendar date, `2026-01-15`. */
object ReadDate : TextReader("date", DateType::class, { LocalDate.parse(it) })
/** `datetime`: a moment with its offset, `2026-01-15T09:00:00Z`; a text with no offset is a local time, not a moment, and is absent. */
object ReadDateTime : TextReader("datetime", DateTimeType::class, { Instant.parse(it) })
/** `regex`: a regular expression in the Java dialect; a text that does not compile is absent. */
object ReadRegex : TextReader("regex", RegexType::class, { RegexValue(it) })

/** `abs`: anything [Signed], returned as what it was. */
object Abs : BuiltinFunction("abs") {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(Signed::class), takes = "a number or a duration")),
        result = Returns.SameAs(0),
        apply = { _, args -> args[0].capability<Signed<Any>>().abs(args[0].value) },
    ))
}

/** A part of the calendar date a value names: a `Date`, a `DateTime` read in the system's zone, or any type with a [HasCalendarDate]. */
sealed class DatePart(name: String, part: (java.time.LocalDate) -> Int) : BuiltinFunction(name) {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(HasCalendarDate::class), takes = "a Date or a DateTime")),
        result = Returns.Is(IntegerType::class),
        apply = { ctx, args -> BigDecimal(part(args[0].capability<HasCalendarDate<Any>>().calendarDate(args[0].value, ctx))) },
    ))
}

object Year : DatePart("year", { it.year })
object Month : DatePart("month", { it.monthValue })
object Day : DatePart("day", { it.dayOfMonth })
/** Monday is 1 and Sunday is 7. */
object DayOfWeek : DatePart("dayOfWeek", { it.dayOfWeek.value })

/** A part of the time of day a value names: a `time`, a `DateTime` read in the system's zone, or any type with a [HasTimeOfDay]. */
sealed class TimePart(name: String, part: (java.time.LocalTime) -> Int) : BuiltinFunction(name) {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(HasTimeOfDay::class), takes = "a time or a DateTime")),
        result = Returns.Is(IntegerType::class),
        apply = { ctx, args -> BigDecimal(part(args[0].capability<HasTimeOfDay<Any>>().timeOfDay(args[0].value, ctx))) },
    ))
}

object Hour : TimePart("hour", { it.hour })
object Minute : TimePart("minute", { it.minute })

/**
 * A span of time read as a count of one unit. A duration holds whole seconds and no
 * calendar unit, so every unit has a fixed length in seconds.
 */
sealed class SpanInUnits(name: String, unitCount: (Duration) -> Long) : BuiltinFunction(name) {
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("span", Accepts.Is(DurationType::class), takes = "a duration")),
        result = Returns.Is(IntegerType::class),
        apply = { _, args -> BigDecimal(unitCount(args[0].value as Duration)) },
    ))
}

/** The whole units in a span, the part of a unit dropped toward zero: `days(36 hours)` is 1 and `days(-36 hours)` is -1. */
private fun wholeUnits(span: Duration, unit: Duration): Long = span.seconds / unit.seconds

/** The units a span has begun, a part of a unit counted as a whole one away from zero: `daysStarted(36 hours)` is 2 and `daysStarted(-36 hours)` is -2. */
private fun unitsBegun(span: Duration, unit: Duration): Long {
    val secondsPastWholeUnits = span.seconds % unit.seconds
    return wholeUnits(span, unit) + secondsPastWholeUnits.sign
}

private val WEEK: Duration = Duration.ofDays(7)
private val DAY: Duration = Duration.ofDays(1)
private val HOUR: Duration = Duration.ofHours(1)
private val MINUTE: Duration = Duration.ofMinutes(1)
private val SECOND: Duration = Duration.ofSeconds(1)

object Weeks : SpanInUnits("weeks", { span -> wholeUnits(span, WEEK) })
object Days : SpanInUnits("days", { span -> wholeUnits(span, DAY) })
object Hours : SpanInUnits("hours", { span -> wholeUnits(span, HOUR) })
object Minutes : SpanInUnits("minutes", { span -> wholeUnits(span, MINUTE) })
object Seconds : SpanInUnits("seconds", { span -> wholeUnits(span, SECOND) })

/** No `secondsStarted`: a duration holds whole seconds, so it would be `seconds`. */
object WeeksStarted : SpanInUnits("weeksStarted", { span -> unitsBegun(span, WEEK) })
object DaysStarted : SpanInUnits("daysStarted", { span -> unitsBegun(span, DAY) })
object HoursStarted : SpanInUnits("hoursStarted", { span -> unitsBegun(span, HOUR) })
object MinutesStarted : SpanInUnits("minutesStarted", { span -> unitsBegun(span, MINUTE) })

/**
 * `max`/`min` over anything [Ordered]: two or more values of one type, or one
 * `many` of values, absent when it is empty. Both shapes are one function, so
 * a rule that writes `x = max(x, y)` folds with the two facts below.
 */
sealed class Extreme(name: String, private val pickFirst: (Int) -> Boolean) : BuiltinFunction(name) {
    @Suppress("UNCHECKED_CAST")
    private fun pick(type: Ordered<Any>, values: List<Any>): Any? =
        values.reduceOrNull { a, b -> if (pickFirst(type.ordering.compare(a, b))) a else b }

    override val signatures = listOf(
        Signature(
            parameters = listOf(
                Parameter("first", Accepts.Is(Ordered::class), takes = "values of one ordered type"),
                Parameter("rest", Accepts.SameAs(0), variadic = true),
            ),
            result = Returns.SameAs(0),
            apply = { _, args -> pick(args[0].capability(), args.map { it.value }) },
        ),
        Signature(
            parameters = listOf(Parameter("values", Accepts.ManyOf(Ordered::class), takes = "a many of ordered values")),
            result = Returns.ElementOf(0),
            optionalResult = true,
            absentOnlyWhenEmpty = true,
            apply = { _, args -> pick(args[0].capability(), args[0].elements) },
        ),
    )
    override val foldStepsCommute = true
    override val foldIdempotent = true
    override val argumentsInAnyOrder = true
    override val groupingDoesNotMatter = true
}

object Max : Extreme("max", { it >= 0 })
object Min : Extreme("min", { it <= 0 })
