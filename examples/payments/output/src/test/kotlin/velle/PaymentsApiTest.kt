package velle

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import velle.generated.PaymentsSystem

/** The async charge lifecycle, driven end-to-end through the typed surface. */
class PaymentsApiTest {

    private fun newSystem() = PaymentsSystem()

    private fun PaymentsSystem.cardedCustomer(): PaymentsSystem.CustomerView {
        commitContact("Grace", "grace@velle.example")
        commitCard("4242", LocalDate.of(2027, 1, 1), contacts().last())
        commitCustomer("Ada", cards().last())
        return customers().last()
    }

    private fun PaymentsSystem.newOrder(
        amount: String = "100",
        dueBy: LocalDate = LocalDate.of(2026, 2, 1),
    ): PaymentsSystem.OrderView {
        commitOrder(cardedCustomer(), BigDecimal(amount), dueBy, "12 Loop Ave")
        return orders().last()
    }

    @Test
    fun `happy path - attempt, approval, receipt, nightly shipment`() {
        val sys = newSystem()
        val order = sys.newOrder()

        // placement reserved stock and kicked off the charge
        assertEquals(1, sys.stockReservations().size)
        val attempt = sys.chargeAttempts().single()
        with(sys) { assertTrue(attempt.isPendingAttempt()) }
        assertEquals("grace@velle.example", order.receiptEmail)

        // the processor approves — as its own later act
        sys.commitChargeResponse(attempt, "approved")
        with(sys) {
            assertTrue(attempt.isSuccessfulCharge())
            assertTrue(order.isSettledOrder())
        }
        assertEquals(1, sys.receipts().size)
        assertEquals(0, order.netPaid.compareTo(BigDecimal("100")))

        // ready but unshipped until the warehouse's nightly run
        assertEquals(1, sys.readyToShips().size)
        assertEquals(0, sys.shipments().size)
        sys.tickNightly()
        assertEquals(1, sys.shipments().size)
        sys.tickNightly()
        assertEquals(1, sys.shipments().size) // and only once
    }

    @Test
    fun `address change refused while ready to ship, applied before settlement`() {
        val sys = newSystem()
        val order = sys.newOrder()

        sys.commitChangeShippingAddress(order, "9 Corrected St")
        assertEquals("9 Corrected St", order.shippingAddress) // not ready yet: applied

        sys.commitChargeResponse(sys.chargeAttempts().single(), "approved")
        sys.commitChangeShippingAddress(order, "1 Too Late Rd")
        assertEquals("9 Corrected St", order.shippingAddress) // frozen: refused
        assertEquals(1, sys.addressChangeRefusals().size)
        assertEquals("order is ready to ship", sys.addressChangeRefusals().single().reason)
    }

    @Test
    fun `failure path - retries under the cap, exhaustion releases the stock`() {
        val sys = newSystem()
        val order = sys.newOrder()

        sys.commitChargeResponse(sys.chargeAttempts().last(), "declined")
        assertEquals(2, sys.chargeAttempts().size) // the decline triggered a retry

        sys.commitChargeResponse(sys.chargeAttempts().last(), "error")
        assertEquals(3, sys.chargeAttempts().size)

        sys.commitChargeResponse(sys.chargeAttempts().last(), "declined")
        assertEquals(3, sys.chargeAttempts().size) // budget spent: no fourth attempt
        with(sys) { assertTrue(order.isExhaustedOrder()) }
        assertEquals(1, sys.reservationReleases().size) // compensation fired
        assertEquals(0, sys.activeReservations().size)
    }

    @Test
    fun `a silent processor is timed out and the retry path unblocks`() {
        val sys = newSystem()
        sys.newOrder()
        val first = sys.chargeAttempts().single()

        sys.advanceSeconds(16 * 60)
        sys.tickQuarterHourly()
        assertEquals(1, sys.attemptTimeouts().size)
        with(sys) {
            assertTrue(first.isTimedOutAttempt())
            assertTrue(!first.isPendingAttempt())
        }
        assertEquals(2, sys.chargeAttempts().size) // the timeout unblocked a retry
    }

    @Test
    fun `refund reverses settlement and a manual charge drives a second episode`() {
        val sys = newSystem()
        val order = sys.newOrder()
        sys.commitChargeResponse(sys.chargeAttempts().single(), "approved")
        sys.tickNightly() // ship it

        sys.commitRefund(order, BigDecimal("100"))
        with(sys) { assertTrue(!order.isSettledOrder()) }
        assertEquals(1, sys.settlementReversals().size)
        assertEquals(1, sys.receipts().size) // the receipt survives its premise

        sys.commitManualCharge(order)
        sys.commitChargeResponse(sys.chargeAttempts().last(), "approved")
        with(sys) { assertTrue(order.isSettledOrder()) }
        assertEquals(2, sys.receipts().size) // a second episode, a second receipt
    }

    @Test
    fun `dunning - reminders respect the grace period, the sweep closes the episode`() {
        val sys = newSystem()
        // born overdue: due date already past, nothing paid; the flag opens at placement
        val order = sys.newOrder(dueBy = LocalDate.of(2025, 12, 15))
        assertEquals(1, sys.dunningFlags().size)

        sys.commitExtensionRequest(order)
        assertEquals(LocalDate.of(2026, 1, 15), sys.paymentExtensions().single().endsOn)
        sys.tickDaily()
        assertEquals(0, sys.paymentReminders().size) // grace period: no nagging

        sys.advanceDays(20) // grace expired
        sys.tickDaily()
        assertEquals(1, sys.paymentReminders().size)
        sys.tickDaily()
        assertEquals(1, sys.paymentReminders().size) // three-day lull per order

        sys.commitChargeResponse(sys.chargeAttempts().last(), "approved")
        sys.tickDaily()
        assertEquals(1, sys.dunningResolutions().size) // the sweep closed the episode
        assertEquals(1, sys.paymentReminders().size)   // and the nagging stopped
    }

    @Test
    fun `derived odds and ends - firstAttemptedOn, surplus, refund reconciliation`() {
        val sys = newSystem()
        sys.commitCustomer("Cardless", null)
        sys.commitOrder(sys.customers().last(), BigDecimal("50"), LocalDate.of(2026, 2, 1), "3 Quiet Ln")
        val bare = sys.orders().last()
        assertNull(bare.firstAttemptedOn) // never charged
        assertNull(bare.receiptEmail)     // no card on file
        with(sys) { assertTrue(bare.isNeverCharged()) }

        val order = sys.newOrder(amount = "60")
        sys.commitChargeResponse(sys.chargeAttempts().last(), "declined")
        sys.commitChargeResponse(sys.chargeAttempts().last(), "approved")
        sys.commitManualCharge(order) // support double-charges by mistake
        sys.commitChargeResponse(sys.chargeAttempts().last(), "approved")
        // two approvals of 60 against a 60 order: settled with surplus
        assertEquals(0, sys.settledOrders().single().surplus.compareTo(BigDecimal("60")))

        sys.advanceSeconds(60) // the refund lands later than the failed attempt
        sys.commitRefund(order, BigDecimal("10"))
        with(sys) { assertTrue(order.isRefundAfterFailure()) } // refund after a failed charge
    }
}
