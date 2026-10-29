package velle.moderation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import velle.generated.ModerationSystem
import java.time.Instant

/**
 * The moderation system as a web application. One bean, the generated system; the
 * Velle starter does the rest: the PostgreSQL store from the DataSource, REST at
 * `/api`, GraphQL at `/graphql`, the schedules, the console at `/velle`.
 */
@SpringBootApplication
class ModerationApplication {

    @Bean
    fun moderation(): ModerationSystem = ModerationSystem(startTime = Instant.now())
}

fun main(args: Array<String>) {
    runApplication<ModerationApplication>(*args)
}
