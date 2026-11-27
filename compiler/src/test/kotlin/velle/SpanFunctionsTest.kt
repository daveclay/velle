package velle

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The functions of Reference §5 that read a span of time as a whole number of
 * one unit: `days`, `weeks`, `hours`, `minutes`, and `seconds`. The part of a
 * unit is dropped toward zero, so `days(36 hours)` is 1 and `days(-36 hours)`
 * is -1.
 */
class SpanFunctionsTest {

    private val spec = """
        act Span {
            span: duration
            notedSpan: duration?
            inDays: integer = days(span)
            inWeeks: integer = weeks(span)
            inHours: integer = hours(span)
            inMinutes: integer = minutes(span)
            inSeconds: integer = seconds(span)
            notedDays: integer? = days(notedSpan)
        }
        act Return {
            dueOn: Date
            returnedOn: Date
            daysLate: integer = days(returnedOn - dueOn)
            lateFee: decimal = 2 * daysLate
        }
        act Shift {
            startedAt: DateTime
            endedAt: DateTime
            hoursWorked: integer = hours(endedAt - startedAt)
        }
    """.trimIndent()

    private fun system(): VelleSystem {
        val declarations = Parser.parse(spec)
        val diagnostics = Validator.validate(declarations)
        check(diagnostics.isEmpty()) { diagnostics.toString() }
        return VelleSystem(Model(declarations))
    }

    private fun VelleSystem.span(span: Duration, notedSpan: Duration? = null): Long {
        val fields = mutableMapOf<String, Any?>("span" to span)
        if (notedSpan != null) fields["notedSpan"] = notedSpan
        return assertIs<CommitResult.Accepted>(commit("Span", fields), "refused").id
    }

    private fun readOf(span: Duration, property: String): Any? {
        val sys = system()
        return sys.scope { sys.get(sys.span(span), property) }
    }

    @Test
    fun `days drops the part of a day toward zero`() {
        assertEquals(BigDecimal(1), readOf(Duration.ofHours(36), "inDays"))
        assertEquals(BigDecimal(-1), readOf(Duration.ofHours(-36), "inDays"))
    }

    @Test
    fun `weeks drops the part of a week toward zero`() {
        assertEquals(BigDecimal(1), readOf(Duration.ofDays(10), "inWeeks"))
        assertEquals(BigDecimal(-1), readOf(Duration.ofDays(-10), "inWeeks"))
    }

    @Test
    fun `hours drops the part of an hour toward zero`() {
        assertEquals(BigDecimal(1), readOf(Duration.ofMinutes(90), "inHours"))
        assertEquals(BigDecimal(-1), readOf(Duration.ofMinutes(-90), "inHours"))
    }

    @Test
    fun `minutes drops the part of a minute toward zero`() {
        assertEquals(BigDecimal(1), readOf(Duration.ofSeconds(90), "inMinutes"))
        assertEquals(BigDecimal(-1), readOf(Duration.ofSeconds(-90), "inMinutes"))
    }

    @Test
    fun `seconds reads a span in whole seconds, the smallest unit a duration holds`() {
        assertEquals(BigDecimal(5400), readOf(Duration.ofMinutes(90), "inSeconds"))
        assertEquals(BigDecimal(-5400), readOf(Duration.ofMinutes(-90), "inSeconds"))
    }

    @Test
    fun `an absent span gives an absent number`() {
        val sys = system()
        sys.scope {
            assertNull(sys.get(sys.span(Duration.ofDays(1)), "notedDays"))
            assertEquals(BigDecimal(3), sys.get(sys.span(Duration.ofDays(1), notedSpan = Duration.ofDays(3)), "notedDays"))
        }
    }

    @Test
    fun `a late fee is 2 for each day between the due date and the return`() {
        val sys = system()
        sys.scope {
            val fields = mapOf("dueOn" to LocalDate.of(2026, 1, 10), "returnedOn" to LocalDate.of(2026, 1, 14))
            val returned = assertIs<CommitResult.Accepted>(sys.commit("Return", fields), "refused").id
            assertEquals(BigDecimal(4), sys.get(returned, "daysLate"))
            assertEquals(0, BigDecimal(8).compareTo(sys.get(returned, "lateFee") as BigDecimal))
        }
    }

    @Test
    fun `hours of two moments subtracted drops the part of an hour`() {
        val sys = system()
        sys.scope {
            val fields = mapOf(
                "startedAt" to Instant.parse("2026-01-03T09:00:00Z"),
                "endedAt" to Instant.parse("2026-01-03T17:45:00Z"),
            )
            val shift = assertIs<CommitResult.Accepted>(sys.commit("Shift", fields), "refused").id
            assertEquals(BigDecimal(8), sys.get(shift, "hoursWorked"))
        }
    }

    private fun diagnosticsOf(property: String): List<Diagnostic> = Validator.validate(Parser.parse(
        """
        act Return {
            dueOn: Date
            returnedOn: Date
            $property
        }
        """.trimIndent()
    ))

    @Test
    fun `days takes a duration and refuses a number or a date`() {
        val number = diagnosticsOf("daysLate: integer = days(3)")
        assertEquals(listOf("[F2] 'days(3)' — 'days' takes a duration, but '3' is a number"), number.map { it.toString() })
        val date = diagnosticsOf("daysLate: integer = days(dueOn)")
        assertEquals(listOf("[F2] 'days(dueOn)' — 'days' takes a duration, but 'dueOn' is a Date"), date.map { it.toString() })
    }

    @Test
    fun `a duration multiplied by a number is told to read it with days`() {
        val durationOnTheRight = diagnosticsOf("lateFee: decimal = 2 * (returnedOn - dueOn)")
        assertEquals(
            listOf("[F2] '2 * (returnedOn - dueOn)' — '*' is not defined on a duration; " +
                "read it as a number first, 'days(returnedOn - dueOn)' (Reference §5)"),
            durationOnTheRight.map { it.toString() },
        )
        val durationOnTheLeft = diagnosticsOf("lateFee: decimal = (returnedOn - dueOn) * 2")
        assertEquals(
            listOf("[F2] '(returnedOn - dueOn) * 2' — '*' is not defined on a duration; " +
                "read it as a number first, 'days(returnedOn - dueOn)' (Reference §5)"),
            durationOnTheLeft.map { it.toString() },
        )
    }

    @Test
    fun `a duration divided is told to read it with days`() {
        val diagnostics = diagnosticsOf("daysLate: integer = (returnedOn - dueOn) / 1 days")
        assertEquals(
            listOf("[F2] '(returnedOn - dueOn) / 1 days' — '/' is not defined on a duration; " +
                "read it as a number first, 'days(returnedOn - dueOn)' (Reference §5)"),
            diagnostics.map { it.toString() },
        )
    }

    @Test
    fun `a duration in a remainder keeps the remainder message`() {
        val diagnostics = diagnosticsOf("daysLeft: integer = (returnedOn - dueOn) % 7")
        assertTrue(
            diagnostics.any { it.toString() == "[F2] '(returnedOn - dueOn) % 7' — '%' takes numbers, but 'returnedOn - dueOn' is not a number" },
            diagnostics.toString(),
        )
    }

    @Test
    fun `a number computed as a duration is told to read it with days`() {
        val diagnostics = diagnosticsOf("daysLate: integer = returnedOn - dueOn")
        assertEquals(
            listOf("[F2] 'Return.daysLate' is declared an integer and computed as a duration; declare it 'daysLate: duration', " +
                "or compute an integer, such as 'days(returnedOn - dueOn)' (Reference §5)"),
            diagnostics.map { it.toString() },
        )
    }
}
