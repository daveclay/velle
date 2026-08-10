package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The partition-drift exhibit: clean on the required checks (the hazard is
 * semantic, not an error), and the A4 advisory fires on exactly the two
 * bare-partition rules — the handled-once family passes because its rules
 * disarm their own triggers.
 */
class PartitionDriftSpecTest {

    private val source = File("../examples/partition-drift/partition_drift.velle").readText()

    @Test
    fun `partition-drift fixture validates with no required diagnostics`() {
        val result = Validator.validate(source)
        assertEquals(emptyList(), result, "expected a clean fixture, got: $result")
    }

    @Test
    fun `A4 flags the bare partition rules and only those`() {
        val advisories = Validator.advisories(source)
        assertEquals(2, advisories.size, "got: $advisories")
        assertTrue(advisories.all { it.code == "A4" }, "got: $advisories")
        assertTrue(advisories.any { "ApplyBareEdit" in it.message }, "got: $advisories")
        assertTrue(advisories.any { "RefuseBareEdit" in it.message }, "got: $advisories")
    }
}
