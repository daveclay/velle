package velle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** An act described from the model ([ActDescription]), pinned against the example specs. */
class ActDescriptionTest {

    private fun example(name: String) = Model(Parser.parse(File("../examples/$name/$name.velle").readText()))

    @Test
    fun `a payment is described with its refusal and the rule it starts`() {
        assertEquals(
            """
            Commits a SubmitPayment. It leaves no record of its own.
            A SubmitPayment is refused when its amount is at most 0.
            Committing it can start rule RecordPayment, which creates a Payment.
            Every prohibition in the spec is checked when its work is done; a refusal names the prohibition it broke.
            """.trimIndent(),
            ActDescription(example("billing")).of("SubmitPayment"),
        )
    }

    @Test
    fun `a hire is described with the actor who commits it`() {
        assertEquals(
            """
            Commits a HireStaff. It leaves no record of its own. It is committed by an Administrator: the caller must be one.
            Committing it can start rule RecordHire, which creates a Staff.
            Every prohibition in the spec is checked when its work is done; a refusal names the prohibition it broke.
            """.trimIndent(),
            ActDescription(example("pharmacy")).of("HireStaff"),
        )
    }
}
