package velle

import java.time.Duration
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The `time` and `bytes` scalars (Reference §5). */
class TimeBytesTest {

    private val spec = """
        act Shift {
            name: text
            opens: time
            closes: time
            badge: bytes
            photo: bytes?
            length: duration = closes - opens
            lastEntry: time = closes - 30 minutes
        }
        shape Morning = Shift where opens < "12:00"
        shape Late = Shift where closes > opens + 8 hours
        shape Badged = Shift where photo is some
        never (Shift where closes < opens)
    """.trimIndent()

    private fun system(): VelleSystem {
        val diags = Validator.validate(spec)
        check(diags.isEmpty()) { diags.toString() }
        return VelleSystem(Model(Parser.parse(spec)))
    }

    private fun VelleSystem.shift(opens: LocalTime, closes: LocalTime, badge: ByteArray = byteArrayOf(1, 2, 3), photo: ByteArray? = null): CommitResult {
        val fields = mutableMapOf<String, Any?>("name" to "day", "opens" to opens, "closes" to closes, "badge" to badge)
        if (photo != null) fields["photo"] = photo
        return commit("Shift", fields)
    }

    @Test
    fun `a time orders, reads a text constant, and moves by a duration`() {
        val sys = system()
        sys.scope {
            val s = assertIs<CommitResult.Accepted>(sys.shift(LocalTime.of(9, 0), LocalTime.of(17, 30))).id
            assertEquals(LocalTime.of(9, 0), sys.get(s, "opens"))
            assertEquals(Duration.ofHours(8).plusMinutes(30), sys.get(s, "length"))
            assertEquals(LocalTime.of(17, 0), sys.get(s, "lastEntry"))
            assertTrue(sys.isMember(s, "Morning"))
            assertTrue(sys.isMember(s, "Late"))
            val short = assertIs<CommitResult.Accepted>(sys.shift(LocalTime.of(13, 0), LocalTime.of(15, 0))).id
            assertTrue(!sys.isMember(short, "Morning") && !sys.isMember(short, "Late"))
            assertIs<CommitResult.Refused>(sys.shift(LocalTime.of(17, 0), LocalTime.of(9, 0)), "a never over two times")
        }
    }

    @Test
    fun `bytes pass through by content`() {
        val sys = system()
        sys.scope {
            val s = assertIs<CommitResult.Accepted>(sys.shift(LocalTime.of(9, 0), LocalTime.of(17, 0), photo = byteArrayOf(9, 8))).id
            assertContentEquals(byteArrayOf(1, 2, 3), sys.get(s, "badge") as ByteArray)
            assertContentEquals(byteArrayOf(9, 8), sys.get(s, "photo") as ByteArray)
            assertTrue(sys.isMember(s, "Badged"))
            assertIs<CommitResult.Refused>(sys.commit("Shift", mapOf("name" to "x", "opens" to LocalTime.NOON, "closes" to LocalTime.MIDNIGHT.minusSeconds(1), "badge" to "not bytes")))
        }
    }

    @Test
    fun `bytes compare by content and order byte by byte`() {
        val src = """
            act Blob { data: bytes }
            act Pair { a: one Blob, b: one Blob }
            shape Same = Pair where a.data == b.data
            shape Less = Pair where a.data < b.data
        """
        check(Validator.validate(src).isEmpty()) { Validator.validate(src).toString() }
        val sys = VelleSystem(Model(Parser.parse(src)))
        sys.scope {
            fun blob(vararg b: Byte) = assertIs<CommitResult.Accepted>(sys.commit("Blob", mapOf("data" to b))).id
            val x = blob(1, 2); val y = blob(1, 2); val z = blob(1, 2, 0); val w = blob(-1)
            fun pair(a: Long, b: Long) = assertIs<CommitResult.Accepted>(sys.commit("Pair", mapOf("a" to a, "b" to b))).id
            assertTrue(sys.isMember(pair(x, y), "Same"))
            assertTrue(!sys.isMember(pair(x, z), "Same"))
            assertTrue(sys.isMember(pair(x, z), "Less"), "a prefix orders first")
            assertTrue(sys.isMember(pair(x, w), "Less"), "bytes order unsigned, as a BLOB does")
        }
    }
}
