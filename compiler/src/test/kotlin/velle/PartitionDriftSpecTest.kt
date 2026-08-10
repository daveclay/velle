package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The partition-drift exhibit parses and validates clean — which is part of
 * the point: the bare-partition hazard it demonstrates is semantic, invisible
 * to v0's checks (a candidate advisory, working-docs/TODO.md).
 */
class PartitionDriftSpecTest {

    @Test
    fun `partition-drift fixture validates with no diagnostics`() {
        val result = Validator.validate(File("../examples/partition-drift/partition_drift.velle").readText())
        assertEquals(emptyList(), result, "expected a clean fixture, got: $result")
    }
}
