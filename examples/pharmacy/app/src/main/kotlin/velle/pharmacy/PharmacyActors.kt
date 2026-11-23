package velle.pharmacy

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import velle.Query
import velle.Ref
import velle.api.spring.ActorResolver
import velle.generated.PharmacySystem
import velle.generated.StaffFields

/**
 * Who is calling. Every act in the pharmacy spec that is declared `by` is committed
 * by a member of staff, and every read is for one, so the starter asks this bean
 * once per request. PharmacySecurity has already established who signed in at the
 * identity provider; the token's `sub` is the provider's identifier for that
 * person, and the actor is the Staff record whose `identityId` holds it. A person
 * the provider knows and no Staff record names resolves to no actor.
 *
 * The staff member is looked up as a reference only. The generated lookup,
 * `pharmacy.read.staffByIdentityId`, builds the whole Staff record, which reads
 * every fill, verification, and licence renewal that names them, on every
 * request (PharmacyStatementsTest).
 *
 * The seed (PharmacySeed) puts the staff on the books with the identityId the
 * blueprint (authentik/pharmacy.yaml) gives each person as their `pk`; Roster
 * holds both, and the two files must agree.
 */
@Configuration(proxyBeanMethods = false)
class PharmacyActors {

    @Bean
    fun actors(pharmacy: PharmacySystem): ActorResolver = ActorResolver { request ->
        val signedInIdentityId = identityIdOfTheSignedInPerson(request) ?: return@ActorResolver null
        staffMemberWithIdentity(pharmacy, signedInIdentityId)
    }

    /** The Staff record whose identityId this is, or null when none has it; `unique` on identityId makes it one or none. */
    private fun staffMemberWithIdentity(pharmacy: PharmacySystem, identityId: String): Ref<*>? {
        val staffWithThatIdentity = Query(root = "Staff", where = StaffFields().identityId eq identityId)
        val staffMemberHandle = pharmacy.system.query(staffWithThatIdentity).ids.singleOrNull() ?: return null
        return pharmacy.system.refFor(staffMemberHandle)
    }

    /** The `sub` of whoever signed in: from the browser's session, or from the token in the Authorization header. */
    private fun identityIdOfTheSignedInPerson(request: HttpServletRequest): String? =
        when (val authentication = request.userPrincipal) {
            is OAuth2AuthenticationToken -> (authentication.principal as? OidcUser)?.subject
            is JwtAuthenticationToken -> authentication.token.subject
            else -> null
        }
}
