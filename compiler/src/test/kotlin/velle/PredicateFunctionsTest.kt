package velle

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The predicate forms of Reference §10 beyond the comparisons: `in` over a listed
 * set of values, `matches` with a regular expression, `is empty` on text, `between`;
 * and the functions of Reference §5 that feed them: the parts of a date, a moment,
 * and a time, `abs`, `%`, and `sum`/`max`/`min` over a `many` of values.
 */
class PredicateFunctionsTest {

    private val spec = """
        act Order {
            status: text
            email: text
            note: text?
            qty: integer
            placed: Date
            placedAt: DateTime
            pickup: time?
            lead: duration
            scores: many integer
            waits: many duration
            parity: integer = qty % 2
            total: integer = sum(scores)
            best: integer? = max(scores)
            worst: integer? = min(scores)
            waited: duration = sum(waits)
            slack: duration = abs(lead - 3 days)
            drift: integer = abs(qty - 10)
        }
        shape Live = Order where status in ["open", "pending"]
        shape Closed = Order where status not in ["open", "pending"]
        shape Scored = Order where qty in scores
        shape Mailable = Order where email matches "[^@ ]+@[^@ ]+\\.[a-z]+"
        shape Unnoted = Order where note is empty
        shape Noted = Order where note is not empty
        shape Mid = Order where qty between 2 and 5
        shape Weekend = Order where dayOfWeek(placed) >= 6
        shape January2026 = Order where year(placed) == 2026 and month(placed) == 1 and day(placed) < 10
        shape MorningPickup = Order where hour(pickup) < 12
        shape LatePlacement = Order where hour(placedAt) >= 22 or minute(placedAt) == 30
        shape Even = Order where qty % 2 == 0
    """.trimIndent()

    private fun model(): Model {
        val decls = Parser.parse(spec)
        val diags = Validator.validate(decls)
        check(diags.isEmpty()) { diags.toString() }
        return Model(decls)
    }

    private fun VelleSystem.order(
        status: String = "open", email: String = "ada@example.com", note: String? = null, qty: Int = 3,
        placed: LocalDate = LocalDate.of(2026, 1, 3), placedAt: Instant = Instant.parse("2026-01-03T22:30:00Z"),
        pickup: LocalTime? = null, lead: Duration = Duration.ofDays(1),
        scores: List<Int> = emptyList(), waits: List<Duration> = emptyList(),
    ): Long {
        val fields = mutableMapOf<String, Any?>(
            "status" to status, "email" to email, "qty" to qty, "placed" to placed, "placedAt" to placedAt,
            "lead" to lead, "scores" to scores, "waits" to waits,
        )
        if (note != null) fields["note"] = note
        if (pickup != null) fields["pickup"] = pickup
        return assertIs<CommitResult.Accepted>(commit("Order", fields), "refused").id
    }

    private fun diagsOf(extra: String): List<Diagnostic> = Validator.validate(
        """
        act Order {
            status: text
            qty: integer
            placed: Date
            lead: duration
            flags: many boolean
            scores: many integer
            lines: many Line
        }
        act Line { amount: integer }
        """.trimIndent() + "\n" + extra
    )

    @Test
    fun `in tests membership of a listed set or a many of values, and not in is its negation`() {
        val sys = VelleSystem(model())
        val open = sys.order(status = "open")
        val done = sys.order(status = "done")
        assertTrue(sys.isMember(open, "Live"))
        assertTrue(!sys.isMember(done, "Live"))
        assertTrue(sys.isMember(done, "Closed"))
        assertTrue(!sys.isMember(open, "Closed"))
        val scored = sys.order(qty = 7, scores = listOf(5, 7))
        assertTrue(sys.isMember(scored, "Scored"))
        assertTrue(!sys.isMember(open, "Scored"))
        assertEquals("status in [\"open\", \"pending\"]", Printer.expr((model().refinements.getValue("Live").expr as RefName).where!!))
    }

    @Test
    fun `in lists values of the left side's type and sits only on the right of in`() {
        val wrong = diagsOf("shape Odd = Order where status in [1, 2]")
        assertEquals(2, wrong.size, wrong.toString())
        assertTrue(wrong.all { "is not a text" in it.message }, wrong.toString())
        val stray = diagsOf("shape Odd = Order where status == [\"a\"]")
        assertTrue(stray.any { "only on the right of `in`" in it.message }, stray.toString())
        val records = diagsOf("shape Odd = Order where qty in lines")
        assertTrue(records.any { "'in' takes a list of values" in it.message }, records.toString())
        val bools = diagsOf("shape Odd = Order where qty in flags")
        assertTrue(bools.any { "holds 'boolean' values" in it.message }, bools.toString())
    }

