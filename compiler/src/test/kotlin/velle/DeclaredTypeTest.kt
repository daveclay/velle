package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A declared type against the expression that computes it (Reference §7; checks.md F2):
 * a derived property, a captured property, an `initially`, a field in a `from` block, and
 * an assignment are refused when the expression makes another type, or may be absent where
 * the declaration is required. A derived property may state no type and take its
 * expression's; a cycle of such properties is refused.
 */
class DeclaredTypeTest {

    private fun diagnostics(spec: String): List<String> = Validator.validate(Parser.parse(spec.trimIndent())).map { it.toString() }

    private fun system(spec: String): VelleSystem {
        val decls = Parser.parse(spec.trimIndent())
        val diags = Validator.validate(decls)
        assertEquals(emptyList(), diags)
        return VelleSystem(Model(decls))
    }

    private fun assertOne(diags: List<String>, vararg phrases: String) {
        assertEquals(1, diags.size, diags.toString())
        for (phrase in phrases) assertTrue(phrase in diags.single(), "expected '$phrase' in: ${diags.single()}")
    }

    @Test
    fun `a derived property computed as another type is refused`() {
        assertOne(diagnostics("""
            act Kind {
                parentName: text?
                label: text = (parentName is none)
            }
        """), "[F2]", "'Kind.label' is declared a text and computed as a boolean", "declare it 'label: boolean', or compute a text")
        assertOne(diagnostics("""
            act Kind {
                count: integer
                label: text = count * 2
            }
        """), "'Kind.label' is declared a text and computed as a decimal")
        assertOne(diagnostics("""
            act Kind {
                name: text
                seen: Date = now
            }
        """), "'Kind.seen' is declared a Date and computed as a DateTime")
        assertOne(diagnostics("""
            act Kind {
                name: text
                names: many text = name
            }
        """), "'Kind.names' is declared a many of text and computed as a text")
        assertOne(diagnostics("""
            act Kind {
                name: text
                flagged: boolean = if name == "x" then "yes" else "no"
            }
        """), "'Kind.flagged' is declared a boolean and computed as a text")
    }

    @Test
    fun `the number types are one kind for the check`() {
        assertEquals(emptyList(), diagnostics("""
            act Kind {
                total: decimal
                half: integer = total / 2
                whole: decimal = 5
            }
        """))
    }

    @Test
    fun `a required declaration refuses an expression that may be absent, and a narrowed one passes`() {
        assertOne(diagnostics("""
            act Kind {
                parentName: text?
                parent: text = parentName
            }
        """), "'Kind.parent' is declared a text and may be absent", "declare it 'parent: text?'", "is some")
        assertOne(diagnostics("""
            act Kind {
                parentName: text?
                parent: text = if parentName is some then "named" else none
            }
        """), "'Kind.parent' is declared a text and may be absent")
        assertEquals(emptyList(), diagnostics("""
            act Kind {
                parentName: text?
                parent: text? = parentName
                parentOrRoot: text = if parentName is some then parentName else "root"
                rootOrParent: text = if parentName is none then "root" else parentName
            }
        """))
    }

    @Test
    fun `an initially, a from block, a captured property, and an assignment are checked the same way`() {
        assertOne(diagnostics("""
            act Kind {
                name: text
                applied: boolean initially 5
            }
        """), "'Kind.applied' is declared a boolean and starts as a decimal", "declare it 'applied: decimal', or start it with a boolean")
        assertOne(diagnostics("""
            act Reading {
                efficacy: decimal
            }
            shape Verdict {
                reading: one Reading
                effective: boolean
            }
            rule RecordVerdict when (Reading where not exists Verdict for this) {
                Verdict from { reading: this, effective: "yes" }
            }
        """), "rule 'RecordVerdict' supplies 'Verdict.effective'", "declared a boolean, given a text; supply a boolean")
        assertOne(diagnostics("""
            act Note {
                locked: boolean initially false
            }
            act Lock {
                note: one Note
            }
            rule ApplyLock when Lock {
                note.locked = 5
            }
        """), "rule 'ApplyLock' assigns 'Note.locked'", "declared a boolean, given a decimal; assign a boolean")
        assertOne(diagnostics("""
            act Kind {
                name: text
                ready: boolean initially false
            }
            shape ReadyKind = Kind where ready {
                captured since: Date = now
            }
        """), "'ReadyKind.since' is declared a Date and computed as a DateTime")
    }

    @Test
    fun `a constant the declared type reads is accepted, and a generator in an initially`() {
        assertEquals(emptyList(), diagnostics("""
            act Kind {
                name: text
                opensAt: time initially "09:00"
                ref: uuid initially randomUUID
                tag: uuid = "3b241101-e2bb-4255-8caf-4136c566a962"
                since: Date initially today
                span: duration initially 3 days
                tags: many text initially empty
            }
        """))
        assertOne(diagnostics("""
            act Kind {
                opensAt: time initially "late"
            }
        """), "rejects this constant")
    }

