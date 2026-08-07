package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The second fixture: membership.velle parses and validates clean. */
class MembershipSpecTest {

    @Test
    fun `membership fixture validates with no diagnostics`() {
        val result = Validator.validate(File("../membership.velle").readText())
        assertEquals(emptyList(), result, "expected a clean fixture, got:\n" + result.joinToString("\n"))
    }
}
