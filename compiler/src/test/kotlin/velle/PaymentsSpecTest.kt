package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The third fixture: payments.velle parses and validates clean. */
class PaymentsSpecTest {

    @Test
    fun `payments fixture validates with no diagnostics`() {
        val result = Validator.validate(File("../examples/payments/payments.velle").readText())
        assertEquals(emptyList(), result, "expected a clean fixture, got: $result")
    }
}
