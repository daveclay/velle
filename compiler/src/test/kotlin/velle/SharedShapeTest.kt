package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A shape taken by other shapes with `is` (Reference §9, "Sharing a field
 * across shapes"): the taken shape's declarations are one declaration on
 * every taker, the taken shape is a static union of its takers, and a field
 * it declares infers one inverse named from it.
 */
class SharedShapeTest {

    private fun codes(source: String): List<String> = Validator.validate(source).map { it.code }
    private fun diags(source: String): List<Diagnostic> = Validator.validate(source)
    private fun messages(source: String): List<String> = diags(source).map { it.message }

    private val dated = """
        act Site { name: text unique }
        shape Dated { due: Date }
        act SiteVisit is Dated { site: one Site, visitor: text }
        act PlotInspection is Dated { site: one Site, plot: text }
        shape Overdue = Dated where due < today
        shape Reminder { about: one Dated, sentOn: Date }
        rule RemindOverdue when Overdue on Daily { Reminder from { about: this, sentOn: today } }
    """.trimIndent()

    private val entryChanges = """
        act Entry { name: text unique, changeCount: integer = count(entryChanges) }
        shape EntryChange { entry: one Entry }
        act RenameEntry is EntryChange { newName: text }
        act MoveEntry is EntryChange { newSite: text }
        act MergeEntry is EntryChange { into: one Entry }
        shape Touch { entry: one Entry, touchedOn: DateTime }
        rule TouchEntry when EntryChange { Touch from { entry: this.entry, touchedOn: now } }
    """.trimIndent()

    // ── the model ────────────────────────────────────────────────────────────

    @Test
    fun `a taken shape is a static union of its takers, and its field is one declaration on each`() {
        assertEquals(emptyList(), diags(dated))
        val model = Model(Parser.parse(dated))
        assertTrue(model.isStaticUnion("Dated"))
        assertEquals(setOf("SiteVisit", "PlotInspection"), model.basesOf("Dated"))
        assertEquals("Dated", model.membersOf("SiteVisit").getValue("due").owner)
        assertEquals("Dated", model.membersOf("PlotInspection").getValue("due").owner)
        assertTrue("due" in model.membersOf("Dated"), "the shared field is read over the taken shape")
        assertTrue("site" !in model.membersOf("Dated"), "two declarations that agree are still not shared")
        assertTrue("Dated" !in model.shapes, "no records of its own")
    }

    @Test
    fun `a reference on a taken shape infers one inverse named from it, and a taker's own reference is named from the taker`() {
        assertEquals(emptyList(), diags(entryChanges))
        val model = Model(Parser.parse(entryChanges))
        val entry = model.membersOf("Entry")
        assertEquals("EntryChange", entry.getValue("entryChanges").inverse?.shape)
        assertEquals("MergeEntry", entry.getValue("mergeEntrys").inverse?.shape)
        assertTrue("renameEntrys" !in entry, "the taken field infers nothing under the taker's name")
    }

    @Test
    fun `a chain takes everything along it, and a declaration reached twice is taken once`() {
        val src = """
            shape Dated { due: Date }
            shape Scheduled is Dated { slot: text }
            act SiteVisit is Scheduled, Dated { visitor: text }
            shape Overdue = Dated where due < today
            shape Late = Scheduled where due < today
        """.trimIndent()
        assertEquals(emptyList(), diags(src))
        val model = Model(Parser.parse(src))
        assertEquals("Dated", model.membersOf("SiteVisit").getValue("due").owner)
        assertEquals("Scheduled", model.membersOf("SiteVisit").getValue("slot").owner)
        assertEquals(setOf("SiteVisit"), model.basesOf("Dated"))
        assertEquals(setOf("SiteVisit"), model.basesOf("Scheduled"))
    }

    // ── the refusals ─────────────────────────────────────────────────────────

    @Test
    fun `a cycle of takes is refused naming both lines`() {
        val src = """
            shape A is B { x: text }
            shape B is A { y: text }
        """.trimIndent()
        assertTrue(messages(src).any { "'A' is B" in it && "'B' is A" in it }, "got: ${diags(src)}")
    }

    @Test
    fun `a taken shape cannot be created and cannot be an act`() {
        val created = dated + "\nact Ping { site: one Site }\nrule Mint when Ping { Dated from { due: today } }\n"
        assertTrue(messages(created).any { "'Dated'" in it && "SiteVisit" in it }, "got: ${diags(created)}")
        val asAct = """
            act Dated { due: Date }
            act SiteVisit is Dated { visitor: text }
        """.trimIndent()
        assertTrue(messages(asAct).any { "'Dated'" in it && "SiteVisit" in it }, "got: ${diags(asAct)}")
    }

    @Test
    fun `a taker redeclaring a shared field, or taking two shapes that declare one name, is refused`() {
        val redeclared = """
            shape Dated { due: Date }
            act SiteVisit is Dated { due: Date, visitor: text }
        """.trimIndent()
        assertTrue(messages(redeclared).any { "'due'" in it && "Dated" in it }, "got: ${diags(redeclared)}")
        val twice = """
            shape Dated { due: Date }
            shape Deadlined { due: Date }
            act SiteVisit is Dated, Deadlined { visitor: text }
        """.trimIndent()
        assertTrue(messages(twice).any { "'due'" in it && "Dated" in it && "Deadlined" in it }, "got: ${diags(twice)}")
    }

    @Test
    fun `a field typed with a taken shape that a transient act takes is refused, and a closure over its inverse carries every taker`() {
        val typed = """
            act Entry { name: text unique }
            shape EntryChange { entry: one Entry }
            transient act RenameEntry is EntryChange { newName: text }
            shape Touch { change: one EntryChange }
            rule TouchEntry when EntryChange { Touch from { change: this } }
        """.trimIndent()
        assertTrue("V17" in codes(typed), "got: ${diags(typed)}")
        assertTrue(messages(typed).any { "'Touch.change'" in it && "RenameEntry" in it }, "got: ${diags(typed)}")
        val closure = """
            act Entry { name: text unique } with entryChanges
            shape EntryChange { entry: one Entry }
            shape RenameEntry is EntryChange { newName: text }
            shape MoveEntry is EntryChange { newSite: text }
        """.trimIndent()
        assertEquals(emptyList(), diags(closure))
        // one edge over the taken shape, its parts of every taker (Reference §6, §9)
        val edge = Model(Parser.parse(closure)).closures.getValue("Entry").single()
        assertEquals("EntryChange", edge.partShape)
        assertEquals("entry", edge.backRef)
        assertEquals(setOf("RenameEntry", "MoveEntry"), Model(Parser.parse(closure)).basesOf(edge.partShape).toSet())
    }

    @Test
    fun `a rule over a taken shape answers each transient act that takes it`() {
        val src = """
            act Entry { name: text unique }
            shape EntryChange { entry: one Entry }
            transient act RenameEntry is EntryChange { newName: text }
            transient act MoveEntry is EntryChange { newSite: text }
            shape Touch { entry: one Entry, touchedOn: DateTime }
            rule TouchEntry when EntryChange { Touch from { entry: this.entry, touchedOn: now } }
        """.trimIndent()
        assertEquals(emptyList(), diags(src))
    }

    // ── the runtime ──────────────────────────────────────────────────────────

    private fun system(source: String): VelleSystem {
        val d = Validator.validate(source)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(Parser.parse(source)))
    }

    private fun VelleSystem.mustCommit(shape: String, vararg fields: Pair<String, Any?>): Long {
        val r = commit(shape, fields.toMap())
        return assertIs<CommitResult.Accepted>(r, "commit of $shape refused: $r").id
    }

    @Test
    fun `a rule over a taken shape fires for a record of each taker, and the refinement over it reads the shared field`() {
        val sys = system(dated)
        sys.scope {
            val site = sys.mustCommit("Site", "name" to "north field")
            // the runtime's clock starts on 2026-01-01
            val visit = sys.mustCommit("SiteVisit", "site" to site, "visitor" to "Ada", "due" to java.time.LocalDate.of(2025, 12, 31))
            val inspection = sys.mustCommit("PlotInspection", "site" to site, "plot" to "A1", "due" to java.time.LocalDate.of(2026, 1, 2))
            assertTrue(sys.isMember(visit, "Dated"))
            assertTrue(sys.isMember(inspection, "Dated"))
            assertTrue(sys.isMember(visit, "Overdue"))
            assertTrue(!sys.isMember(inspection, "Overdue"))
            sys.tick("Daily")
            assertEquals(listOf(visit), sys.instancesOf("Reminder").map { sys.get(it, "about") })
        }
    }

    @Test
    fun `the inverse of a shared reference holds every taker's records`() {
        val sys = system(entryChanges)
        sys.scope {
            val entry = sys.mustCommit("Entry", "name" to "oak")
            val other = sys.mustCommit("Entry", "name" to "elm")
            val rename = sys.mustCommit("RenameEntry", "entry" to entry, "newName" to "old oak")
            val move = sys.mustCommit("MoveEntry", "entry" to entry, "newSite" to "south")
            val merge = sys.mustCommit("MergeEntry", "entry" to other, "into" to entry)
            assertEquals(setOf(rename, move), (sys.get(entry, "entryChanges") as List<*>).toSet())
            assertEquals(listOf(merge), sys.get(other, "entryChanges"))
            assertEquals(listOf(merge), sys.get(entry, "mergeEntrys"))
            assertEquals(java.math.BigDecimal(2), sys.get(entry, "changeCount"))
            assertEquals(3, sys.instancesOf("Touch").size)
        }
    }

    @Test
    fun `a never over a taken shape narrows to a transient taker and refuses at that taker's door`() {
        val src = """
            act Entry { name: text unique }
            shape EntryChange { entry: one Entry }
            transient act RenameEntry is EntryChange { newName: text }
            transient act MoveEntry is EntryChange { newSite: text }
            never (EntryChange where (this is RenameEntry and this.newName == "") or (this is MoveEntry and this.newSite == ""))
            shape Touch { entry: one Entry, touchedOn: DateTime }
            rule TouchEntry when EntryChange { Touch from { entry: this.entry, touchedOn: now } }
        """.trimIndent()
        assertEquals(emptyList(), diags(src))
        val sys = system(src)
        sys.scope {
            val entry = sys.mustCommit("Entry", "name" to "oak")
            assertIs<CommitResult.Refused>(sys.commit("RenameEntry", mapOf("entry" to entry, "newName" to "")))
            assertIs<CommitResult.Refused>(sys.commit("MoveEntry", mapOf("entry" to entry, "newSite" to "")))
            sys.mustCommit("RenameEntry", "entry" to entry, "newName" to "old oak")
            sys.mustCommit("MoveEntry", "entry" to entry, "newSite" to "south")
            assertEquals(2, sys.instancesOf("Touch").size)
        }
        // a never over an unrelated shape still may not name the act
        val elsewhere = src + "\nact Note { body: text }\nnever (Note where count(RenameEntry) > 0)"
        assertTrue("V17" in codes(elsewhere), "got: ${diags(elsewhere)}")
    }

    @Test
    fun `a rule over a taken shape fires inside a transient taker's own commit, and narrows to the taker's own fields`() {
        val src = """
            act Entry { name: text unique }
            shape EntryChange { entry: one Entry }
            transient act RenameEntry is EntryChange { newName: text }
            transient act MoveEntry is EntryChange { newSite: text }
            shape Touch { entry: one Entry, note: text, touchedOn: DateTime }
            rule TouchEntry when EntryChange {
                Touch from { entry: this.entry, note: if this is RenameEntry then this.newName else this.newSite, touchedOn: now }
            }
        """.trimIndent()
        val sys = system(src)
        sys.scope {
            val entry = sys.mustCommit("Entry", "name" to "oak")
            sys.mustCommit("RenameEntry", "entry" to entry, "newName" to "old oak")
            sys.mustCommit("MoveEntry", "entry" to entry, "newSite" to "south")
            assertEquals(listOf(entry, entry), sys.instancesOf("Touch").map { sys.get(it, "entry") })
            assertEquals(listOf("old oak", "south"), sys.instancesOf("Touch").map { sys.get(it, "note") })
            assertEquals(emptyList<Long>(), sys.instancesOf("RenameEntry"), "a transient act is not kept")
        }
    }
}

