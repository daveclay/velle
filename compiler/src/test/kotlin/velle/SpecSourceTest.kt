package velle

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A running system offers the text of its spec, comments included, when it was built from one ([VelleSystem.source]). */
class SpecSourceTest {

    private val spec = """
        -- a customer signs up with a name
        act Customer {
            name: text
        }
    """.trimIndent()

    private class CustomersSystem(spec: String) : GeneratedSystem(spec, Instant.parse("2026-01-01T09:00:00Z"))

    @Test
    fun `a generated system offers its spec's text`() {
        assertEquals(spec, CustomersSystem(spec).system.source)
    }

    @Test
    fun `a system built from a model alone has no text to offer`() {
        assertNull(VelleSystem(Model(Parser.parse(spec))).source)
    }
}
