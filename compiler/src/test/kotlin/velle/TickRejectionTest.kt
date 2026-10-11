package velle

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A tick rejected by a constraint (`evaluation.md`, "Ticks"). V29 refuses a
 * scheduled rule whose record a constraint consults, so in a valid spec the
 * case that remains is the one validation cannot see: a constraint that reads
 * the clock is violated by aging alone, and a firing on that state meets it
 * at the transaction end. The tick throws [TickRejected] carrying the same
 * [Rejection] an act's refusal returns, with the rule and the record it ran
 * for in the act's place.
 */
class TickRejectionTest {

    private val spec = """
        expose shape Site { name: text }
        expose shape Permit { site: one Site  expiresOn: Date }
        shape PermittedSite = Site where exists (Permit where site == this and expiresOn >= today)

        expose shape Planting { site: one Site  plantedOn: Date }
        never (Planting where not site is PermittedSite)

        shape ScoutingReminder { planting: one Planting  sentOn: Date }
        shape UnremindedPlanting = Planting where not exists (scoutingReminders)
        rule RemindScouting when UnremindedPlanting on Nightly {
            ScoutingReminder from { planting: this, sentOn: today }
        }
    """.trimIndent()

    private fun newSystem(): VelleSystem {
        assertEquals(emptyList(), Validator.validate(spec), "the spec validates: V29 does not fire, no rule produces the forbidden state")
        return VelleSystem(Model(Parser.parse(spec)), startTime = Instant.parse("2026-10-01T09:00:00Z"))
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long =
        assertIs<CommitResult.Accepted>(commit(shape, fields.toMap())).id

    @Test
    fun `a constraint that reads the clock rejects the nightly firing once the permit lapses`() {
        val sys = newSystem()
        val site = sys.mustCommit("Site", "name" to "north field")
        sys.mustCommit("Permit", "site" to site, "expiresOn" to LocalDate.of(2026, 10, 5))
        val planting = sys.mustCommit("Planting", "site" to site, "plantedOn" to LocalDate.of(2026, 10, 2))

        sys.setTime(Instant.parse("2026-10-07T02:00:00Z"))
        val e = assertFailsWith<TickRejected> { sys.tick("Nightly") }

        assertEquals(emptyList(), sys.instancesOf("ScoutingReminder"), "the firing rolled back")
        assertEquals("Nightly", e.schedule)
        val r = e.rejections.single()
        assertEquals(e.message, r.message)
        assertNull(r.act)
        assertEquals("RemindScouting", r.rule)
        assertEquals("Nightly", r.schedule)
        assertEquals(RecordRef("Planting", planting), r.subject?.ref)
        assertEquals(listOf("ScoutingReminder"), r.produced.map { it.shape })
        assertEquals(listOf(RecordRef("Planting", planting)), r.violating.map { it.ref })
        assertEquals("2026-10-07", r.today)
        assertEquals(
            """
            Nightly tick rejected: Planting#3 would be a Planting where not (site is PermittedSite).
              never #1: Planting where not (site is PermittedSite)
              rule RemindScouting for Planting#3: site = Site#1, plantedOn = 2026-10-02
              produced ScoutingReminder#4: planting = Planting#3, sentOn = 2026-10-07
              through Site#1: name = "north field"
              also Permit#2: site = Site#1, expiresOn = 2026-10-05
              evaluation:
                Planting#3.site = Site#1
                Permit#2.site = Site#1
                Permit#2.expiresOn = 2026-10-05
                Site#1 is not PermittedSite
              today: 2026-10-07
            """.trimIndent(),
            r.message,
        )

        // The next tick is refused the same way: the condition stands until the permit is renewed.
        assertFailsWith<TickRejected> { sys.tick("Nightly") }
        sys.mustCommit("Permit", "site" to site, "expiresOn" to LocalDate.of(2027, 10, 5))
        sys.tick("Nightly")
        assertEquals(1, sys.instancesOf("ScoutingReminder").size, "renewed, the firing lands")
    }
}
