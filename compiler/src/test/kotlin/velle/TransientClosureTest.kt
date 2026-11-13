package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A transient act with a closure (Reference §4 "Transient acts", §6 "Inline
 * part creation"): the parts arrive as values in the act's commit, are read by
 * what runs at that commit, and are removed with the act when the transaction
 * closes. A part is a transient shape in every sense V17 has; V18 proves
 * coverage over the act alone. The spec is the survey system of
 * `working-docs/examples/survey/README.md`: a submission arrives with its
 * readings, and the rule records an entry with one value per reading.
 */
class TransientClosureTest {

    private val submission = """
        transient act Submission {
            kindName: text
        } with readings

        shape Reading {
            submission: one Submission
            fieldName: text
            value: text
        }

        shape Entry {
            kindName: text
        }

        shape EntryValue {
            entry: one Entry
            fieldName: text
            value: text
        }

        never (Submission where count(readings) == 0)

        rule RecordEntry when Submission {
            Entry from { kindName: kindName } as entry
            for each reading in readings {
                EntryValue from { entry: entry, fieldName: reading.fieldName, value: reading.value }
            }
        }
    """.trimIndent()

    private fun system(spec: String): VelleSystem {
        val diagnostics = Validator.validate(spec)
        check(diagnostics.isEmpty()) { diagnostics.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun codes(spec: String) = Validator.validate(spec).map { it.code }

    private fun messages(spec: String) = Validator.validate(spec).joinToString("\n") { "[${it.code}] ${it.message}" }

    // ── D1: the combination is declarable ─────────────────────────────────────

    @Test
    fun `a transient act with a closure validates clean`() {
        assertEquals(emptyList(), Validator.validate(submission))
    }

    // ── D2: a part is a transient shape in every sense V17 has ───────────────

    @Test
    fun `a refinement over a part, a never over it, and a rule on it at the commit are allowed`() {
        val spec = submission + """

            shape BlankReading = Reading where value == ""
            never (Reading where fieldName == "")
            rule NoteBlank when BlankReading {
                Entry from { kindName: this.submission.kindName }
            }
        """
        assertEquals(emptyList(), Validator.validate(spec))
    }

    @Test
    fun `V17 - a durable shape may not reference a part of a transient closure`() {
        val spec = submission + """

            shape Flag { reading: one Reading }
        """
        assertTrue("V17" in codes(spec), messages(spec))
    }

    @Test
    fun `V17 - a part's name may appear in no expression`() {
        val spec = submission + """

            shape Busy = Entry where count(Reading) > 3
        """
        assertTrue("V17" in codes(spec), messages(spec))
    }

    @Test
    fun `V17 - no after-commit firing on a part`() {
        val spec = submission + """

            rule Later when Reading after commit {
                Entry from { kindName: this.fieldName }
            }
        """
        assertTrue("V17" in codes(spec), messages(spec))
    }

    @Test
    fun `V17 - a rule may not create a part of a transient closure`() {
        val spec = submission + """

            act Nudge { kindName: text }
            rule Fake when Nudge {
                Submission from { kindName: kindName }
            }
        """
        assertTrue("V17" in codes(spec), messages(spec))
    }

    // ── D3: the act alone owes the answer ─────────────────────────────────────

    @Test
    fun `V18 - the act alone owes an answer, a part owes none`() {
        val answeredByBareRule = submission.replace("never (Submission where count(readings) == 0)\n", "")
        assertEquals(emptyList(), Validator.validate(answeredByBareRule))
        val unanswered = answeredByBareRule.replace("rule RecordEntry when Submission {", "rule RecordEntry when (Submission where kindName == \"plot\") {")
        assertEquals(listOf("V18"), codes(unanswered), messages(unanswered))
    }

    // ── the runtime: the parts are read at the commit and gone after it ───────

    @Test
    fun `the act's consequences persist, the act and its parts do not`() {
        val sys = system(submission)
        sys.scope {
            val result = sys.commit(
                "Submission",
                mapOf("kindName" to "plot"),
                mapOf(
                    "readings" to listOf(
                        mapOf("fieldName" to "soil", "value" to "clay"),
                        mapOf("fieldName" to "slope", "value" to "steep"),
                    ),
                ),
            )
            assertIs<CommitResult.Accepted>(result, "commit refused: $result")
            val entries = sys.instancesOf("Entry")
            assertEquals(1, entries.size)
            assertEquals("plot", sys.get(entries.single(), "kindName"))
            val copied = sys.instancesOf("EntryValue").map { valueId ->
                Triple(sys.get(valueId, "entry"), sys.get(valueId, "fieldName"), sys.get(valueId, "value"))
            }.toSet()
            assertEquals(
                setOf(
                    Triple(entries.single(), "soil", "clay"),
                    Triple(entries.single(), "slope", "steep"),
                ),
                copied,
            )
            assertEquals(emptyList(), sys.instancesOf("Submission"))
            assertEquals(emptyList(), sys.instancesOf("Reading"))
        }
    }

    @Test
    fun `a never over the act reading its parts refuses the whole closure at the door`() {
        val sys = system(submission)
        sys.scope {
            val result = sys.commit("Submission", mapOf("kindName" to "plot"), mapOf("readings" to emptyList<Map<String, Any?>>()))
            assertIs<CommitResult.Refused>(result, "an empty submission should be refused: $result")
            assertEquals(emptyList(), sys.instancesOf("Entry"))
        }
    }
}
