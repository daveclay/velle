package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The lookup pattern (wiki/patterns.md, "An act that names a record by its
 * values"): a selection written in a rule's `from`, finding the record an act's
 * identifying values mean, run through the runtime. The specs are those of
 * `working-docs/examples/survey/09-lookup-*.velle`. Each test prints what the
 * runtime did, so the survey README can quote it.
 */
class LookupProbeTest {

    private val entryAndSubmission = """
        act Entry {
            key: text
            initialLabel: text
            arrivals: many Arrival = (Arrival where entry == this)
        }

        act Submission {
            entryKey: text
            newLabel: text?
        }

        shape Arrival {
            submission: one Submission
            entry: one Entry
        }

        shape Unpaired = Submission where not exists Arrival for this
    """

    private fun system(src: String): VelleSystem {
        val model = Model(Parser.parse(src))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return VelleSystem(model)
    }

    private fun report(label: String, r: Result<CommitResult>) {
        val text = r.fold(
            onSuccess = { "$it" },
            onFailure = { "${it::class.simpleName}: ${it.message}" },
        )
        println("PROBE $label -> $text")
    }

    @Test
    fun `first with both prohibitions, a key that exists, pairs the submission with its entry`() {
        val sys = system(entryAndSubmission + """
            never (Submission where not exists (Entry where key == this.entryKey))
            never (Entry where count(Entry where key == this.key) > 1)
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: first(Entry where key == this.entryKey by key) }
            }
        """)
        sys.scope {
            val plot = assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-0417", "initialLabel" to "a"))).id
            val sub = assertIs<CommitResult.Accepted>(sys.commit("Submission", mapOf("entryKey" to "PLOT-0417"))).id
            val arrivals = sys.instancesOf("Arrival")
            assertEquals(1, arrivals.size)
            assertEquals(sub, sys.get(arrivals.single(), "submission"))
            assertEquals(plot, sys.get(arrivals.single(), "entry"))
            println("PROBE first/good-key -> Accepted; Arrival(submission=$sub, entry=$plot)")
        }
    }

    @Test
    fun `first with both prohibitions, a key no entry has, is refused at the door`() {
        val sys = system(entryAndSubmission + """
            never (Submission where not exists (Entry where key == this.entryKey))
            never (Entry where count(Entry where key == this.key) > 1)
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: first(Entry where key == this.entryKey by key) }
            }
        """)
        sys.scope {
            val r = runCatching { sys.commit("Submission", mapOf("entryKey" to "PLOT-9999")) }
            report("first/bad-key/with-existence-never", r)
            assertIs<CommitResult.Refused>(r.getOrThrow())
            assertEquals(0, sys.instancesOf("Arrival").size)
        }
    }

    @Test
    fun `first with no existence prohibition, a key no entry has`() {
        val sys = system(entryAndSubmission + """
            never (Entry where count(Entry where key == this.key) > 1)
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: first(Entry where key == this.entryKey by key) }
            }
        """)
        sys.scope {
            val r = runCatching { sys.commit("Submission", mapOf("entryKey" to "PLOT-9999")) }
            report("first/bad-key/no-existence-never", r)
            // `first` over an empty selection is absent, and the `from` would store the absence
            // in a required `one Entry`. The firing fails, the commit is undone, and the caller
            // is told (Reference §14). The existence `never` is what keeps this case out.
            val e = r.exceptionOrNull()
            assertIs<VelleRuntimeError>(e)
            assertTrue(e.message!!.contains("'Arrival.entry' is required"), e.message)
            assertEquals(0, sys.instancesOf("Submission").size)
            assertEquals(0, sys.instancesOf("Arrival").size)
        }
    }

    @Test
    fun `first with no uniqueness prohibition and two entries sharing the key`() {
        val sys = system(entryAndSubmission + """
            never (Submission where not exists (Entry where key == this.entryKey))
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: first(Entry where key == this.entryKey by key) }
            }
        """)
        sys.scope {
            sys.commit("Entry", mapOf("key" to "PLOT-0417", "initialLabel" to "a"))
            sys.commit("Entry", mapOf("key" to "PLOT-0417", "initialLabel" to "b"))
            val r = runCatching { sys.commit("Submission", mapOf("entryKey" to "PLOT-0417")) }
            report("first/two-entries-share-key", r)
            println("PROBE first/two-entries-share-key -> submissions=${sys.instancesOf("Submission").size} arrivals=${sys.instancesOf("Arrival").size}")
        }
    }

    @Test
    fun `a second rule reads the entry through the pairing record`() {
        val sys = system(entryAndSubmission + """
            never (Submission where not exists (Entry where key == this.entryKey))
            never (Entry where count(Entry where key == this.key) > 1)
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: first(Entry where key == this.entryKey by key) }
            }
            shape Outcome {
                submission: one Submission
                entry: one Entry
                label: text
            }
            shape Relabel = Submission where newLabel is some and exists Arrival for this and not exists Outcome for this
            rule ApplyRelabel when Relabel {
                Outcome from { submission: this, entry: (Arrival for this).entry, label: newLabel }
            }
        """)
        sys.scope {
            val plot = assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-0417", "initialLabel" to "a"))).id
            val r = runCatching { sys.commit("Submission", mapOf("entryKey" to "PLOT-0417", "newLabel" to "b")) }
            report("read-through-pairing", r)
            val outcomes = sys.instancesOf("Outcome")
            println("PROBE read-through-pairing -> arrivals=${sys.instancesOf("Arrival").size} outcomes=${outcomes.size} outcome.entry=${outcomes.map { sys.get(it, "entry") }}")
            assertIs<CommitResult.Accepted>(r.getOrThrow())
            assertEquals(1, outcomes.size)
            assertEquals(plot, sys.get(outcomes.single(), "entry"))
        }
    }

    @Test
    fun `the singular for query with a text subject is refused by the validator`() {
        val diags = Validator.validate(entryAndSubmission + """
            never (Submission where not exists (Entry where key == this.entryKey))
            never (Entry where count(Entry where key == this.key) > 1)
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: (Entry for this.entryKey) }
            }
        """)
        println("PROBE for/text-subject -> $diags")
        // the subject after `for` is a record; a text there is the F2 that names the selection to write
        assertTrue(diags.any { it.code == "F2" && it.message.contains("the subject after 'for' is a record") }, "expected an F2, got: $diags")
    }
}
