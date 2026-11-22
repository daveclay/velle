package velle.vendors

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import velle.Commit
import velle.Ref
import velle.api.spring.ActorResolver
import velle.generated.NewAssignVendor
import velle.generated.NewListProduct
import velle.generated.VendorsSystem
import java.math.BigDecimal

/**
 * Who is calling, and who there is to call as. The vendors spec gates its acts on
 * staff and shows its records to staff, so the starter asks this bean for the
 * request's actor once per request. VendorsSecurity has already established who
 * signed in at the identity provider; the token's `sub` is the provider's
 * identifier for that person, and the actor is the Staff record whose
 * `identityId` holds it. A person the provider knows and no Staff record names
 * resolves to no actor.
 *
 * Staff records are fixtures the spec never creates (nothing in it hires), so on
 * an empty database this configuration also seeds two of them, two vendors, and
 * a few products. Each staff member's identityId is the `pk` the blueprint
 * (authentik/vendors.yaml) gives the same person; the two files must agree.
 */
@Configuration(proxyBeanMethods = false)
class VendorsActors {

    private val log = LoggerFactory.getLogger(VendorsActors::class.java)

    @Bean
    fun actors(vendors: VendorsSystem): ActorResolver = ActorResolver { request ->
        val signedInIdentityId = identityIdOfTheSignedInPerson(request) ?: return@ActorResolver null
        val staffMember = vendors.read.staffByIdentityId(signedInIdentityId) ?: return@ActorResolver null
        Ref.Persisted("Staff", staffMember.key)
    }

    /** The `sub` of whoever signed in: from the browser's session, or from the token in the Authorization header. */
    private fun identityIdOfTheSignedInPerson(request: HttpServletRequest): String? =
        when (val authentication = request.userPrincipal) {
            is OAuth2AuthenticationToken -> (authentication.principal as? OidcUser)?.subject
            is JwtAuthenticationToken -> authentication.token.subject
            else -> null
        }

    /** Grace (an administrator, identityId 1001) and Linus (staff, identityId 1002), two vendors, and the products Grace lists. */
    @Bean
    fun fixtures(vendors: VendorsSystem): SmartInitializingSingleton = SmartInitializingSingleton {
        if (vendors.staffs().isNotEmpty()) return@SmartInitializingSingleton
        vendors.system.seed("Staff", mapOf("name" to "Grace Hopper", "email" to "grace@vendors.example", "identityId" to "1001", "role" to "admin"))
        vendors.system.seed("Staff", mapOf("name" to "Linus Pauling", "email" to "linus@vendors.example", "identityId" to "1002", "role" to "staff"))
        vendors.system.seed("Vendor", mapOf("name" to "Acme Fasteners"))
        vendors.system.seed("Vendor", mapOf("name" to "Bolt & Nut Supply"))
        val (grace, linus) = vendors.staffs()
        val (acme, bolt) = vendors.vendors()
        fun accept(result: Commit<*, *>) = check(result is Commit.Accepted<*, *>) { "vendors fixtures: $result" }
        accept(vendors.commit(NewAssignVendor(linus, acme), grace))
        accept(vendors.commit(NewListProduct(acme, "M6 hex bolt, box of 100", BigDecimal("12.50"), BigDecimal("7.10")), grace))
        accept(vendors.commit(NewListProduct(acme, "M6 washer, box of 500", BigDecimal("9.00"), BigDecimal("4.20")), grace))
        accept(vendors.commit(NewListProduct(bolt, "Wood screw assortment", BigDecimal("18.00"), BigDecimal("11.60")), grace))
        log.info("Velle vendors: seeded two staff (sign in as grace or linus, password vendors-demo), two vendors, three products")
    }
}
