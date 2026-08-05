package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parses the billing.velle fixture and spot-checks the resulting AST. */
class BillingFixtureTest {

    private val decls: List<Decl> by lazy { Parser.parse(File("../billing.velle").readText()) }

    private inline fun <reified T : Decl> all(): List<T> = decls.filterIsInstance<T>()
    private fun shape(name: String) = all<ShapeDecl>().single { it.name == name }
    private fun refinement(name: String) = all<RefinementDecl>().single { it.name == name }
    private fun rule(name: String) = all<RuleDecl>().single { it.name == name }

    @Test
    fun `declaration inventory`() {
        assertEquals(14, all<ShapeDecl>().size)
        assertEquals(8, all<RefinementDecl>().size)
        assertEquals(8, all<RuleDecl>().size)
        assertEquals(3, all<NeverDecl>().size)
        assertEquals(0, all<ExposeDecl>().size) // fixture uses only the inline form
    }

    @Test
    fun `nine shapes are exposed via MockHarness`() {
        val exposed = all<ShapeDecl>().filter { it.exposedVia != null }
        assertEquals(9, exposed.size)
        assertTrue(exposed.all { it.exposedVia == "MockHarness" })
    }

    @Test
    fun `invoice declares every property kind`() {
        val invoice = shape("Invoice")
        val reference = invoice.members.filterIsInstance<StoredProp>().single { it.name == "reference" }
        assertEquals(PathExpr("randomUUID"), reference.initially)

        val issuedOn = invoice.members.filterIsInstance<TimestampProp>().single()
        assertEquals("create", issuedOn.on)

        val balance = invoice.members.filterIsInstance<DerivedProp>().single { it.name == "balance" }
        val minus = assertIs<Binary>(balance.expr)
        assertEquals("-", minus.op)
        val sum = assertIs<AggCall>(minus.right)
        assertEquals("sum", sum.name)
        assertEquals("amount", sum.field)

        val status = invoice.members.filterIsInstance<DerivedProp>().single { it.name == "status" }
        val outer = assertIs<IfExpr>(status.expr)
        assertIs<IfExpr>(outer.elseExpr) // else-if chain
    }

    @Test
    fun `email correction is a single assignment`() {
        val body = rule("ApplyEmailCorrection").body
        val assignment = assertIs<Assignment>(body.single())
        assertEquals(PathExpr("customer", listOf(Seg("email", false))), assignment.target)
        assertEquals(PathExpr("corrected"), assignment.value)
    }

    @Test
    fun `the fold assigns through a two-hop literal path`() {
        val assignment = assertIs<Assignment>(rule("TrackLargestPayment").body.single())
        assertEquals("invoice", assignment.target.root)
        assertEquals(listOf("customer", "largestPayment"), assignment.target.segs.map { it.name })
        val max = assertIs<FunCall>(assignment.value)
        assertEquals("max", max.name)
        assertEquals(2, max.args.size)
    }

    @Test
    fun `after commit with backstop parses both triggers`() {
        val r = rule("EmailReceipt")
        assertEquals("after", r.preposition)
        assertEquals(listOf("commit", "Hourly"), r.triggers)
        val creation = assertIs<Creation>(r.body.single())
        assertEquals("ReceiptEmail", creation.shape)
        assertEquals(listOf("receipt", "queuedOn"), creation.fields.map { it.name })
    }

    @Test
    fun `the sweep names its schedule and carries its guard in the condition`() {
        val r = rule("RemindOverdue")
        assertEquals("on", r.preposition)
        assertEquals(listOf("Weekly"), r.triggers)
        val cond = assertIs<RefName>(r.condition)
        assertEquals("ActionableOverdue", cond.name)
        assertNotNull(cond.where) // not exists (Reminder where ... 7 days)
    }

    @Test
    fun `composition parses and-not`() {
        val actionable = refinement("ActionableOverdue")
        val and = assertIs<RefAnd>(actionable.expr)
        assertEquals(RefName("OverdueInvoice"), and.left)
        assertEquals(RefNot(RefName("ArchivedInvoice")), and.right)
    }

    @Test
    fun `issuance freezes the due date`() {
        val issued = refinement("IssuedInvoice")
        val frozen = issued.members.filterIsInstance<FrozenClause>().single()
        assertEquals(listOf("due"), frozen.fields)
        val name = assertIs<RefName>(issued.expr)
        val exists = assertIs<ExistsExpr>(name.where)
        assertEquals("Issuance", exists.shape)
    }

