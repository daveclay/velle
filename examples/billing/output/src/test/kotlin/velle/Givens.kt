package velle.generated.billing

import java.math.BigDecimal
import velle.generated.*
import java.time.LocalDate
import velle.generated.BillingSystem

/**
 * The human-owned scenarios the generated specs demand (testgen.md): how to
 * *reach* each interesting state is business judgment the spec doesn't contain,
 * so it lives here — one findable place, named in business language. Everything
 * asserted about what happens next is generated from the spec.
 */
class Givens(private val sys: BillingSystem) : RequiredGivens {

    private fun customer(): Customer {
        sys.commit(NewSignUp("Ada", "ada@example.com"))
        return sys.customers().last()
    }

    private fun invoice(due: LocalDate = LocalDate.of(2026, 2, 1)): Invoice {
        sys.commit(NewBillCustomer(customer(), due))
        return sys.invoices().last()
    }

    private fun invoiceWithBalance(
        amount: BigDecimal,
        due: LocalDate = LocalDate.of(2026, 2, 1),
    ): Invoice {
        val inv = invoice(due)
        sys.commit(NewLineItem(inv, "widgets", amount, 1))
        return inv
    }

    override fun signUp() {
        sys.commit(NewSignUp("Grace", "grace@example.com"))
    }

    override fun billCustomer() {
        sys.commit(NewBillCustomer(customer(), LocalDate.of(2026, 2, 1)))
    }

    override fun submitPayment() {
        sys.commit(NewSubmitPayment(invoiceWithBalance(BigDecimal("100")), BigDecimal("40")))
    }

    override fun correctEmail(): CorrectEmail {
        sys.commit(NewCorrectEmail(customer(), "corrected@example.com"))
        return sys.correctEmails().last()
    }

    override fun payment(): Payment {
        sys.commit(NewSubmitPayment(invoiceWithBalance(BigDecimal("100")), BigDecimal("40")))
        return sys.payments().last()
    }

    override fun someInvoice(): Invoice = invoice()

    override fun paidInvoice(): Invoice {
        val inv = invoiceWithBalance(BigDecimal("100"))
        sys.commit(NewSubmitPayment(inv, BigDecimal("100"))) // covering payment: newly paid
        return inv
    }

    override fun unemailedReceipt(): Receipt {
        paidInvoice()
        return sys.receipts().last()
    }

    override fun invoiceForRemindOverdue(): Invoice =
        invoiceWithBalance(BigDecimal("100"), due = LocalDate.of(2025, 12, 1))

    override fun applicableDueChange() {
        sys.commit(NewChangeDueDate(invoice(), LocalDate.of(2026, 3, 1))) // draft: change applies
    }

    override fun refusedDueChange() {
        val inv = invoice()
        sys.commit(NewIssuance(inv)) // issued: the due date is frozen
        sys.commit(NewChangeDueDate(inv, LocalDate.of(2026, 3, 1)))
    }

    override fun archivedInvoice(): Invoice {
        val inv = invoice()
        sys.commit(NewArchiveRequest(inv))
        return inv
    }
}
