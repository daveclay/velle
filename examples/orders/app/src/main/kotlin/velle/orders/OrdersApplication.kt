package velle.orders

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import velle.generated.OrdersSystem
import java.time.Instant

/**
 * The orders system as a web application. One bean, the generated system; the
 * Velle starter does the rest: the PostgreSQL store from the DataSource, REST at
 * `/api`, GraphQL at `/graphql`, the schedules, the console at `/velle`.
 */
@SpringBootApplication
class OrdersApplication {

    @Bean
    fun orders(): OrdersSystem = OrdersSystem(startTime = Instant.now())
}

fun main(args: Array<String>) {
    runApplication<OrdersApplication>(*args)
}
