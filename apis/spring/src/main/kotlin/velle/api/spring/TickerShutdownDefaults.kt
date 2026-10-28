package velle.api.spring

import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * A shutdown waits for a sweep in flight. With an entry under `velle.schedules` the ticks are
 * tasks on the application's scheduler, and Boot's own default on shutdown is to interrupt a
 * running task and return at once, so the DataSource closes under a firing and the log says a
 * tick failed for what was a restart. These two defaults sit below every property source of
 * the application's, so its own setting of either property wins. Registered in
 * META-INF/spring.factories.
 */
class TickerShutdownDefaults : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val schedules = Binder.get(environment)
            .bind("velle.schedules", Bindable.mapOf(String::class.java, Any::class.java))
            .orElse(emptyMap())
        if (schedules.isNullOrEmpty()) return
        environment.propertySources.addLast(MapPropertySource(SOURCE, mapOf(
            "spring.task.scheduling.shutdown.await-termination" to "true",
            "spring.task.scheduling.shutdown.await-termination-period" to "30s",
        )))
    }

    companion object {
        const val SOURCE = "velleTickerShutdownDefaults"
    }
}
