package velle

import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pre-filter compiler (Query.kt): predicates compile to a filter the
 * authoritative predicate always implies — inexpressible pieces degrade to
 * True in positive position and False under negation, so the candidate set
 * only ever widens (investigate_runtime.md §2).
 */
class QueryCompilerTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 11)
    private val now: Instant = Instant.parse("2026-08-11T09:00:00Z")

    private fun billing(): Model {
        val model = Model(Parser.parse(File("../examples/billing/billing.velle").readText()))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return model
    }

    private fun compiler(model: Model) = QueryCompiler(model, today, now)

    private fun inline(source: String): Model {
        val model = Model(Parser.parse(source))
        check(model.diagnostics.none { !it.advisory }) { model.diagnostics.toString() }
        return model
    }

    // ── exact shapes over billing.velle ──────────────────────────────────────

    @Test
    fun `time comparison compiles, derived conjunct drops to true`() {
        // OverdueInvoice = Invoice where balance > 0 and due < today
        // balance is derived (not a column) -> that conjunct degrades to True
        val m = billing()
        val f = compiler(m).filterFor(RefName("OverdueInvoice"))
        assertEquals(QF.Cmp("due", "<", QConst.QDate(today)), f)
    }

    @Test
    fun `fully derived predicate degrades to no filter`() {
        // PaidInvoice = Invoice where total > 0 and balance <= 0 — both derived
        val m = billing()
        assertEquals(QF.True, compiler(m).filterFor(RefName("PaidInvoice")))
    }

    @Test
    fun `guard exists compiles to a correlated exists under not`() {
        // UnemailedReceipt = Receipt where not exists ReceiptEmail for this
        val m = billing()
        val f = compiler(m).filterFor(RefName("UnemailedReceipt"))
        assertEquals(QF.Not(QF.Exists("ReceiptEmail", "receipt", QF.True)), f)
    }

    @Test
    fun `general-form exists extracts the correlation and keeps the date guard`() {
        // RemindOverdue's condition: ActionableOverdue where
        //   not exists (Reminder where invoice == this and sentOn > today - 7 days)
        val m = billing()
        val rule = m.rules.getValue("RemindOverdue")
        val f = compiler(m).filterFor(rule.condition)

        val leaves = flatten(f)
        // due < today survives from OverdueInvoice
        assertTrue(QF.Cmp("due", "<", QConst.QDate(today)) in leaves, "expected due-cmp in $f")
        // the reminder guard is a correlated NOT EXISTS with the window intact
        val reminder = leaves.filterIsInstance<QF.Exists>().single { it.shape == "Reminder" }
        assertEquals("invoice", reminder.refField)
        assertEquals(QF.Cmp("sentOn", ">", QConst.QDate(today.minusDays(7))), reminder.inner)
        // ArchivedInvoice (negated composition operand) compiles exactly, too
        assertTrue(leaves.filterIsInstance<QF.Exists>().any { it.shape == "ArchiveRequest" }, "in $f")
    }

    @Test
    fun `negation strengthens where it can and degrades where it cannot`() {
        val m = billing()
        // not IssuedInvoice: the evidence-exists predicate strengthens exactly
        assertEquals(
            QF.Not(QF.Exists("Issuance", "invoice", QF.True)),
            compiler(m).filterFor(RefNot(RefName("IssuedInvoice")))
        )
        // not PaidInvoice: nothing inside is expressible -> True (fetch everything)
        assertEquals(QF.True, compiler(m).filterFor(RefNot(RefName("PaidInvoice"))))
    }

    @Test
    fun `membership through a to-one reference becomes a forward join`() {
        // ApplicableDueChange = ChangeDueDate where not invoice is IssuedInvoice
        val m = billing()
        val f = compiler(m).filterFor(RefName("ApplicableDueChange"))
        assertEquals(
            QF.Not(QF.RelPred("invoice", "Invoice", QF.Exists("Issuance", "invoice", QF.True))),
            f
        )
    }

    @Test
    fun `never target over a decimal column still compiles - encoding is the renderer's concern`() {
        val m = billing()
        val target = m.nevers.single { m.baseOfExpr(it.target) == "Payment" }.target
        val f = compiler(m).filterFor(target)
        assertEquals(QF.Cmp("amount", "<=", QConst.QNum(BigDecimal(0))), f)
    }

    // ── inline spec: boolean atoms and null checks ───────────────────────────

    @Test
    fun `boolean atoms and optionality checks compile to columns`() {
        val m = inline(
            """
            shape Account {
                balance: integer
                suspended: boolean
                closedOn: Date?
            }
            shape Delinquent = Account where balance < 0 and not suspended
            shape Open = Account where closedOn is none
            """.trimIndent()
        )
        assertEquals(
            QF.And(
                QF.Cmp("balance", "<", QConst.QNum(BigDecimal(0))),
                QF.Not(QF.Cmp("suspended", "==", QConst.QBool(true)))
            ),
            compiler(m).filterFor(RefName("Delinquent"))
        )
        assertEquals(QF.NullCheck("closedOn", isNull = true), compiler(m).filterFor(RefName("Open")))
    }

    @Test
    fun `element-scope this degrades by polarity instead of miscompiling`() {
        // `this` inside a nested element scope names the outermost subject —
        // only a top-level exists can express that correlation
        val m = inline(
            """
            shape Node {
                parent: one Node?
                weight: integer
            }
            shape Heavy = Node where exists (Node where parent == this and weight > weight)
            """.trimIndent()
        )
        val f = compiler(m).filterFor(RefName("Heavy"))
        // correlation extracted; the self-comparing conjunct compiles on the
        // element (both sides element-scoped) — the point is no crash and no
        // False in positive position
        assertTrue(f != QF.False)
    }

    private fun flatten(f: QF): List<QF> = when (f) {
        is QF.And -> flatten(f.l) + flatten(f.r)
        is QF.Or -> flatten(f.l) + flatten(f.r)
        is QF.Not -> flatten(f.inner)
        else -> listOf(f)
    }
}
