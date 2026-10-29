package velle.pharmacy

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import velle.api.VelleApi
import velle.api.spring.ActorResolver

/**
 * Who is calling. Every act in the pharmacy spec that is declared `by` is committed
 * by a member of staff, and every read is for one, so the starter asks this bean
 * once per request. This application has no login: the request names the staff
 * member in a header, `X-Velle-Actor: Staff:1`, which the console sends for whoever
 * the developer chose to act as. An application behind Spring Security would read
 * the authenticated principal here instead and look up the staff record.
 */
@Configuration(proxyBeanMethods = false)
class PharmacyActors {

    @Bean
    fun actors(api: VelleApi): ActorResolver = ActorResolver { request ->
        request.getHeader("X-Velle-Actor")?.let { header ->
            val (shape, key) = header.split(':', limit = 2)
            api.refTo(shape, key)
        }
    }
}
