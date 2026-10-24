package velle.finance.sqlite

import velle.CommitResult
import velle.Model
import velle.Parser
import velle.Validator
import velle.VelleSystem
import velle.finance.Money
import velle.sqlite.SqliteStore
import velle.sqlite.SqliteTypeBinding
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Alice's `Money` over the generic SQLite store through Bob's binding (Reference §5,
 * "Extension types"): the flow runs over real storage, the store refuses to start
 * without a binding (E4) or with one that does not round-trip, and a binding that
 * answers fewer comparisons degrades to the evaluator without changing an answer.
 */
class MoneyOverSqliteTest {

    private val spec = """
        act Customer { name: text }
        act Account {
            owner: one Customer
            balance: Money initially 0
        }
        act Deposit {
            account: one Account
            amount: Money
        }
        rule ApplyDeposit when Deposit { account.balance = account.balance + amount }
        act Withdrawal {
            account: one Account
            amount: Money
        }
        rule ApplyWithdrawal when Withdrawal { account.balance = account.balance - amount }
        never (Deposit where amount <= 0)
        shape Overdrawn = Account where balance < 0
        shape Funded = Account where balance >= 10
    """.trimIndent()

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun system(): VelleSystem {
        val decls = Parser.parse(spec)
        val d = Validator.validate(decls)
        check(d.isEmpty()) { d.toString() }
        return VelleSystem(Model(decls))
    }

    private fun accepted(r: CommitResult): Long = (r as? CommitResult.Accepted)?.id ?: error((r as CommitResult.Refused).reason)

    private fun flow(sys: VelleSystem) {
        val owner = sys.scope { accepted(sys.commit("Customer", mapOf("name" to "Ada"))) }
        val acct = sys.scope { sys.refFor(accepted(sys.commit("Account", mapOf("owner" to sys.instancesOf("Customer").single())))) }
        fun acctId() = sys.idOf(acct)
        assertIs<CommitResult.Accepted>(sys.commit("Deposit", mapOf("account" to acctId(), "amount" to Money.of("12.50"))))
        assertEquals(Money.of("12.50"), sys.scope { sys.get(acctId(), "balance") })
        assertIs<CommitResult.Refused>(sys.commit("Deposit", mapOf("account" to acctId(), "amount" to Money.ZERO)))
        assertEquals(true, sys.scope { sys.isMember(acctId(), "Funded") })
        assertIs<CommitResult.Accepted>(sys.commit("Withdrawal", mapOf("account" to acctId(), "amount" to Money.of("20.00"))))
        assertEquals(Money.of("-7.50"), sys.scope { sys.get(acctId(), "balance") })
        assertEquals(true, sys.scope { sys.isMember(acctId(), "Overdrawn") })
        assertEquals(false, sys.scope { sys.isMember(acctId(), "Funded") })
        assertEquals(1, sys.scope { sys.instancesOf("Overdrawn").size })
        assertEquals(0, sys.scope { sys.instancesOf("Funded").size })
        check(owner > 0)
    }

    @Test
    fun `the deposit flow runs over SQLite through the binding found on the classpath`() {
        val sys = system()
        SqliteStore.attach(sys, conn)
        flow(sys)
        // the column holds cents
        conn.prepareStatement("SELECT balance FROM Account").use { ps -> ps.executeQuery().use { rs -> rs.next(); assertEquals(-750L, rs.getLong(1)) } }
    }

    /** A TEXT column of the decimal: faithful for equality, not for order, so it claims only `==`/`!=`. */
    private class TextMoneyBinding : SqliteTypeBinding<Money> {
        override val type = "velle.finance.Money"
        override val columnType = "TEXT"
        override fun bind(value: Money): Any = value.toString()
        override fun read(rs: ResultSet, column: String): Money = Money.of(rs.getString(column))
        override val answers = setOf("==", "!=")
    }

    @Test
    fun `a binding that answers fewer comparisons degrades to the evaluator and answers the same`() {
        val sys = system()
        SqliteStore.attach(sys, conn, bindings = listOf(TextMoneyBinding()))
        flow(sys)
        conn.prepareStatement("SELECT balance FROM Account").use { ps -> ps.executeQuery().use { rs -> rs.next(); assertEquals("-7.50", rs.getString(1)) } }
    }

    @Test
    fun `the store refuses to start without a binding for a stored type`() {
        val sys = system()
        val e = assertFailsWith<IllegalStateException> { SqliteStore(sys.model, conn, bindings = emptyList()) }
        assertTrue("SqliteStore has no binding for 'Money' (velle.finance.Money)" in e.message!!, e.message)
        assertTrue("SqliteTypeBinding<Money>" in e.message!!, e.message)
    }

    private class LyingBinding : SqliteTypeBinding<Money> {
        override val type = "velle.finance.Money"
        override val columnType = "INTEGER"
        override fun bind(value: Money): Any = value.cents / 100 // drops the cents
        override fun read(rs: ResultSet, column: String): Money = Money.ofCents(rs.getLong(column) * 100)
        override val answers = setOf("==", "!=")
    }

    @Test
    fun `the store refuses a binding that does not round-trip the type's samples`() {
        val sys = system()
        val e = assertFailsWith<IllegalStateException> { SqliteStore(sys.model, conn, bindings = listOf(LyingBinding())) }
        assertTrue("does not round-trip 12.50" in e.message!!, e.message)
    }
}
