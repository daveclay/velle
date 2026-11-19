package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A stored field started by `initially` (Reference §5, §14; checks.md F2, A2), run through the
 * compiler and the runtime. A required `initially` through a selection is refused unless a test
 * shows the selection matches, and the runtime fails the firing for a required field its
 * `initially` left absent. The runtime tests build their model without validating, since no spec
 * that validates reaches the runtime check. The specs are those of
 * `working-docs/examples/survey/22-initially-*.velle`. The last test pins what an `initially`
 * reading a field declared after it does today, which OQ145 asks about.
 */
class InitiallySelectionProbeTest {

    private val patientAndVisit = """
        act Patient {
            name: text
            nickname: text?
            visits: many Visit = (Visit where patient == this)
        }
        act Visit {
            patient: one Patient
            seenOn: Date
        }
        act OpenChart {
            patient: one Patient
        }
    """

    private val chartOpenedByFirstVisit = patientAndVisit + """
        shape Chart {
            patient: one Patient
            opened: Date initially first(patient.visits by seenOn).seenOn
            openedYear: integer = year(opened)
        }
        rule Open when OpenChart {
            Chart from { patient: patient }
        }
    """

    private val chartReadingItsOwnNotes = patientAndVisit + """
        shape Chart {
            patient: one Patient
            notes: many Note = (Note where chart == this)
            firstNote: text initially first(notes by body).body
            noteCount: integer initially count(notes)
        }
        shape Note {
            chart: one Chart
            body: text
        }
        rule Open when OpenChart {
            Chart from { patient: patient } as chart
            Note from { chart: chart, body: "opened" }
        }
    """

    private val chartReadingALaterField = patientAndVisit + """
        shape Chart {
            patient: one Patient
            doubled: integer initially base * 2
            base: integer initially 5
        }
        rule Open when OpenChart {
            Chart from { patient: patient }
        }
    """

    private val chartCountingItsOwnNotes = chartReadingItsOwnNotes.replace("            firstNote: text initially first(notes by body).body\n", "")

    private fun assertNoErrorsOrAdvisories(source: String) {
        assertEquals(emptyList(), Validator.validate(source))
        assertEquals(emptyList(), Validator.advisories(source))
    }

    private fun system(source: String): VelleSystem = VelleSystem(Model(Parser.parse(source)))

    private fun VelleSystem.commitPatient(): Long =
        assertIs<CommitResult.Accepted>(commit("Patient", mapOf("name" to "Ada"))).id

    // ── the compiler ─────────────────────────────────────────────────────────

    @Test
    fun `a required initially through a selection nothing shows to match is refused`() {
        assertEquals(
            listOf("[F2] 'Chart.opened' is declared a Date and may start absent: 'first(patient.visits by seenOn)' may match nothing; declare it 'opened: Date?', " +
                "or start it where a test shows the selection matches, such as 'exists (patient.visits)' (Reference §10)"),
            Validator.validate(chartOpenedByFirstVisit).map { diagnostic -> "$diagnostic" })
    }

    @Test
    fun `a required initially reading a field declared optional is refused`() {
        val diagnostics = Validator.validate(patientAndVisit + """
            shape Chart {
                patient: one Patient
                label: text initially patient.nickname
            }
            rule Open when OpenChart {
                Chart from { patient: patient }
            }
        """)
        assertEquals(listOf("[F2] 'Chart.label' is declared a text and may start absent; declare it 'label: text?'"), diagnostics.map { diagnostic -> "$diagnostic" })
    }

    @Test
    fun `a required initially with the selection under an exists test validates`() {
        assertNoErrorsOrAdvisories(patientAndVisit + """
            shape Chart {
                patient: one Patient
                opened: Date initially if exists (patient.visits) then first(patient.visits by seenOn).seenOn else today
            }
            rule Open when OpenChart {
                Chart from { patient: patient }
            }
        """)
    }