    @Test
    fun `matches takes a regular expression the whole text must match`() {
        val sys = VelleSystem(model())
        val ok = sys.order(email = "ada@example.com")
        val partial = sys.order(email = "see ada@example.com now")
        val bad = sys.order(email = "ada-at-example")
        assertTrue(sys.isMember(ok, "Mailable"))
        assertTrue(!sys.isMember(partial, "Mailable"), "the whole text matches, not a part of it")
        assertTrue(!sys.isMember(bad, "Mailable"))
        val malformed = diagsOf("shape Odd = Order where status matches \"[a-\"")
        assertEquals(1, malformed.size, malformed.toString())
        assertTrue("not a regular expression" in malformed.single().message, malformed.single().message)
        val field = diagsOf("shape Odd = Order where status matches status")
        assertTrue(field.any { "quoted regular expression" in it.message }, field.toString())
    }

    @Test
    fun `is empty on text is absent or whitespace only`() {
        val sys = VelleSystem(model())
        val none = sys.order()
        val blank = sys.order(note = "   ")
        val noted = sys.order(note = " call first ")
        assertTrue(sys.isMember(none, "Unnoted"))
        assertTrue(sys.isMember(blank, "Unnoted"))
        assertTrue(!sys.isMember(noted, "Unnoted"))
        assertTrue(sys.isMember(noted, "Noted"))
        assertTrue(!sys.isMember(blank, "Noted"))
        val number = diagsOf("shape Odd = Order where qty is empty")
        assertEquals(1, number.size, number.toString())
        assertTrue("tests a collection or a text" in number.single().message, number.single().message)
    }

    @Test
    fun `between includes both bounds`() {
        val sys = VelleSystem(model())
        assertTrue(sys.isMember(sys.order(qty = 2), "Mid"))
        assertTrue(sys.isMember(sys.order(qty = 5), "Mid"))
        assertTrue(!sys.isMember(sys.order(qty = 6), "Mid"))
        assertTrue(!sys.isMember(sys.order(qty = 1), "Mid"))
    }

    @Test
    fun `the parts of a date, a moment, and a time read as whole numbers`() {
        val sys = VelleSystem(model())
        val saturday = sys.order(placed = LocalDate.of(2026, 1, 3))
        val monday = sys.order(placed = LocalDate.of(2026, 1, 5))
        assertTrue(sys.isMember(saturday, "Weekend"))
        assertTrue(!sys.isMember(monday, "Weekend"))
        assertTrue(sys.isMember(saturday, "January2026"))
        assertTrue(!sys.isMember(sys.order(placed = LocalDate.of(2026, 1, 15)), "January2026"))
        val early = sys.order(pickup = LocalTime.of(9, 30))
        val late = sys.order(pickup = LocalTime.of(14, 0))
        assertTrue(sys.isMember(early, "MorningPickup"))
        assertTrue(!sys.isMember(late, "MorningPickup"))
        assertTrue(!sys.isMember(sys.order(), "MorningPickup"), "an absent time has no hour")
        // a moment's parts are read in the system's zone (UTC here)
        assertTrue(sys.isMember(sys.order(placedAt = Instant.parse("2026-01-03T22:05:00Z")), "LatePlacement"))
        assertTrue(sys.isMember(sys.order(placedAt = Instant.parse("2026-01-03T08:30:00Z")), "LatePlacement"))
        assertTrue(!sys.isMember(sys.order(placedAt = Instant.parse("2026-01-03T08:05:00Z")), "LatePlacement"))
        val wrong = diagsOf("shape Odd = Order where hour(placed) > 3")
        assertEquals(1, wrong.size, wrong.toString())
        assertTrue("takes a time or a DateTime" in wrong.single().message, wrong.single().message)
        val text = diagsOf("shape Odd = Order where year(status) > 3")
        assertTrue(text.any { "takes a Date or a DateTime" in it.message }, text.toString())
    }

