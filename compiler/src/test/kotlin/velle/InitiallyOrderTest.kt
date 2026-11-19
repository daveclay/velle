package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * One `initially` reading another field of the same record that has an `initially` of its own
 * (Reference §5; checks.md V14): the starting values are computed in the order they read one
 * another, wherever each is declared, and two that read each other are refused. The last test
 * pins what an `initially` selecting over the record's own shape does today, which OQ146 asks about.
 */
class InitiallyOrderTest {

    private val patientAndRequest = """
        act Patient {
            name: text
        }
        act OpenChart {
            patient: one Patient
        }
        rule Open when OpenChart {
            Chart from { patient: patient }
        }
    """

    private fun diagnosticsOf(chart: String): List<String> = Validator.validate(patientAndRequest + chart).map { diagnostic -> "$diagnostic" }

    /** The fields of the one chart opened for a new patient. */
    private fun openedChart(chart: String, fields: List<String>): Map<String, Any?> {
        assertEquals(emptyList(), Validator.validate(patientAndRequest + chart))
        val sys = VelleSystem(Model(Parser.parse(patientAndRequest + chart)))
        return sys.scope {
            val patient = assertIs<CommitResult.Accepted>(sys.commit("Patient", mapOf("name" to "Ada"))).id
            assertIs<CommitResult.Accepted>(sys.commit("OpenChart", mapOf("patient" to patient)))
            val chartId = sys.instancesOf("Chart").single()
            fields.associateWith { field -> sys.get(chartId, field) }
        }
    }

    private fun whole(value: Any?): Long = (value as Number).toLong()

    @Test
    fun `an initially reads a field whose own initially is declared after it`() {
        val chart = openedChart("""
            shape Chart {
                patient: one Patient
                doubled: integer initially base * 2
                base: integer initially 5
            }
        """, listOf("doubled", "base"))
        assertEquals(10L, whole(chart["doubled"]))
        assertEquals(5L, whole(chart["base"]))
    }

    @Test
    fun `an initially reads a later field through a derived property`() {
        val chart = openedChart("""
            shape Chart {
                patient: one Patient
                doubled: integer initially twice
                twice: integer = base * 2
                base: integer initially 5
            }
        """, listOf("doubled"))
        assertEquals(10L, whole(chart["doubled"]))
    }

    @Test
    fun `a chain of three is computed from its end`() {
        val chart = openedChart("""
            shape Chart {
                patient: one Patient
                third: integer initially this.second + 1
                second: integer initially first + 1
                first: integer initially 1
            }
        """, listOf("third"))
        assertEquals(3L, whole(chart["third"]))
    }

    @Test
    fun `an initially reads a later collection that starts empty`() {
        val chart = openedChart("""
            shape Chart {
                patient: one Patient
                tagCount: integer initially count(tags)
                tags: many text initially empty
            }
        """, listOf("tagCount"))
        assertEquals(0L, whole(chart["tagCount"]))
    }

    @Test
    fun `two initially values that read each other are refused`() {
        assertEquals(listOf("[V14] 'Chart.doubled' starts from 'Chart.base', which starts from 'Chart.doubled', so neither can be computed first; " +
            "start one of them without the other"), diagnosticsOf("""
            shape Chart {
                patient: one Patient
                doubled: integer initially base * 2
                base: integer initially doubled + 1
            }
        """))
    }

    @Test
    fun `two initially values that read each other through a derived property are refused`() {
        assertEquals(listOf("[V14] 'Chart.doubled' starts from 'Chart.base', which starts from 'Chart.doubled', so neither can be computed first; " +
            "start one of them without the other"), diagnosticsOf("""
            shape Chart {
                patient: one Patient
                doubled: integer initially twice
                twice: integer = base * 2
                base: integer initially doubled + 1
            }
        """))
    }

    @Test
    fun `an initially that reads its own field is refused`() {
        assertEquals(listOf("[V14] 'Chart.total' starts from its own value, which it does not have until it has started; start it without 'total'"), diagnosticsOf("""
            shape Chart {
                patient: one Patient
                total: integer initially total + 1
            }
        """))
    }

    @Test
    fun `an initially reading the same field on another record is accepted`() {
        val chart = openedChart("""
            shape Chart {
                patient: one Patient
                depth: integer initially if latestElsewhere is some then latestElsewhere.depth + 1 else 1
                latestElsewhere: one Chart? = latest(Chart where patient != this.patient by depth)
            }
        """, listOf("depth"))
        assertEquals(1L, whole(chart["depth"]))
    }

    @Test
    fun `an initially selecting over the record's own shape meets the new record and throws out of the commit`() {
        val source = patientAndRequest + """
            shape Chart {
                patient: one Patient
                sequence: integer initially if exists (Chart where patient == this.patient) then latest(Chart where patient == this.patient by sequence).sequence + 1 else 1
            }
        """
        assertEquals(emptyList(), Validator.validate(source))
        val sys = VelleSystem(Model(Parser.parse(source)))
        sys.scope {
            val patient = assertIs<CommitResult.Accepted>(sys.commit("Patient", mapOf("name" to "Ada"))).id
            // the chart being created is already among `Chart` when its `sequence` is computed, with no `sequence` to order by
            val error = assertFailsWith<VelleRuntimeError> { sys.commit("OpenChart", mapOf("patient" to patient)) }
            assertEquals("cannot order VNone against VNone", error.message)
        }
    }
}
