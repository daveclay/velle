package velle.ext

import velle.ext.builtin.BooleanType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Functions and one more type the compiler's own tests register
 * (`META-INF/services/velle.ext.VelleFunction` and `...VelleType` under test resources):
 * - [IsBusinessDay] (`velle.test.isBusinessDay`): a predicate over anything with a calendar date.
 * - [OtherBusinessDay] (`velle.testalt.isBusinessDay`): a second function of the same name, so a
 *   spec that calls it must write a `uses` line.
 * - [Median] (`velle.test.median`): over a `many` of any ordered type, absent when empty.
 * - [RoundTo] (`velle.test.roundTo`): any number to a number of places.
 * - [Clamp] (`velle.test.clamp`): a value of any ordered type between two of the same type.
 * - [TestDay] (`velle.test.Day`): a type with a calendar date and nothing else, so `year(d)`
 *   and `isBusinessDay(d)` reach it through `HasCalendarDate` alone.
 */
class IsBusinessDay : VelleFunction {
    override val name = "isBusinessDay"
    override val namespace = "velle.test"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("date", Accepts.Is(HasCalendarDate::class))),
        result = Returns.Is(BooleanType::class),
        apply = { ctx, args ->
            val d = args[0].capability<HasCalendarDate<Any>>().calendarDate(args[0].value, ctx)
            d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
        },
    ))
}

class OtherBusinessDay : VelleFunction {
    override val name = "isBusinessDay"
    override val namespace = "velle.testalt"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("date", Accepts.Is(HasCalendarDate::class))),
        result = Returns.Is(BooleanType::class),
        apply = { _, _ -> true },
    ))
}

class Median : VelleFunction {
    override val name = "median"
    override val namespace = "velle.test"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("values", Accepts.ManyOf(Ordered::class))),
        result = Returns.ElementOf(0),
        optionalResult = true,
        apply = { _, args ->
            val sorted = args[0].elements.sortedWith(args[0].capability<Ordered<Any>>().ordering)
            if (sorted.isEmpty()) null else sorted[sorted.size / 2]
        },
    ))
}

class RoundTo : VelleFunction {
    override val name = "roundTo"
    override val namespace = "velle.test"
    override val signatures = listOf(Signature(
        parameters = listOf(Parameter("value", Accepts.Is(Numeric::class)), Parameter("places", Accepts.Is(Numeric::class))),
        result = Returns.SameAs(0),
        apply = { _, args ->
            val n = args[0].capability<Numeric<Any>>()
            val places = args[1].capability<Numeric<Any>>().toDecimal(args[1].value).intValueExact()
            n.fromDecimal(n.toDecimal(args[0].value).setScale(places, RoundingMode.HALF_EVEN))
        },
    ))
}

class Clamp : VelleFunction {
    override val name = "clamp"
    override val namespace = "velle.test"
    override val signatures = listOf(Signature(
        parameters = listOf(
            Parameter("value", Accepts.Is(Ordered::class)),
            Parameter("low", Accepts.SameAs(0)),
            Parameter("high", Accepts.SameAs(0)),
        ),
        result = Returns.SameAs(0),
        apply = { _, args ->
            val o = args[0].capability<Ordered<Any>>().ordering
            val (v, lo, hi) = args.map { it.value }
            if (o.compare(v, lo) < 0) lo else if (o.compare(v, hi) > 0) hi else v
        },
    ))
}

data class Day(val date: LocalDate)

class TestDay : VelleType<Day>, HasCalendarDate<Day> {
    override val name = "Day"
    override val namespace = "velle.test"
    override val kotlinClass = Day::class
    override val literals = Literals<Day>(fromText = { t ->
        try { Day(LocalDate.parse(t)) } catch (e: java.time.format.DateTimeParseException) { throw LiteralRejected("'$t' is not a date") }
    })
    override fun canonical(value: Day) = value.date.toString()
    override val operators: List<Operator<*, *, *>> = emptyList()
    override fun calendarDate(v: Day, context: FunctionContext): LocalDate = v.date
    override val samples = Samples(Day(LocalDate.of(2026, 1, 15)), kotlinSource = { "velle.ext.Day(java.time.LocalDate.parse(\"${it.date}\"))" })
}
