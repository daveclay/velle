package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the A4 advisory currently stands across the example specs: payments
 * uses the handled-once idiom and is clean; billing and membership still carry
 * the bare partition (working-docs/TODO.md tracks converting them — this test
 * is the inventory of exactly what that item owes).
 */
class AdvisorySweepTest {

    private fun a4(path: String): List<String> =
        Validator.advisories(File(path).readText()).filter { it.code == "A4" }.map { it.message }

    @Test
    fun `payments is anchored - no A4`() {
        assertEquals(emptyList(), a4("../examples/payments/payments.velle"))
    }

    @Test
    fun `billing still carries the bare due-change partition`() {
        val hits = a4("../examples/billing/billing.velle")
        assertEquals(2, hits.size, "got: $hits")
        assertTrue(hits.any { "ApplyDueChange" in it } && hits.any { "RecordDueChangeRefusal" in it }, "got: $hits")
    }

    @Test
    fun `membership still carries the bare assignment partition`() {
        val hits = a4("../examples/membership/membership.velle")
        assertEquals(2, hits.size, "got: $hits")
        assertTrue(hits.any { "ApplyAssignment" in it } && hits.any { "RecordAssignmentRefusal" in it }, "got: $hits")
    }
}