    @Test
    fun `an initially reading the record's own collection draws an advisory`() {
        assertEquals(emptyList(), Validator.validate(chartCountingItsOwnNotes))
        assertEquals(
            listOf("[A2] 'Chart.noteCount' starts from 'notes', the Note records that point at this Chart. None can when the Chart is created, " +
                "so 'notes' is empty every time; write the value 'noteCount' starts with, or declare it with '=' to keep it current (Reference §5)"),
            Validator.advisories(chartCountingItsOwnNotes).map { diagnostic -> "$diagnostic" })
    }

    @Test
    fun `an initially reading a field whose own initially is declared later validates with no advisory`() {
        assertNoErrorsOrAdvisories(chartReadingALaterField)
    }

    // ── the runtime ──────────────────────────────────────────────────────────

    @Test
    fun `a selection that matches fills the required field`() {
        val sys = system(chartOpenedByFirstVisit)
        sys.scope {
            val patient = sys.commitPatient()
            sys.commit("Visit", mapOf("patient" to patient, "seenOn" to java.time.LocalDate.parse("2026-03-04")))
            assertIs<CommitResult.Accepted>(sys.commit("OpenChart", mapOf("patient" to patient)))
            val chart = sys.instancesOf("Chart").single()
            assertEquals(java.time.LocalDate.parse("2026-03-04"), sys.get(chart, "opened"))
            assertEquals(2026L, (sys.get(chart, "openedYear") as Number).toLong())
        }
    }

    @Test
    fun `a selection that matches nothing fails the firing and the act is refused`() {
        val sys = system(chartOpenedByFirstVisit)
        sys.scope {
            val patient = sys.commitPatient()
            val refused = assertIs<CommitResult.Refused>(sys.commit("OpenChart", mapOf("patient" to patient)))
            assertEquals("rule 'Open' did not fire: 'Chart.opened' is required and its 'initially', " +
                "'first(patient.visits by seenOn).seenOn', is absent (Reference §14)", refused.reason)
            assertEquals(0, sys.instancesOf("Chart").size)
            assertEquals(0, sys.instancesOf("OpenChart").size)
        }
    }

    @Test
    fun `the same selection written in the from block refuses the act`() {
        val sys = system(chartOpenedByFirstVisit.replace(
            "Chart from { patient: patient }",
            "Chart from { patient: patient, opened: first(patient.visits by seenOn).seenOn }"))
        sys.scope {
            val patient = sys.commitPatient()
            val refused = assertIs<CommitResult.Refused>(sys.commit("OpenChart", mapOf("patient" to patient)))
            assertTrue(refused.reason.startsWith("rule 'Open' did not fire: 'Chart.opened' is required"), refused.reason)
            assertEquals(0, sys.instancesOf("Chart").size)
        }
    }

    @Test
    fun `the record's own collection is empty when its initially is computed`() {
        val sys = system(chartCountingItsOwnNotes)
        sys.scope {
            val patient = sys.commitPatient()
            assertIs<CommitResult.Accepted>(sys.commit("OpenChart", mapOf("patient" to patient)))
            val chart = sys.instancesOf("Chart").single()
            // the note is created by the same firing, after the chart, so the chart starts with none
            assertEquals(1, sys.instancesOf("Note").size)
            assertEquals(0L, (sys.get(chart, "noteCount") as Number).toLong())
        }
    }

    @Test
    fun `a required initially selecting from the record's own collection fails the firing`() {
        val sys = system(chartReadingItsOwnNotes)
        sys.scope {
            val patient = sys.commitPatient()
            val refused = assertIs<CommitResult.Refused>(sys.commit("OpenChart", mapOf("patient" to patient)))
            assertTrue(refused.reason.startsWith("rule 'Open' did not fire: 'Chart.firstNote' is required and its 'initially'"), refused.reason)
            assertEquals(0, sys.instancesOf("Chart").size)
            assertEquals(0, sys.instancesOf("Note").size)
        }
    }

    @Test
    fun `an initially reading a field whose own initially is declared later throws out of the commit`() {
        val sys = system(chartReadingALaterField)
        sys.scope {
            val patient = sys.commitPatient()
            val error = assertFailsWith<VelleRuntimeError> { sys.commit("OpenChart", mapOf("patient" to patient)) }
            assertEquals("'*' on non-number", error.message)
        }
    }
}
