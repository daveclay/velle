package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sixth fixture: moderation.velle — OQ37's delete machinery — parses and
 *  validates clean, with no advisories either. */
class ModerationSpecTest {

    @Test
    fun `moderation fixture validates with no diagnostics`() {
        val result = Validator.validate(File("../examples/moderation/moderation.velle").readText())
        assertEquals(emptyList(), result, "expected a clean fixture, got: $result")
    }

    @Test
    fun `moderation fixture carries no advisories`() {
        val result = Validator.advisories(File("../examples/moderation/moderation.velle").readText())
        assertEquals(emptyList(), result, "expected no advisories, got: $result")
    }
}
