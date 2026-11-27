package velle.sql

import velle.CommitResult
import velle.CommitSet
import velle.Model
import velle.Parser
import velle.QArg
import velle.QCall
import velle.QConst
import velle.QF
import velle.Ref
import velle.Row
import velle.StateResolver
import velle.StoreKey
import velle.VType
import velle.Validator
import velle.VelleSystem
import velle.finance.Money
import java.math.BigDecimal
import velle.AdmissionKey
import velle.Outcome
import velle.WorkKind
import java.sql.Connection
import javax.sql.DataSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every SQL dialect must get right, run over a real database by the
 * dialect's own test module: every builtin scalar and a collection of each
 * kind round-trips through the dialect's columns; references, union-typed
 * references and their join reads; the pre-filter pushed down where the
 * dialect's column answers the comparison and left to the evaluator where it
 * does not, with the same answer either way; capture memory across processes
 * and its upsert on re-entry; deletion emptying every reference at the row; E4 on an
 * extension type's binding; and the unit of work: a connection per read or
 * envelope, returned at its end, a read's snapshot, a refusal writing nothing,
 * and, where the dialect serializes keys, two envelopes on one key taking turns.
 *
 * A dialect module subclasses this with its `DataSource` and store.
 */
abstract class SqlStoreConformance {

    /** A `DataSource` over a fresh, empty database (or schema) of its own, handing out real connections: a unit of work takes one and returns it. */
    protected abstract fun dataSource(): DataSource

    protected abstract fun store(
        model: Model,
        dataSource: DataSource,
        bindings: List<SqlTypeBinding<*>>,
        lockTimeout: Duration = Duration.ofSeconds(10),
        functionBindings: List<SqlFunctionBinding> = emptyList(),
        insistRendered: Set<String> = emptySet(),
    ): SqlStore

    /** Releases what [dataSource] created, a temporary file for one database; nothing by default. */
    protected open fun release(dataSource: DataSource) {}

    private val open = mutableListOf<DataSource>()

    @AfterTest
    fun releaseDataSources() {
        for (ds in open) runCatching { release(ds) }
        open.clear()
    }

    protected fun ds(): DataSource = dataSource().also { open += it }

    /** Counts the connections a store takes and returns: every unit of work takes one and gives it back. */
    protected class Leases(private val inner: DataSource) : DataSource by inner {
        var taken = 0
        var open = 0
        override fun getConnection(): Connection {
            val c = inner.connection
            taken++; open++
            return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
                if (m.name == "close") { open--; c.close(); null }
                else try { if (args == null) m.invoke(c) else m.invoke(c, *args) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
            } as Connection
        }
    }

    // ── a spec touching every column kind and every store path ──────────────

    protected val spec = """
        act Customer {
            name: text
            email: text?
            tier: integer
            balance: decimal
            score: double
            visits: long
            active: boolean
            since: Date
            lastSeen: DateTime?
            token: uuid initially randomUUID
            window: duration
            opensAt: time?
            avatar: bytes?
            tags: many text
            notes: many text tolerates duplication
            joinedOn: timestamp on create
        }
        act Order {
            customer: one Customer? initially required
            total: decimal
        }

        shape Gold = Customer where tier >= 3
        shape NotGold = Customer where not (tier >= 3)
        shape Rich = Customer where balance > 100
        shape HighScore = Customer where score >= 0.5
        shape Frequent = Customer where visits > 10
        shape Inactive = Customer where active == false
        shape Veteran = Customer where since < today
        shape SeenBefore = Customer where lastSeen < now
        shape NeverSeen = Customer where lastSeen is none
        shape ShortWindow = Customer where window < 4 weeks
        shape EarlyOpener = Customer where opensAt < "09:00"
        shape WithAvatar = Customer where avatar is some
        shape ANames = Customer where name starts with "A"
        shape ANamesToo = Customer where name ends with "a"
        shape DNames = Customer where name contains "d"
        shape LikeNames = Customer where name like "A_a%"
        shape Mailable = Customer where email matches "[^@ ]+@[^@ ]+\\.[a-z]+"
        shape NoEmail = Customer where email is empty
        shape Vip = Customer where tags contains "vip"

        transient act SetTier {
            customer: one Customer
            tier: integer
        }
        rule ApplyTier when SetTier { this.customer.tier = this.tier }

        shape Flagged = Customer where tier >= 5 {
            captured flaggedOn: Date = today
            captured flaggedTier: integer = tier
        }
        shape UnflagNotice {
            customer: one Customer? initially required
            wasFlaggedOn: Date
            tierThen: integer
        }
        rule NoteUnflag when leaving Flagged {
            UnflagNotice from { customer: this, wasFlaggedOn: flaggedOn, tierThen: flaggedTier }
        }

        transient act Remove {
            customer: one Customer
        }
        rule ApplyRemove when Remove { delete customer }

        act Post { title: text }
        act Photo { caption: text }
        shape Content = Post or Photo
        act Comment {
            target: one Content
            body: text
        }
        shape Popular = Content where count(comments) >= 2
    """.trimIndent()

    protected fun system(spec: String = this.spec, startTime: Instant = Instant.parse("2026-01-15T09:00:00Z")): VelleSystem {
        val decls = Parser.parse(spec)
        val d = Validator.validate(decls)
        check(d.isEmpty()) { d.joinToString("\n") }
        return VelleSystem(Model(decls), startTime = startTime)
    }

    /** Records what the runtime asked, to tell a pushed-down scan from a full one. */
    protected class Counting(private val inner: StateResolver) : StateResolver by inner {
        val fetchAlls = mutableListOf<String>()
        val candidates = mutableListOf<Triple<String, QF, Int>>()
        override fun fetchAll(shape: String): List<Row> { fetchAlls += shape; return inner.fetchAll(shape) }
        override fun fetchCandidates(shape: String, filter: QF): List<Row> =
            inner.fetchCandidates(shape, filter).also { candidates += Triple(shape, filter, it.size) }
        fun reset() { fetchAlls.clear(); candidates.clear() }
    }

    protected class Attached(val sys: VelleSystem, val store: SqlStore, val counting: Counting, val ds: DataSource)

    protected fun attach(
        ds: DataSource = ds(),
        spec: String = this.spec,
        bindings: List<SqlTypeBinding<*>> = emptyList(),
        functionBindings: List<SqlFunctionBinding> = emptyList(),
        insistRendered: Set<String> = emptySet(),
    ): Attached {
        val sys = system(spec)
        val store = store(sys.model, ds, bindings, functionBindings = functionBindings, insistRendered = insistRendered).apply { createSchema() }
        val counting = Counting(store)
        sys.connect(counting, store, store)
        return Attached(sys, store, counting, ds)
    }

    /** The record a commit made. A handle dies with the commit that minted it, so tests hold the reference and mint a handle per use. */
    protected fun accepted(r: CommitResult): Ref.Persisted? = when (r) {
        is CommitResult.Accepted -> r.ref // null for a transient act, which leaves no record
        is CommitResult.Refused -> error(r.reason)
    }

    /** A handle for [ref], live for the next commit or scope. */
    protected fun Attached.id(ref: Ref.Persisted): Long = sys.idOf(ref)

    protected fun Attached.customer(
        name: String, email: String? = "$name@example.com", tier: Int = 1, balance: String = "10.00", score: Double = 0.25,
        visits: Long = 1, active: Boolean = true, since: LocalDate = LocalDate.of(2024, 6, 1), lastSeen: Instant? = Instant.parse("2025-12-31T23:59:59Z"),
        window: Duration = Duration.ofDays(7), opensAt: LocalTime? = LocalTime.of(8, 30), avatar: ByteArray? = null,
        tags: List<String> = emptyList(), notes: List<String> = emptyList(),
    ): Ref.Persisted = accepted(sys.commit("Customer", mapOf(
        "name" to name, "email" to email, "tier" to tier, "balance" to BigDecimal(balance), "score" to score, "visits" to visits,
        "active" to active, "since" to since, "lastSeen" to lastSeen, "window" to window, "opensAt" to opensAt, "avatar" to avatar,
        "tags" to tags, "notes" to notes,
    ).filterValues { it != null }))!!

