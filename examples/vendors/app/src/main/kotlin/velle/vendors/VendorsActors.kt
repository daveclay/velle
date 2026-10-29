package velle.vendors

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import velle.Commit
import velle.api.VelleApi
import velle.api.spring.ActorResolver
import velle.generated.NewAssignVendor
import velle.generated.NewListProduct
import velle.generated.VendorsSystem
import java.math.BigDecimal

/**
 * Who is calling, and who there is to call as. The vendors spec gates its acts on
 * staff and shows its records to staff, so the starter asks this bean for the
 * request's actor once per request: a header, `X-Velle-Actor: Staff:1`, which the
 * console sends for whoever the developer chose to act as. Staff records are
 * fixtures the spec never creates (nothing in it hires), so on an empty database
 * this configuration also seeds two of them, two vendors, and a few products.
 */
@Configuration(proxyBeanMethods = false)
class VendorsActors {

    private val log = LoggerFactory.getLogger(VendorsActors::class.java)

    @Bean
    fun actors(api: VelleApi): ActorResolver = ActorResolver { request ->
        request.getHeader("X-Velle-Actor")?.let { header ->
            val (shape, key) = header.split(':', limit = 2)
            api.refTo(shape, key)
        }
    }

    /** Grace (an administrator, `Staff:1`) and Linus (staff, `Staff:2`), two vendors, and the products Grace lists. */
    @Bean
    fun fixtures(vendors: VendorsSystem, @Suppress("unused") api: VelleApi): SmartInitializingSingleton = SmartInitializingSingleton {
        if (vendors.staffs().isNotEmpty()) return@SmartInitializingSingleton
        vendors.system.seed("Staff", mapOf("name" to "Grace Hopper", "role" to "admin"))
        vendors.system.seed("Staff", mapOf("name" to "Linus Pauling", "role" to "staff"))
        vendors.system.seed("Vendor", mapOf("name" to "Acme Fasteners"))
        vendors.system.seed("Vendor", mapOf("name" to "Bolt & Nut Supply"))
        val (grace, linus) = vendors.staffs()
        val (acme, bolt) = vendors.vendors()
        fun accept(r: Commit<*, *>) = check(r is Commit.Accepted<*, *>) { "vendors fixtures: $r" }
        accept(vendors.commit(NewAssignVendor(linus, acme), grace))
        accept(vendors.commit(NewListProduct(acme, "M6 hex bolt, box of 100", BigDecimal("12.50"), BigDecimal("7.10")), grace))
        accept(vendors.commit(NewListProduct(acme, "M6 washer, box of 500", BigDecimal("9.00"), BigDecimal("4.20")), grace))
        accept(vendors.commit(NewListProduct(bolt, "Wood screw assortment", BigDecimal("18.00"), BigDecimal("11.60")), grace))
        log.info("Velle vendors: seeded two staff (act as Staff:1 for the administrator), two vendors, three products")
    }
}
