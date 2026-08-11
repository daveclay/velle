package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The A4 inventory across the example specs: every act partition in the corpus
 * is now drift-free — payments anchors with the handled-once idiom, and
 * billing/membership mark their request acts `expose transient` (the partition
 * then evaluates once, at the act's commit). The exhibit's deliberately broken
 * bare family is asserted separately (PartitionDriftSpecTest).
 */
class AdvisorySweepTest {

    private fun a4(path: String): List<String> =
        Validator.advisories(File(path).readText()).filter { it.code == "A4" }.map { it.message }

    @Test
    fun `payments is anchored - no A4`() {
        assertEquals(emptyList(), a4("../examples/payments/payments.velle"))
    }

    @Test
    fun `billing's due-change act is transient - no A4`() {
        assertEquals(emptyList(), a4("../examples/billing/billing.velle"))
    }

    @Test
    fun `membership's assignment act is transient - no A4`() {
        assertEquals(emptyList(), a4("../examples/membership/membership.velle"))
    }
}
