package velle.pharmacy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import velle.generated.PharmacySystem
import java.time.Instant

/**
 * The pharmacy system as a web application. One bean, the generated system; the
 * Velle starter does the rest: the PostgreSQL store from the DataSource, REST at
 * `/api`, GraphQL at `/graphql`, the schedules, the console at `/velle`.
 */
@SpringBootApplication
class PharmacyApplication {

    @Bean
    fun pharmacy(): PharmacySystem = PharmacySystem(startTime = Instant.now())
}

fun main(args: Array<String>) {
    runApplication<PharmacyApplication>(*args)
}
