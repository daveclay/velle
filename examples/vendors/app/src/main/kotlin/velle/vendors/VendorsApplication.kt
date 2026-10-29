package velle.vendors

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import velle.generated.VendorsSystem
import java.time.Instant

/**
 * The vendors system as a web application. One bean, the generated system; the
 * Velle starter does the rest: the PostgreSQL store from the DataSource, REST at
 * `/api`, GraphQL at `/graphql`, the schedules, the console at `/velle`.
 */
@SpringBootApplication
class VendorsApplication {

    @Bean
    fun vendors(): VendorsSystem = VendorsSystem(startTime = Instant.now())
}

fun main(args: Array<String>) {
    runApplication<VendorsApplication>(*args)
}
