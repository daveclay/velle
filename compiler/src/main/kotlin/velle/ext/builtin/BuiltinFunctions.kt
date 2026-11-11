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
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
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
            apply = { _, args -> pick(args[0].capability(), args[0].elements) },
        ),
    )
    override val foldStepsCommute = true
    override val foldIdempotent = true
}

object Max : Extreme("max", { it >= 0 })
object Min : Extreme("min", { it <= 0 })
