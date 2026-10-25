package velle

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The `uuid` and `duration` scalars (Reference §5) and the text functions
 *  `uppercase`, `trim`, `length` beside `lowercase`. */
class UuidDurationTest {

    private val spec = """
        act Ticket {
            title: text
            reference: uuid initially randomUUID
            externalId: uuid?
            sla: duration
            opened: DateTime
            received: Date
            due: Date = received + sla
            closesBy: DateTime = opened + sla
            upper: text = uppercase(title)
            trimmed: text = trim(title)
            titleLength: integer = length(title)
        }
        shape Rushed = Ticket where sla < 2 days
        shape LongTitle = Ticket where length(title) > 10
        never (Ticket where sla < 1 hours)
    """.trimIndent()

    private fun system(): VelleSystem {
        val diags = Validator.validate(spec)
        check(diags.isEmpty()) { diags.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.ticket(sla: Duration, title: String = "  Printer jam  ", externalId: UUID? = null): Long {
        val fields = mutableMapOf<String, Any?>(
            "title" to title, "sla" to sla,
            "opened" to Instant.parse("2026-01-15T09:00:00Z"), "received" to LocalDate.of(2026, 1, 15),
        )
        if (externalId != null) fields["externalId"] = externalId
        return assertIs<CommitResult.Accepted>(commit("Ticket", fields), "refused").id
    }

    @Test
    fun `a duration field moves a Date and a DateTime, and orders`() {
        val sys = system()
        val t = sys.ticket(Duration.ofDays(3))
        assertEquals(Duration.ofDays(3), sys.get(t, "sla"))
        assertEquals(LocalDate.of(2026, 1, 18), sys.get(t, "due"))
        assertEquals(Instant.parse("2026-01-18T09:00:00Z"), sys.get(t, "closesBy"))
        assertTrue(!sys.isMember(t, "Rushed"))
        val rushed = sys.ticket(Duration.ofHours(36))
        assertTrue(sys.isMember(rushed, "Rushed"))
        assertEquals(Duration.ofHours(36), sys.get(rushed, "sla"))
    }

    @Test
    fun `a never over a duration refuses at the boundary`() {
        val sys = system()
        val r = sys.commit("Ticket", mapOf("title" to "x", "sla" to Duration.ofMinutes(30),
            "opened" to Instant.parse("2026-01-15T09:00:00Z"), "received" to LocalDate.of(2026, 1, 15)))
        assertIs<CommitResult.Refused>(r)
    }

    @Test
    fun `a Date plus a duration that is not whole days is a runtime error, as the Reference says`() {
        val sys = system()
        val t = sys.ticket(Duration.ofHours(36))
        val e = runCatching { sys.get(t, "due") }.exceptionOrNull()
        assertTrue(e is VelleRuntimeError && "clock time" in e.message!!, e.toString())
    }

    @Test
    fun `randomUUID mints a uuid for a uuid field, and a text literal compares to one`() {
        val src = spec + "\nshape Flagged = Ticket where externalId == \"00000000-0000-4000-8000-000000000001\""
        check(Validator.validate(src).isEmpty()) { Validator.validate(src).toString() }
        val sys = VelleSystem(Model(Parser.parse(src)))
        val id = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val t = sys.ticket(Duration.ofDays(1), externalId = id)
        assertIs<UUID>(sys.get(t, "reference"))
        assertEquals(id, sys.get(t, "externalId"))
        assertTrue(sys.isMember(t, "Flagged"))
        val other = sys.ticket(Duration.ofDays(1), externalId = UUID.randomUUID())
        assertTrue(!sys.isMember(other, "Flagged"))
        // the boundary takes a UUID, not its text
        val r = sys.commit("Ticket", mapOf("title" to "x", "sla" to Duration.ofDays(1), "externalId" to id.toString(),
            "opened" to Instant.parse("2026-01-15T09:00:00Z"), "received" to LocalDate.of(2026, 1, 15)))
        assertIs<CommitResult.Refused>(r)
    }

    @Test
    fun `uppercase, trim, and length`() {
        val sys = system()
        val t = sys.ticket(Duration.ofDays(1))
        assertEquals("  PRINTER JAM  ", sys.get(t, "upper"))
        assertEquals("Printer jam", sys.get(t, "trimmed"))
        assertEquals(15, (sys.get(t, "titleLength") as Number).toInt())
        assertTrue(sys.isMember(t, "LongTitle"))
        assertTrue(!sys.isMember(sys.ticket(Duration.ofDays(1), title = "short"), "LongTitle"))
    }

    @Test
    fun `durations add and subtract, and two moments or two dates differ by a duration`() {
        val src = """
            act Job {
                started: DateTime
                finished: DateTime
                planned: Date
                done: Date
                grace: duration
                elapsed: duration = finished - started
                total: duration = elapsed + grace
                late: duration = done - planned
            }
        """
        check(Validator.validate(src).isEmpty()) { Validator.validate(src).toString() }
        val sys = VelleSystem(Model(Parser.parse(src)))
        val j = assertIs<CommitResult.Accepted>(sys.commit("Job", mapOf(
            "started" to Instant.parse("2026-01-15T09:00:00Z"), "finished" to Instant.parse("2026-01-15T10:30:00Z"),
            "planned" to LocalDate.of(2026, 1, 15), "done" to LocalDate.of(2026, 1, 20), "grace" to Duration.ofMinutes(30),
        ))).id
        assertEquals(Duration.ofMinutes(90), sys.get(j, "elapsed"))
        assertEquals(Duration.ofHours(2), sys.get(j, "total"))
        assertEquals(Duration.ofDays(5), sys.get(j, "late"))
    }

    @Test
    fun `F2 - the text functions take one text argument, and uuid is not ordered`() {
        fun f2(body: String) = Validator.validate("act T { name: text, n: integer, u: uuid }\nshape S = T where $body").filter { it.code == "F2" }
        assertTrue(f2("length(n) > 1").isNotEmpty(), "length of a number")
        assertTrue(f2("uppercase(name, name) == \"A\"").isNotEmpty(), "arity")
        assertTrue(f2("trim(name) == \"A\"").isEmpty(), f2("trim(name) == \"A\"").toString())
        assertTrue(f2("length(name) > 1").isEmpty(), f2("length(name) > 1").toString())
        assertTrue(f2("u < u").isNotEmpty(), "uuid has no order")
    }
}