    @Test
    fun `abs and the remainder`() {
        val sys = VelleSystem(model())
        val o = sys.order(qty = 7, lead = Duration.ofDays(1))
        assertEquals(java.math.BigDecimal(1), sys.get(o, "parity"))
        assertEquals(java.math.BigDecimal(3), sys.get(o, "drift"))
        assertEquals(Duration.ofDays(2), sys.get(o, "slack"))
        assertTrue(!sys.isMember(o, "Even"))
        assertTrue(sys.isMember(sys.order(qty = 8), "Even"))
        assertEquals(Duration.ofDays(2), sys.get(sys.order(lead = Duration.ofDays(5)), "slack"))
        val text = diagsOf("shape Odd = Order where status % 2 == 0")
        assertTrue(text.any { "'%' takes numbers" in it.message }, text.toString())
        val abs = diagsOf("shape Odd = Order where abs(status) == 0")
        assertTrue(abs.any { "takes a number or a duration" in it.message }, abs.toString())
    }

    @Test
    fun `sum, max, and min read a many of values`() {
        val sys = VelleSystem(model())
        val none = sys.order()
        assertEquals(java.math.BigDecimal(0), sys.get(none, "total"))
        assertNull(sys.get(none, "best"))
        assertNull(sys.get(none, "worst"))
        assertEquals(Duration.ZERO, sys.get(none, "waited"))
        val some = sys.order(scores = listOf(4, 9, 2), waits = listOf(Duration.ofMinutes(90), Duration.ofMinutes(30)))
        assertEquals(java.math.BigDecimal(15), sys.get(some, "total"))
        assertEquals(java.math.BigDecimal(9), sys.get(some, "best"))
        assertEquals(java.math.BigDecimal(2), sys.get(some, "worst"))
        assertEquals(Duration.ofHours(2), sys.get(some, "waited"))
        val records = diagsOf("shape Odd = Order where sum(lines) > 1")
        assertTrue(records.any { "names the field to sum" in it.message }, records.toString())
        val bools = diagsOf("shape Odd = Order where sum(flags) > 1")
        assertTrue(bools.any { "adds numbers or durations" in it.message }, bools.toString())
        val order = diagsOf("shape Odd = Order where max(flags) == true")
        assertTrue(order.any { "have no order" in it.message }, order.toString())
    }

    @Test
    fun `the pre-filter pushes in, matches, is empty, and between down as column tests`() {
        val qc = QueryCompiler(model(), LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(
            QF.Or(QF.Cmp("status", "==", QConst.QText("open")), QF.Cmp("status", "==", QConst.QText("pending"))),
            qc.filterFor(RefName("Live"))
        )
        assertEquals(
            QF.Not(QF.Or(QF.Cmp("status", "==", QConst.QText("open")), QF.Cmp("status", "==", QConst.QText("pending")))),
            qc.filterFor(RefName("Closed"))
        )
        assertEquals(QF.True, qc.filterFor(RefName("Scored")), "a many of values is not a column")
        assertEquals(QF.Cmp("email", "matches", QConst.QText("[^@ ]+@[^@ ]+\\.[a-z]+")), qc.filterFor(RefName("Mailable")))
        assertEquals(QF.TextBlank("note"), qc.filterFor(RefName("Unnoted")))
        assertEquals(QF.Not(QF.TextBlank("note")), qc.filterFor(RefName("Noted")))
        assertEquals(
            QF.And(QF.Cmp("qty", ">=", QConst.QNum(java.math.BigDecimal(2))), QF.Cmp("qty", "<=", QConst.QNum(java.math.BigDecimal(5)))),
            qc.filterFor(RefName("Mid"))
        )
        // a function of a column is not a column: the evaluator decides
        assertEquals(QF.True, qc.filterFor(RefName("Weekend")))
        assertEquals(QF.True, qc.filterFor(RefName("Even")))
    }

    @Test
    fun `the query handles spell the same predicates`() {
        val f = TextField(listOf("status"))
        assertEquals(Binary("in", PathExpr("this", listOf(Seg("status", false))), ListLit(listOf(TextLit("a"), TextLit("b")))), f.oneOf("a", "b"))
        assertEquals(Binary("matches", PathExpr("this", listOf(Seg("status", false))), TextLit("a+")), f matches "a+")
        assertEquals(IsExpr(PathExpr("this", listOf(Seg("status", false))), "empty"), f.isEmpty())
        val n = NumField(listOf("qty"))
        assertEquals("(this.qty >= 2) and (this.qty <= 5)", Printer.expr(n.between(2, 5)))
        assertEquals("dayOfWeek(this.placed) >= 6", Printer.expr(DateField(listOf("placed")).dayOfWeek gte 6))
        assertEquals("hour(this.placedAt) in [22, 23]", Printer.expr(DateTimeField(listOf("placedAt")).hour.oneOf(22, 23)))
    }
}
