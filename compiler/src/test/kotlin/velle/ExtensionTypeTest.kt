package velle

import velle.ext.AltCents
import velle.ext.Cents
import velle.ext.Literals
import velle.ext.Operator
import velle.ext.Ordered
import velle.ext.Samples
import velle.ext.Summable
import velle.ext.Badge
import velle.ext.TestMoney
import velle.ext.TestBadge
import velle.ext.VelleType
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Extension types (Reference §5; OQ65): a scalar type an engineer writes in Kotlin,
 * registered on the classpath, named in a spec like `Date`. The fixtures are in
 * `velle.ext.TestTypes` and registered under test resources.
 */
class ExtensionTypeTest {

    private fun diags(spec: String): List<Diagnostic> = Validator.validate(Parser.parse(spec))
    private fun codes(spec: String) = diags(spec).map { it.code }
    private fun messages(spec: String) = diags(spec).joinToString("\n")

    private fun accepted(r: CommitResult): Long = (r as? CommitResult.Accepted)?.id ?: error((r as CommitResult.Refused).reason)

    private fun system(spec: String): VelleSystem {
        val decls = Parser.parse(spec)
        val d = Validator.validate(decls)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(decls))
    }

    // ── the registry (E1, E2) ────────────────────────────────────────────────

    private class BadlyNamed : VelleType<Badge> by TestBadge() { override val name = "money" }
    private class BuiltinNamed : VelleType<Badge> by TestBadge() { override val name = "Date" }
    private class UnknownOperand : VelleType<Cents> by TestMoney() {
        override val name = "Price"
        override val operators = listOf(Operator<Cents, Cents, Cents>("+", qualifiedName, "velle.nowhere.Thing", qualifiedName, { a, _ -> a }, true, false))
    }

    @Test
    fun `E1 refuses a type whose name is not a capitalized identifier, is a builtin, or is claimed twice`() {
        val r = TypeRegistry(listOf(BadlyNamed(), BuiltinNamed(), TestMoney(), TestMoney(), UnknownOperand()))
        val e1 = r.diagnostics.filter { it.code == "E1" }.map { it.message }
        assertTrue(e1.any { "'money' is not a capitalized identifier" in it }, e1.toString())
        assertTrue(e1.any { "'Date' is a builtin type name" in it }, e1.toString())
        assertTrue(e1.any { "two registered types claim 'velle.test.Price'" in it }, e1.toString())
        assertTrue(e1.any { "'velle.nowhere.Thing', which is neither a builtin nor a registered type" in it }, e1.toString())
    }

    private class LyingOrder : VelleType<Cents> by TestMoney(), Ordered<Cents> {
        override val name = "Price"
        override val operators = emptyList<Operator<*, *, *>>()
        override val ordering: Comparator<Cents> = compareByDescending { it.cents } // below(v) now orders above v
    }
    private class LyingFold : VelleType<Cents> by TestMoney() {
        override val name = "Price"
        override val operators = listOf(Operator<Cents, Cents, Cents>("+", qualifiedName, qualifiedName, qualifiedName, { a, b -> Cents(a.cents + b.cents) }, foldStepsCommute = true, foldIdempotent = true))
    }
    private class LyingSum : VelleType<Cents> by TestMoney(), Summable<Cents> {
        override val name = "Price"
        override val operators = emptyList<Operator<*, *, *>>()
        override val zero = Cents(1) // not a zero
        override fun plus(a: Cents, b: Cents) = Cents(a.cents + b.cents)
    }
    private class LyingLiteral : VelleType<Cents> by TestMoney() {
        override val name = "Price"
        override val operators = emptyList<Operator<*, *, *>>()
        override val literals = Literals<Cents>(fromNumber = { Cents(0) })
    }

    @Test
    fun `E2 refuses a type whose claims fail on its own samples, one clause each`() {
        fun e2(t: VelleType<*>) = TypeRegistry(listOf(t)).diagnostics.also { d -> assertTrue(d.all { it.code == "E2" }, d.toString()) }.map { it.message }
        assertTrue(e2(LyingOrder()).any { "below(sample) does not order strictly below the sample" in it })
        assertTrue(e2(LyingFold()).any { "claims foldIdempotent but" in it })
        assertTrue(e2(LyingSum()).any { "zero + sample is not the sample" in it })
        assertTrue(e2(LyingLiteral()).any { "fromNumber(canonical(sample)) is not equal to the sample" in it })
        assertEquals(emptyList(), TypeRegistry(listOf(TestMoney(), TestBadge())).diagnostics)
    }

    @Test
    fun `the default registry carries the test fixtures and passes its own checks`() {
        assertEquals(emptyList(), TypeRegistry.default.diagnostics)
        assertEquals(setOf("velle.test.Price", "velle.testalt.Price", "velle.test.Badge", "velle.test.Day", "velle.finance.Money"), TypeRegistry.default.byQualifiedName.keys)
    }

    // ── naming a type (D1: `uses`) ───────────────────────────────────────────

    @Test
    fun `uses parses and prints back as written`() {
        val decls = Parser.parse("uses velle.test.Price as Cash\nuses velle.test.Badge")
        assertEquals(listOf(UsesDecl("velle.test.Price", "Cash"), UsesDecl("velle.test.Badge")), decls)
        assertEquals("uses velle.test.Price as Cash", Printer.print(decls[0]))
        assertEquals("uses velle.test.Badge", Printer.print(decls[1]))
    }

    @Test
    fun `a registered type is in scope under its own name with nothing declared`() {
        assertEquals(emptyList(), diags("act Note { label: Badge }"))
    }

    @Test
    fun `a name two libraries register needs a uses line to pick one`() {
        val m = messages("act Account { balance: Price }")
        assertTrue("'Price' names 2 registered types ('velle.test.Price', 'velle.testalt.Price')" in m, m)
        assertTrue("add `uses <one of them>`" in m, m)
        assertEquals(emptyList(), diags("uses velle.test.Price\nact Account { balance: Price }"))
        assertEquals(emptyList(), diags("uses velle.testalt.Price\nact Account { balance: Price }"))
    }

    @Test
    fun `uses as renames the type and frees its name for a shape`() {
        val spec = """
            uses velle.test.Badge as Label
            act Badge { note: text }
            act Note { label: Label, tag: one Badge }
        """.trimIndent()
        assertEquals(emptyList(), diags(spec), messages(spec))
        val m = Model(Parser.parse(spec))
        assertEquals("velle.test.Badge", m.typeOf(ScalarType("Label", false)).let { (it as VType.Ext).qualifiedName })
    }

    @Test
    fun `a shape sharing a registered type's name is E1 with the uses line to write`() {
        val m = messages("act Badge { label: text }")
        assertTrue("'Badge' is both a shape in this spec and the registered type 'velle.test.Badge'" in m, m)
        assertTrue("add `uses velle.test.Badge as <Name>`" in m, m)
    }

    @Test
    fun `an unknown name in type position is F1, and a shape there is told to write one or many`() {
        val m1 = messages("act Account { balance: Dollars }")
        assertTrue("no builtin or registered type is named 'Dollars'" in m1, m1)
        val m2 = messages("act Customer { name: text }\nact Account { owner: Customer }")
        assertTrue("'Customer' is a shape; a field holding a record is `one Customer` or `many Customer`" in m2, m2)
        val m3 = messages("act Account { label: one Badge }")
        assertTrue("'Badge' is a registered type, not a shape; a field of it is `label: Badge`" in m3, m3)
        val m4 = messages("uses velle.finance.Price\nact Account { balance: Price }")
        assertTrue("`uses velle.finance.Price`: no registered type or function has that name; the registry has 'velle.test.Price', 'velle.testalt.Price'" in m4, m4)
    }

    // ── capabilities (D2, D4; E3) ────────────────────────────────────────────

    private val money = "uses velle.test.Price\n"

    @Test
    fun `an ordering comparison, max, min, and a selector on an unordered type are E3 naming the Kotlin class`() {
        val lt = messages("act Note { label: Badge }\nnever (Note where label < \"a\")")
        assertTrue("'Badge' (velle.ext.TestBadge) declares no ordering, so '<' is not defined on it" in lt, lt)
        val mx = messages("act Note { label: Badge, other: Badge, top: Badge initially \"x\" }\nrule Top when Note { top = max(label, other) }")
        assertTrue("declares no ordering, so 'max' is not defined on it" in mx, mx)
        val by = messages("act Note { label: Badge }\nact Board { latestNote: one Note? }\nrule Pick when Board { latestNote = latest(Note by label) }")
        assertTrue("'Badge' (velle.ext.TestBadge) declares no ordering, so 'Note.label' cannot order a selector" in by, by)
        assertEquals(emptyList(), diags("act Note { label: Badge }\nnever (Note where label == \"spam\")"))
    }

    @Test
    fun `arithmetic on a type with no such operator line is E3`() {
        val m = messages("act Note { label: Badge, other: Badge, both: Badge initially \"x\" }\nrule Join when Note { both = label + other }")
        assertTrue("no type declares 'Badge + Badge'; 'Badge' (velle.ext.TestBadge) is where to declare it" in m, m)
        val m2 = messages(money + "act Account { balance: Price initially 0 }\nact Fee { account: one Account }\nrule Halve when Fee { account.balance = account.balance / 2 }")
        assertTrue("'Price' (velle.ext.TestMoney) declares no '/' taking a constant on the right" in m2, m2)
    }

    @Test
    fun `sum over a type with no sum is E3`() {
        val m = messages("uses velle.testalt.Price\nact Bank { name: text }\nact Account { bank: one Bank, balance: Price }\nshape Busy = Bank where sum(accounts, balance) == 0")
        assertTrue("'Price' (velle.ext.OtherMoney) declares no sum" in m, m)
    }

    @Test
    fun `a constant takes the type of the other operand and is read at compile time`() {
        assertEquals(emptyList(), diags(money + "act Account { balance: Price initially 0 }\nnever (Account where balance < 0)"))
        val m = messages(money + "act Account { balance: Price initially 0 }\nnever (Account where balance < 0.125)")
        assertTrue("'Price' (velle.ext.TestMoney) rejects this constant: 0.125 has more than two decimal places" in m, m)
        val t = messages(money + "act Account { balance: Price initially 0 }\nnever (Account where balance == \"ten\")")
        assertTrue("reads no text constant" in t, t)
        val init = messages(money + "act Account { balance: Price initially \"zero\" }")
        assertTrue("'Account.balance initially'" in init || "reads no text constant" in init, init)
    }

    @Test
    fun `the operator table types a constant, times a number stays a number, plus a constant reads as the type`() {
        val spec = money + """
            act Account { balance: Price initially 0 }
            act Bonus { account: one Account }
            rule Double when Bonus { account.balance = account.balance * 2 }
            act Tip { account: one Account }
            rule AddTen when Tip { account.balance = account.balance + 10 }
        """.trimIndent()
        assertEquals(emptyList(), diags(spec), messages(spec))
    }

    @Test
    fun `two different types never meet, and a builtin never lands in an extension field`() {
        val cmp = messages(money + "act Account { balance: Price, label: Badge }\nnever (Account where balance == label)")
        assertTrue("compares a 'Price' (velle.ext.TestMoney) with a 'Badge' (velle.ext.TestBadge), two different types" in cmp, cmp)
        val asg = messages(money + "act Account { balance: Price initially 0, count: integer }\nact Fix { account: one Account }\nrule Fix when Fix { account.balance = account.count }")
        assertTrue("assigns 'account.count' to a field of 'Price' (velle.ext.TestMoney)" in asg, asg)
    }

    @Test
    fun `V8 reads the operator's fold facts`() {
        // `+` on Price commutes (declared) and is not idempotent: a scheduled fold passes the ordering leg
        val commuting = messages(money + "act Account { balance: Price initially 0, fee: Price }\nrule Charge when Account on Nightly { balance = balance - fee }")
        assertTrue("order-dependent fold" !in commuting, commuting)
        // `*` on Price declares neither: a scheduled fold is refused as order-dependent
        val notCommuting = messages(money + "act Account { balance: Price initially 0 }\nrule Grow when Account on Nightly { balance = balance * 2 }")
        assertTrue("order-dependent fold" in notCommuting, notCommuting)
    }

    // ── the worked Price (D6) in memory ──────────────────────────────────────

    private val bank = money + """
        act Customer { name: text }
        act Account {
            owner: one Customer
            balance: Price initially 0
            tags: many Badge
        }
        act Deposit {
            account: one Account
            amount: Price
        }
        rule ApplyDeposit when Deposit { account.balance = account.balance + amount }
        act Withdrawal {
            account: one Account
            amount: Price
        }
        rule ApplyWithdrawal when Withdrawal { account.balance = account.balance - amount }
        never (Deposit where amount <= 0)
        never (Withdrawal where amount <= 0)
        shape Overdrawn = Account where balance < 0
        act Statement { customer: one Customer, total: Price initially 0, largest: Price initially 0 }
        rule Total when Statement {
            total = sum(customer.accounts, balance)
            largest = max(sum(customer.accounts, balance), 1)
        }
    """.trimIndent()

    @Test
    fun `the worked spec validates and runs the deposit flow with the type's own values`() {
        val sys = system(bank)
        val owner = accepted(sys.commit("Customer", mapOf("name" to "Ada")))
        val dup = sys.commit("Account", mapOf("owner" to owner, "tags" to listOf(Badge("urgent"), Badge("urgent"))))
        assertIs<CommitResult.Refused>(dup)                             // a `many` is a set, under the type's `equal`
        val acct = accepted(sys.commit("Account", mapOf("owner" to owner, "tags" to listOf(Badge("urgent"), Badge("vip")))))
        assertEquals(Cents(0), sys.get(acct, "balance"))              // `initially 0` read as Price
        assertEquals(listOf(Badge("urgent"), Badge("vip")), sys.get(acct, "tags"))
        assertIs<CommitResult.Accepted>(sys.commit("Deposit", mapOf("account" to acct, "amount" to Cents.of("12.50"))))
        assertEquals(Cents(1250), sys.get(acct, "balance"))

        val zero = sys.commit("Deposit", mapOf("account" to acct, "amount" to Cents(0)))
        assertIs<CommitResult.Refused>(zero)
        assertTrue("0.00" in zero.reason, zero.reason) // the value reads as the type displays it
        assertEquals(false, sys.isMember(acct, "Overdrawn"))
        assertIs<CommitResult.Accepted>(sys.commit("Withdrawal", mapOf("account" to acct, "amount" to Cents.of("20.00"))))
        assertEquals(Cents(-750), sys.get(acct, "balance"))
        assertEquals(true, sys.isMember(acct, "Overdrawn"))            // `balance < 0` read 0 as Price
        assertIs<CommitResult.Accepted>(sys.commit("Deposit", mapOf("account" to acct, "amount" to Cents.of("20.00"))))

        val statement = accepted(sys.commit("Statement", mapOf("customer" to owner)))
        assertEquals(Cents(1250), sys.get(statement, "total"))
        assertEquals(Cents(1250), sys.get(statement, "largest"))
    }

    @Test
    fun `the boundary refuses a value of another class and one the type's own validate refuses`() {
        val sys = system(bank)
        val owner = accepted(sys.commit("Customer", mapOf("name" to "Ada")))
        val acct = accepted(sys.commit("Account", mapOf("owner" to owner)))
        val wrongClass = sys.commit("Deposit", mapOf("account" to acct, "amount" to BigDecimal("12.50")))
        assertIs<CommitResult.Refused>(wrongClass)
        assertTrue(wrongClass.reason.startsWith("type:"), wrongClass.reason)
        val otherMoney = sys.commit("Deposit", mapOf("account" to acct, "amount" to AltCents(1250)))
        assertIs<CommitResult.Refused>(otherMoney)
        val blank = sys.commit("Account", mapOf("owner" to owner, "tags" to listOf(Badge("  "))))
        assertIs<CommitResult.Refused>(blank)
        assertEquals("type: Badge — a badge is never blank", blank.reason)
    }
}
