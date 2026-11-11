package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A text literal with pieces, `"INV-${number}"` (Reference §5): the one text
 * literal form, read with its pieces when it has any. A piece is any value
 * expression; a piece that is not a text renders as its type's canonical text;
 * an absent piece makes the whole text absent; `\$` is the escape for a literal
 * `${`. Settled as OQ80.
 */
class TextInterpolationTest {

    private fun lex(source: String) = Lexer(source).lex()

    private fun diags(spec: String) = Validator.validate(spec)

    private fun f2(spec: String) = diags(spec).filter { it.code == "F2" }.map { it.message }

    private fun model(spec: String): Model {
        val decls = Parser.parse(spec)
        val found = Validator.validate(decls)
        check(found.none { !it.advisory }) { found.toString() }
        return Model(decls)
    }

    // ── the lexer and the parser ─────────────────────────────────────────────

    @Test
    fun `a literal with pieces lexes as one token carrying its runs and pieces`() {
        val tokens = lex("\"INV-\${number}\"")
        assertEquals(listOf(TokType.TEXT_INTERP, TokType.EOF), tokens.map { it.type })
        assertEquals(listOf(RawTextPart.Run("INV-"), RawTextPart.Piece("number", 1, 8)), tokens[0].parts)
    }

    @Test
    fun `a literal with no pieces is the constant it was`() {
        assertEquals(TokType.TEXT, lex("\"open\"")[0].type)
        assertEquals("open", lex("\"open\"")[0].text)
    }

    @Test
    fun `the escape gives the characters, and a dollar not followed by a brace is a character`() {
        val escaped = lex("\"Pay \\\${amount} now\"")[0]
        assertEquals(TokType.TEXT, escaped.type)
        assertEquals("Pay \${amount} now", escaped.text)
        val price = lex("\"\$5 and \$\${amount}\"")[0]
        assertEquals(TokType.TEXT_INTERP, price.type)
        assertEquals(listOf(RawTextPart.Run("\$5 and \$"), RawTextPart.Piece("amount", 1, 12)), price.parts)
    }

    @Test
    fun `a piece may hold a quoted text and braces of its own`() {
        val token = lex("\"a\${if paid then \"yes\" else \"no\"}b\"")[0]
        assertEquals(listOf(RawTextPart.Run("a"), RawTextPart.Piece("if paid then \"yes\" else \"no\"", 1, 5), RawTextPart.Run("b")), token.parts)
    }

    @Test
    fun `a piece that does not close is a syntax error`() {
        assertFailsWith<VelleSyntaxError> { lex("\"INV-\${number\"") }
    }

    @Test
    fun `the parser reads each piece as a value expression and the printer writes it back`() {
        val decls = Parser.parse("shape Invoice {\n number: integer\n reference: text = \"INV-\${number}-\${count(lineItems)}\"\n}")
        val reference = (decls.single() as ShapeDecl).members.filterIsInstance<DerivedProp>().single().expr
        assertIs<Interpolation>(reference)
        assertEquals(TextRun("INV-"), reference.parts[0])
        assertEquals(TextPiece(PathExpr("number")), reference.parts[1])
        assertEquals(TextRun("-"), reference.parts[2])
        assertIs<AggCall>((reference.parts[3] as TextPiece).expr)
        assertEquals("\"INV-\${number}-\${count(lineItems)}\"", Printer.expr(reference))
    }

    @Test
    fun `the printer escapes a run that holds a quote, a backslash, or a dollar brace`() {
        val literal = Interpolation(listOf(TextRun("say \"\${hi}\" \\ "), TextPiece(PathExpr("name"))))
        assertEquals("\"say \\\"\\\${hi}\\\" \\\\ \${name}\"", Printer.expr(literal))
    }

    // ── the validator ────────────────────────────────────────────────────────

    private val invoice = """
        act Customer {
            name: text
        }
        act Invoice {
            customer: one Customer
            number: integer
            poNumber: text?
            due: Date
            reference: text = "INV-${'$'}{number}"
            purchaseOrder: text? = "PO ${'$'}{poNumber}"
            label: text = "Invoice ${'$'}{reference} for ${'$'}{customer.name}, due ${'$'}{due}"
        }
    """.trimIndent()

    @Test
    fun `a literal with pieces is accepted in a derived property, a from block, and a predicate operand`() {
        val spec = """
            $invoice
            shape ReferencedInvoice = Invoice where purchaseOrder is some
            shape Flagged = Invoice where label contains "for ${'$'}{customer.name}"
            shape Notice {
                subject: text
            }
            rule Notify when Invoice {
                Notice from { subject: "${'$'}{reference} is due ${'$'}{due}" }
            }
        """.trimIndent()
        assertEquals(emptyList(), diags(spec).filter { !it.advisory }.map { it.message })
    }

    @Test
    fun `a piece that is a record or a collection is refused, naming the piece`() {
        val spec = """
            act Customer {
                name: text
            }
            act Invoice {
                customer: one Customer
                label: text = "for ${'$'}{customer}"
            }
        """.trimIndent()
        val messages = f2(spec)
        assertEquals(1, messages.size, messages.toString())
        assertEquals("'\"for \${customer}\"' — a piece of a text is a value, but 'customer' is a record; write the field you mean, such as 'customer.name'", messages.single())
    }

