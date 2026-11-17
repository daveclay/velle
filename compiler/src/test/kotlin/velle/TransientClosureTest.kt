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

    // ── variants of a part (Reference §9, "Sharing a field across shapes") ────
    //
    // A batch arrives with create rows and update rows. Each row kind takes the
    // fields the two share from one shape and declares its own; the values of a
    // row point at the shared shape, so one value shape serves both kinds.

    private val batch = """
        act Item { key: text unique }

        transient act Batch {
            note: text
        } with { createRows with { rowValues }, updateRows with { rowValues } }

        shape Row {
            identifier: text
        }

        shape CreateRow is Row {
            batch: one Batch
            newKey: text
        }

        shape UpdateRow is Row {
            batch: one Batch
            key: text
        }

        shape RowValue {
            row: one Row
            name: text
        }

        shape Made { key: text, valueCount: integer }

        rule HandleBatch when Batch {
            for each row in createRows { Made from { key: row.newKey, valueCount: count(row.rowValues) } }
            for each row in updateRows { Made from { key: row.key, valueCount: count(row.rowValues) } }
        }
    """.trimIndent()

    @Test
    fun `a part may point at the shape its closure's variants take`() {
        assertEquals(emptyList(), Validator.validate(batch), messages(batch))
    }

    @Test
    fun `V17 - a durable shape may not point at the shape a closure's variants take`() {
        val spec = batch + """

            shape Flag { row: one Row }
        """
        assertEquals(listOf("V17", "V17"), codes(spec), messages(spec))
        assertTrue("transient part 'CreateRow' is Row" in messages(spec), messages(spec))
    }

    @Test
    fun `each variant's values are read through the shared shape at the commit`() {
        val sys = system(batch)
        sys.scope {
            sys.commit("Item", mapOf("key" to "T1"))
            val result = sys.commit(
                "Batch",
                mapOf("note" to "nightly"),
                mapOf(
                    "createRows" to listOf(mapOf("identifier" to "c1", "newKey" to "N1",
                        "rowValues" to listOf(mapOf("name" to "a"), mapOf("name" to "b")))),
                    "updateRows" to listOf(mapOf("identifier" to "u1", "key" to "T1",
                        "rowValues" to listOf(mapOf("name" to "c")))),
                ),
            )
            assertIs<CommitResult.Accepted>(result, "commit refused: $result")
            val made = sys.instancesOf("Made").map { madeId -> "${sys.get(madeId, "key")}:${sys.get(madeId, "valueCount")}" }.toSet()
            assertEquals(setOf("N1:2", "T1:1"), made)
            assertEquals(emptyList(), sys.instancesOf("RowValue"))
        }
    }

    @Test
    fun `an input refusal of a part names the committer's own mistake, never the back-reference`() {
        val sys = system(batch)
        sys.scope {
            fun refusal(row: Map<String, Any?>): String {
                val result = sys.commit("Batch", mapOf("note" to "nightly"), mapOf("createRows" to listOf(row)))
                return assertIs<CommitResult.Refused>(result, "expected a refusal: $result").reason
            }
            assertEquals("type: 'CreateRow' requires [newKey] (inline part)", refusal(mapOf("identifier" to "c1")))
            assertEquals("type: 'CreateRow' does not declare [key] (inline part)",
                refusal(mapOf("identifier" to "c1", "newKey" to "N1", "key" to "T1")))
            val actResult = sys.commit("Batch", mapOf("note" to "nightly", "foo" to "bar"), emptyMap())
            assertEquals("type: 'Batch' does not declare [foo]", assertIs<CommitResult.Refused>(actResult).reason)
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

    // ── the batch's fields read once, on the shape the variants take ──────────
    //
    // The spelling V22's message offers for a mixed inverse: `batch` declared on
    // the shared shape, and one view per variant on the act. The views read the
    // act's own parts inside its commit, as the inferred inverse does.

    private val batchReadThroughRow = """
        transient act Batch {
            note: text
            createRows: many CreateRow = (CreateRow where batch == this)
            updateRows: many UpdateRow = (UpdateRow where batch == this)
        } with { createRows with { rowValues }, updateRows with { rowValues } }

        shape Row {
            batch: one Batch
            identifier: text
            note: text = batch.note
        }

        shape CreateRow is Row { newKey: text }
        shape UpdateRow is Row { key: text }

        shape RowValue {
            row: one Row
            name: text
            note: text = row.note
        }

        shape Made { key: text, note: text, valuesWithNote: integer }

        rule HandleBatch when Batch {
            for each row in createRows { Made from { key: row.newKey, note: row.note, valuesWithNote: count(row.rowValues where note == "nightly") } }
            for each row in updateRows { Made from { key: row.key, note: row.note, valuesWithNote: count(row.rowValues where note == "nightly") } }
        }
    """.trimIndent()

    @Test
    fun `a view on the act over one variant of its parts is the spelling V22 offers, and it validates`() {
        assertEquals(emptyList(), Validator.validate(batchReadThroughRow), messages(batchReadThroughRow))
    }

    @Test
    fun `each variant reads the batch through the shared shape, and a value reads it through its row`() {
        val sys = system(batchReadThroughRow)
        sys.scope {
            val result = sys.commit(
                "Batch",
                mapOf("note" to "nightly"),
                mapOf(
                    "createRows" to listOf(mapOf("identifier" to "c1", "newKey" to "N1",
                        "rowValues" to listOf(mapOf("name" to "a"), mapOf("name" to "b")))),
                    "updateRows" to listOf(mapOf("identifier" to "u1", "key" to "T1",
                        "rowValues" to listOf(mapOf("name" to "c")))),
                ),
            )
            assertIs<CommitResult.Accepted>(result, "commit refused: $result")
            val made = sys.instancesOf("Made")
                .map { madeId -> "${sys.get(madeId, "key")}:${sys.get(madeId, "note")}:${sys.get(madeId, "valuesWithNote")}" }
                .toSet()
            assertEquals(setOf("N1:nightly:2", "T1:nightly:1"), made)
            assertEquals(emptyList(), sys.instancesOf("RowValue"))
        }
    }

    @Test
    fun `V17 - a durable shape's view over a part is still a read across commits`() {
        val spec = batchReadThroughRow + """

            shape Audit { note: text, rows: many CreateRow = (CreateRow where batch.note == note) }
        """
        assertTrue(codes(spec).contains("V17"), messages(spec))
    }
}
