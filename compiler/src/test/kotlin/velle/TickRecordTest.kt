package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tick is not a record. The store keeps one moment per schedule, the moment
 * the schedule last ran ([TickMemory]), and the previous tick a `leaving` rule
 * compares against is that moment (Reference §17). So a record that left
 * between two ticks is noticed at the second even when the process restarted in
 * between, and `Tick` is a name a spec may use for a shape of its own.
 */
class TickRecordTest {

    private val spec = """
        act Permit { site: text  expiresOn: Date }
        shape Valid = Permit where expiresOn >= today
        shape Lapse { permit: one Permit  noticedOn: Date }
        rule NoticeLapse when leaving Valid on Nightly { Lapse from { permit: this, noticedOn: today } }
        shape Renewal { permit: one Permit }
        rule OfferRenewal when Valid on Weekly { Renewal from { permit: this } }
    """.trimIndent()

    private fun system(store: MemoryStore, start: Instant) =
        VelleSystem(Model(Parser.parse(spec)), startTime = start).also { it.connect(store, store, store) }

    @Test
    fun `a schedule that has never run has no latest tick`() {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        val store = MemoryStore(model)
        val permits = system(store, Instant.parse("2026-10-01T02:00:00Z"))
        assertNull(permits.latestTick("Nightly"))
        assertNull(store.latestTick("Nightly"))
    }

    @Test
    fun `a tick leaves the moment its schedule ran with the store, and no record of any shape`() {
        val model = Model(Parser.parse(spec))
        val store = MemoryStore(model)
        val ranAt = Instant.parse("2026-10-01T02:00:00Z")
        val permits = system(store, ranAt)
        permits.tick("Nightly")
        assertEquals(ranAt, permits.latestTick("Nightly"))
        assertEquals(ranAt, store.latestTick("Nightly"))
        assertTrue("Tick" !in model.shapes, "Velle declares no shape for a tick")
        for (shape in model.shapes.keys) assertEquals(0, store.fetchAll(shape).size, "the tick created no $shape")
    }

    @Test
    fun `each schedule keeps its own latest tick, replaced by its next`() {
        val store = MemoryStore(Model(Parser.parse(spec)))
        val firstNight = Instant.parse("2026-10-01T02:00:00Z")
        val permits = system(store, firstNight)
        permits.tick("Nightly")
        val weeklyRun = firstNight.plusSeconds(3_600)
        permits.setTime(weeklyRun)
        permits.tick("Weekly")
        assertEquals(firstNight, permits.latestTick("Nightly"), "Weekly's tick left Nightly's moment alone")
        assertEquals(weeklyRun, permits.latestTick("Weekly"))

        val secondNight = firstNight.plusSeconds(86_400)
        permits.setTime(secondNight)
        permits.tick("Nightly")
        assertEquals(secondNight, permits.latestTick("Nightly"), "the next tick replaces the moment")
        assertEquals(weeklyRun, permits.latestTick("Weekly"))
    }

    @Test
    fun `a leaver between two ticks is noticed after a restart, because the previous tick is in the store`() {
        val store = MemoryStore(Model(Parser.parse(spec)))
        val first = system(store, Instant.parse("2026-10-01T02:00:00Z"))
        first.scope { assertIs<CommitResult.Accepted>(first.commit("Permit", mapOf("site" to "north", "expiresOn" to LocalDate.of(2026, 10, 5)))) }
        first.tick("Nightly") // the permit is valid: nothing leaves
        assertEquals(0, store.fetchAll("Lapse").size)

        // the process is down over the 5th; a new one starts on the 7th over the same store
        val restartedAt = Instant.parse("2026-10-07T02:00:00Z")
        val restarted = system(store, restartedAt)
        restarted.tick("Nightly")
        assertEquals(1, store.fetchAll("Lapse").size, "the permit left Valid since the previous tick, which the store remembers")

        restarted.tick("Nightly") // the next night: it left before the previous tick, so nothing new
        assertEquals(1, store.fetchAll("Lapse").size)
        assertEquals(restartedAt, store.latestTick("Nightly"), "three ticks, one moment kept")
    }

    @Test
    fun `a spec may declare a shape named Tick, which is its own`() {
        val model = Model(Parser.parse("shape Tick { label: text }\n" + spec))
        assertEquals(emptyList(), model.diagnostics.map { it.message })
        assertEquals(listOf("label"), model.membersOf("Tick").keys.filter { memberName -> memberName != "id" })
    }
}