    @Test
    fun `archival captures today at entry`() {
        val archived = refinement("ArchivedInvoice")
        val captured = archived.members.filterIsInstance<DerivedProp>().single()
        assertTrue(captured.captured)
        assertEquals("archivedOn", captured.name)
        assertEquals(TodayLit, captured.expr)
        assertEquals(ScalarType("Date", optional = false), captured.type)
    }

    @Test
    fun `the exit rule is a leaving rule reading the capture`() {
        val r = rule("NoteUnarchival")
        assertTrue(r.leaving)
        assertNull(r.preposition)
        val creation = assertIs<Creation>(r.body.single())
        assertEquals(PathExpr("archivedOn"), creation.fields.single { it.name == "wasArchivedOn" }.value)
    }

    @Test
    fun `nevers are inline refinements over act shapes`() {
        val targets = all<NeverDecl>().map { assertIs<RefName>(it.target) }
        assertEquals(setOf("LineItem", "Payment"), targets.map { it.name }.toSet())
        assertTrue(targets.all { it.where != null })
    }
}

/** Grammar coverage beyond the fixture — the §10 constructs billing.velle doesn't use. */
class ExpressionTest {

    private fun predicate(s: String): Expr = Parser(Lexer(s).lex()).parsePredicate()
    private fun value(s: String): Expr = Parser(Lexer(s).lex()).parseValue()

    @Test
    fun `as binding reaches the middle scope`() {
        val e = predicate("exists (invoices as inv where count(inv.payments where amount > inv.amount) >= 1)")
        val exists = assertIs<ExistsExpr>(e)
        val binding = exists.collection!!.bindings.single()
        assertEquals("inv", binding.alias)
        assertEquals(PathExpr("invoices"), binding.source)
    }

    @Test
    fun `sibling joins share one where`() {
        val e = predicate(
            "exists (tickets as tix, orders as ord where tix is OverdueTicket and ord.product == tix.product)")
        val coll = assertIs<ExistsExpr>(e).collection!!
        assertEquals(listOf("tix", "ord"), coll.bindings.map { it.alias })
        assertNotNull(coll.where)
    }

    @Test
    fun `qdot short-circuits without narrowing`() {
        val e = value("if parent?.root is none then parent else parent?.root")
        val ifE = assertIs<IfExpr>(e)
        val subject = assertIs<PathExpr>(assertIs<IsExpr>(ifE.condition).subject)
        assertTrue(subject.segs.single().viaQdot)
    }

    @Test
    fun `singular for-query takes accessors`() {
        val e = value("(NurseVerification for this).nurse")
        val access = assertIs<Access>(e)
        val singular = assertIs<SingularFor>(access.target)
        assertEquals("NurseVerification", singular.shape)
        assertEquals("nurse", access.segs.single().name)
    }

    @Test
    fun `selectors take shape-for collections and accessors`() {
        val e = value("latest(EmailCorrection for this).corrected")
        val access = assertIs<Access>(e)
        val latest = assertIs<AggCall>(access.target)
        val source = assertIs<ShapeForSource>(latest.collection.bindings.single().source)
        assertEquals("EmailCorrection", source.shape)
    }

    @Test
    fun `duration arithmetic binds as an additive operand`() {
        val e = value("now + 14 days")
        val plus = assertIs<Binary>(e)
        assertEquals(DurationLit(14, "days"), plus.right)
    }

    @Test
    fun `unary minus parses and nests`() {
        val e = value("-amount")
        assertEquals(UnaryMinus(PathExpr("amount")), e)
    }

    @Test
    fun `is not empty is a single atom`() {
        val e = predicate("corrections is not empty")
        assertEquals("notEmpty", assertIs<IsExpr>(e).kind)
    }

    @Test
    fun `bare refinement name filters a collection`() {
        val e = value("count(invoices where OverdueInvoice)")
        val count = assertIs<AggCall>(e)
        assertEquals(PathExpr("OverdueInvoice"), count.collection.where)
    }

    @Test
    fun `precedence - not binds tighter than and, and tighter than or`() {
        val e = predicate("not a and b or c")
        val or = assertIs<Binary>(e)
        assertEquals("or", or.op)
        val and = assertIs<Binary>(or.left)
        assertEquals("and", and.op)
        assertIs<NotExpr>(and.left)
    }
}
