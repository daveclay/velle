package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A comparison never reads a text as another type, and two builtin kinds that
 * differ never meet (Reference §5; checks.md F2). The one reading kept: a quoted
 * text beside a `uuid` or a `time` is read as one when the spec is compiled.
 * Settled as OQ75.
 */
class MixedKindComparisonTest {

    private fun diags(spec: String) = Validator.validate(spec)
    private fun f2(spec: String) = diags(spec).filter { it.code == "F2" }.map { it.message }

    private val reading = """
        act Field {
            name: text
            type: text
            min: decimal?
        }
        shape Reading {
            fieldName: text
            value: text?
            field: one Field? =
                if exists (Field where name == this.fieldName) then first(Field where name == this.fieldName by name)
                else none
        }
    """.trimIndent()

    @Test
    fun `a text field beside a decimal is refused, naming number() and the declaration to write`() {
        val m = f2("$reading\nnever (Reading where field is some and field.min is some and value is some and value < field.min)")
        assertEquals(1, m.size, m.toString())
        assertEquals("'value < field.min' — compares the text 'value' with a decimal; a text is not compared as a decimal. " +
            "Write number(value), which is absent when the text is not a number, or declare 'value' as a decimal on a shape of its own", m.single())
    }

    @Test
    fun `the text side may be on the right, and a Date or a DateTime names its own reader`() {
        val spec = """
            act P {
                label: text
                due: Date
                at: DateTime
                n: integer
            }
            never (P where n > label)
            never (P where due == label)
            never (P where label < at)
        """.trimIndent()
        val m = f2(spec)
        assertEquals(3, m.size, m.toString())
        assertTrue("compares the integer 'n' with a text; a text is not compared as an integer. Write number(label), which is absent when the text is not a number, or declare 'label' as an integer on a shape of its own" in m[0], m[0])
        assertTrue("compares the Date 'due' with a text; a text is not compared as a Date. Write date(label), which is absent when the text is not a date, or declare 'label' as a Date on a shape of its own" in m[1], m[1])
        assertTrue("compares the text 'label' with a DateTime; a text is not compared as a DateTime. Write datetime(label), which is absent when the text is not a moment, or declare 'label' as a DateTime on a shape of its own" in m[2], m[2])
    }

    @Test
    fun `every pair of builtin kinds that differ is refused, field against field and field against a constant`() {
        // the survey's `10-mixed-comparisons-refused.velle`: one prohibition per pair
        val fields = listOf("textV: text", "decimalV: decimal", "booleanV: boolean", "dateV: Date", "datetimeV: DateTime",
            "durationV: duration", "timeV: time", "uuidV: uuid", "bytesV: bytes")
        val names = fields.map { it.substringBefore(":") }
        val pairs = names.flatMapIndexed { i, a -> names.drop(i + 1).map { b -> a to b } }
        val spec = "act P {\n" + fields.joinToString("\n") + "\n}\n" +
            pairs.joinToString("\n") { (a, b) -> "never (P where $a == $b)" } + "\n" +
            names.joinToString("\n") { n -> "never (P where $n == ${if (n == "textV") "5" else "\"abc\""})" }
        val m = f2(spec)
        // 36 field pairs; of the 9 constants, "abc" beside timeV and uuidV is refused by the reader, the rest as a mismatch
        assertEquals(pairs.size + names.size, m.size, "${m.size} of ${pairs.size + names.size}:\n" + m.joinToString("\n"))
        assertTrue(m.any { "'textV == 5' — compares the text 'textV' with a number; a number is not compared as a text" in it }, m.toString())
        assertTrue(m.any { "'dateV == \"abc\"' — compares the Date 'dateV' with a text; a text is not compared as a Date" in it }, m.toString())
        assertTrue(m.any { "'\"abc\"' — 'time' rejects this constant: 'abc' is not a time of day" in it }, m.toString())
        assertTrue(m.any { "'\"abc\"' — 'uuid' rejects this constant: 'abc' is not a UUID" in it }, m.toString())
        assertTrue(m.any { "'dateV == datetimeV' — compares the Date 'dateV' with a DateTime; a Date is not compared as a DateTime" in it }, m.toString())
        assertTrue(m.none { "Write" in it && "textV" !in it }, "only a text field gets a function to write: " + m.filter { "Write" in it })
    }

    @Test
    fun `a quoted text beside a time or a uuid is read when the spec is compiled, and a text field beside one is refused`() {
        val spec = """
            act Shop {
                opensAt: time
                code: uuid
                label: text
            }
            shape Early = Shop where opensAt < "09:00"
            shape Known = Shop where code == "123e4567-e89b-12d3-a456-426614174000"
            never (Shop where opensAt == label)
            never (Shop where code == label)
        """.trimIndent()
        val m = f2(spec)
        assertEquals(2, m.size, m.toString())
        assertEquals("'opensAt == label' — compares the time 'opensAt' with a text; a text is not compared as a time. Declare 'label' as a time", m[0])
        assertEquals("'code == label' — compares the uuid 'code' with a text; a text is not compared as a uuid. Declare 'label' as a uuid", m[1])
    }

    @Test
    fun `the kinds that agree still meet, an integer beside a decimal among them`() {
        val spec = """
            act P {
                n: integer
                d: decimal
                due: Date
                at: DateTime
                span: duration
                opensAt: time
                label: text
                flag: boolean
            }
            shape A = P where n < d
            shape B = P where due < today
            shape C = P where at < now
            shape D = P where span < 3 days
            shape E = P where opensAt < opensAt + 1 hours
            shape F = P where label == "x" and flag == true
            shape G = P where due - today < 3 days
        """.trimIndent()
        assertEquals(emptyList(), diags(spec))
    }

    @Test
    fun `plus between two texts is refused`() {
        val spec = """
            act Field {
                name: text
                unitName: text = name + "_unit"
            }
        """.trimIndent()
        val m = f2(spec)
        assertEquals(1, m.size, m.toString())
        assertEquals("'name + \"_unit\"' — '+' is not defined on a text; Velle does not join or compute with texts. " +
            "A value made of parts is a shape with a field for each part, and a value that is a number or a date is declared as one", m.single())
    }

    @Test
    fun `at runtime a quoted text beside a time or a uuid is the value, read through the type`() {
        val decls = Parser.parse("""
            act Shop {
                opensAt: time
                code: uuid
            }
            shape Early = Shop where opensAt < "09:00"
            shape Known = Shop where code == "123e4567-e89b-12d3-a456-426614174000"
        """.trimIndent())
        check(Validator.validate(decls).isEmpty())
        val sys = VelleSystem(Model(decls))
        sys.scope {
            val early = (sys.commit("Shop", mapOf("opensAt" to java.time.LocalTime.of(8, 0),
                "code" to java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))) as CommitResult.Accepted).id
            val late = (sys.commit("Shop", mapOf("opensAt" to java.time.LocalTime.of(10, 0), "code" to java.util.UUID.randomUUID())) as CommitResult.Accepted).id
            assertTrue(sys.isMember(early, "Early"))
            assertTrue(sys.isMember(early, "Known"))
            assertTrue(!sys.isMember(late, "Early"))
            assertTrue(!sys.isMember(late, "Known"))
        }
    }
}
