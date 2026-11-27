package velle

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What an act does with a field that has a starting value and with a timestamp field
 * (Reference §5, "Starting values" and "Timestamps"; checks.md V21): a field with
 * `initially` that the committer leaves out is filled at the commit, one the committer
 * gives keeps the committer's value, and a timestamp is the commit's own, never the
 * committer's. Each test commits through [VelleSystem] and reads the record back.
 */
class ActStartingValueAndTimestampTest {

    private val clockStart: Instant = Instant.parse("2026-01-01T09:00:00Z")
    private val clockStartDate: LocalDate = LocalDate.parse("2026-01-01")

    private fun systemOf(source: String): VelleSystem {
        assertEquals(emptyList(), Validator.validate(source))
        return VelleSystem(Model(Parser.parse(source)))
    }

    private fun accepted(result: CommitResult): Long {
        assertIs<CommitResult.Accepted>(result, "commit refused: $result")
        return result.id
    }

    // ── a computed starting value left out is filled at the commit ─────────────

    private val payments = """
        act Payment {
            amount: decimal
            receivedOn: Date initially today
            receivedAt: DateTime initially now
            reference: uuid initially randomUUID
            isRefund: boolean initially false
        }
    """.trimIndent()

    @Test
    fun `a payment committed without receivedOn starts on the clock's date`() {
        val system = systemOf(payments)
        system.scope {
            system.advanceDays(3)
            val payment = accepted(system.commit("Payment", mapOf("amount" to BigDecimal("12.50"))))
            assertEquals(clockStartDate.plusDays(3), system.get(payment, "receivedOn"))
        }
    }

    @Test
    fun `a payment committed without receivedAt starts at the commit's moment`() {
        val system = systemOf(payments)
        system.scope {
            system.advance(90)
            val payment = accepted(system.commit("Payment", mapOf("amount" to BigDecimal("12.50"))))
            assertEquals(clockStart.plusSeconds(90), system.get(payment, "receivedAt"))
        }
    }

    @Test
    fun `two payments committed without reference each get a different uuid`() {
        val system = systemOf(payments)
        system.scope {
            val first = accepted(system.commit("Payment", mapOf("amount" to BigDecimal("12.50"))))
            val second = accepted(system.commit("Payment", mapOf("amount" to BigDecimal("12.50"))))
            val firstReference = system.get(first, "reference")
            assertIs<UUID>(firstReference)
            assertNotEquals(firstReference, system.get(second, "reference"))
        }
    }

    // ── a value the committer gives is kept ────────────────────────────────────

    @Test
    fun `a committer who gives a field with a fixed starting value has that value kept`() {
        val system = systemOf(payments)
        system.scope {
            val payment = accepted(system.commit("Payment", mapOf("amount" to BigDecimal("12.50"), "isRefund" to true)))
            assertEquals(true, system.get(payment, "isRefund"))
        }
    }

    @Test
    fun `a committer who gives fields with computed starting values has those values kept`() {
        val system = systemOf(payments)
        val givenDate = LocalDate.parse("2025-12-24")
        val givenMoment = Instant.parse("2025-12-24T15:30:00Z")
        val givenReference = UUID.fromString("0b6f3c1e-8a52-4d0c-9f3e-2a7d1b5c4e90")
        system.scope {
            val payment = accepted(system.commit("Payment", mapOf(
                "amount" to BigDecimal("12.50"),
                "receivedOn" to givenDate,
                "receivedAt" to givenMoment,
                "reference" to givenReference,
            )))
            assertEquals(givenDate, system.get(payment, "receivedOn"))
            assertEquals(givenMoment, system.get(payment, "receivedAt"))
            assertEquals(givenReference, system.get(payment, "reference"))
        }
    }

    // ── a timestamp is the commit's ────────────────────────────────────────────

    private val stampedPayments = """
        act Payment {
            amount: decimal
            receivedOn: timestamp on create
        }
    """.trimIndent()

    @Test
    fun `a payment's timestamp on create is the moment of its commit`() {
        val system = systemOf(stampedPayments)
        system.scope {
            system.advance(600)
            val payment = accepted(system.commit("Payment", mapOf("amount" to BigDecimal("12.50"))))
            assertEquals(clockStart.plusSeconds(600), system.get(payment, "receivedOn"))
        }
    }

    @Test
    fun `a committer who supplies a timestamp field is refused, naming the field`() {
        val system = systemOf(stampedPayments)
        system.scope {
            val result = system.commit("Payment", mapOf(
                "amount" to BigDecimal("12.50"),
                "receivedOn" to Instant.parse("2025-12-24T15:30:00Z"),
            ))
            assertIs<CommitResult.Refused>(result)
            assertEquals("type: 'Payment.receivedOn' is a timestamp the commit fills; a committer cannot supply it", result.reason)
            assertEquals(emptyList(), system.instancesOf("Payment"))
        }
    }

    @Test
    fun `an act's timestamp on update moves forward when a rule writes a field of it`() {
        val system = systemOf("""
            act Invoice {
                code: text
                lastRemindedOn: Date?
                touchedAt: timestamp on update
            }
            transient act Remind { invoice: one Invoice }
            rule ApplyRemind when Remind { invoice.lastRemindedOn = today }
        """.trimIndent())
        system.scope {
            val invoice = accepted(system.commit("Invoice", mapOf("code" to "A-1")))
            assertEquals(clockStart, system.get(invoice, "touchedAt"))

            system.advanceDays(2)
            accepted(system.commit("Remind", mapOf("invoice" to invoice)))
            assertEquals(clockStart.plusSeconds(2 * 86_400), system.get(invoice, "touchedAt"))
        }
    }

    // ── a transient act: the rule it fires reads the filled values ─────────────

    @Test
    fun `the rule a transient act fires reads its filled starting value and timestamp`() {
        val system = systemOf("""
            transient act ReceivePayment {
                amount: decimal
                receivedOn: Date initially today
                receivedAt: timestamp on create
            }
            shape Receipt {
                amount: decimal
                receivedOn: Date
                receivedAt: DateTime
            }
            rule RecordReceipt when ReceivePayment {
                Receipt from { amount: amount, receivedOn: receivedOn, receivedAt: receivedAt }
            }
        """.trimIndent())
        system.scope {
            system.advanceDays(1)
            accepted(system.commit("ReceivePayment", mapOf("amount" to BigDecimal("12.50"))))
            val receipt = system.instancesOf("Receipt").single()
            assertEquals(clockStartDate.plusDays(1), system.get(receipt, "receivedOn"))
            assertEquals(clockStart.plusSeconds(86_400), system.get(receipt, "receivedAt"))
        }
    }

    // ── the generated input value ──────────────────────────────────────────────

    @Test
    fun `the generated input takes a field with a starting value as an optional parameter and no timestamp`() {
        val generated = Codegen.generate("""
            act Payment {
                amount: decimal
                receivedOn: Date initially today
                reference: uuid initially randomUUID
                recordedAt: timestamp on create
            }
        """.trimIndent(), "Payments")
        val inputParameters = generated.substringAfter("data class NewPayment(").substringBefore(") :")
        assertTrue("val receivedOn: LocalDate? = null," in inputParameters, inputParameters)
        assertTrue("val reference: UUID? = null," in inputParameters, inputParameters)
        assertTrue("recordedAt" !in inputParameters, inputParameters)
    }
}
