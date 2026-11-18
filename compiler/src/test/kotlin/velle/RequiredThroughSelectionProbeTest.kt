package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OQ143: what the runtime does with a property declared required whose expression reads
 * through a selection, when the selection matches nothing or the field read off the
 * selected record is absent. The specs are those of
 * `working-docs/examples/survey/21-first-into-required-*.velle`, with `Row` an act so a
 * test can commit one. Each test prints what the runtime did, so the OQ file can quote it.
 */
class RequiredThroughSelectionProbeTest {

    private val uploadAndRow = """
        act Upload {
            note: text?
            rows: many Row = (Row where upload == this)
        }

        act Row {
            upload: one Upload
            key: text
            newKey: text?
        }

        shape Kept {
            other: one Other
            label: text
        }
    """

    private fun system(source: String): VelleSystem {
        val model = Model(Parser.parse(source))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return VelleSystem(model)
    }

    private fun report(label: String, outcome: Result<Any?>) {
        val text = outcome.fold(
            onSuccess = { "$it" },
            onFailure = { "${it::class.simpleName}: ${it.message}" },
        )
        println("PROBE $label -> $text")
    }

    private fun acceptedId(result: CommitResult): Any = (result as CommitResult.Accepted).id

    @Test
    fun `a required derived property over a selection that matches nothing is read`() {
        val sys = system(uploadAndRow + """
            act Other {
                upload: one Upload
                keyFromFirst: text = first(upload.rows by key).key
            }
        """)
        sys.scope {
            val upload = acceptedId(sys.commit("Upload", mapOf()))
            val committed = runCatching { sys.commit("Other", mapOf("upload" to upload)) }
            report("derived/no-row/commit", committed)
            assertIs<CommitResult.Accepted>(committed.getOrThrow())
            val other = sys.instancesOf("Other").single()
            val keyRead = runCatching { sys.get(other, "keyFromFirst") }
            report("derived/no-row/read", keyRead)
            // nothing refuses the read: the property declared `text` holds no text
            assertNull(keyRead.getOrThrow())
        }
    }

    @Test
    fun `a rule condition compares a required derived property that is absent`() {
        val sys = system(uploadAndRow + """
            act Other {
                upload: one Upload
                keyFromFirst: text = first(upload.rows by key).key
            }
            shape Flagged {
                other: one Other
            }
            shape Unflagged = Other where keyFromFirst != "PLOT-1" and not exists Flagged for this
            rule Flag when Unflagged {
                Flagged from { other: this }
            }
        """)
        sys.scope {
            val upload = acceptedId(sys.commit("Upload", mapOf()))
            report("derived/no-row/compared", runCatching { sys.commit("Other", mapOf("upload" to upload)) })
            println("PROBE derived/no-row/compared -> flagged=${sys.instancesOf("Flagged").size}")
            // `!=` holds on the absent value, so the rule fires on a key nobody supplied
            assertEquals(1, sys.instancesOf("Flagged").size)
        }
    }

    @Test
    fun `a rule stores a required derived property that is absent`() {
        val sys = system(uploadAndRow + """
            act Other {
                upload: one Upload
                keyFromFirst: text = first(upload.rows by key).key
            }
            shape Unkept = Other where not exists Kept for this
            rule Keep when Unkept {
                Kept from { other: this, label: keyFromFirst }
            }
        """)
        sys.scope {
            val upload = acceptedId(sys.commit("Upload", mapOf()))
            val committed = runCatching { sys.commit("Other", mapOf("upload" to upload)) }
            report("derived/no-row/stored", committed)
            println("PROBE derived/no-row/stored -> kept=${sys.instancesOf("Kept").size}")
            // the absence is refused only where it is stored, and the reason names the reader
            val refused = assertIs<CommitResult.Refused>(committed.getOrThrow())
            assertTrue(refused.reason.contains("'Kept.label' is required and 'keyFromFirst' is absent"), refused.reason)
        }
    }

    @Test
    fun `a rule stores an optional field read off a selected record that exists`() {
        val sys = system(uploadAndRow + """
            act Other {
                upload: one Upload
            }
            shape Unkept = Other where not exists Kept for this
            rule Keep when Unkept {
                Kept from { other: this, label: first(upload.rows by key).newKey }
            }
        """)
        sys.scope {
            val upload = acceptedId(sys.commit("Upload", mapOf()))
            sys.commit("Row", mapOf("upload" to upload, "key" to "PLOT-1"))
            val committed = runCatching { sys.commit("Other", mapOf("upload" to upload)) }
            report("from/row-exists/optional-field-absent", committed)
            println("PROBE from/row-exists/optional-field-absent -> kept=${sys.instancesOf("Kept").size}")
            // the selection matched a row; the reason says it matched nothing
            val refused = assertIs<CommitResult.Refused>(committed.getOrThrow())
            assertTrue(refused.reason.endsWith("the selection matched nothing (Reference §14)"), refused.reason)
        }
    }
}
