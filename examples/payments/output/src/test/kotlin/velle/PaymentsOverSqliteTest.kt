package velle

import java.math.BigDecimal
import velle.generated.*
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import velle.generated.PaymentsStoreOverGeneric
import velle.generated.PaymentsStoreResolver
import velle.generated.PaymentsSystem
import velle.sqlite.SqliteStore

/**
 * The payments flows over real storage — the async charge lifecycle with its
 * outcome-state dispatch, retries, timeouts, and compensation, every later
 * verdict a fresh envelope over stored rows. Once through the generic
 * protocol, once through the generated typed store surface.
 */
class PaymentsApiOverSqliteTest : PaymentsApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PaymentsSystem = PaymentsSystem().also { SqliteStore.attach(it.system, conn) }
}

class PaymentsApiOverTypedStoreTest : PaymentsApiTest() {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    override fun newSystem(): PaymentsSystem = PaymentsSystem().also { sys ->
        val backend = SqliteStore(sys.system.model, conn).apply { createSchema() }
        val store = PaymentsStoreOverGeneric(backend, sys.system.model)
        sys.system.connect(PaymentsStoreResolver(store, sys.system.model), backend)
    }
}

/** The async lifecycle spanning processes: the processor's verdict and the warehouse's nightly run arrive later, elsewhere. */
class PaymentsPersistenceTest {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @AfterTest
    fun close() = conn.close()

    private fun process(startTime: Instant = Instant.parse("2026-01-01T09:00:00Z")): PaymentsSystem =
        PaymentsSystem(startTime).also { SqliteStore.attach(it.system, conn) }

    private fun accept(r: CommitResult) = assertIs<CommitResult.Accepted>(r, "refused: $r")

    private fun PaymentsSystem.placeOrder() {
        accept(commitContact("Grace", "grace@velle.example"))
        accept(commitCard("4242", LocalDate.of(2027, 1, 1), contacts().last()))
        accept(commitCustomer("Ada", cards().last()))
        accept(commitPlaceOrder(customers().last(), BigDecimal("100"), LocalDate.of(2026, 2, 1), "12 Loop Ave"))
    }

    @Test
    fun `verdict, receipt, and the nightly shipment - once - arrive in later processes`() {
        val first = process()
        first.placeOrder()
        assertEquals(1, first.chargeAttempts().size)
        assertEquals(1, first.stockReservations().size)

        val second = process()
        val attempt = second.read.chargeAttempt(1L)
        accept(second.commitProcessorVerdict(attempt, "approved"))
        val order = second.read.order(1L)
        with(second) {
            assertTrue(second.read(attempt).isSuccessfulCharge)
            assertTrue(second.read(order).isSettledOrder)
        }
        assertEquals(1, second.receipts().size)
        assertEquals(0, second.read(order).netPaid.compareTo(BigDecimal("100")))

        val third = process()
        third.tickNightly()
        assertEquals(1, third.shipments().size, "the sweep selected the ready order from storage")
        val fourth = process()
        fourth.tickNightly()
        assertEquals(1, fourth.shipments().size, "the shipment evidence in storage suppresses a second one")
    }

    @Test
    fun `a silent processor is timed out by a sweep in a later process`() {
        val first = process()
        first.placeOrder()

        val later = process(startTime = Instant.parse("2026-01-01T09:16:00Z"))
        later.tickQuarterHourly()
        assertEquals(1, later.attemptTimeouts().size, "requestedOn is a stored timestamp the sweep compares against")
        val attempt = later.read.chargeAttempt(1L)
        with(later) {
            assertTrue(later.read(attempt).isTimedOutAttempt)
            assertTrue(!later.read(attempt).isPendingAttempt)
        }
        assertEquals(2, later.chargeAttempts().size, "the timeout unblocked a retry")
    }
}