    @Test
    fun `a derived property may state no type and take its expression's`() {
        val sys = system("""
            shape Entry {
                name: text unique
                weight: decimal
            }
            act Kind {
                parentName: text?
                count: integer
                isRoot = parentName is none or parentName == "survey"
                doubled = count * 2
                parent = parentName
                heaviest = first(Entry where weight > 0 by weight)
                heaviestName = first(Entry where weight > 0 by weight).name
                entries = (Entry where weight > 0)
                label = "kind ${'$'}{count}"
            }
            shape RootKind = Kind where isRoot
            shape DoubledKind = Kind where doubled == 6
            shape Orphan = Kind where parent is none
        """)
        val model = sys.model
        val members = model.membersOf("Kind")
        assertEquals(VType.Bool, members.getValue("isRoot").type)
        assertEquals(VType.Num("decimal"), members.getValue("doubled").type)
        assertEquals(VType.Optional(VType.Text), members.getValue("parent").type)
        assertEquals(VType.Optional(VType.Inst("Entry")), members.getValue("heaviest").type)
        assertEquals(VType.Optional(VType.Text), members.getValue("heaviestName").type)
        assertEquals(VType.Coll("Entry"), members.getValue("entries").type)
        assertEquals(VType.Text, members.getValue("label").type)
        sys.scope {
            val root = assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("count" to 3))).id
            assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("count" to 1, "parentName" to "page")))
            assertEquals(listOf(root), sys.instancesOf("RootKind"))
            assertEquals(listOf(root), sys.instancesOf("DoubledKind"))
            assertEquals(listOf(root), sys.instancesOf("Orphan"))
            assertEquals(true, sys.get(root, "isRoot"))
            assertEquals(BigDecimal(6), sys.get(root, "doubled"))
            assertNull(sys.get(root, "heaviest"))
        }
    }

    @Test
    fun `an untyped property prints back as written`() {
        val decls = Parser.parse("act Kind {\n    parentName: text?\n    isRoot = parentName is none\n}")
        val prop = (decls.single() as ShapeDecl).members.filterIsInstance<DerivedProp>().single()
        assertNull(prop.type)
        assertEquals("isRoot = parentName is none", Printer.member(prop))
    }

    @Test
    fun `untyped properties that take their type from one another are refused`() {
        // a definition cycle is V14's as well; the type cycle is reported beside it
        val mutual = diagnostics("""
            act Kind {
                count: integer
                a = b + 1
                b = a * 2
            }
        """)
        assertTrue(mutual.any { "[F2] 'Kind.a' and 'Kind.b' each take the type of the next and none declares one; declare a type on one of them" in it }, mutual.toString())
        // a self-reference along a predecessor is well-founded (V14 certifies the descent) and still has no type to start from
        assertOne(diagnostics("""
            shape Assay {
                name: text
            }
            act AssayReading {
                assay: one Assay
                hoursElapsed: integer
                inhibitionPercent: decimal
                unique (assay, hoursElapsed)
                previous: one AssayReading? = latest(AssayReading where assay == this.assay and hoursElapsed < this.hoursElapsed by hoursElapsed)
                runningPeak = if previous is some then max(previous.runningPeak, inhibitionPercent) else inhibitionPercent
            }
        """), "[F2]", "'AssayReading.runningPeak' takes its type from itself and declares none; declare it")
        // a declared type breaks the cycle, and an untyped property may read the typed one
        assertEquals(emptyList(), diagnostics("""
            shape Assay {
                name: text
            }
            act AssayReading {
                assay: one Assay
                hoursElapsed: integer
                inhibitionPercent: decimal
                unique (assay, hoursElapsed)
                previous = latest(AssayReading where assay == this.assay and hoursElapsed < this.hoursElapsed by hoursElapsed)
                runningPeak: decimal = if previous is some then max(previous.runningPeak, inhibitionPercent) else inhibitionPercent
                twicePeak = runningPeak * 2
            }
        """))
    }

    @Test
    fun `an expression the compiler reads no type off is refused`() {
        assertOne(diagnostics("""
            act Kind {
                name: text
                nothing = empty
            }
        """), "'Kind.nothing' states no type, and the compiler reads none off 'empty'", "declare it")
    }

    /** A field read off every record of a set denotation is a collection of what the field holds
     *  (`examples/peptides/peptides.velle`, `PeptideBatch.inputs`); the declared `many` admits it. */
    @Test
    fun `a field read off a set denotation types as a collection`() {
        val diags = diagnostics("""
            visible to anyone
            shape Batch {
                code: text
                inputs: many Batch = (Component where into == this).source
                inputCodes: many text = (Component where into == this).source.code
                components = (Component where into == this)
                sources: many Batch = components.source
                sourceCodes: many text = components.source.code
            }
            shape Component {
                into: one Batch
                source: one Batch
            }
        """)
        assertEquals(emptyList(), diags.filter { "[F2]" in it }, diags.toString())
    }
}