    /** The engineer's own SQL beside the store, on a connection of its own. */
    protected fun Attached.scalar(sql: String): Any? = ds.connection.use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null } }
    }

    protected fun Attached.execute(sql: String) = ds.connection.use { c -> c.createStatement().use { st -> st.execute(sql) } }

    protected fun Attached.count(table: String): Long = (scalar("""SELECT COUNT(*) FROM "$table"""") as Number).toLong()

    // ── every column kind round-trips, in this process and the next ──────────

    @Test
    fun `every builtin scalar and collection reads back as written, in a fresh process too`() {
        val a = attach()
        val avatar = byteArrayOf(0, 1, 2, 127, -128)
        val seen = Instant.parse("2026-01-10T12:34:56Z")
        a.customer("Ada", tier = 3, balance = "150.25", score = 0.75, visits = 12, active = false, since = LocalDate.of(2019, 3, 4),
            lastSeen = seen, window = Duration.ofDays(21), opensAt = LocalTime.of(7, 45), avatar = avatar,
            tags = listOf("vip", "beta"), notes = listOf("late", "early", "late"))
        val key = (a.scalar("""SELECT id FROM "customer"""") as Number).toLong()

        val b = attach(a.ds)
        b.sys.scope {
            val ada = b.sys.handleFor("Customer", key)
            assertEquals("Ada", b.sys.get(ada, "name"))
            assertEquals("Ada@example.com", b.sys.get(ada, "email"))
            assertEquals(BigDecimal(3), b.sys.get(ada, "tier"))
            assertEquals(0, BigDecimal("150.25").compareTo(b.sys.get(ada, "balance") as BigDecimal))
            assertEquals(0, BigDecimal("0.75").compareTo(b.sys.get(ada, "score") as BigDecimal))
            assertEquals(BigDecimal(12), b.sys.get(ada, "visits"))
            assertEquals(false, b.sys.get(ada, "active"))
            assertEquals(LocalDate.of(2019, 3, 4), b.sys.get(ada, "since"))
            assertEquals(seen, b.sys.get(ada, "lastSeen"))
            assertIs<UUID>(b.sys.get(ada, "token"))
            assertEquals(Duration.ofDays(21), b.sys.get(ada, "window"))
            assertEquals(LocalTime.of(7, 45), b.sys.get(ada, "opensAt"))
            assertEquals(avatar.toList(), (b.sys.get(ada, "avatar") as ByteArray).toList())
            assertEquals(listOf("vip", "beta"), b.sys.get(ada, "tags"))
            assertEquals(listOf("late", "early", "late"), b.sys.get(ada, "notes"), "a collection tolerating duplication keeps order and duplicates")
            assertEquals(Instant.parse("2026-01-15T09:00:00Z"), b.sys.get(ada, "joinedOn"), "the creation timestamp is the system clock at commit")
        }
    }

    @Test
    fun `a key is kept by the database as a unique constraint, absent values left out`() {
        val keyed = """
            act Field {
                kindName: text
                name: text
                unique (kindName, name)
            }
            act Entry {
                key: text? unique
                label: text
            }
        """.trimIndent()
        val a = attach(spec = keyed)
        a.execute("""INSERT INTO "field" ("kind_name", "name") VALUES ('site', 'notes')""")
        a.execute("""INSERT INTO "field" ("kind_name", "name") VALUES ('plot', 'notes')""")
        assertFailsWith<Exception>("the second row with the key must be refused by the database") {
            a.execute("""INSERT INTO "field" ("kind_name", "name") VALUES ('site', 'notes')""")
        }
        assertEquals(2L, a.count("field"))
        a.execute("""INSERT INTO "entry" ("key", "label") VALUES (NULL, 'a')""")
        a.execute("""INSERT INTO "entry" ("key", "label") VALUES (NULL, 'b')""")
        a.execute("""INSERT INTO "entry" ("key", "label") VALUES ('PLOT-1', 'c')""")
        assertFailsWith<Exception> { a.execute("""INSERT INTO "entry" ("key", "label") VALUES ('PLOT-1', 'd')""") }
        assertEquals(3L, a.count("entry"))
    }

    @Test
    fun `absent optionals stay absent and an empty collection reads back empty`() {
        val a = attach()
        a.customer("Bea", email = null, lastSeen = null, opensAt = null, avatar = null)
        val key = (a.scalar("""SELECT id FROM "customer"""") as Number).toLong()
        val b = attach(a.ds)
        b.sys.scope {
            val bea = b.sys.handleFor("Customer", key)
            assertNull(b.sys.get(bea, "email"))
            assertNull(b.sys.get(bea, "lastSeen"))
            assertNull(b.sys.get(bea, "opensAt"))
            assertNull(b.sys.get(bea, "avatar"))
            assertEquals(emptyList<String>(), b.sys.get(bea, "tags"))
            assertTrue(b.sys.instancesOf("NeverSeen").contains(bea))
            assertTrue(b.sys.instancesOf("NoEmail").contains(bea))
            assertTrue(b.sys.instancesOf("WithAvatar").isEmpty())
        }
    }

    // ── the pre-filter: pushed down where the column answers, same answer either way ──

    /** The names of the [shape] records in [refinement], and how many rows the store handed back for the scan. */
    private fun Attached.members(refinement: String, shape: String = "Customer"): Pair<Set<String>, Int?> {
        counting.reset()
        val names = sys.scope { sys.instancesOf(refinement).map { sys.get(it, "name") as String }.toSet() }
        val scan = counting.candidates.singleOrNull { it.first == shape }?.third
        return names to scan
    }

    /** [refinement] holds for exactly the records in [expected]; when
     *  [pushed], the store handed back only the matching rows, otherwise every row. */
    private fun Attached.check(refinement: String, expected: Set<String>, pushed: Boolean, total: Int, shape: String = "Customer") {
        val (names, scanned) = members(refinement, shape)
        assertEquals(expected, names, refinement)
        assertNotNull(scanned, "$refinement: the runtime asks for candidates with the compiled filter")
        if (pushed) assertEquals(expected.size, scanned, "$refinement: pushed down, the store returns only the matching rows")
        else assertEquals(total, scanned, "$refinement: left to the evaluator, the store returns every row")
    }

    private fun answers(storage: StorageClass, op: String, a: Attached) = op in a.store.dialect.column(storage).answers

    @Test
    fun `each comparison is pushed down exactly when the dialect's column answers it`() {
        val a = attach()
        val names = listOf(
            a.customer("Ada", tier = 3, balance = "150.25", score = 0.75, visits = 12, active = false, since = LocalDate.of(2019, 3, 4),
                lastSeen = Instant.parse("2026-01-10T12:00:00Z"), window = Duration.ofDays(21), opensAt = LocalTime.of(7, 45), avatar = byteArrayOf(1), tags = listOf("vip")),
            a.customer("Bea", email = "bea at example", tier = 1, balance = "99.99", score = 0.5, visits = 10, since = LocalDate.of(2026, 1, 15),
                lastSeen = Instant.parse("2026-01-15T09:00:00Z"), window = Duration.ofDays(28), opensAt = LocalTime.of(9, 0)),
            a.customer("Cyd", email = "   ", tier = 5, balance = "100.00", score = 0.49, visits = 11, since = LocalDate.of(2030, 1, 1),
                lastSeen = null, window = Duration.ofDays(27), opensAt = null, tags = listOf("beta", "vip")),
            a.customer("Adela", email = null, tier = 2, balance = "100.01", score = 1.0, visits = 0, since = LocalDate.of(2026, 1, 14),
                lastSeen = Instant.parse("2026-01-15T08:59:59Z"), window = Duration.ofDays(1), opensAt = LocalTime.of(8, 59, 59)),
        )
        val keys = a.sys.scope { a.sys.instancesOf("Customer").map { a.sys.get(it, "name") as String } }
        assertEquals(listOf("Ada", "Bea", "Cyd", "Adela"), keys, "the scan read comes back in creation order")
        // a second process, so every read is a hydration from the store
        val b = attach(a.ds)
        val n = 4
        b.check("Gold", setOf("Ada", "Cyd"), pushed = answers(StorageClass.INTEGER, ">=", b), n)
        b.check("NotGold", setOf("Bea", "Adela"), pushed = answers(StorageClass.INTEGER, ">=", b), n)
        b.check("Rich", setOf("Ada", "Adela"), pushed = answers(StorageClass.DECIMAL, ">", b), n)
        b.check("HighScore", setOf("Ada", "Bea", "Adela"), pushed = answers(StorageClass.DECIMAL, ">=", b), n)
        b.check("Frequent", setOf("Ada", "Cyd"), pushed = answers(StorageClass.INTEGER, ">", b), n)
        b.check("Inactive", setOf("Ada"), pushed = answers(StorageClass.BOOLEAN, "==", b), n)
        b.check("Veteran", setOf("Ada", "Adela"), pushed = answers(StorageClass.DATE, "<", b), n)
        b.check("SeenBefore", setOf("Ada", "Adela"), pushed = answers(StorageClass.DATETIME, "<", b), n)
        b.check("NeverSeen", setOf("Cyd"), pushed = true, n)
        b.check("ShortWindow", setOf("Ada", "Cyd", "Adela"), pushed = answers(StorageClass.DURATION, "<", b), n)
        b.check("EarlyOpener", setOf("Ada", "Adela"), pushed = answers(StorageClass.TIME, "<", b), n)
        b.check("WithAvatar", setOf("Ada"), pushed = true, n)
        b.check("ANames", setOf("Ada", "Adela"), pushed = true, n)
        b.check("ANamesToo", setOf("Ada", "Bea", "Adela"), pushed = true, n)
        b.check("DNames", setOf("Ada", "Cyd", "Adela"), pushed = true, n)
        b.check("LikeNames", setOf("Ada"), pushed = true, n)
        b.check("Mailable", setOf("Ada"), pushed = b.store.dialect.regexMatch("x") != null, n)
        b.check("NoEmail", setOf("Cyd", "Adela"), pushed = true, n)
        assertEquals(setOf("Ada", "Cyd"), b.members("Vip").first)
    }

    // ── a registered function's call, through its binding ────────────────────

    private val functionSpec = """
        act Thing {
            name: text
            n: integer
            m: integer?
        }
        shape Big = Thing where doubled(n) >= 6
        shape NotBig = Thing where not (doubled(n) >= 6)
        shape Flipped = Thing where 6 <= doubled(n)
        shape NotSix = Thing where doubled(n) != 6
        shape Even = Thing where isEven(n)
        shape NotEven = Thing where not isEven(n)
        shape MBig = Thing where doubled(m) >= 6
        shape NotMBig = Thing where not (doubled(m) >= 6)
        shape Far = Thing where abs(n - 4) >= 2
    """.trimIndent()

    private fun Attached.thing(name: String, n: Int, m: Int?): Ref.Persisted =
        accepted(sys.commit("Thing", mapOf("name" to name, "n" to n, "m" to m).filterValues { it != null }))!!

    /** Four things; [bindings] attach to a second process over the same database so every read hydrates from the store. */
    private fun things(bindings: List<SqlFunctionBinding>, insistRendered: Set<String> = emptySet()): Attached {
        val first = attach(spec = functionSpec, functionBindings = bindings, insistRendered = insistRendered)
        first.thing("A", 3, 3); first.thing("B", 1, null); first.thing("C", 5, 2); first.thing("D", 2, 4)
        return attach(first.ds, functionSpec, functionBindings = bindings, insistRendered = insistRendered)
    }

    @Test
    fun `a registered function with a binding narrows the scan and the body still decides`() {
        val b = things(listOf(DoubledSql, IsEvenSql))
        val pushed = answers(StorageClass.INTEGER, ">=", b)
        b.check("Big", setOf("A", "C"), pushed, 4, shape = "Thing")
        b.check("NotBig", setOf("B", "D"), pushed, 4, shape = "Thing")
        b.check("Flipped", setOf("A", "C"), pushed, 4, shape = "Thing")
        b.check("NotSix", setOf("B", "C", "D"), pushed, 4, shape = "Thing")
        b.check("Even", setOf("D"), pushed = true, total = 4, shape = "Thing")
        b.check("NotEven", setOf("A", "B", "C"), pushed = true, total = 4, shape = "Thing")
        // an absent argument makes the call absent, which fails the comparison and so satisfies its `not`
        b.check("MBig", setOf("A", "D"), pushed, 4, shape = "Thing")
        b.check("NotMBig", setOf("B", "C"), pushed, 4, shape = "Thing")
    }

    @Test
    fun `a function with no binding runs in memory and a call over an expression stays with the evaluator`() {
        val b = things(emptyList())
        b.check("Big", setOf("A", "C"), pushed = false, total = 4, shape = "Thing")
        b.check("Even", setOf("D"), pushed = false, total = 4, shape = "Thing")
        // `abs` ships a binding, but its argument here is an expression, not the row's own column:
        // nothing of the filter compiles, so the runtime asks for every row outright
        assertEquals(setOf("B", "D"), b.members("Far", "Thing").first)
        assertTrue("Thing" in b.counting.fetchAlls && b.counting.candidates.isEmpty(), "the whole table, with no filter to hand the store")
    }

    @Test
    fun `the binding claiming the dialect is picked over the one claiming any SQL database`() {
        val onThisDialect = DoubledOn(dialectName())
        val onAnother = DoubledOn("NoSuchDatabase")
        val b = things(listOf(DoubledSql, onThisDialect, onAnother))
        DoubledSql.renders = 0; onThisDialect.renders = 0; onAnother.renders = 0
        b.check("Big", setOf("A", "C"), answers(StorageClass.INTEGER, ">=", b), 4, shape = "Thing")
        assertTrue(onThisDialect.renders > 0, "the binding claiming ${dialectName()} rendered the call")
        assertEquals(0, DoubledSql.renders, "the SQL-wide binding was not asked")
        assertEquals(0, onAnother.renders, "a binding claiming another dialect is loaded and ignored")
    }

    @Test
    fun `the store refuses two bindings claiming one function on one dialect, and names them`() {
        val error = assertFailsWith<IllegalStateException> { attach(spec = functionSpec, functionBindings = listOf(DoubledSql, DoubledWithDdl(emptyList()))) }
        assertTrue("velle.sqltest.doubled" in error.message!! && "DoubledSql" in error.message!! && "DoubledWithDdl" in error.message!!, error.message)
        val onDialect = assertFailsWith<IllegalStateException> { attach(spec = functionSpec, functionBindings = listOf(DoubledOn(dialectName()), DoubledOn(dialectName()))) }
        assertTrue(dialectName() in onDialect.message!!, onDialect.message)
    }

    @Test
    fun `the store refuses a binding reading an operand that is no parameter, and one for a function nothing registers`() {
        val wrongName = assertFailsWith<IllegalStateException> { attach(spec = functionSpec, functionBindings = listOf(DoubledWrongName)) }
        assertTrue("'amount'" in wrongName.message!! && "'value'" in wrongName.message!!, wrongName.message)
        val unregistered = assertFailsWith<IllegalStateException> { attach(spec = functionSpec, functionBindings = listOf(NothingSql)) }
        assertTrue("velle.sqltest.nothing" in unregistered.message!!, unregistered.message)
    }

    @Test
    fun `a binding's DDL runs with the schema, again harmlessly, and a refused one is reported with the function left to the body`() {
        val marker = "CREATE TABLE IF NOT EXISTS velle_sqltest_marker (x INTEGER)"
        val b = things(listOf(DoubledWithDdl(listOf(marker))))
        assertEquals(0L, b.count("velle_sqltest_marker"), "the DDL ran with the schema, in both processes")
        assertEquals(emptyMap(), b.store.renderingsNotInstalled)
        b.check("Big", setOf("A", "C"), answers(StorageClass.INTEGER, ">=", b), 4, shape = "Thing")

        val refused = things(listOf(DoubledWithDdl(listOf("CREATE FUNCTION velle_sqltest_bogus("))))
        assertEquals(setOf("velle.sqltest.doubled"), refused.store.renderingsNotInstalled.keys)
        refused.check("Big", setOf("A", "C"), pushed = false, total = 4, shape = "Thing")
    }

    @Test
    fun `a system that insists on a rendering does not start when its DDL is refused`() {
        val error = assertFailsWith<IllegalStateException> {
            attach(spec = functionSpec, functionBindings = listOf(DoubledWithDdl(listOf("CREATE FUNCTION velle_sqltest_bogus("))), insistRendered = setOf("velle.sqltest.doubled"))
        }
        assertTrue("velle.sqltest.doubled" in error.message!! && "insists" in error.message!!, error.message)
    }

    private fun dialectName(): String = store(system(functionSpec).model, ds(), emptyList()).dialect.name

    // ── references, unions, the join read ────────────────────────────────────

    @Test
    fun `a union-typed reference keeps its concrete shape and the join read finds it by key and shape`() {
        val a = attach()
        val post = accepted(a.sys.commit("Post", mapOf("title" to "Hello")))!!
        val photo = accepted(a.sys.commit("Photo", mapOf("caption" to "Sunset")))!!
        // Post#1 and Photo#1 share a key; the discriminator tells them apart
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(post), "body" to "first")))
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(photo), "body" to "nice")))
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(photo), "body" to "very")))
        assertEquals("Photo", a.scalar("""SELECT "target__shape" FROM "comment" WHERE body = 'nice'"""))

        val b = attach(a.ds)
        b.sys.scope {
            val p = b.sys.handleFor("Post", 1L)
            val ph = b.sys.handleFor("Photo", 1L)
            assertEquals(listOf("first"), (b.sys.get(p, "comments") as List<*>).map { b.sys.get(it as Long, "body") })
            assertEquals(listOf("nice", "very"), (b.sys.get(ph, "comments") as List<*>).map { b.sys.get(it as Long, "body") })
            assertEquals(listOf(ph), b.sys.instancesOf("Popular"))
            val c = b.sys.instancesOf("Comment").first()
            assertEquals("Post", b.sys.shapeOf(b.sys.get(c, "target") as Long))
        }
    }

    // ── several records in one statement ─────────────────────────────────────

    /** Notes the text of every statement the store prepares. */
    protected class Statements(private val inner: DataSource) : DataSource by inner {
        val sent = mutableListOf<String>()
        override fun getConnection(): Connection {
            val connection = inner.connection
            return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                if (method.name == "prepareStatement") sent += arguments[0] as String
                try { if (arguments == null) method.invoke(connection) else method.invoke(connection, *arguments) } catch (thrown: java.lang.reflect.InvocationTargetException) { throw thrown.targetException }
            } as Connection
        }
    }

    @Test
    fun `several keys are read in one statement, and each collection field of the rows in one more`() {
        val statements = Statements(ds())
        val a = attach(statements)
        val ada = a.customer("Ada", tags = listOf("vip", "early"))
        a.customer("Bo", tags = listOf("late"))
        val cy = a.customer("Cy", notes = listOf("called", "called"))
        statements.sent.clear()
        val rows = a.store.fetchByKeys("Customer", listOf(cy.key, ada.key, StoreKey(999L)))
        assertEquals(setOf("Ada", "Cy"), rows.map { row -> row.fields["name"] }.toSet(), "a key with no row is left out")
        val byName = rows.associateBy { row -> row.fields["name"] }
        assertEquals(listOf("vip", "early"), byName.getValue("Ada").fields["tags"], "each row has its own members, in their order")
        assertEquals(emptyList<Any>(), byName.getValue("Ada").fields["notes"])
        assertEquals(listOf("called", "called"), byName.getValue("Cy").fields["notes"])
        assertEquals(3, statements.sent.size, "the rows, their tags, their notes: ${statements.sent}")
        assertEquals(emptyList(), a.store.fetchByKeys("Customer", emptyList()))

        statements.sent.clear()
        val everyone = a.store.fetchAll("Customer")
        assertEquals(listOf(listOf("vip", "early"), listOf("late"), emptyList()), everyone.map { row -> row.fields["tags"] })
        assertEquals(3, statements.sent.size, "a scan reads each collection field once, not once per row: ${statements.sent}")

        // a narrowed scan carries the collection fields too, read once for the rows it returned
        a.customer("Di", tier = 3, tags = listOf("gold", "vip"))
        a.customer("Ed", tier = 4, tags = listOf("gold"))
        statements.sent.clear()
        a.sys.scope {
            val gold = a.sys.instancesOf("Gold")
            assertEquals(listOf(listOf("gold", "vip"), listOf("gold")), gold.map { customer -> a.sys.get(customer, "tags") })
        }
        assertEquals(3, statements.sent.size, statements.sent.toString())
    }

    @Test
    fun `the records pointing at any of several records are read in one statement, by key and shape for a union`() {
        val statements = Statements(ds())
        val a = attach(statements)
        val ada = a.customer("Ada")
        val bo = a.customer("Bo")
        val cy = a.customer("Cy")
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("1.00"))))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(bo), "total" to BigDecimal("2.00"))))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("3.00"))))
        statements.sent.clear()
        val orders = a.store.fetchReferencingAny("Order", "customer", listOf(ada, cy))
        assertEquals(listOf(BigDecimal("1.00"), BigDecimal("3.00")), orders.map { row -> row.fields["total"] })
        assertEquals(1, statements.sent.size, statements.sent.toString())
        assertEquals(emptyList(), a.store.fetchReferencingAny("Order", "customer", emptyList()))

        val post = accepted(a.sys.commit("Post", mapOf("title" to "Hello")))!!
        val photo = accepted(a.sys.commit("Photo", mapOf("caption" to "Sunset")))!!
        val otherPost = accepted(a.sys.commit("Post", mapOf("title" to "Again")))!!
        // Post#1 and Photo#1 share a key
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(post), "body" to "first")))
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(photo), "body" to "nice")))
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(otherPost), "body" to "again")))
        statements.sent.clear()
        val onPhotoAndOther = a.store.fetchReferencingAny("Comment", "target", listOf(photo, otherPost))
        assertEquals(listOf("nice", "again"), onPhotoAndOther.map { row -> row.fields["body"] }, "the comment on Post#1 is not one on Photo#1")
        assertEquals(1, statements.sent.size, statements.sent.toString())
    }

    // ── pre-filters the store is handed directly: another table, and a call under a text operator ──

    /** One field of the rows the store hands back for [filter], in the store's order. */
    private fun Attached.candidates(shape: String, filter: QF, field: String = "name"): List<Any?> =
        store.fetchCandidates(shape, filter).map { row -> row.fields[field] }

    @Test
    fun `a pre-filter over the records pointing at a row, or over the record a row points at, narrows the scan`() {
        val a = attach()
        val ada = a.customer("Ada", tier = 3)
        val bo = a.customer("Bo")
        a.customer("Cy")
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("50.00"))))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("5.00"))))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(bo), "total" to BigDecimal("6.00"))))
        val large = QF.Cmp("total", ">", QConst.QNum(BigDecimal("10")))
        val huge = QF.Cmp("total", ">", QConst.QNum(BigDecimal("1000")))
        // where the dialect's decimal column does not answer `>`, the inner comparison is left to the evaluator
        val decimalAnswered = answers(StorageClass.DECIMAL, ">", a)
        val withLargeOrder = if (decimalAnswered) listOf<Any?>("Ada") else listOf<Any?>("Ada", "Bo")
        assertEquals(withLargeOrder, a.candidates("Customer", QF.Exists("Order", "customer", large)))
        assertEquals(listOf<Any?>("Cy"), a.candidates("Customer", QF.Not(QF.Exists("Order", "customer", QF.True))))
        assertEquals(listOf<Any?>("Ada", "Bo", "Cy"), a.candidates("Customer", QF.Exists("Order", null, large)), "no reference field: any order at all")
        val withHugeOrderAnywhere = if (decimalAnswered) emptyList() else listOf<Any?>("Ada", "Bo", "Cy")
        assertEquals(withHugeOrderAnywhere, a.candidates("Customer", QF.Exists("Order", null, huge)))
        val gold = QF.Cmp("tier", ">=", QConst.QNum(BigDecimal(3)))
        assertEquals(listOf<Any?>(BigDecimal("50.00"), BigDecimal("5.00")), a.candidates("Order", QF.RelPred("customer", "Customer", gold), "total"))
        assertEquals(listOf<Any?>(BigDecimal("6.00")), a.candidates("Order", QF.Not(QF.RelPred("customer", "Customer", gold)), "total"))

        val post = accepted(a.sys.commit("Post", mapOf("title" to "Hello")))!!
        val photo = accepted(a.sys.commit("Photo", mapOf("caption" to "Sunset")))!!
        val otherPost = accepted(a.sys.commit("Post", mapOf("title" to "Again")))!!
        // Post#1 and Photo#1 share a key, and only the photo has a comment
        assertEquals(post.key, photo.key)
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(photo), "body" to "nice")))
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(otherPost), "body" to "again")))
        val commented = QF.Exists("Comment", "target", QF.True)
        assertEquals(listOf<Any?>("Again"), a.candidates("Post", commented, "title"), "the comment on Photo#1 is not one on Post#1")
        assertEquals(listOf<Any?>("Sunset"), a.candidates("Photo", commented, "caption"))
        // a hop through a union-typed reference names no one table: every row comes back, under `not` too
        val titled = QF.RelPred("target", "Content", QF.Cmp("title", "==", QConst.QText("Hello")))
        assertEquals(listOf<Any?>("nice", "again"), a.candidates("Comment", titled, "body"))
        assertEquals(listOf<Any?>("nice", "again"), a.candidates("Comment", QF.Not(titled), "body"))
    }

    @Test
    fun `a text operator over a rendered call narrows the scan as it does over a column`() {
        val a = attach(spec = functionSpec, functionBindings = listOf(TrimSql))
        for (name in listOf(" ab", "bad ", "cab", "xyz", "50%")) a.thing(name, 1, null)
        val trimmed = QCall("velle.trim", 0, mapOf("text" to QArg.Column("name", VType.Text)), VType.Text)
        fun named(op: String, text: String): QF = QF.CallCmp(trimmed, op, QConst.QText(text))
        assertEquals(listOf<Any?>(" ab"), a.candidates("Thing", named("starts with", "a")))
        assertEquals(listOf<Any?>("bad "), a.candidates("Thing", named("ends with", "d")))
        assertEquals(listOf<Any?>(" ab", "bad ", "cab"), a.candidates("Thing", named("contains", "a")))
        assertEquals(listOf<Any?>("cab"), a.candidates("Thing", named("like", "c_b")))
        assertEquals(listOf<Any?>("50%"), a.candidates("Thing", named("contains", "%")), "a wildcard in the constant is matched as itself")
        assertEquals(listOf<Any?>("bad ", "cab", "xyz", "50%"), a.candidates("Thing", QF.Not(named("starts with", "a"))))
        val everyName = listOf<Any?>(" ab", "bad ", "cab", "xyz", "50%")
        val matching = if (a.store.dialect.regexMatch("x") != null) listOf<Any?>("cab") else everyName
        assertEquals(matching, a.candidates("Thing", named("matches", "ca.")))
        // a text operator against a constant that is not text is left to the evaluator
        assertEquals(everyName, a.candidates("Thing", QF.CallCmp(trimmed, "contains", QConst.QNum(BigDecimal(1)))))
        // an underscore and a backslash in the constant are matched as themselves too
        a.thing("a_b", 1, null)
        a.thing("a\\b", 1, null)
        assertEquals(listOf<Any?>("a_b"), a.candidates("Thing", named("contains", "_")))
        assertEquals(listOf<Any?>("a\\b"), a.candidates("Thing", named("contains", "\\")))
    }

    @Test
    fun `a constant of each kind is compared as its column's type, and one that is no value of that type is left to the evaluator`() {
        val a = attach()
        val seen = Instant.parse("2026-01-10T12:34:56Z")
        val ada = a.customer("Ada", active = false, since = LocalDate.of(2019, 3, 4), lastSeen = seen, window = Duration.ofDays(21),
            opensAt = LocalTime.of(7, 45), avatar = byteArrayOf(1, 2))
        val bea = a.customer("Bea")
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("1.00"))))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(bea), "total" to BigDecimal("2.00"))))
        val both = listOf<Any?>("Ada", "Bea")
        /** Ada alone where the dialect's column answers `==`, every row where it does not. */
        fun adaWhere(storage: StorageClass, field: String, constant: QConst) {
            val expected = if (answers(storage, "==", a)) listOf<Any?>("Ada") else both
            assertEquals(expected, a.candidates("Customer", QF.Cmp(field, "==", constant)), field)
        }
        val token = a.store.fetchAll("Customer").first().fields["token"] as UUID
        adaWhere(StorageClass.BOOLEAN, "active", QConst.QBool(false))
        adaWhere(StorageClass.DATE, "since", QConst.QDate(LocalDate.of(2019, 3, 4)))
        adaWhere(StorageClass.DATETIME, "lastSeen", QConst.QDateTime(seen))
        adaWhere(StorageClass.DURATION, "window", QConst.QDuration(Duration.ofDays(21)))
        adaWhere(StorageClass.TIME, "opensAt", QConst.QTime(LocalTime.of(7, 45)))
        adaWhere(StorageClass.BYTES, "avatar", QConst.QBytes(byteArrayOf(1, 2)))
        adaWhere(StorageClass.UUID, "token", QConst.QUuid(token))
        adaWhere(StorageClass.TEXT, "name", QConst.QText("Ada"))
        // a reference column is compared by the record's key
        val adasOrders = if (answers(StorageClass.INTEGER, "==", a)) listOf<Any?>(BigDecimal("1.00")) else listOf<Any?>(BigDecimal("1.00"), BigDecimal("2.00"))
        assertEquals(adasOrders, a.candidates("Order", QF.Cmp("customer", "==", QConst.QRef(ada)), "total"))
        // no integer equals 1.5, no integer is a text, and no such column: each is left to the evaluator
        assertEquals(both, a.candidates("Customer", QF.Cmp("tier", "==", QConst.QNum(BigDecimal("1.5")))))
        assertEquals(both, a.candidates("Customer", QF.Cmp("tier", "==", QConst.QText("one"))))
        assertEquals(both, a.candidates("Customer", QF.Cmp("nickname", "==", QConst.QText("Ada"))))

        val isAda = QF.Cmp("name", "==", QConst.QText("Ada"))
        val textAnswered = answers(StorageClass.TEXT, "==", a)
        assertEquals(if (textAnswered) listOf<Any?>("Ada") else both, a.candidates("Customer", QF.Or(isAda, QF.False)))
        assertEquals(emptyList(), a.candidates("Customer", QF.And(isAda, QF.False)))
        assertEquals(if (textAnswered) listOf<Any?>("Bea") else both, a.candidates("Customer", QF.And(QF.Not(isAda), QF.Not(QF.False))))
    }

    @Test
    fun `a binding's expression of each kind is written out with its constants bound in order`() {
        val two = listOf<Any>(2, 2L, BigDecimal(2), 2.0)
        val doublings: List<(SqlExpr) -> SqlExpr> = two.map { literal -> { value: SqlExpr -> SqlExpr.Infix(value, "*", SqlExpr.Literal(literal)) } } + listOf(
            { value -> SqlExpr.Raw("({0} + {0})", value) },
            { value -> SqlExpr.Infix(SqlExpr.Cast(value, StorageClass.INTEGER), "*", SqlExpr.Literal(2)) },
            { value -> SqlExpr.Call("ABS", SqlExpr.Infix(value, "*", SqlExpr.Literal(-2))) },
        )
        for (doubling in doublings) {
            val b = things(listOf(DoubledAs(doubling)))
            b.check("Big", setOf("A", "C"), answers(StorageClass.INTEGER, ">=", b), 4, shape = "Thing")
            b.check("NotSix", setOf("B", "C", "D"), answers(StorageClass.INTEGER, "!=", b), 4, shape = "Thing")
        }
        val b = things(listOf(DoubledSql, IsEvenIsTrueSql, TrimOfJoinedSql))
        b.check("Even", setOf("D"), pushed = true, total = 4, shape = "Thing")
        val trimmed = QCall("velle.trim", 0, mapOf("text" to QArg.Column("name", VType.Text)), VType.Text)
        assertEquals(listOf<Any?>("A"), b.candidates("Thing", QF.CallCmp(trimmed, "starts with", QConst.QText("A"))))

        // a constant operand is bound as the type its signature read it as
        val integer = VType.Num("integer")
        val doubledThree = QCall("velle.sqltest.doubled", 0, mapOf("value" to QArg.Const(QConst.QNum(BigDecimal(3)), integer)), integer)
        val everyThing = listOf<Any?>("A", "B", "C", "D")
        val pushed = answers(StorageClass.INTEGER, ">=", b)
        assertEquals(everyThing, b.candidates("Thing", QF.CallCmp(doubledThree, ">=", QConst.QNum(BigDecimal(6)))))
        assertEquals(if (pushed) emptyList() else everyThing, b.candidates("Thing", QF.CallCmp(doubledThree, ">=", QConst.QNum(BigDecimal(7)))))
        // a function with no binding in this store, and an operand that is no value of its type, are the evaluator's
        val unbound = QCall("velle.sqltest.isEven", 0, mapOf("value" to QArg.Column("n", integer)), VType.Bool)
        assertEquals(everyThing, things(listOf(DoubledSql)).candidates("Thing", QF.Pred(unbound)))
        val doubledText = QCall("velle.sqltest.doubled", 0, mapOf("value" to QArg.Const(QConst.QText("three"), integer)), integer)
        assertEquals(everyThing, b.candidates("Thing", QF.CallCmp(doubledText, ">=", QConst.QNum(BigDecimal(7)))))
    }

    // ── captures across processes, re-entry, retraction ──────────────────────

    @Test
    fun `capture memory survives the process, is overwritten on re-entry, and goes on exit`() {
        val a = attach()
        val ada = a.customer("Ada", tier = 1)
        accepted(a.sys.commit("SetTier", mapOf("customer" to a.id(ada), "tier" to 5)))
        assertEquals(1L, a.count("capture__flagged"))
        assertEquals(5L, (a.scalar("""SELECT "flagged_tier" FROM "capture__flagged"""") as Number).toLong())

        // the next process raises the tier again while still flagged: the memory is untouched
        val b = attach(a.ds)
        val adaB = Ref.Persisted("Customer", StoreKey((a.scalar("""SELECT id FROM "customer"""") as Number).toLong()))
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 7)))
        assertEquals(5L, (b.scalar("""SELECT "flagged_tier" FROM "capture__flagged"""") as Number).toLong())

        // exit: the notice reads the captured values, the row goes
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 1)))
        assertEquals(0L, b.count("capture__flagged"))
        assertEquals(1L, b.count("unflag_notice"))
        assertEquals(5L, (b.scalar("""SELECT "tier_then" FROM "unflag_notice"""") as Number).toLong())

        // re-entry writes fresh memory into the same row key: the upsert path
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 9)))
        assertEquals(1L, b.count("capture__flagged"))
        assertEquals(9L, (b.scalar("""SELECT "flagged_tier" FROM "capture__flagged"""") as Number).toLong())
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 2)))
        assertEquals(2L, b.count("unflag_notice"))
    }

    // ── ticks: one moment per schedule, kept across processes ────────────────

    @Test
    fun `the moment a schedule last ran survives the process, one per schedule, replaced by its next tick`() {
        val permitSpec = """
            act Permit { site: text  expiresOn: Date }
            shape Valid = Permit where expiresOn >= today
            shape Lapse { permit: one Permit  noticedOn: Date }
            rule NoticeLapse when leaving Valid on Nightly { Lapse from { permit: this, noticedOn: today } }
            shape Renewal { permit: one Permit }
            rule OfferRenewal when Valid on Weekly { Renewal from { permit: this } }
        """.trimIndent()
        val firstProcess = attach(spec = permitSpec)
        assertNull(firstProcess.sys.latestTick("Nightly"), "a schedule that has never run has no latest tick")
        accepted(firstProcess.sys.commit("Permit", mapOf("site" to "north", "expiresOn" to LocalDate.of(2026, 1, 20))))
        val firstNight = firstProcess.sys.now
        firstProcess.sys.tick("Nightly")
        val weeklyRun = firstNight.plusSeconds(3_600)
        firstProcess.sys.setTime(weeklyRun)
        firstProcess.sys.tick("Weekly")

        // the next process, over the same database, after the permit expired
        val secondProcess = attach(firstProcess.ds, spec = permitSpec)
        assertEquals(firstNight, secondProcess.sys.latestTick("Nightly"))
        assertEquals(weeklyRun, secondProcess.sys.latestTick("Weekly"))
        val laterNight = Instant.parse("2026-01-25T09:00:00Z")
        secondProcess.sys.setTime(laterNight)
        secondProcess.sys.tick("Nightly")
        assertEquals(1L, secondProcess.count("lapse"), "the permit left Valid since the tick the first process ran")
        assertEquals(laterNight, secondProcess.sys.latestTick("Nightly"), "the next tick replaces the moment")
        assertEquals(weeklyRun, secondProcess.sys.latestTick("Weekly"), "and leaves the other schedule's alone")
        assertEquals(2L, secondProcess.count("velle__tick"), "one moment per schedule, however many ticks")
    }

    // ── deletion: the row and what hangs off it go, and every reference at it is emptied ──

    @Test
    fun `deleting a row removes its collections and captures and empties every reference at it`() {
        val a = attach()
        val ada = a.customer("Ada", tier = 6, tags = listOf("vip"), notes = listOf("x"))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("5.00"))))
        assertEquals(1L, a.count("capture__flagged"))
        assertEquals(1L, a.count("customer__tags"))
        val key = (a.scalar("""SELECT id FROM "customer"""") as Number).toLong()

        accepted(a.sys.commit("Remove", mapOf("customer" to a.id(ada))))
        assertEquals(0L, a.count("customer"))
        assertEquals(0L, a.count("customer__tags"))
        assertEquals(0L, a.count("customer__notes"))
        assertEquals(0L, a.count("capture__flagged"))
        assertEquals(1L, a.count("order"), "the order stays")
        assertNull(a.scalar("""SELECT customer FROM "order""""), "the surviving row's reference is emptied")

        val b = attach(a.ds)
        b.sys.scope {
            val o = b.sys.instancesOf("Order").single()
            assertNull(b.sys.get(o, "customer"), "a reference at a deleted row reads as absent")
        }
        // a new customer never inherits the deleted row's key
        b.customer("Bea")
        assertTrue((b.scalar("""SELECT id FROM "customer"""") as Number).toLong() > key)
    }

    protected val pointedAtSpec = """
        act Seller { name: text }
        act Post { title: text }
        act Photo { caption: text }
        shape Content = Post or Photo
        act Listing {
            title: text
            seller: one Seller? initially required
        }
        act Bookmark {
            label: text
            target: one Content?
        }
        act Shelf {
            label: text
            sellers: many Seller
            items: many Content
        }

        transient act CloseAccount { seller: one Seller }
        rule ApplyClose when CloseAccount { delete seller }

        transient act RemovePost { post: one Post }
        rule ApplyRemovePost when RemovePost { delete post }

        transient act CloseAndRetitle {
            seller: one Seller
            listing: one Listing
            title: text
        }
        rule ApplyCloseAndRetitle when CloseAndRetitle {
            listing.title = title
            delete seller
        }

        transient act Retitle {
            listing: one Listing
            title: text
        }
        rule ApplyRetitle when Retitle { listing.title = title }
    """.trimIndent()

    @Test
    fun `deleting a row empties a union reference at it and leaves one at another shape's row with the same key`() {
        val a = attach(spec = pointedAtSpec)
        val post = accepted(a.sys.commit("Post", mapOf("title" to "Hello")))!!
        val photo = accepted(a.sys.commit("Photo", mapOf("caption" to "Sunset")))!!
        assertEquals(post.key, photo.key, "the first Post and the first Photo share a key")
        accepted(a.sys.commit("Bookmark", mapOf("label" to "on the post", "target" to a.id(post))))
        accepted(a.sys.commit("Bookmark", mapOf("label" to "on the photo", "target" to a.id(photo))))

        accepted(a.sys.commit("RemovePost", mapOf("post" to a.id(post))))
        assertNull(a.scalar("""SELECT "target" FROM "bookmark" WHERE label = 'on the post'"""), "the key column is emptied")
        assertNull(a.scalar("""SELECT "target__shape" FROM "bookmark" WHERE label = 'on the post'"""), "the shape column is emptied with it")
        assertEquals(photo.key.value, (a.scalar("""SELECT "target" FROM "bookmark" WHERE label = 'on the photo'""") as Number).toLong())
        assertEquals("Photo", a.scalar("""SELECT "target__shape" FROM "bookmark" WHERE label = 'on the photo'"""))
    }

    @Test
    fun `deleting a row takes it out of every collection that held it`() {
        val a = attach(spec = pointedAtSpec)
        val ada = accepted(a.sys.commit("Seller", mapOf("name" to "Ada")))!!
        val bea = accepted(a.sys.commit("Seller", mapOf("name" to "Bea")))!!
        val post = accepted(a.sys.commit("Post", mapOf("title" to "Hello")))!!
        val photo = accepted(a.sys.commit("Photo", mapOf("caption" to "Sunset")))!!
        accepted(a.sys.commit("Shelf", mapOf(
            "label" to "all",
            "sellers" to listOf(a.id(ada), a.id(bea)),
            "items" to listOf(a.id(post), a.id(photo)),
        )))

        accepted(a.sys.commit("CloseAccount", mapOf("seller" to a.id(ada))))
        accepted(a.sys.commit("RemovePost", mapOf("post" to a.id(post))))
        assertEquals(1L, a.count("shelf__sellers"))
        assertEquals(bea.key.value, (a.scalar("""SELECT target FROM "shelf__sellers"""") as Number).toLong())
        assertEquals(1L, a.count("shelf__items"))
        assertEquals("Photo", a.scalar("""SELECT target_shape FROM "shelf__items""""), "the photo with the post's key stays")

        val b = attach(a.ds, spec = pointedAtSpec)
        b.sys.scope {
            val shelf = b.sys.instancesOf("Shelf").single()
            assertEquals(1, (b.sys.get(shelf, "sellers") as List<*>).size)
            assertEquals(1, (b.sys.get(shelf, "items") as List<*>).size)
        }
    }

    @Test
    fun `a commit that deletes a row and assigns another field of a row pointing at it leaves the reference empty`() {
        val a = attach(spec = pointedAtSpec)
        val ada = accepted(a.sys.commit("Seller", mapOf("name" to "Ada")))!!
        val lamp = accepted(a.sys.commit("Listing", mapOf("title" to "Lamp", "seller" to a.id(ada))))!!

        accepted(a.sys.commit("CloseAndRetitle", mapOf("seller" to a.id(ada), "listing" to a.id(lamp), "title" to "Brass lamp")))
        assertEquals("Brass lamp", a.scalar("""SELECT title FROM "listing""""))
        assertNull(a.scalar("""SELECT seller FROM "listing""""))
    }

    @Test
    fun `a later commit assigning another field of a row whose reference was emptied does not write the deleted row's key back`() {
        val a = attach(spec = pointedAtSpec)
        val ada = accepted(a.sys.commit("Seller", mapOf("name" to "Ada")))!!
        val lamp = accepted(a.sys.commit("Listing", mapOf("title" to "Lamp", "seller" to a.id(ada))))!!
        accepted(a.sys.commit("CloseAccount", mapOf("seller" to a.id(ada))))

        accepted(a.sys.commit("Retitle", mapOf("listing" to a.id(lamp), "title" to "Brass lamp")))
        assertEquals("Brass lamp", a.scalar("""SELECT title FROM "listing""""))
        assertNull(a.scalar("""SELECT seller FROM "listing""""))
    }

    @Test
    fun `deleting a row empties a reference a refinement remembered`() {
        val escalationSpec = """
            act Agent { name: text }
            act Ticket {
                subject: text
                agent: one Agent? initially required
                urgent: boolean
            }
            shape Escalated = Ticket where urgent {
                captured escalatedWith: one Agent? = agent
            }
            transient act Dismiss { agent: one Agent }
            rule ApplyDismiss when Dismiss { delete agent }
        """.trimIndent()
        val a = attach(spec = escalationSpec)
        val ada = accepted(a.sys.commit("Agent", mapOf("name" to "Ada")))!!
        accepted(a.sys.commit("Ticket", mapOf("subject" to "Outage", "agent" to a.id(ada), "urgent" to true)))
        assertEquals(ada.key.value, (a.scalar("""SELECT "escalated_with" FROM "capture__escalated"""") as Number).toLong())

        accepted(a.sys.commit("Dismiss", mapOf("agent" to a.id(ada))))
        assertEquals(1L, a.count("capture__escalated"), "the ticket is still escalated")
        assertNull(a.scalar("""SELECT "escalated_with" FROM "capture__escalated""""))
    }

    @Test
    fun `a remembered reference reads back as its record, and survives the deletion of another shape's record with the same key`() {
        val escalationSpec = """
            act Agent { name: text }
            act Ticket {
                subject: text
                agent: one Agent? initially required
                urgent: boolean
            }
            shape Escalated = Ticket where urgent {
                captured escalatedWith: one Agent? = agent
            }
            transient act Close { ticket: one Ticket }
            rule ApplyClose when Close { delete ticket }
        """.trimIndent()
        val a = attach(spec = escalationSpec)
        val ada = accepted(a.sys.commit("Agent", mapOf("name" to "Ada")))!!
        val outage = accepted(a.sys.commit("Ticket", mapOf("subject" to "Outage", "agent" to a.id(ada), "urgent" to false)))!!
        val fire = accepted(a.sys.commit("Ticket", mapOf("subject" to "Fire", "agent" to a.id(ada), "urgent" to true)))!!
        assertEquals(ada.key, outage.key, "Agent#1 and Ticket#1 share a key")

        val b = attach(a.ds, spec = escalationSpec)
        assertEquals(mapOf<String, Any?>("escalatedWith" to ada), b.store.fetchCaptures(fire, "Escalated"))
        assertNull(b.store.fetchCaptures(outage, "Escalated"), "a ticket that is not escalated remembers nothing")

        accepted(b.sys.commit("Close", mapOf("ticket" to b.id(outage))))
        assertEquals(mapOf<String, Any?>("escalatedWith" to ada), b.store.fetchCaptures(fire, "Escalated"), "the closed ticket is not the agent")
    }

    // ── foreign keys: the database refuses a reference to a row that is not there ──

    /** A connection of the engineer's own, set up as the store sets up each of its connections. */
    private fun Attached.preparedConnection(): Connection = ds.connection.also { connection -> store.dialect.prepare(connection) }

    private fun Attached.executePrepared(sql: String) =
        preparedConnection().use { connection -> connection.createStatement().use { statement -> statement.execute(sql) } }

    /** Runs [commit] in a write unit of the store's own, as the runtime does, and ends the unit with how it went. */
    private fun Attached.commitDirectly(commit: CommitSet): List<StoreKey> {
        val unit = store.begin(WorkKind.WRITE)
        val minted = try {
            store.onCommit(commit)
        } catch (failure: Exception) {
            unit.end(Outcome.Failed(failure))
            throw failure
        }
        unit.end(Outcome.Committed)
        return minted
    }

    @Test
    fun `the database refuses to remove a row a reference or a collection still holds, and a rule's delete still commits`() {
        val a = attach(spec = pointedAtSpec)
        val ada = accepted(a.sys.commit("Seller", mapOf("name" to "Ada")))!!
        val bea = accepted(a.sys.commit("Seller", mapOf("name" to "Bea")))!!
        accepted(a.sys.commit("Listing", mapOf("title" to "Lamp", "seller" to a.id(ada))))
        accepted(a.sys.commit("Shelf", mapOf("label" to "all", "sellers" to listOf(a.id(bea)), "items" to emptyList<Any>())))

        assertFailsWith<Exception>("a listing's seller") { a.executePrepared("""DELETE FROM "seller" WHERE id = ${ada.key.value}""") }
        assertFailsWith<Exception>("a shelf's member") { a.executePrepared("""DELETE FROM "seller" WHERE id = ${bea.key.value}""") }
        assertEquals(2L, a.count("seller"))

        accepted(a.sys.commit("CloseAccount", mapOf("seller" to a.id(ada))))
        accepted(a.sys.commit("CloseAccount", mapOf("seller" to a.id(bea))))
        assertEquals(0L, a.count("seller"))
        assertNull(a.scalar("""SELECT seller FROM "listing""""))
        assertEquals(0L, a.count("shelf__sellers"))
    }

    @Test
    fun `the store's own connections enforce the foreign key, so a commit naming a record that is not there fails`() {
        val a = attach(spec = pointedAtSpec)
        val missingSeller = Ref.Persisted("Seller", StoreKey(999L))
        val listing = CommitSet.Creation("Listing", mapOf("title" to "Lamp", "seller" to missingSeller))
        assertFailsWith<Exception> { a.commitDirectly(CommitSet(created = listOf(listing), assigned = emptyList())) }
        assertEquals(0L, a.count("listing"), "the failed commit wrote nothing")
    }

    @Test
    fun `one commit creating a record and a record pointing at it writes them in an order the foreign key accepts`() {
        val a = attach(spec = pointedAtSpec)
        val seller = CommitSet.Creation("Seller", mapOf("name" to "Ada"))
        val listing = CommitSet.Creation("Listing", mapOf("title" to "Lamp", "seller" to Ref.Pending("Seller", 0)))
        val shelf = CommitSet.Creation("Shelf", mapOf("label" to "all", "sellers" to listOf(Ref.Pending("Seller", 0)), "items" to emptyList<Any>()))
        val minted = a.commitDirectly(CommitSet(created = listOf(seller, listing, shelf), assigned = emptyList()))
        assertEquals(minted[0].value, (a.scalar("""SELECT seller FROM "listing"""") as Number).toLong())
        assertEquals(minted[0].value, (a.scalar("""SELECT target FROM "shelf__sellers"""") as Number).toLong())
    }

    @Test
    fun `two records pointing at each other are accepted by the foreign key`() {
        val partnerSpec = """
            act Dancer {
                name: text
                partner: one Dancer?
            }
            transient act Pair {
                dancer: one Dancer
                partner: one Dancer
            }
            rule ApplyPair when Pair { dancer.partner = partner }
        """.trimIndent()
        val a = attach(spec = partnerSpec)
        val ada = accepted(a.sys.commit("Dancer", mapOf("name" to "Ada")))!!
        val bea = accepted(a.sys.commit("Dancer", mapOf("name" to "Bea", "partner" to a.id(ada))))!!
        accepted(a.sys.commit("Pair", mapOf("dancer" to a.id(ada), "partner" to a.id(bea))))
        assertEquals(bea.key.value, (a.scalar("""SELECT partner FROM "dancer" WHERE name = 'Ada'""") as Number).toLong())
        assertFailsWith<Exception> { a.executePrepared("""DELETE FROM "dancer" WHERE id = ${ada.key.value}""") }
    }

    @Test
    fun `a reference typed with a union has no foreign key`() {
        val a = attach(spec = pointedAtSpec)
        val post = accepted(a.sys.commit("Post", mapOf("title" to "Hello")))!!
        accepted(a.sys.commit("Bookmark", mapOf("label" to "on the post", "target" to a.id(post))))
        a.executePrepared("""DELETE FROM "post" WHERE id = ${post.key.value}""")
        assertEquals(0L, a.count("post"))
    }

    @Test
    fun `a table that already exists is left as it is, without a foreign key`() {
        val dataSource = ds()
        val dialect = store(system(pointedAtSpec).model, dataSource, emptyList()).dialect
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""CREATE TABLE "listing" (id ${dialect.keyColumn}, "title" TEXT, "seller" BIGINT)""")
            }
        }
        val a = attach(dataSource, spec = pointedAtSpec)
        val ada = accepted(a.sys.commit("Seller", mapOf("name" to "Ada")))!!
        val bea = accepted(a.sys.commit("Seller", mapOf("name" to "Bea")))!!
        accepted(a.sys.commit("Listing", mapOf("title" to "Lamp", "seller" to a.id(ada))))
        accepted(a.sys.commit("Shelf", mapOf("label" to "all", "sellers" to listOf(a.id(bea)), "items" to emptyList<Any>())))

        a.executePrepared("""DELETE FROM "seller" WHERE id = ${ada.key.value}""")
        assertEquals(ada.key.value, (a.scalar("""SELECT seller FROM "listing"""") as Number).toLong(), "the existing table has no foreign key")
        assertFailsWith<Exception>("a table the store created has its foreign key") {
            a.executePrepared("""DELETE FROM "seller" WHERE id = ${bea.key.value}""")
        }
    }

    // ── names: every table and column in snake case, and two Velle names that give one are refused ──

    @Test
    fun `every table and column the store creates is named in snake case`() {
        val a = attach()
        val ada = a.customer("Ada", tier = 6, tags = listOf("vip"), lastSeen = Instant.parse("2025-12-31T23:59:59Z"))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("5.00"))))
        val photo = accepted(a.sys.commit("Photo", mapOf("caption" to "Sunset")))!!
        accepted(a.sys.commit("Comment", mapOf("target" to a.id(photo), "body" to "nice")))

        assertNotNull(a.scalar("""SELECT last_seen FROM customer"""), "a field in camel case is a column in snake case")
        assertEquals(1L, a.count("customer__tags"))
        assertEquals(ada.key.value, (a.scalar("""SELECT customer FROM "order"""") as Number).toLong(), "a table named for an SQL keyword is quoted")
        assertEquals("Photo", a.scalar("""SELECT target__shape FROM comment"""), "the shape column holds the Velle name of the shape")
        assertEquals(6L, (a.scalar("""SELECT flagged_tier FROM capture__flagged""") as Number).toLong())
        assertEquals(0L, a.count("velle__tick"))
    }

    private fun refusalOfNames(spec: String): String =
        assertFailsWith<IllegalStateException> { store(system(spec).model, ds(), emptyList()) }.message!!

    @Test
    fun `the store refuses to start when two shapes give one table name`() {
        val message = refusalOfNames("""
            act HTTPServer { hostName: text }
            act HttpServer { port: integer }
        """.trimIndent())
        assertTrue("shape 'HTTPServer'" in message && "shape 'HttpServer'" in message && "'http_server'" in message, message)
    }

    @Test
    fun `the store refuses to start when two fields of one shape give one column name`() {
        val message = refusalOfNames("""
            act Page {
                pathURL: text
                pathUrl: text
            }
        """.trimIndent())
        assertTrue("field 'Page.pathURL'" in message && "field 'Page.pathUrl'" in message && "'path_url'" in message && "'page'" in message, message)
    }

    @Test
    fun `the store refuses to start when a shape's table would be one of the store's own`() {
        val message = refusalOfNames("""
            act Velle { tick: many text }
        """.trimIndent())
        assertTrue("field 'Velle.tick'" in message && "'velle__tick'" in message, message)
    }

    // ── E4: an extension type's binding, checked at start-up through the dialect's column ──

    protected val moneySpec = """
        act Account { balance: Money initially 0 }
        act Deposit {
            account: one Account
            amount: Money
        }
        rule ApplyDeposit when Deposit { account.balance = account.balance + amount }
        shape Funded = Account where balance >= 10
    """.trimIndent()

    // ── the unit of work: a connection and a transaction per read or envelope ──

    @Test
    fun `every unit of work takes one connection and returns it when the unit ends`() {
        val leases = Leases(ds())
        val a = attach(leases)
        val afterSchema = leases.taken
        val ada = a.customer("Ada")
        assertEquals(0, leases.open, "the commit's write unit returned its connection")
        a.sys.scope { assertEquals("Ada", a.sys.get(a.id(ada), "name")) }
        assertEquals(0, leases.open, "the read's unit returned its connection when the scope closed")
        assertTrue(leases.taken >= afterSchema + 2, "one connection per unit: a commit and a read took at least two (${leases.taken - afterSchema})")
        a.sys.scope {
            a.sys.get(a.id(ada), "name")
            assertEquals(1, leases.open, "inside the scope the read unit holds exactly one connection")
            a.sys.instancesOf("Gold")
            assertEquals(1, leases.open, "every fetch of one read shares that connection")
        }
        assertEquals(0, leases.open)
    }

    @Test
    fun `a read answers from one snapshot while a writer commits on another connection`() {
        val a = attach()
        val ada = a.customer("Ada")
        val bea = a.customer("Bea")
        a.sys.scope {
            assertEquals("Ada", a.sys.get(a.id(ada), "name")) // the read unit's snapshot is taken here
            a.execute("""UPDATE "customer" SET "name" = 'Beatrix' WHERE "name" = 'Bea'""") // another connection, committed
            assertEquals("Bea", a.sys.get(a.id(bea), "name"), "the read sees the state it started from, not the write landed since")
        }
        a.sys.scope { assertEquals("Beatrix", a.sys.get(a.id(bea), "name"), "the next read sees it") }
    }

    @Test
    fun `a commit the store fails partway through leaves nothing behind`() {
        val a = attach()
        a.customer("Ada")
        // the customer's own row is written before its tags, and the tags now have nowhere to go
        a.execute("""DROP TABLE "customer__tags"""")
        assertFailsWith<Exception> { a.customer("Bea", tags = listOf("vip")) }
        assertEquals(1L, a.count("customer"), "Bea's row went with the failed commit")
    }

    @Test
    fun `a commit handed to the store with no write unit open is refused and writes nothing`() {
        val a = attach()
        val byHand = CommitSet(created = listOf(CommitSet.Creation("Post", mapOf("title" to "Hello"))), assigned = emptyList())
        // the commit callback is the runtime's to call, inside the write unit it opened
        assertFailsWith<IllegalStateException> { a.store.onCommit(byHand) }
        val readUnit = a.store.begin(WorkKind.READ)
        try {
            assertFailsWith<IllegalStateException> { a.store.onCommit(byHand) }
        } finally {
            readUnit.end(Outcome.Committed)
        }
        assertEquals(0L, a.count("post"))
    }

    @Test
    fun `a refused envelope writes nothing and returns its connection`() {
        val leases = Leases(ds())
        val a = attach(leases, spec = spec + "\nnever (Customer where visits > 1000)")
        val r = a.sys.commit("Customer", mapOf("name" to "Zed", "tier" to 1, "balance" to BigDecimal.ONE, "score" to 0.1, "visits" to 5000L,
            "active" to true, "since" to LocalDate.of(2024, 1, 1), "window" to Duration.ofDays(1), "tags" to emptyList<String>(), "notes" to emptyList<String>()))
        assertIs<CommitResult.Refused>(r)
        assertEquals(0L, a.count("customer"))
        assertEquals(0, leases.open)
    }

    @Test
    fun `two envelopes sharing a queue key run one at a time and disjoint keys do not wait`() {
        val a = attach()
        if (!a.store.dialect.serializesKeys) return // one writer at a time is this database's own rule
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val shared = AdmissionKey.Row("Customer", StoreKey(1L))
        val holder = Thread {
            val u = a.store.begin(WorkKind.WRITE)
            u.serialize(setOf(shared))
            held.countDown()
            release.await()
            u.end(Outcome.Committed)
        }.apply { start() }
        held.await()
        val disjointStarted = System.nanoTime()
        Thread {
            val u = a.store.begin(WorkKind.WRITE)
            u.serialize(setOf(AdmissionKey.Row("Customer", StoreKey(2L))))
            u.end(Outcome.Committed)
        }.apply { start(); join() }
        assertTrue(System.nanoTime() - disjointStarted < 2_000_000_000L, "a disjoint key does not wait for the held one")
        var waited = -1L
        val waiter = Thread {
            val u = a.store.begin(WorkKind.WRITE)
            val t0 = System.nanoTime()
            u.serialize(setOf(shared))
            waited = System.nanoTime() - t0
            u.end(Outcome.Committed)
        }.apply { start() }
        Thread.sleep(300)
        release.countDown()
        holder.join(); waiter.join()
        assertTrue(waited >= 200_000_000L, "the second envelope on the key waited for the first (${waited / 1_000_000} ms)")
    }

    @Test
    fun `a queue key that cannot be taken in time fails the envelope`() {
        val a = attach()
        if (!a.store.dialect.serializesKeys) return
        val impatient = store(a.sys.model, a.ds, emptyList(), lockTimeout = Duration.ofMillis(200))
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val key = AdmissionKey.Shape("Customer")
        val holder = Thread {
            val u = a.store.begin(WorkKind.WRITE)
            u.serialize(setOf(key))
            held.countDown(); release.await()
            u.end(Outcome.Committed)
        }.apply { start() }
        held.await()
        var failure: Throwable? = null
        Thread {
            val u = impatient.begin(WorkKind.WRITE)
            try { u.serialize(setOf(key)) } catch (e: Exception) { failure = e; u.end(Outcome.Failed(e)) }
        }.apply { start(); join() }
        release.countDown(); holder.join()
        assertNotNull(failure, "the lock timed out")
    }

    protected class CentsBinding : SqlTypeBinding<Money> {
        override val type = "velle.finance.Money"
        override val storage = StorageClass.INTEGER
        override fun encode(value: Money): Any = value.cents
        override fun decode(stored: Any): Money = Money.ofCents(stored as Long)
        override val answers = ALL_COMPARISONS
    }

    @Test
    fun `an extension type flows through its binding and the comparison is pushed down`() {
        val a = attach(spec = moneySpec, bindings = listOf(CentsBinding()))
        val acct = accepted(a.sys.commit("Account", emptyMap()))!!
        accepted(a.sys.commit("Account", emptyMap()))
        accepted(a.sys.commit("Deposit", mapOf("account" to a.id(acct), "amount" to Money.of("12.50"))))
        assertEquals(1250L, (a.scalar("""SELECT balance FROM "account" WHERE balance > 0""") as Number).toLong())
        val b = attach(a.ds, spec = moneySpec, bindings = listOf(CentsBinding()))
        b.counting.reset()
        assertEquals(1, b.sys.scope { b.sys.instancesOf("Funded") }.size)
        assertEquals(1, b.counting.candidates.single().third, "the binding answers >=, so the store returns only the funded account of the two")
        assertEquals(Money.of("12.50"), b.sys.scope { b.sys.get(b.sys.instancesOf("Funded").single(), "balance") })
    }

    @Test
    fun `the store refuses to start without a binding for a stored type`() {
        val sys = system(moneySpec)
        val e = assertFailsWith<IllegalStateException> { store(sys.model, ds(), emptyList()) }
        assertTrue("has no binding for 'Money' (velle.finance.Money)" in e.message!!, e.message)
        assertTrue("SqlTypeBinding<Money>" in e.message!!, e.message)
    }

    @Test
    fun `the store refuses a binding that does not round-trip the type's samples`() {
        class Lying : SqlTypeBinding<Money> {
            override val type = "velle.finance.Money"
            override val storage = StorageClass.INTEGER
            override fun encode(value: Money): Any = value.cents / 100 // drops the cents
            override fun decode(stored: Any): Money = Money.ofCents(stored as Long * 100)
            override val answers = EQUALITY_ONLY
        }
        val sys = system(moneySpec)
        val e = assertFailsWith<IllegalStateException> { store(sys.model, ds(), listOf(Lying())) }
        assertTrue("does not round-trip 12.50" in e.message!!, e.message)
    }

    @Test
    fun `the store refuses a binding whose encoding is not its storage class's carrier`() {
        class WrongCarrier : SqlTypeBinding<Money> {
            override val type = "velle.finance.Money"
            override val storage = StorageClass.INTEGER
            override fun encode(value: Money): Any = value.toString() // text into an integer column
            override fun decode(stored: Any): Money = Money.of(stored.toString())
            override val answers = EQUALITY_ONLY
        }
        val sys = system(moneySpec)
        val e = assertFailsWith<IllegalStateException> { store(sys.model, ds(), listOf(WrongCarrier())) }
        assertTrue("names INTEGER storage but encodes 12.50 as String, not Long" in e.message!!, e.message)
    }
}
