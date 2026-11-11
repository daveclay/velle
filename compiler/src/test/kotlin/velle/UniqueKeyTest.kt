package velle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A key (Reference §21, `unique`): `name: text unique` on a field, or
 * `unique (kindName, name)` as a body line. The word stands for the count
 * `never` an author wrote by hand before; the rejection quotes the word. A
 * selection naming every field of a key is one record (Reference §10,
 * "Reading the one related record"), checked by V36.
 */
class UniqueKeyTest {

    private fun system(src: String): VelleSystem {
        val model = Model(Parser.parse(src))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return VelleSystem(model)
    }

    private fun codes(src: String): List<String> = Validator.validate(src).map { it.code }
    private fun messages(src: String): List<String> = Validator.validate(src).map { it.message }

    // ── the two spellings parse ──────────────────────────────────────────────

    @Test
    fun `a field suffix and a body line both declare a key`() {
        val decls = Parser.parse("""
            act Kind {
                name: text unique
                parent: one Kind?
            }
            act Field {
                kindName: text
                name: text
                unique (kindName, name)
            }
        """.trimIndent())
        val kind = decls.filterIsInstance<ShapeDecl>().single { it.name == "Kind" }
        assertTrue(kind.members.filterIsInstance<StoredProp>().single { it.name == "name" }.unique)
        val field = decls.filterIsInstance<ShapeDecl>().single { it.name == "Field" }
        assertEquals(UniqueKey(listOf("kindName", "name")), field.members.filterIsInstance<UniqueKey>().single())
        val model = Model(decls)
        assertEquals(listOf(UniqueKey(listOf("name"), onField = true)), model.keysOf("Kind"))
        assertEquals(listOf(UniqueKey(listOf("kindName", "name"))), model.keysOf("Field"))
    }

    // ── the word is its never ────────────────────────────────────────────────

    @Test
    fun `a second record sharing a two-field key is refused and the rejection quotes the declaration`() {
        val sys = system("""
            act Field {
                kindName: text
                name: text
                unique (kindName, name)
            }
        """.trimIndent())
        sys.scope {
            assertIs<CommitResult.Accepted>(sys.commit("Field", mapOf("kindName" to "site", "name" to "notes")))
            assertIs<CommitResult.Accepted>(sys.commit("Field", mapOf("kindName" to "plot", "name" to "notes")))
            val refused = assertIs<CommitResult.Refused>(sys.commit("Field", mapOf("kindName" to "site", "name" to "notes")))
            val rejection = assertNotNull(refused.rejection)
            assertEquals("unique (kindName, name)", rejection.declaration)
            assertEquals("Field", rejection.targetShape)
            assertEquals(2, sys.instancesOf("Field").size)
        }
    }

    @Test
    fun `a field suffix refuses the second record and quotes the field line`() {
        val sys = system("""
            act Kind {
                name: text unique
            }
        """.trimIndent())
        sys.scope {
            assertIs<CommitResult.Accepted>(sys.commit("Kind", mapOf("name" to "site")))
            val refused = assertIs<CommitResult.Refused>(sys.commit("Kind", mapOf("name" to "site")))
            assertEquals("name: text unique", assertNotNull(refused.rejection).declaration)
        }
    }

