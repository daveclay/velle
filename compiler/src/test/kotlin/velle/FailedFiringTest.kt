package velle

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A rule firing that fails on the books inside an act's transaction refuses the act,
 * naming the rule and the reason (Reference §10, §14; `evaluation.md`, "Errors, refusals,
 * and retry"): here a `latest` two records tie for. Nothing of the act or its consequence
 * is stored, and the refusal carries no `Rejection`, since no `never` spoke.
 */
class FailedFiringTest {

    private val spec = """
        act Entry {
            key: text unique
        }

        act Arrival {
            entry: one Entry
            at: DateTime
        }

        act Audit {
            entry: one Entry
        }

        shape AuditNote {
            arrival: one Arrival
            audit: one Audit
        }

        rule NoteLatestArrival when Audit {
            AuditNote from { arrival: latest(Arrival where entry == this.entry by at), audit: this }
        }
    """.trimIndent()

    @Test
    fun `a selection that does not single out a winner refuses the act naming the rule`() {
        assertEquals(emptyList(), Validator.validate(spec))
        val sys = VelleSystem(Model(Parser.parse(spec)))
        sys.scope {
            val entry = assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-1"))).id
            val sameMoment = Instant.parse("2026-11-13T08:00:00Z")
            assertIs<CommitResult.Accepted>(sys.commit("Arrival", mapOf("entry" to entry, "at" to sameMoment)))
            assertIs<CommitResult.Accepted>(sys.commit("Arrival", mapOf("entry" to entry, "at" to sameMoment)))
            val refused = assertIs<CommitResult.Refused>(sys.commit("Audit", mapOf("entry" to entry)))
            assertEquals("rule 'NoteLatestArrival' did not fire: latest(... by at) does not discriminate: " +
                "2 instances share the ordering datum — declare a further criterion (Reference §10)", refused.reason)
            assertEquals(null, refused.rejection)
            assertEquals(0, sys.instancesOf("AuditNote").size)
            assertEquals(0, sys.instancesOf("Audit").size)
        }
    }
}
