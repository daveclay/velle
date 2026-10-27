package velle

import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The store a system runs over until another is connected (`MemoryStore`): the
 * rows outlive every scope while the handles die with theirs, two envelopes on
 * one queue key take turns, and reads run beside each other.
 */
class MemoryStoreTest {

    private val spec = """
        act Account { owner: text, balance: decimal initially 0 }
        act Deposit { account: one Account, amount: decimal }
        rule ApplyDeposit when Deposit { account.balance = account.balance + amount }
    """.trimIndent()

    private fun system(): VelleSystem {
        val model = Model(Parser.parse(spec))
        check(model.diagnostics.isEmpty()) { model.diagnostics.toString() }
        return VelleSystem(model)
    }

    private fun accepted(r: CommitResult): Ref.Persisted =
        assertIs<CommitResult.Accepted>(r, "refused: $r").ref ?: error("transient")

    @Test
    fun `rows outlive the scope that made them, and handles do not`() {
        val sys = system()
        val account = sys.scope { accepted(sys.commit("Account", mapOf("owner" to "Ada"))) }
        assertEquals(0, sys.liveHandles, "the scope dropped its handles")
        sys.scope {
            val id = sys.idOf(account)
            assertEquals("Ada", sys.get(id, "owner"))
            assertEquals(listOf(id), sys.instancesOf("Account"))
        }
        assertEquals(0, sys.liveHandles)
    }

    @Test
    fun `two envelopes on one queue key take turns - no deposit is lost`() {
        val sys = system()
        val account = sys.scope { accepted(sys.commit("Account", mapOf("owner" to "Ada"))) }
        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val futures = (1..threads).map {
            pool.submit {
                start.await()
                repeat(perThread) {
                    sys.scope {
                        accepted(sys.commit("Deposit", mapOf("account" to sys.idOf(account), "amount" to BigDecimal.ONE)))
                    }
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(emptyList(), sys.failures)
        sys.scope {
            val balance = sys.get(sys.idOf(account), "balance") as BigDecimal
            assertEquals(0, BigDecimal(threads * perThread).compareTo(balance), "every deposit landed: $balance")
        }
    }

    @Test
    fun `reads run beside each other`() {
        val sys = system()
        val account = sys.scope { accepted(sys.commit("Account", mapOf("owner" to "Ada"))) }
        val pool = Executors.newFixedThreadPool(2)
        val bothReading = CountDownLatch(2)
        val readers = (1..2).map {
            pool.submit {
                sys.scope {
                    assertEquals("Ada", sys.get(sys.idOf(account), "owner")) // the read unit is open now
                    bothReading.countDown()
                    assertTrue(bothReading.await(10, TimeUnit.SECONDS), "a read waited for another read")
                }
            }
        }
        readers.forEach { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
    }

    @Test
    fun `a read sees a commit whole or not at all`() {
        val sys = system()
        val account = sys.scope { accepted(sys.commit("Account", mapOf("owner" to "Ada"))) }
        val pool = Executors.newFixedThreadPool(2)
        val stop = CountDownLatch(1)
        val writer = pool.submit {
            repeat(200) {
                sys.scope { accepted(sys.commit("Deposit", mapOf("account" to sys.idOf(account), "amount" to BigDecimal.ONE))) }
            }
            stop.countDown()
        }
        val reader = pool.submit {
            while (stop.count > 0) {
                sys.scope {
                    val id = sys.idOf(account)
                    // the balance and the number of deposits are two rows read in one unit: they agree
                    val balance = sys.get(id, "balance") as BigDecimal
                    val deposits = sys.instancesOf("Deposit").size
                    assertEquals(0, BigDecimal(deposits).compareTo(balance), "a read saw $deposits deposits and a balance of $balance")
                }
            }
        }
        writer.get(60, TimeUnit.SECONDS)
        reader.get(60, TimeUnit.SECONDS)
        pool.shutdown()
    }
}