    @Test
    fun `absent values of an optional key do not collide`() {
        val sys = system("""
            act Entry {
                key: text? unique
                label: text
            }
        """.trimIndent())
        sys.scope {
            assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("label" to "a")))
            assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("label" to "b")))
            assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-1", "label" to "c")))
            assertIs<CommitResult.Refused>(sys.commit("Entry", mapOf("key" to "PLOT-1", "label" to "d")))
            assertEquals(3, sys.instancesOf("Entry").size)
        }
    }

    @Test
    fun `a key on a reference field keeps one record per target`() {
        val sys = system("""
            act Origin {
                name: text
            }
            act Batch {
                origin: one Origin unique
                quantity: decimal
            }
        """.trimIndent())
        sys.scope {
            val origin = assertIs<CommitResult.Accepted>(sys.commit("Origin", mapOf("name" to "ferment-1"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Batch", mapOf("origin" to origin, "quantity" to 10)))
            assertIs<CommitResult.Refused>(sys.commit("Batch", mapOf("origin" to origin, "quantity" to 11)))
        }
    }

    // ── V35: a key names stored fields ───────────────────────────────────────

    @Test
    fun `V35 - a key naming a derived, many, or undeclared field is refused`() {
        val derived = codes("""
            act Kind {
                name: text
                label: text = name
                unique (label)
            }
        """.trimIndent())
        assertEquals(listOf("V35"), derived)
        val many = codes("""
            act Kind {
                tags: many text unique
            }
        """.trimIndent())
        assertEquals(listOf("V35"), many)
        val unknown = messages("""
            act Kind {
                name: text
                unique (name, kind)
            }
        """.trimIndent())
        assertEquals(1, unknown.size)
        assertTrue(unknown.single().contains("'kind'"), unknown.single())
    }

    // ── V36: a selection by a full key is one record ─────────────────────────

    private val entryAndSubmission = """
        act Entry {
            key: text unique
            initialLabel: text
            arrivals: many Arrival = (Arrival where entry == this)
        }
        shape Arrival {
            submission: one Submission
            entry: one Entry
        }
        shape Unpaired = Submission where not exists Arrival for this
    """

    @Test
    fun `a filter naming the whole key is one record in a from block`() {
        val sys = system(entryAndSubmission + """
            act Submission {
                entryKey: text
            }
            never (Submission where not exists (Entry where key == this.entryKey))
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: (Entry where key == this.entryKey) }
            }
        """)
        sys.scope {
            val plot = assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-0417", "initialLabel" to "a"))).id
            val sub = assertIs<CommitResult.Accepted>(sys.commit("Submission", mapOf("entryKey" to "PLOT-0417"))).id
            val arrival = sys.instancesOf("Arrival").single()
            assertEquals(sub, sys.get(arrival, "submission"))
            assertEquals(plot, sys.get(arrival, "entry"))
        }
    }

    @Test
    fun `a filter naming the whole key is one record in a derived property`() {
        val sys = system("""
            act Entry {
                key: text unique
                label: text
            }
            act Submission {
                entryKey: text
                entry: one Entry? = (Entry where key == this.entryKey)
            }
        """.trimIndent())
        sys.scope {
            val plot = assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-1", "label" to "a"))).id
            val found = assertIs<CommitResult.Accepted>(sys.commit("Submission", mapOf("entryKey" to "PLOT-1"))).id
            val missing = assertIs<CommitResult.Accepted>(sys.commit("Submission", mapOf("entryKey" to "PLOT-9"))).id
            assertEquals(plot, sys.get(found, "entry"))
            assertEquals(null, sys.get(missing, "entry"))
        }
    }

    @Test
    fun `V36 - a filter naming part of a key is a set, and the diagnostic names the missing field`() {
        val diagnostics = Validator.validate("""
            act Field {
                kindName: text
                name: text
                unique (kindName, name)
            }
            act Reading {
                kindName: text
                field: one Field? = (Field where kindName == this.kindName)
            }
        """.trimIndent())
        assertEquals(listOf("V36"), diagnostics.map { it.code })
        assertTrue(diagnostics.single().message.contains("'name'"), diagnostics.single().message)
    }

    @Test
    fun `V36 - a filter comparing a key with a value that may be absent is a set`() {
        val diagnostics = Validator.validate(entryAndSubmission + """
            act Submission {
                entryKey: text?
            }
            rule RecordArrival when Unpaired {
                Arrival from { submission: this, entry: (Entry where key == this.entryKey) }
            }
        """)
        assertEquals(listOf("V36"), diagnostics.map { it.code })
        assertTrue(diagnostics.single().message.contains("entryKey is some"), diagnostics.single().message)
    }

    @Test
    fun `a refinement's is some makes the compared value present in the rule's body`() {
        val sys = system("""
            act Entry {
                key: text? unique
                initialLabel: text
            }
            act Submission {
                entryKey: text?
            }
            shape Arrival {
                submission: one Submission
                entry: one Entry
            }
            shape Keyed = Submission where entryKey is some and not exists Arrival for this
            never (Keyed where not exists (Entry where key == this.entryKey))
            rule RecordArrival when Keyed {
                Arrival from { submission: this, entry: (Entry where key == this.entryKey) }
            }
        """.trimIndent())
        sys.scope {
            val plot = assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("key" to "PLOT-1", "initialLabel" to "a"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Entry", mapOf("initialLabel" to "no key yet")))
            val sub = assertIs<CommitResult.Accepted>(sys.commit("Submission", mapOf("entryKey" to "PLOT-1"))).id
            assertIs<CommitResult.Accepted>(sys.commit("Submission", emptyMap()))
            val arrival = sys.instancesOf("Arrival").single()
            assertEquals(sub, sys.get(arrival, "submission"))
            assertEquals(plot, sys.get(arrival, "entry"))
        }
    }

    @Test
    fun `V36 - a filter in a one-typed position over a shape with no key is a set`() {
        val diagnostics = Validator.validate("""
            act Entry {
                key: text
                label: text
            }
            act Submission {
                entryKey: text
                entry: one Entry? = (Entry where key == this.entryKey)
            }
        """.trimIndent())
        assertEquals(listOf("V36"), diagnostics.map { it.code })
        assertTrue(diagnostics.single().message.contains("first("), diagnostics.single().message)
    }

    // ── the domain: a key never keys on the committed value ──────────────────

    @Test
    fun `a key never keys the commit on the value, not the whole shape`() {
        val analysis = DomainAnalysis(Model(Parser.parse("""
            act Customer {
                email: text unique
            }
        """.trimIndent())))
        val domain = analysis.actDomains.getValue("Customer")
        assertTrue(domain.valueKeys.any { it.field == "email" }, domain.toString())
        assertTrue(domain.widenings.isEmpty(), domain.widenings.toString())
    }
}
