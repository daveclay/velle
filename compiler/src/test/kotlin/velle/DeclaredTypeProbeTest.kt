package velle

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OQ82 probe (`working-docs/QUESTIONS.md`): a declared type is never compared with the
 * expression that computes it. Each case validates with no diagnostic, and the runtime
 * hands back whatever the expression made. The cases are the survey's `12-declared-type-*`
 * files; this test pins what each does at the read or the commit today.
 */
class DeclaredTypeProbeTest {

    private fun system(spec: String): VelleSystem {
        val decls = Parser.parse(spec)
        assertEquals(emptyList(), Validator.validate(decls))
        return VelleSystem(Model(decls))
    }

    @Test
    fun `a derived property reads back whatever its expression makes`() {
        val sys = system(
            """
            act Kind {
                parentName: text?
                count: integer
                label: text = (parentName is none)
                doubled: text = count * 2
                seen: Date = now
                parent: text = parentName
                names: many text = label
                flagged: boolean = if count == 1 then "yes" else "no"
                half: integer = count / 2
            }
            """.trimIndent()
        )
        sys.scope {
            val id = assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("count" to 3))).id
            assertEquals(true, sys.get(id, "label"))
            assertEquals(BigDecimal(6), sys.get(id, "doubled"))
            assertIs<java.time.Instant>(sys.get(id, "seen"))
            assertNull(sys.get(id, "parent"))
            assertEquals(true, sys.get(id, "names"))
            assertEquals("no", sys.get(id, "flagged"))
            assertEquals(BigDecimal("1.5"), sys.get(id, "half"))
        }
    }

    @Test
    fun `an initially reads back coerced, a from block fails the read, an assignment reads back coerced`() {
        val sys = system(
            """
            act Reading {
                efficacy: decimal
                applied: boolean initially 5
                locked: boolean initially false
            }
            shape Verdict {
                reading: one Reading
                effective: boolean
            }
            rule RecordVerdict when (Reading where not exists Verdict for this) {
                Verdict from { reading: this, effective: "yes" }
            }
            act Lock {
                reading: one Reading
            }
            rule ApplyLock when Lock {
                reading.locked = 5
            }
            """.trimIndent()
        )
        sys.scope {
            val reading = assertIs<CommitResult.Accepted>(sys.commit("Reading", mapOf("efficacy" to 1))).id
            assertEquals(true, sys.get(reading, "applied"))
            // listing the verdicts hydrates each record, and the text in the boolean field fails there
            // listing the verdicts hydrates each record, and the text in the boolean field fails there
            val read = runCatching { sys.instancesOf("Verdict") }.exceptionOrNull()
            assertTrue(read is VelleRuntimeError && read.message!!.contains("not a boolean"), read.toString())
            // the number is read back as true, as the `initially 5` was
            assertIs<CommitResult.Accepted>(sys.commit("Lock", mapOf("reading" to reading)))
            assertEquals(true, sys.get(reading, "locked"))
        }
    }

    @Test
    fun `an assignment of a number to a boolean fails the commit once a refinement with a capture reads the field`() {
        val sys = system(
            """
            act Reading {
                locked: boolean initially false
            }
            act Lock {
                reading: one Reading
            }
            rule ApplyLock when Lock {
                reading.locked = 5
            }
            shape LockedReading = Reading where locked {
                captured since: Date = now
            }
            """.trimIndent()
        )
        sys.scope {
            val reading = assertIs<CommitResult.Accepted>(sys.commit("Reading", mapOf())).id
            val lock = runCatching { sys.commit("Lock", mapOf("reading" to reading)) }.exceptionOrNull()
            assertTrue(lock is VelleRuntimeError && lock.message!!.contains("non-boolean in predicate position"), lock.toString())
        }
    }
}
