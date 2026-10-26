package velle.billing

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import velle.api.VelleApi
import velle.generated.BillingSystem
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The application's own endpoint, written against the generated typed surface
 * ([Binding API]) rather than the generic one: the overdue invoices and what each
 * still owes, as the collections team's screen wants them. It runs under the same
 * lock as the generic endpoints, through the API's `locked`, so it never reads
 * mid-commit.
 */
@RestController
class OverdueController(private val billing: BillingSystem, private val api: VelleApi) {

    data class Overdue(val invoice: Long, val customer: String, val due: LocalDate, val balance: String, val daysLate: Long)

    @GetMapping("/overdue")
    fun overdue(): List<Overdue> = api.locked {
        val today = LocalDate.ofInstant(billing.now, ZoneOffset.UTC)
        billing.read.overdueInvoices.orderBy { due.ascending }.list().map { inv ->
            Overdue(
                invoice = inv.key.value as Long,
                customer = billing.read(inv.customer).name,
                due = inv.due,
                balance = inv.balance.toString(),
                daysLate = java.time.temporal.ChronoUnit.DAYS.between(inv.due, today),
            )
        }
    }
}
