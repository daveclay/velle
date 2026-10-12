package velle

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The requirement spelling of a prohibition (README §21): `Subject requires P`
 * means `never (Subject where not P)`. The parser derives the complement, so the
 * rest of the compiler sees the forbidden configuration a hand-written `never`
 * would state; the declaration as written survives on the AST, and rejections
 * and diagnostics quote it in the author's polarity.
 */
class RequirementTest {

    private fun never(src: String): NeverDecl = Parser.parse(src).filterIsInstance<NeverDecl>().single()
    private fun forbidden(src: String): String = Printer.expr((never(src).target as RefName).where!!)

    @Test
    fun `a comparison flips`() {
        assertEquals("unitPrice < 0", forbidden("PriceChange requires unitPrice >= 0"))
        assertEquals("quantity <= 0", forbidden("Prescription requires quantity > 0"))
        assertEquals("fillNumber != count(prescription.fills)", forbidden("Fill requires fillNumber == count(prescription.fills)"))
    }

    @Test
    fun `membership and non-membership exchange, and or becomes and`() {
        assertEquals("pharmacist is not LicensedPharmacist", forbidden("VerifyFill requires pharmacist is LicensedPharmacist"))
        assertEquals(
            "(filledBy is not Technician) and (filledBy is not Pharmacist)",
            forbidden("FillPrescription requires filledBy is Technician or filledBy is Pharmacist"),
        )
        assertEquals("(lot is ExpiredLot) or (lot is QuarantinedLot)",
            forbidden("FillPrescription requires lot is not ExpiredLot and lot is not QuarantinedLot"))
    }

    @Test
    fun `optionality and emptiness exchange, anything else is wrapped`() {
        assertEquals("assignee is none", forbidden("Ticket requires assignee is some"))
        assertEquals("lines is empty", forbidden("Order requires lines is not empty"))
        assertEquals("not (exists Receipt for this)", forbidden("Payment requires exists Receipt for this"))
        assertEquals("not applied", forbidden("Deposit requires applied"))
    }

    @Test
    fun `the declaration prints as written and round-trips`() {
        val src = "VerifyFill requires pharmacist is LicensedPharmacist tolerates contention"
        val d = never(src)
        assertEquals(Requirement("VerifyFill", IsExpr(PathExpr("pharmacist"), "refinement", "LicensedPharmacist")), d.requirement)
        assertTrue(d.toleratesContention)
        assertEquals("requires", d.keyword)
        assertEquals(src, Printer.print(d))
        assertEquals(d, never(Printer.print(d)))
        assertEquals("never (PriceChange where unitPrice < 0)", Printer.print(never("never (PriceChange where unitPrice < 0)")))
    }

    @Test
    fun `requires is reserved`() {
        val e = kotlin.test.assertFailsWith<VelleSyntaxError> { Parser.parse("shape Task { requires: boolean }") }
        assertTrue("requires" in e.message!!, e.message)
    }

    private val pharmacy = """
        expose shape Staff { name: text  role: text }
        shape Pharmacist = Staff where role == "pharmacist"
        expose shape Fill { quantity: integer }
        expose transient shape VerifyFill { fill: one Fill  pharmacist: one Staff }
        VerifyFill requires pharmacist is Pharmacist
        shape Verification { fill: one Fill  pharmacist: one Staff }
        rule RecordVerification when VerifyFill {
            Verification from { fill: fill, pharmacist: pharmacist }
        }
    """.trimIndent()

    @Test
    fun `a refusal renders as the requirement unmet`() {
        assertEquals(emptyList(), Validator.validate(pharmacy))
        val sys = VelleSystem(Model(Parser.parse(pharmacy)), startTime = Instant.parse("2026-10-11T09:00:00Z"))
        val tech = assertIs<CommitResult.Accepted>(sys.commit("Staff", mapOf("name" to "Ana", "role" to "technician"))).id
        val fill = assertIs<CommitResult.Accepted>(sys.commit("Fill", mapOf("quantity" to 30))).id
        val r = assertIs<CommitResult.Refused>(sys.commit("VerifyFill", mapOf("fill" to fill, "pharmacist" to tech)))
        val rejection = r.rejection!!
        assertEquals("VerifyFill requires pharmacist is Pharmacist", rejection.declaration)
        assertEquals("pharmacist is Pharmacist", rejection.requirement)
        assertEquals("VerifyFill where pharmacist is not Pharmacist", rejection.prohibition)
        assertEquals(
            """
            VerifyFill refused: VerifyFill requires pharmacist is Pharmacist, and this VerifyFill does not.
              requires #1: VerifyFill requires pharmacist is Pharmacist
              this VerifyFill: fill = Fill#2, pharmacist = Staff#1
              produced Verification#4: fill = Fill#2, pharmacist = Staff#1
              through Staff#1: name = "Ana", role = "technician"
              evaluation:
                this VerifyFill.pharmacist = Staff#1
                Staff#1.role = "technician"
                Staff#1 is not Pharmacist
            """.trimIndent(),
            rejection.message,
        )
        assertNull(rejection.rule)
    }

    @Test
    fun `a never still renders as the forbidden configuration`() {
        val src = pharmacy.replace("VerifyFill requires pharmacist is Pharmacist", "never (VerifyFill where pharmacist is not Pharmacist)")
        val sys = VelleSystem(Model(Parser.parse(src)), startTime = Instant.parse("2026-10-11T09:00:00Z"))
        val tech = assertIs<CommitResult.Accepted>(sys.commit("Staff", mapOf("name" to "Ana", "role" to "technician"))).id
        val fill = assertIs<CommitResult.Accepted>(sys.commit("Fill", mapOf("quantity" to 30))).id
        val r = assertIs<CommitResult.Refused>(sys.commit("VerifyFill", mapOf("fill" to fill, "pharmacist" to tech)))
        assertTrue(r.rejection!!.message.startsWith("VerifyFill refused: this VerifyFill would be a VerifyFill where pharmacist is not Pharmacist."), r.rejection!!.message)
        assertNull(r.rejection!!.requirement)
    }

    @Test
    fun `V29 quotes the requirement`() {
        val src = """
            expose shape Order { placedOn: Date }
            shape Cancellation { order: one Order  requestedOn: Date }
            shape CancelledOrder = Order where exists (cancellations)
            expose shape Shipment { order: one Order }
            Shipment requires order is not CancelledOrder
            rule CancelOld when (Order where placedOn < today - 7 days and not exists (cancellations)) on Nightly {
                Cancellation from { order: this, requestedOn: today }
            }
        """.trimIndent()
        val v29 = Validator.validate(src).filter { it.code == "V29" }
        assertEquals(1, v29.size, Validator.validate(src).toString())
        assertTrue("which requires #1 (Shipment requires order is not CancelledOrder) consults" in v29.single().message, v29.single().message)
    }
}
