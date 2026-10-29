package velle.enrollment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import velle.generated.EnrollmentSystem
import java.time.Instant

/**
 * The enrollment system as a web application. One bean, the generated system; the
 * Velle starter does the rest: the PostgreSQL store from the DataSource, REST at
 * `/api`, GraphQL at `/graphql`, the schedules, the console at `/velle`.
 */
@SpringBootApplication
class EnrollmentApplication {

    @Bean
    fun enrollment(): EnrollmentSystem = EnrollmentSystem(startTime = Instant.now())
}

fun main(args: Array<String>) {
    runApplication<EnrollmentApplication>(*args)
}