    @Test
    fun `a literal with an optional piece is declared optional`() {
        val spec = """
            act Invoice {
                poNumber: text?
                purchaseOrder: text = "PO ${'$'}{poNumber}"
            }
        """.trimIndent()
        val messages = f2(spec)
        assertEquals(1, messages.size, messages.toString())
        assertEquals("'Invoice.purchaseOrder' — the text is absent when 'poNumber' is; declare it `text?`", messages.single())
    }

    @Test
    fun `plus between texts names the literal with pieces as the spelling`() {
        val messages = f2("act Field {\n name: text\n unitName: text = name + \"_unit\"\n}")
        assertEquals(1, messages.size, messages.toString())
        assertEquals("'name + \"_unit\"' — '+' is not defined on a text; a text made from other values is a text literal with pieces, \"\${name}_unit\"", messages.single())
    }

    // ── the evaluator ────────────────────────────────────────────────────────

    @Test
    fun `pieces render as their type's canonical text, and an absent piece makes the text absent`() {
        val spec = """
            $invoice
            act Measure {
                amount: decimal
                paid: boolean
                at: DateTime
                summary: text = "${'$'}{amount} paid=${'$'}{paid} at ${'$'}{at} over ${'$'}{at - at}"
            }
        """.trimIndent()
        val sys = VelleSystem(model(spec))
        sys.scope {
            val customer = assertIs<CommitResult.Accepted>(sys.commit("Customer", mapOf("name" to "Acme"))).id
            val withPo = assertIs<CommitResult.Accepted>(sys.commit("Invoice", mapOf(
                "customer" to customer, "number" to 42, "poNumber" to "PO-7", "due" to LocalDate.of(2026, 11, 4)))).id
            val withoutPo = assertIs<CommitResult.Accepted>(sys.commit("Invoice", mapOf(
                "customer" to customer, "number" to 43, "due" to LocalDate.of(2026, 11, 5)))).id
            assertEquals(Value.VText("INV-42"), sys.evaluator.readMember(withPo, "Invoice", "reference"))
            assertEquals(Value.VText("PO PO-7"), sys.evaluator.readMember(withPo, "Invoice", "purchaseOrder"))
            assertEquals(Value.VNone, sys.evaluator.readMember(withoutPo, "Invoice", "purchaseOrder"))
            assertEquals(Value.VText("Invoice INV-43 for Acme, due 2026-11-05"), sys.evaluator.readMember(withoutPo, "Invoice", "label"))

            val measure = assertIs<CommitResult.Accepted>(sys.commit("Measure", mapOf(
                "amount" to java.math.BigDecimal("1.50"), "paid" to true, "at" to Instant.parse("2026-11-11T09:30:00Z")))).id
            assertEquals(Value.VText("1.5 paid=true at 2026-11-11T09:30:00Z over PT0S"), sys.evaluator.readMember(measure, "Measure", "summary"))
        }
    }

    @Test
    fun `the canonical text of a number, a date, and a moment reads back as the value`() {
        val spec = """
            act Sample {
                amount: decimal
                day: Date
                at: DateTime
                amountBack: decimal? = number("${'$'}{amount}")
                dayBack: Date? = date("${'$'}{day}")
                atBack: DateTime? = datetime("${'$'}{at}")
            }
        """.trimIndent()
        val sys = VelleSystem(model(spec))
        sys.scope {
            val sample = assertIs<CommitResult.Accepted>(sys.commit("Sample", mapOf(
                "amount" to java.math.BigDecimal("12.340"), "day" to LocalDate.of(2026, 2, 3), "at" to Instant.parse("2026-02-03T04:05:06Z")))).id
            assertEquals(Value.VNum(java.math.BigDecimal("12.34")), sys.evaluator.readMember(sample, "Sample", "amountBack"))
            assertEquals(Value.VDate(LocalDate.of(2026, 2, 3)), sys.evaluator.readMember(sample, "Sample", "dayBack"))
            assertEquals(Value.VDateTime(Instant.parse("2026-02-03T04:05:06Z")), sys.evaluator.readMember(sample, "Sample", "atBack"))
        }
    }

    @Test
    fun `a piece in a like pattern is characters, never a wildcard`() {
        val spec = """
            act Note {
                tag: text
                body: text
            }
            shape SelfTagged = Note where body like "%[${'$'}{tag}]%"
        """.trimIndent()
        val sys = VelleSystem(model(spec))
        sys.scope {
            val literal = assertIs<CommitResult.Accepted>(sys.commit("Note", mapOf("tag" to "50%", "body" to "a [50%] discount"))).id
            val wildcard = assertIs<CommitResult.Accepted>(sys.commit("Note", mapOf("tag" to "50%", "body" to "a [50 percent] discount"))).id
            assertEquals(listOf(literal), sys.instancesOf("SelfTagged"))
            assertTrue(wildcard !in sys.instancesOf("SelfTagged"))
        }
    }

    // ── the pre-filter ───────────────────────────────────────────────────────

    @Test
    fun `a literal whose pieces all fold is one text constant in the filter, and a column piece degrades`() {
        val spec = """
            act Note {
                tag: text
                day: Date
            }
            shape TaggedToday = Note where tag == "d:${'$'}{today}"
            shape SelfTagged = Note where tag contains "[${'$'}{day}]"
        """.trimIndent()
        val today = LocalDate.of(2026, 8, 11)
        val compiler = QueryCompiler(model(spec), today, Instant.parse("2026-08-11T09:00:00Z"))
        assertEquals(QF.Cmp("tag", "==", QConst.QText("d:2026-08-11")), compiler.filterFor(RefName("TaggedToday")))
        assertEquals(QF.True, compiler.filterFor(RefName("SelfTagged")))
    }
}
