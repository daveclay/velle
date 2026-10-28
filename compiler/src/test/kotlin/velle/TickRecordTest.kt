package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A tick leaves one `Tick` row behind, through the ordinary commit path, and the
 * previous tick a `leaving` rule compares against is the latest such row in the
 * store (Reference §17; OQ71 D3). So a record that left between two ticks is
 * noticed at the second even when the process restarted in between, and a spec
 * may not take the name for itself.
 */
class TickRecordTest {

    private val spec = """
        act Permit { site: text  expiresOn: Date }
        shape Valid = Permit where expiresOn >= today
        shape Lapse { permit: one Permit  noticedOn: Date }
        rule NoticeLapse when leaving Valid on Nightly { Lapse from { permit: this, noticedOn: today } }
    """.trimIndent()

    private fun system(store: MemoryStore, start: Instant) =
        VelleSystem(Model(Parser.parse(spec)), startTime = start).also { it.connect(store, store) }

    @Test
    fun `a tick records itself as a Tick row with its schedule and moment`() {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val store = MemoryStore(model)
        val at = Instant.parse("2026-10-01T02:00:00Z")
        val sys = system(store, at)
        sys.tick("Nightly")
        val ticks = store.fetchAll(Model.TICK)
        assertEquals(1, ticks.size)
        sys.scope {
            val id = sys.query(Query(Model.TICK, orderBy = listOf(OrderDatum("at")))).ids.single()
            assertEquals("Nightly", sys.get(id, "schedule"))
            assertEquals(at, sys.get(id, "at"))
        }
    }

    @Test
    fun `a leaver between two ticks is noticed after a restart, because the previous tick is in the store`() {
        val store = MemoryStore(Model(Parser.parse(spec)))
        val first = system(store, Instant.parse("2026-10-01T02:00:00Z"))
        first.scope { assertIs<CommitResult.Accepted>(first.commit("Permit", mapOf("site" to "north", "expiresOn" to LocalDate.of(2026, 10, 5)))) }
        first.tick("Nightly") // the permit is valid: nothing leaves
        assertEquals(0, store.fetchAll("Lapse").size)

        // the process is down over the 5th; a new one starts on the 7th over the same store
        val restarted = system(store, Instant.parse("2026-10-07T02:00:00Z"))
        restarted.tick("Nightly")
        assertEquals(1, store.fetchAll("Lapse").size, "the permit left Valid since the previous tick, which the store remembers")

        restarted.tick("Nightly") // the next night: it left before the previous tick, so nothing new
        assertEquals(1, store.fetchAll("Lapse").size)
        assertEquals(3, store.fetchAll(Model.TICK).size)
    }

    @Test
    fun `a spec may not declare a shape named Tick`() {
        val model = Model(Parser.parse("shape Tick { label: text }\n" + spec))
        val f1 = model.diagnostics.filter { it.code == "F1" }
        assertEquals(1, f1.size, model.diagnostics.toString())
        assertTrue("Velle's own shape" in f1.single().message, f1.single().message)
    }
}
