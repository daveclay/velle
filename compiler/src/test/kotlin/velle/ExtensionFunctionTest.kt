package velle

import velle.ext.Accepts
import velle.ext.Clamp
import velle.ext.Day
import velle.ext.IsBusinessDay
import velle.ext.Median
import velle.ext.Parameter
import velle.ext.Returns
import velle.ext.Signature
import velle.ext.TestMoney
import velle.ext.VelleFunction
import velle.ext.builtin.BuiltinFunctions
import velle.ext.builtin.TextType
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Registered functions (Reference §5; `velle.ext.VelleFunction`): a function an engineer
 * writes in Kotlin against the capability interfaces, registered beside the types, and
 * Velle's own builtins implemented the same way. The fixtures are `velle.ext.TestFunctions`.
 */
class ExtensionFunctionTest {

    private fun diags(spec: String) = Validator.validate(spec)
    private fun messages(spec: String) = diags(spec).joinToString("\n") { "[${it.code}] ${it.message}" }

    private fun system(spec: String): VelleSystem {
        val decls = Parser.parse(spec)
        val d = Validator.validate(decls)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(decls))
    }

    // ── the registry (E1) ────────────────────────────────────────────────────

    private class BadlyNamed : VelleFunction by IsBusinessDay() { override val name = "IsBusinessDay" }
    private class BuiltinNamed : VelleFunction by IsBusinessDay() { override val name = "abs" }
    private class Reserved : VelleFunction by IsBusinessDay() { override val name = "count" }
    private class VariadicFirst : VelleFunction by Clamp() {
        override val signatures = listOf(Signature(
            listOf(Parameter("a", Accepts.Is(TextType::class), variadic = true), Parameter("b", Accepts.SameAs(0))),
            Returns.SameAs(0)) { _, a -> a[0].value })
    }

    @Test
    fun `E1 refuses a function whose name is not a lowercase identifier, is a builtin, is a construct, is claimed twice, or whose signature is malformed`() {
        val r = TypeRegistry(emptyList(), listOf(BadlyNamed(), BuiltinNamed(), Reserved(), Median(), Median(), VariadicFirst()))
        val e1 = r.diagnostics.filter { it.code == "E1" }.map { it.message }
        assertTrue(e1.any { "'IsBusinessDay' is not a lowercase identifier" in it }, e1.toString())
        assertTrue(e1.any { "'abs' is a builtin function name" in it }, e1.toString())
        assertTrue(e1.any { "'count' is a language construct with its own syntax" in it }, e1.toString())
        assertTrue(e1.any { "two registered functions claim 'velle.test.median'" in it }, e1.toString())
        assertTrue(e1.any { "parameter 'a' is variadic but not last" in it }, e1.toString())
    }

    @Test
    fun `the builtins are registered functions in the velle namespace, and the default registry passes its own checks`() {
        assertEquals(emptyList(), TypeRegistry.default.diagnostics)
        val builtin = BuiltinFunctions.all.map { it.qualifiedName }.toSet()
        assertTrue(builtin.containsAll(setOf("velle.max", "velle.min", "velle.abs", "velle.lowercase", "velle.year", "velle.hour")), builtin.toString())
        assertTrue(TypeRegistry.default.functions.keys.containsAll(builtin + setOf("velle.test.median", "velle.test.clamp", "velle.test.isBusinessDay", "velle.testalt.isBusinessDay")))
        assertEquals(emptyList(), TypeRegistry.EMPTY.diagnostics)
    }

    // ── calling one (Reference §5, §10) ──────────────────────────────────────

    private val spec = """
        uses velle.test.isBusinessDay
        act Order {
            placed: Date
            placedAt: DateTime?
            day: Day?
            scores: many integer
            note: text?
            amount: decimal
            median: integer? = median(scores)
            rounded: decimal = roundTo(amount, 1)
            clamped: decimal = clamp(amount, 0, 100)
        }
        shape Weekday = Order where isBusinessDay(placed)
        shape WeekdayMoment = Order where isBusinessDay(placedAt)
        shape WeekdayDay = Order where isBusinessDay(day)
        shape In2026 = Order where year(day) == 2026
        shape Lowered = Order where lowercase(note) == "x"
        shape Topped = Order where max(scores) >= 10
    """.trimIndent()

    private fun VelleSystem.order(placed: LocalDate = LocalDate.of(2026, 1, 5), placedAt: Instant? = null, day: Day? = null,
                                  scores: List<Int> = emptyList(), note: String? = null, amount: String = "12.345"): Long {
        val fields = mutableMapOf<String, Any?>("placed" to placed, "scores" to scores, "amount" to java.math.BigDecimal(amount))
        placedAt?.let { fields["placedAt"] = it }
        day?.let { fields["day"] = it }
        note?.let { fields["note"] = it }
        return assertIs<CommitResult.Accepted>(commit("Order", fields)).id
    }

    @Test
    fun `a registered predicate over a capability reaches a Date, a DateTime, and a registered type alike`() {
        val sys = system(spec)
        sys.scope {
            val monday = sys.order(placed = LocalDate.of(2026, 1, 5))
            val saturday = sys.order(placed = LocalDate.of(2026, 1, 3))
            assertTrue(sys.isMember(monday, "Weekday"))
            assertTrue(!sys.isMember(saturday, "Weekday"))
            // a moment's date is read in the system's zone (UTC here): late Friday UTC is a weekday
            assertTrue(sys.isMember(sys.order(placedAt = Instant.parse("2026-01-02T23:30:00Z")), "WeekdayMoment"))
            assertTrue(!sys.isMember(sys.order(placedAt = Instant.parse("2026-01-03T00:30:00Z")), "WeekdayMoment"))
            // a registered type with a calendar date and nothing else
            val d = sys.order(day = Day(LocalDate.of(2026, 1, 7)))
            assertTrue(sys.isMember(d, "WeekdayDay"))
            assertTrue(sys.isMember(d, "In2026"))
            assertTrue(!sys.isMember(sys.order(day = Day(LocalDate.of(2025, 12, 31))), "In2026"))
        }
    }

    @Test
    fun `an absent argument gives an absent result, and a predicate over it is false`() {
        val sys = system(spec)
        sys.scope {
            val o = sys.order()
            assertTrue(!sys.isMember(o, "WeekdayMoment"), "no moment: not a member")
            assertTrue(!sys.isMember(o, "WeekdayDay"))
            assertTrue(!sys.isMember(o, "In2026"))
            assertTrue(!sys.isMember(o, "Lowered"), "lowercase of an absent text is absent, never an error")
            assertTrue(sys.isMember(sys.order(note = "X"), "Lowered"))
        }
    }

    @Test
    fun `a function over a many of an ordered type, absent when the many is empty`() {
        val sys = system(spec)
        sys.scope {
            assertEquals(null, sys.get(sys.order(), "median"))
            assertEquals(java.math.BigDecimal(5), sys.get(sys.order(scores = listOf(9, 1, 5)), "median"))
            assertTrue(sys.isMember(sys.order(scores = listOf(3, 12)), "Topped"))
            assertTrue(!sys.isMember(sys.order(), "Topped"))
        }
    }

    @Test
    fun `a numeric function and a same-type function with constants`() {
        val sys = system(spec)
        sys.scope {
            val o = sys.order(amount = "12.345")
            assertEquals(0, java.math.BigDecimal("12.3").compareTo(sys.get(o, "rounded") as java.math.BigDecimal))
            assertEquals(0, java.math.BigDecimal("12.345").compareTo(sys.get(o, "clamped") as java.math.BigDecimal))
            assertEquals(0, java.math.BigDecimal("100").compareTo(sys.get(sys.order(amount = "250"), "clamped") as java.math.BigDecimal))
            assertEquals(0, java.math.BigDecimal("0").compareTo(sys.get(sys.order(amount = "-3"), "clamped") as java.math.BigDecimal))
        }
    }

    @Test
    fun `a constant beside an extension argument reads as that type, and is refused when the type cannot read it`() {
        val money = "uses velle.test.Price\n"
        assertEquals(emptyList(), diags(money + "act Account { balance: Price, capped: Price = clamp(balance, 0, 100) }"))
        val m = messages(money + "act Account { balance: Price, capped: Price = clamp(balance, 0, 1.005) }")
        assertTrue("has more than two decimal places" in m, m)
        val sys = system(money + "act Account { balance: Price, capped: Price = clamp(balance, 0, 100) }")
        sys.scope {
            val id = assertIs<CommitResult.Accepted>(sys.commit("Account", mapOf("balance" to velle.ext.Cents.of("250.00")))).id
            assertEquals(velle.ext.Cents.of("100.00"), sys.get(id, "capped"))
        }
    }

    // ── what the compiler refuses (F2, E3, E1) ───────────────────────────────

    @Test
    fun `a call that no signature accepts is refused naming what the parameter takes`() {
        val m1 = messages("uses velle.test.isBusinessDay\nact Order { note: text }\nshape X = Order where isBusinessDay(note)")
        assertTrue("'isBusinessDay' takes a Date, a DateTime or 'Day' (velle.ext.TestDay), but 'note' is a text" in m1, m1)
        val m2 = messages("uses velle.test.isBusinessDay\nact Order { label: Badge }\nshape X = Order where isBusinessDay(label)")
        assertTrue("'Badge' (velle.ext.TestBadge) does not implement `HasCalendarDate`, so 'isBusinessDay' is not defined on it; implement `HasCalendarDate` on the type" in m2, m2)
        val m3 = messages("act Order { flags: many boolean }\nshape X = Order where median(flags) == true")
        assertTrue("'boolean' values have no order, so 'median' is not defined on them" in m3, m3)
        val m4 = messages("act Order { a: decimal }\nshape X = Order where roundTo(a) == 1")
        assertTrue("'roundTo' takes 2 arguments" in m4, m4)
        val m5 = messages("act Order { a: decimal, b: text }\nshape X = Order where clamp(a, b, 3) == 1")
        assertTrue("'clamp' takes one type; 'b' is not a decimal" in m5, m5)
    }

    @Test
    fun `an unknown name is F2, and a name two libraries register needs a uses line`() {
        val m = messages("act Order { note: text }\nshape X = Order where shout(note) == \"x\"")
        assertTrue("no builtin or registered function is named 'shout'" in m, m)
        val amb = messages("act Order { placed: Date }\nshape X = Order where isBusinessDay(placed)")
        assertTrue("'isBusinessDay' names 2 registered functions ('velle.test.isBusinessDay', 'velle.testalt.isBusinessDay'); add `uses <one of them>` to pick one" in amb, amb)
        assertEquals(emptyList(), diags("uses velle.testalt.isBusinessDay\nact Order { placed: Date }\nshape X = Order where isBusinessDay(placed)"))
        assertEquals(emptyList(), diags("uses velle.testalt.isBusinessDay as open\nact Order { placed: Date }\nshape X = Order where open(placed)"))
        val sys = system("uses velle.testalt.isBusinessDay as open\nact Order { placed: Date }\nshape X = Order where open(placed)")
        sys.scope {
            assertTrue(sys.isMember(assertIs<CommitResult.Accepted>(sys.commit("Order", mapOf("placed" to LocalDate.of(2026, 1, 3)))).id, "X"), "the alt library says every day is open")
        }
    }

    @Test
    fun `a function's fold facts answer V8 as an operator's do`() {
        fun fold(step: String) = diags("""
            act Gauge { peak: decimal initially 0 }
            act Reading { gauge: one Gauge
                value: decimal }
            rule Peak when Reading on commit, Hourly { gauge.peak = $step }
        """.trimIndent()).filter { it.code == "V8" }
        // max commutes and repeats harmlessly: the unguarded scheduled fold is accepted
        assertEquals(emptyList(), fold("max(gauge.peak, value)"))
        // a registered function claims neither: the same fold is refused
        assertTrue(fold("roundTo(gauge.peak, value)").isNotEmpty())
    }
}
