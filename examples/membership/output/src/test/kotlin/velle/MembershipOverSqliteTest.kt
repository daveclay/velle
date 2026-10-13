package velle

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
import kotlin.test.assertTrue
import velle.generated.MembershipStoreOverGeneric
import velle.generated.MembershipStoreResolver
import velle.generated.MembershipSystem
import velle.sqlite.SqliteStore

/**
 * The membership flows over real storage — the ledger with `latest`, the
 * balance fold, timestamps, captures read at exit, and the ticks' candidate
 * sweeps. Once through the generic protocol, once through the generated typed
 * store surface (which routes each tick's filter to its named question).
 */
class MembershipApiOverSqliteTest : MembershipApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): MembershipSystem = MembershipSystem().also { SqliteStore.attach(it.system, conn) }
}

class MembershipApiOverTypedStoreTest : MembershipApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): MembershipSystem = MembershipSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = MembershipStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(MembershipStoreResolver(store, sys.system.model), backend)
    }
}

/** The ledger, a capture, and a tick across processes. */
class MembershipPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(): MembershipSystem = MembershipSystem().also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")

    private fun rows(sql: String): List<List<Any?>> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { i ->
                        rs.getObject(i).let { if (it is Number) it.toLong() else it }
                    })
                }
            }
        }

    private fun MembershipSystem.ada(): Member {
        accept(commitPlan("Standard", BigDecimal("20")))
        accept(commitSignUp("Ada", "Ada@Example.com", plans().last()))
        return members().last()
    }

    @Test
    fun `the email ledger, a capture, and its exit read across processes`() {
        val first = process()
        val ada = first.ada()
        accept(first.commitChangeEmail(ada, "New@Example.com"))
        accept(first.commitAgent("Grace", "grace@velle.example"))
        val grace = first.agents().last()
        accept(first.commitRaiseTicket(ada, "billing question", LocalDate.of(2026, 3, 1), "normal"))
        accept(first.commitCloseTicket(first.tickets().last(), grace))
        assertEquals(listOf(listOf(1L)), rows("""SELECT closedBy FROM capture_ClosedTicket"""), "the capture landed as a row")

        val second = process()
        val ada2 = second.read.member(1L)
        assertEquals("new@example.com", second.read(ada2).email, "latest(...) over the stored EmailChange rows")

        val ticket = second.read.ticket(1L)
        val grace2 = second.read.agent(1L)
        assertTrue(second.system.isMember(second.system.idOf(ticket), "ClosedTicket"))
        assertEquals(grace2, second.closedTickets().single().closedBy, "the capture hydrates from its row")

        accept(second.commitReopenTicket(ticket))
        assertTrue(!second.system.isMember(second.system.idOf(ticket), "ClosedTicket"))
        assertEquals(grace2, second.read(second.reopenNotices().single()).reassignTo, "the exit rule was the capture's last reader")
        assertEquals(emptyList(), rows("""SELECT closedBy FROM capture_ClosedTicket"""), "the retraction deleted the row")
    }

    @Test
    fun `the monthly renewal and the balance fold run from storage`() {
        val first = process()
        val ada = first.ada()
        accept(first.commitMakeDeposit(ada, BigDecimal("30")))
        assertEquals(0, first.read(ada).balance.compareTo(BigDecimal("30")))

        val second = process()
        second.tickMonthly()
        val ada2 = second.read.member(1L)
        assertEquals(1, second.charges().size, "the tick's sweep selected the member from storage")
        assertEquals(0, second.read(ada2).balance.compareTo(BigDecimal("10")), "the fold read the stored deposit and charge")
        assertEquals(listOf(listOf(1L)), rows("""SELECT COUNT(*) FROM Charge"""))
    }
}

