package velle

import velle.ext.Accepts
import velle.ext.Parameter
import velle.ext.Returns
import velle.ext.Signature
import velle.ext.VelleFunction
import velle.ext.builtin.DurationType
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A `duration` holds whole seconds (Reference §5). A span with a part of a second that
 * arrives with an act is refused before anything is written, naming the field; a query
 * compared against one is refused naming the member; a registered function that returns
 * one fails the read; a span a store hands back with a part of a second, and the span
 * between two moments, keep their whole seconds, the part dropped toward zero.
 */
class DurationWholeSecondsTest {

    private val spec = """
        act Shift {
            worker: text
            breakTaken: duration
            laps: many duration
            breakMinutes: integer = minutes(breakTaken)
        }
    """.trimIndent()

    private fun system(specText: String = spec, registry: TypeRegistry = TypeRegistry.default): VelleSystem {
        val model = Model(Parser.parse(specText), registry)
        val errors = model.diagnostics.filterNot { diagnostic -> diagnostic.advisory }
        check(errors.isEmpty()) { errors.toString() }
        return VelleSystem(model)
    }

    private fun VelleSystem.shift(breakTaken: Duration, laps: List<Duration> = emptyList()): CommitResult =
        commit("Shift", mapOf("worker" to "Ada", "breakTaken" to breakTaken, "laps" to laps))

    @Test
    fun `an act that supplies a part of a second is refused naming the field, and nothing is written`() {
        val sys = system()
        sys.scope {
            val refused = assertIs<CommitResult.Refused>(sys.shift(Duration.ofMillis(1500)))
            assertEquals("type: 'Shift.breakTaken' holds whole seconds; PT1.5S has a part of a second", refused.reason)
            assertEquals(emptyList(), sys.instancesOf("Shift"))
        }
    }

    @Test
    fun `an act that supplies a part of a second before zero is refused naming the field`() {
        val sys = system()
        sys.scope {
            val refused = assertIs<CommitResult.Refused>(sys.shift(Duration.ofMillis(-1500)))
            assertEquals("type: 'Shift.breakTaken' holds whole seconds; PT-1.5S has a part of a second", refused.reason)
            assertEquals(emptyList(), sys.instancesOf("Shift"))
        }
    }

    @Test
    fun `a part of a second in one of a many is refused naming the field`() {
        val sys = system()
        sys.scope {
            val refused = assertIs<CommitResult.Refused>(sys.shift(Duration.ofSeconds(90), laps = listOf(Duration.ofSeconds(30), Duration.ofMillis(30_250))))
            assertEquals("type: 'Shift.laps' holds whole seconds; PT30.25S has a part of a second", refused.reason)
            assertEquals(emptyList(), sys.instancesOf("Shift"))
        }
    }

    @Test
    fun `a dry run gives the same refusal`() {
        val sys = system()
        sys.scope {
            val refused = assertIs<CheckResult.WouldBeRefused>(sys.check("Shift", mapOf("worker" to "Ada", "breakTaken" to Duration.ofMillis(90_500))))
            assertEquals("type: 'Shift.breakTaken' holds whole seconds; PT1M30.5S has a part of a second", refused.reason)
        }
    }

    @Test
    fun `a whole-second span is accepted, before zero too, however it was built`() {
        val sys = system()
        sys.scope {
            val ninety = assertIs<CommitResult.Accepted>(sys.shift(Duration.ofMillis(90_000))).id
            assertEquals(Duration.ofSeconds(90), sys.get(ninety, "breakTaken"))
            assertEquals(1, (sys.get(ninety, "breakMinutes") as Number).toInt())
            val beforeZero = assertIs<CommitResult.Accepted>(sys.shift(Duration.ofSeconds(-90))).id
            assertEquals(Duration.ofSeconds(-90), sys.get(beforeZero, "breakTaken"))
        }
    }

