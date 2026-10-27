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
 * still owes, as the collections team's screen wants them. It runs in one scope
 * of the API, as the generic endpoints do, so its reads are one consistent read
 * and never see a commit half landed.
 */
@RestController
class OverdueController(private val billing: BillingSystem, private val api: VelleApi) {

    data class Overdue(val invoice: Long, val customer: String, val due: LocalDate, val balance: String, val daysLate: Long)

    @GetMapping("/overdue")
    fun overdue(): List<Overdue> = api.scope {
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
