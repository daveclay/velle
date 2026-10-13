package velle.generated.loyalty

import java.math.BigDecimal
import velle.generated.*
import velle.generated.Member
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import velle.CommitResult
import velle.generated.LoyaltyStoreOverGeneric
import velle.generated.LoyaltyStoreResolver
import velle.generated.LoyaltySystem
import velle.sqlite.SqliteStore

/**
 * The loyalty flows over real storage — derived history (the predecessor
 * recurrence, the referral chain) recomputed from rows on every read. Once
 * through the generic protocol, once through the generated typed store surface.
 */
class LoyaltyFlowOverSqliteTest : LoyaltyFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): LoyaltySystem = LoyaltySystem().also { SqliteStore.attach(it.system, conn) }
}

class LoyaltyFlowOverTypedStoreTest : LoyaltyFlowTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): LoyaltySystem = LoyaltySystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = LoyaltyStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(LoyaltyStoreResolver(store, sys.system.model), backend)
    }
}

/** Derived history across processes: nothing is stored but the facts, and a fresh process derives the same answers. */
class LoyaltyPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): LoyaltySystem = LoyaltySystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")

    private fun dues(sys: LoyaltySystem, m: Member, month: String, dueBy: LocalDate, paidOn: LocalDate) =
        accept(sys.commitDuesPayment(m, month, dueBy, paidOn))

    @Test
    fun `the streak derives from stored payments, and a late arrival in another process heals it`() {
        val first = process()
        accept(first.commitJoin("Ada"))
        val ada = first.members().last()
        dues(first, ada, "January", dueBy = LocalDate.of(2026, 1, 31), paidOn = LocalDate.of(2026, 1, 10))
        dues(first, ada, "March", dueBy = LocalDate.of(2026, 3, 31), paidOn = LocalDate.of(2026, 3, 5))
        assertEquals(2, first.read(ada).streak)

        val second = process()
        val ada2 = second.read.member(1L)
        assertEquals(2, second.read(ada2).streak, "the recurrence re-derives from the two stored payments")
        dues(second, ada2, "February", dueBy = LocalDate.of(2026, 2, 28), paidOn = LocalDate.of(2026, 2, 8))
        assertEquals(3, second.read(ada2).streak)
        val chain = second.duesPayments().filter { second.read(it).member == ada2 }.sortedBy { second.read(it).paidOn }
        assertEquals(listOf(1, 2, 3), chain.map { second.read(it).streakAfter })
    }

    @Test
    fun `the referral chain and the two-member promotion read across processes`() {
        val first = process()
        accept(first.commitJoin("Founder"))
        val founder = first.members().last()
        accept(first.commitJoin("Scout", referrer = founder))
        val scout = first.members().last()
        accept(first.commitJoin("Recruit", referrer = scout))

        val second = process()
        val founder2 = second.read.member(1L)
        val scout2 = second.read.member(2L)
        val recruit2 = second.read.member(3L)
        assertEquals(founder2, second.read(recruit2).foundingReferrer, "the chain walks stored references to its root")

        accept(second.commitPurchase(recruit2, BigDecimal("150")))
        assertEquals("gold", second.read(recruit2).tier)
        assertEquals("advocate", second.read(scout2).tier, "the referrer's promotion wrote a different stored row")
        assertEquals(1, second.thankYouNotes().size)
    }
}

