package velle.sql

import velle.CommitResult
import velle.Model
import velle.Parser
import velle.QF
import velle.Ref
import velle.Row
import velle.StateResolver
import velle.StoreKey
import velle.Validator
import velle.VelleSystem
import velle.finance.Money
import java.math.BigDecimal
import java.sql.Connection
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
 * and its upsert on re-entry; deletion with the absorbing reference; and E4
 * on an extension type's binding.
 *
 * A dialect module subclasses this with its connection and store.
 */
abstract class SqlStoreConformance {

    /** A connection to a fresh, empty database (or schema) of its own. */
    protected abstract fun connect(): Connection

    protected abstract fun store(model: Model, conn: Connection, bindings: List<SqlTypeBinding<*>>): SqlStore

    private val open = mutableListOf<Connection>()

    @AfterTest
    fun closeConnections() {
        for (c in open) runCatching { c.close() }
        open.clear()
    }

    protected fun conn(): Connection = connect().also { open += it }

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

    protected class Attached(val sys: VelleSystem, val store: SqlStore, val counting: Counting, val conn: Connection)

    protected fun attach(conn: Connection = conn(), spec: String = this.spec, bindings: List<SqlTypeBinding<*>> = emptyList()): Attached {
        val sys = system(spec)
        val store = store(sys.model, conn, bindings).apply { createSchema() }
        val counting = Counting(store)
        sys.connect(counting, store)
        return Attached(sys, store, counting, conn)
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

    protected fun Attached.scalar(sql: String): Any? =
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null } }

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
        val key = (a.scalar("""SELECT id FROM "Customer"""") as Number).toLong()

        val b = attach(a.conn)
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
    fun `absent optionals stay absent and an empty collection reads back empty`() {
        val a = attach()
        a.customer("Bea", email = null, lastSeen = null, opensAt = null, avatar = null)
        val key = (a.scalar("""SELECT id FROM "Customer"""") as Number).toLong()
        val b = attach(a.conn)
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

    /** The names of the customers in [refinement], and how many rows the store handed back for the scan. */
    private fun Attached.members(refinement: String): Pair<Set<String>, Int?> {
        counting.reset()
        val names = sys.scope { sys.instancesOf(refinement).map { sys.get(it, "name") as String }.toSet() }
        val scan = counting.candidates.singleOrNull { it.first == "Customer" }?.third
        return names to scan
    }

    /** [refinement] holds for exactly the customers in [expected]; when
     *  [pushed], the store handed back only the matching rows, otherwise every row. */
    private fun Attached.check(refinement: String, expected: Set<String>, pushed: Boolean, total: Int) {
        val (names, scanned) = members(refinement)
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
        val b = attach(a.conn)
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
        assertEquals("Photo", a.scalar("""SELECT "target__shape" FROM "Comment" WHERE body = 'nice'"""))

        val b = attach(a.conn)
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

    // ── captures across processes, re-entry, retraction ──────────────────────

    @Test
    fun `capture memory survives the process, is overwritten on re-entry, and goes on exit`() {
        val a = attach()
        val ada = a.customer("Ada", tier = 1)
        accepted(a.sys.commit("SetTier", mapOf("customer" to a.id(ada), "tier" to 5)))
        assertEquals(1L, a.count("capture_Flagged"))
        assertEquals(5L, (a.scalar("""SELECT "flaggedTier" FROM "capture_Flagged"""") as Number).toLong())

        // the next process raises the tier again while still flagged: the memory is untouched
        val b = attach(a.conn)
        val adaB = Ref.Persisted("Customer", StoreKey((a.scalar("""SELECT id FROM "Customer"""") as Number).toLong()))
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 7)))
        assertEquals(5L, (b.scalar("""SELECT "flaggedTier" FROM "capture_Flagged"""") as Number).toLong())

        // exit: the notice reads the captured values, the row goes
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 1)))
        assertEquals(0L, b.count("capture_Flagged"))
        assertEquals(1L, b.count("UnflagNotice"))
        assertEquals(5L, (b.scalar("""SELECT "tierThen" FROM "UnflagNotice"""") as Number).toLong())

        // re-entry writes fresh memory into the same row key: the upsert path
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 9)))
        assertEquals(1L, b.count("capture_Flagged"))
        assertEquals(9L, (b.scalar("""SELECT "flaggedTier" FROM "capture_Flagged"""") as Number).toLong())
        accepted(b.sys.commit("SetTier", mapOf("customer" to b.id(adaB), "tier" to 2)))
        assertEquals(2L, b.count("UnflagNotice"))
    }

    // ── deletion: the row and what hangs off it go, references at it read absent ──

    @Test
    fun `deleting a row removes its collections and captures and leaves references at it reading absent`() {
        val a = attach()
        val ada = a.customer("Ada", tier = 6, tags = listOf("vip"), notes = listOf("x"))
        accepted(a.sys.commit("Order", mapOf("customer" to a.id(ada), "total" to BigDecimal("5.00"))))
        assertEquals(1L, a.count("capture_Flagged"))
        assertEquals(1L, a.count("Customer__tags"))
        val key = (a.scalar("""SELECT id FROM "Customer"""") as Number).toLong()

        accepted(a.sys.commit("Remove", mapOf("customer" to a.id(ada))))
        assertEquals(0L, a.count("Customer"))
        assertEquals(0L, a.count("Customer__tags"))
        assertEquals(0L, a.count("Customer__notes"))
        assertEquals(0L, a.count("capture_Flagged"))
        assertEquals(key, (a.scalar("""SELECT customer FROM "Order"""") as Number).toLong(), "the surviving row still holds the dead key")

        val b = attach(a.conn)
        b.sys.scope {
            val o = b.sys.instancesOf("Order").single()
            assertNull(b.sys.get(o, "customer"), "a reference at a deleted row reads as absent")
        }
        // a new customer never inherits the dead key
        b.customer("Bea")
        assertTrue((b.scalar("""SELECT id FROM "Customer"""") as Number).toLong() > key)
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
        accepted(a.sys.commit("Deposit", mapOf("account" to a.id(acct), "amount" to Money.of("12.50"))))
        assertEquals(1250L, (a.scalar("""SELECT balance FROM "Account"""") as Number).toLong())
        val b = attach(a.conn, spec = moneySpec, bindings = listOf(CentsBinding()))
        b.counting.reset()
        assertEquals(1, b.sys.scope { b.sys.instancesOf("Funded") }.size)
        assertEquals(1, b.counting.candidates.single().third, "the binding answers >=, so the store returns only the funded account")
        assertEquals(Money.of("12.50"), b.sys.scope { b.sys.get(b.sys.instancesOf("Account").single(), "balance") })
    }

    @Test
    fun `the store refuses to start without a binding for a stored type`() {
        val sys = system(moneySpec)
        val e = assertFailsWith<IllegalStateException> { store(sys.model, conn(), emptyList()) }
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
        val e = assertFailsWith<IllegalStateException> { store(sys.model, conn(), listOf(Lying())) }
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
        val e = assertFailsWith<IllegalStateException> { store(sys.model, conn(), listOf(WrongCarrier())) }
        assertTrue("names INTEGER storage but encodes 12.50 as String, not Long" in e.message!!, e.message)
    }
}
