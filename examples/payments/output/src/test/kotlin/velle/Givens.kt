package velle.generated.payments

import java.math.BigDecimal
import velle.generated.*
import java.time.LocalDate
import velle.generated.PaymentsSystem

/**
 * The human-owned scenarios the generated payments specs demand (testgen.md):
 * how to *reach* each interesting state is business judgment the spec doesn't
 * contain, so it lives here — one findable place, named in business language.
 *
 * The system clock starts 2026-01-01. Committing an order whose customer has a
 * card immediately fires ReserveStock and RequestInitialCharge, so a fresh
 * carded order already carries one pending attempt.
 */
class Givens(private val sys: PaymentsSystem) : RequiredGivens {

    private fun card(): Card {
        sys.commitContact("Grace", "grace@velle.example")
        sys.commitCard("4242", LocalDate.of(2027, 1, 1), sys.contacts().last())
        return sys.cards().last()
    }

    private fun customer(withCard: Boolean = true): Customer {
        sys.commitCustomer("Ada", if (withCard) card() else null)
        return sys.customers().last()
    }

    private fun order(
        withCard: Boolean = true,
        amount: BigDecimal = BigDecimal("100"),
        dueBy: LocalDate = LocalDate.of(2026, 2, 1),
    ): Order {
        sys.commitPlaceOrder(customer(withCard), amount, dueBy, "12 Loop Ave")
        return sys.orders().last()
    }

    private fun pendingAttempt(): ChargeAttempt {
        order()
        return sys.chargeAttempts().last()
    }

    private fun respond(attempt: ChargeAttempt, outcome: String) {
        sys.commitProcessorVerdict(attempt, outcome)
    }

    override fun cardUpdate(): CardUpdate {
        sys.commitCardUpdate(customer(withCard = false), card())
        return sys.cardUpdates().last()
    }

    override fun orderForReserveStock(): Order = order(withCard = false)

    override fun someCustomer(): Customer = customer()

    override fun orderForRequestInitialCharge(): Order = order()

    override fun stalePendingAttempt(): ChargeAttempt {
        val attempt = pendingAttempt()
        sys.advanceSeconds(16 * 60) // past the fifteen-minute window
        return attempt
    }

    override fun successfulCharge(): ChargeAttempt {
        val attempt = pendingAttempt()
        respond(attempt, "approved")
        return attempt
    }

    override fun retryableOrder(): Order {
        val o = order()
        respond(sys.chargeAttempts().last(), "declined")
        return o
    }

    override fun orderForReleaseStockOnExhaustion(): Order {
        // each decline triggers the next retry; the third failure exhausts
        val o = order()
        repeat(3) { respond(sys.chargeAttempts().last(), "declined") }
        return o
    }

    override fun readyToShip(): Order {
        val o = order()
        respond(sys.chargeAttempts().last(), "approved") // settled + reserved + unshipped
        return o
    }

    override fun applicableAddressChange(): ChangeShippingAddress {
        sys.commitChangeShippingAddress(order(withCard = false), "9 Corrected St")
        return sys.changeShippingAddresss().last()
    }

    override fun refusedAddressChange(): ChangeShippingAddress {
        sys.commitChangeShippingAddress(readyToShip(), "9 Corrected St")
        return sys.changeShippingAddresss().last()
    }

    override fun settledOrder(): Order {
        val o = order()
        respond(sys.chargeAttempts().last(), "approved")
        return o
    }

    override fun exitSettledOrder(order: Order) {
        sys.commitIssueRefund(order, BigDecimal("40")) // the refund breaks settlement
    }

    override fun manualCharge(): ManualCharge {
        sys.commitManualCharge(order(withCard = false))
        return sys.manualCharges().last()
    }

    override fun extensionRequest(): ExtensionRequest {
        sys.commitExtensionRequest(order(withCard = false))
        return sys.extensionRequests().last()
    }

    /** Born overdue: due date already past at placement, nothing paid. */
    private fun overdueOrder(withCard: Boolean = false): Order =
        order(withCard = withCard, dueBy = LocalDate.of(2025, 12, 15))

    override fun orderForRemindPayment(): Order = overdueOrder()

    override fun orderForOpenDunningEpisode(): Order = overdueOrder()

    override fun closableDunningFlag(): DunningFlag {
        // the flag opens at the overdue order's own creation commit; paying in
        // full makes it closable with no Daily tick involved
        overdueOrder(withCard = true)
        respond(sys.chargeAttempts().last(), "approved")
        return sys.dunningFlags().last()
    }

    override fun someOrder(): Order = order(withCard = false)

    override fun placeOrder() {
        order(withCard = false)
    }

    override fun processorVerdict() {
        respond(pendingAttempt(), "approved")
    }

    override fun issueRefund() {
        sys.commitIssueRefund(settledOrder(), BigDecimal("40"))
    }
}