    @Test
    fun `a span a store hands back with a part of a second reads back with the part dropped toward zero`() {
        val model = Model(Parser.parse(spec))
        val sys = VelleSystem(model)
        val store = MemoryStore(model)
        sys.connect(store, store, store)
        val shift = sys.scope { assertIs<CommitResult.Accepted>(sys.shift(Duration.ofSeconds(90))).ref!! }
        assertEquals(Duration.ofSeconds(1), readBackAfterTheStoreHolds(sys, store, shift, Duration.ofMillis(1500)))
        assertEquals(Duration.ofSeconds(-1), readBackAfterTheStoreHolds(sys, store, shift, Duration.ofMillis(-1500)))
    }

    /** What `breakTaken` of [shift] reads as once the engineer's own code has written [storedSpan] to the store beside the runtime. */
    private fun readBackAfterTheStoreHolds(sys: VelleSystem, store: MemoryStore, shift: Ref.Persisted, storedSpan: Duration): Any? {
        store.onCommit(CommitSet(created = emptyList(), assigned = listOf(CommitSet.Assign(shift, "breakTaken", storedSpan))))
        return sys.scope { sys.get(sys.idOf(shift), "breakTaken") }
    }

    @Test
    fun `the span between two moments with parts of a second drops the part toward zero`() {
        val sys = system("""
            act Lap {
                startedAt: DateTime
                endedAt: DateTime
                took: duration = endedAt - startedAt
            }
        """.trimIndent())
        sys.scope {
            val forward = assertIs<CommitResult.Accepted>(sys.commit("Lap", mapOf(
                "startedAt" to Instant.parse("2026-01-01T09:00:00Z"), "endedAt" to Instant.parse("2026-01-01T09:00:01.500Z")))).id
            assertEquals(Duration.ofSeconds(1), sys.get(forward, "took"))
            val backward = assertIs<CommitResult.Accepted>(sys.commit("Lap", mapOf(
                "startedAt" to Instant.parse("2026-01-01T09:00:01.500Z"), "endedAt" to Instant.parse("2026-01-01T09:00:00Z")))).id
            assertEquals(Duration.ofSeconds(-1), sys.get(backward, "took"))
        }
    }

    /** A registered function that adds half a second to a span: an engineer's bug the runtime must not hide. */
    private class AddHalfASecond : VelleFunction {
        override val name = "addHalfASecond"
        override val namespace = "velle.test"
        override val signatures = listOf(Signature(
            parameters = listOf(Parameter("span", Accepts.Is(DurationType::class))),
            result = Returns.Is(DurationType::class),
            apply = { _, arguments -> (arguments[0].value as Duration).plusMillis(500) },
        ))
    }

    @Test
    fun `a registered function that returns a part of a second fails the read, saying so`() {
        val sys = system(
            """
                act Shift {
                    breakTaken: duration
                    padded: duration = addHalfASecond(breakTaken)
                }
            """.trimIndent(),
            TypeRegistry(emptyList(), listOf(AddHalfASecond())),
        )
        sys.scope {
            val shift = assertIs<CommitResult.Accepted>(sys.commit("Shift", mapOf("breakTaken" to Duration.ofSeconds(90)))).id
            val failure = assertFailsWith<VelleRuntimeError> { sys.get(shift, "padded") }
            assertEquals("a function returned PT1M30.5S, which has a part of a second; a duration holds whole seconds", failure.message)
        }
    }

    @Test
    fun `a query compared against a part of a second is refused naming the member`() {
        val breakTaken = DurationField(listOf("breakTaken"))
        val refusal = assertFailsWith<IllegalArgumentException> { breakTaken lt Duration.ofMillis(1500) }
        assertEquals("'breakTaken' holds whole seconds; PT1.5S has a part of a second", refusal.message)
        assertTrue(runCatching { breakTaken lt Duration.ofSeconds(-2) }.isSuccess, "a whole-second span before zero is a query's literal")
    }
}
