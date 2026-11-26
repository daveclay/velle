package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The values the state-flow analyses know at the moment a record is created, and how they compare. */
class BirthValuesTest {

    private val today = BirthValue(BirthValueKind.FROM_TODAY, 0.0)
    private val now = BirthValue(BirthValueKind.FROM_NOW, 0.0)
    private val sevenDays = BirthValues.duration(DurationLit(7, "days"))
    private val twoHours = BirthValues.duration(DurationLit(2, "hours"))

    private fun number(amount: Double) = BirthValue.number(amount)

    @Test
    fun `a day is counted in seconds and a month is not counted`() {
        assertEquals(BirthValue(BirthValueKind.DURATION, 86_400.0), BirthValues.duration(DurationLit(1, "days")))
        assertNull(BirthValues.duration(DurationLit(1, "months")))
    }

    @Test
    fun `today moved by seven days is a week of seconds from today, in either order`() {
        val expected = BirthValue(BirthValueKind.FROM_TODAY, 604_800.0)

        assertEquals(expected, BirthValues.arithmetic("+", today, sevenDays))
        assertEquals(expected, BirthValues.arithmetic("+", sevenDays, today))
        assertEquals(BirthValue(BirthValueKind.FROM_TODAY, -604_800.0), BirthValues.arithmetic("-", today, sevenDays))
    }

    @Test
    fun `today moved by hours is not a date, and now moved by hours is a moment`() {
        assertNull(BirthValues.arithmetic("+", today, twoHours))
        assertEquals(BirthValue(BirthValueKind.FROM_NOW, 7_200.0), BirthValues.arithmetic("+", now, twoHours))
    }

    @Test
    fun `a span less a moment, a moment plus a number, and two moments added are unknown`() {
        assertNull(BirthValues.arithmetic("-", sevenDays, today))
        assertNull(BirthValues.arithmetic("+", today, number(1.0)))
        assertNull(BirthValues.arithmetic("+", today, today))
    }

    @Test
    fun `numbers add, subtract, multiply and divide, and a division by zero is unknown`() {
        assertEquals(number(5.0), BirthValues.arithmetic("+", number(2.0), number(3.0)))
        assertEquals(number(-1.0), BirthValues.arithmetic("-", number(2.0), number(3.0)))
        assertEquals(number(6.0), BirthValues.arithmetic("*", number(2.0), number(3.0)))
        assertEquals(number(2.0), BirthValues.arithmetic("/", number(6.0), number(3.0)))
        assertNull(BirthValues.arithmetic("/", number(6.0), number(0.0)))
    }

    @Test
    fun `a span times a number and an unknown operand are unknown`() {
        assertNull(BirthValues.arithmetic("*", sevenDays, number(2.0)))
        assertNull(BirthValues.arithmetic("+", null, number(2.0)))
        assertNull(BirthValues.arithmetic("%", number(6.0), number(3.0)))
    }

    @Test
    fun `a moment negated is unknown, a span negated is a span`() {
        assertNull(BirthValues.negated(today))
        assertEquals(BirthValue(BirthValueKind.DURATION, -604_800.0), sevenDays?.let(BirthValues::negated))
    }

    @Test
    fun `two equal values compare as equal by every operator`() {
        assertEquals(false, BirthValues.compared("<", today, today))
        assertEquals(true, BirthValues.compared("<=", today, today))
        assertEquals(false, BirthValues.compared(">", today, today))
        assertEquals(true, BirthValues.compared(">=", today, today))
        assertEquals(true, BirthValues.compared("==", today, today))
        assertEquals(false, BirthValues.compared("!=", today, today))
    }

    @Test
    fun `a later moment compares as later`() {
        val nextWeek = BirthValues.arithmetic("+", today, sevenDays)

        assertEquals(true, BirthValues.compared("<", today, nextWeek))
        assertEquals(true, BirthValues.compared(">", nextWeek, today))
        assertEquals(false, BirthValues.compared(">=", today, nextWeek))
        assertEquals(false, BirthValues.compared("<=", nextWeek, today))
    }

    @Test
    fun `values on different clocks or of different kinds do not compare`() {
        assertNull(BirthValues.compared("<", today, now))
        assertNull(BirthValues.compared("<", number(0.0), today))
        assertNull(BirthValues.compared("<", null, today))
        assertNull(BirthValues.compared("like", today, today))
    }
}
