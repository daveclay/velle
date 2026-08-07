package velle.generated.billing

import java.math.BigDecimal
import java.time.LocalDate
import velle.generated.BillingSystem

/**
 * The human-owned scenarios the generated specs demand (testgen.md): how to
 * *reach* each interesting state is business judgment the spec doesn't contain,
 * so it lives here — one findable place, named in business language. Everything
 * asserted about what happens next is generated from the spec.
 */
class Givens(private val sys: BillingSystem) : RequiredGivens {

    private fun customer(): BillingSystem.CustomerView {
        sys.commitCustomer("Ada", "ada@example.com")
        return sys.customers().last()
    }

    private fun invoice(due: LocalDate = LocalDate.of(2026, 2, 1)): BillingSystem.InvoiceView {
        sys.commitInvoice(customer(), due)
        return sys.invoices().last()
    }

    private fun invoiceWithBalance(
        amount: BigDecimal,
        due: LocalDate = LocalDate.of(2026, 2, 1),
    ): BillingSystem.InvoiceView {
        val inv = invoice(due)
        sys.commitLineItem(inv, "widgets", amount, 1)
        return inv
    }

    override fun enterApplyEmailCorrection(): BillingSystem.CorrectEmailView {
        sys.commitCorrectEmail(customer(), "corrected@example.com")
        return sys.correctEmails().last()
    }

    override fun enterTrackLargestPayment(): BillingSystem.PaymentView {
        sys.commitPayment(invoiceWithBalance(BigDecimal("100")), BigDecimal("40"))
        return sys.payments().last()
    }

    override fun someInvoice(): BillingSystem.InvoiceView = invoice()

    override fun enterSendReceipt(): BillingSystem.InvoiceView {
        val inv = invoiceWithBalance(BigDecimal("100"))
        sys.commitPayment(inv, BigDecimal("100")) // covering payment: newly paid
        return inv
    }

    override fun enterEmailReceipt(): BillingSystem.ReceiptView {
        enterSendReceipt()
        return sys.receipts().last()
    }

    override fun populateRemindOverdue(): BillingSystem.InvoiceView =
        invoiceWithBalance(BigDecimal("100"), due = LocalDate.of(2025, 12, 1))

    override fun enterApplyDueChange(): BillingSystem.ChangeDueDateView {
        sys.commitChangeDueDate(invoice(), LocalDate.of(2026, 3, 1)) // draft: change applies
        return sys.changeDueDates().last()
    }

    override fun enterRecordDueChangeRefusal(): BillingSystem.ChangeDueDateView {
        val inv = invoice()
        sys.commitIssuance(inv) // issued: the due date is frozen
        sys.commitChangeDueDate(inv, LocalDate.of(2026, 3, 1))
        return sys.changeDueDates().last()
    }

    override fun exitNoteUnarchival(): BillingSystem.InvoiceView {
        val inv = invoice()
        sys.commitArchiveRequest(inv)
        sys.commitUnarchiveRequest(inv)
        return inv
    }
}
