package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pharmacy spec, rewritten onto the actor (Reference §22), validates clean. */
class PharmacyValidatesTest {
    @Test
    fun `pharmacy validates with no diagnostics`() {
        val d = Validator.validate(File("../examples/pharmacy/pharmacy.velle").readText())
        assertEquals(emptyList(), d, d.joinToString("\n"))
    }
}
