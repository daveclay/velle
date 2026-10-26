package velle.api.spring

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import velle.Model
import velle.Parser
import velle.Ref
import velle.StoreKey
import velle.Validator
import velle.VelleSystem
import java.io.File
import java.time.Instant

/**
 * A test application over the vendors spec with no generated code: the runtime's
 * system declared as a bean, and an actor resolver that reads `X-Velle-Actor: Staff:1`,
 * standing in for the application's security filter.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class VendorsApp {

    @Bean
    fun system(): VelleSystem {
        val spec = File("../../examples/vendors/vendors.velle").readText()
        val diags = Validator.validate(spec)
        check(diags.none { !it.advisory }) { diags.toString() }
        return VelleSystem(Model(Parser.parse(spec)), startTime = Instant.parse("2026-03-10T12:00:00Z"))
    }

    @Bean
    fun actors(): ActorResolver = ActorResolver { request: HttpServletRequest ->
        request.getHeader("X-Velle-Actor")?.let { header ->
            val (shape, key) = header.split(':', limit = 2)
            Ref.Persisted(shape, StoreKey(key.toLong()))
        }
    }
}
