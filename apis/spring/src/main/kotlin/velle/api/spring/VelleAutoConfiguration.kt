package velle.api.spring

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.support.CronTrigger
import org.springframework.web.filter.OncePerRequestFilter
import reactor.core.publisher.Mono
import velle.GeneratedSystem
import velle.TickListener
import velle.Ref
import velle.VelleSystem
import velle.api.VelleApi
import velle.api.graphql.VelleGraphQL
import velle.postgres.PostgresStore
import velle.sql.SqlStore
import velle.sqlite.SqliteStore
import java.time.Clock
import java.time.ZoneId
import javax.sql.DataSource

/**
 * The Spring Boot side of a Velle system. The application declares one bean: its
 * generated system (`BillingSystem()`), or the runtime's `VelleSystem` for a spec
 * with no generated code. From that this configuration:
 *
 * - connects the system to the application's `DataSource` through the SQL store,
 *   choosing the dialect from the database, and creates the tables;
 * - serves every act, shape, refinement, and schedule over REST at `velle.api.path`
 *   ([VelleRestController]) and over GraphQL at Spring's `/graphql`;
 * - resolves the actor once per request through the application's [ActorResolver]
 *   bean, when it declares one, and hands it to both;
 * - ticks each schedule the application gives a cron expression under `velle.schedules`,
 *   as tasks on Spring's scheduler ([Ticker]);
 * - registers who hears a tick, and an act whose after-commit firings were refused or
 *   failed: the application's [TickListener] beans, or the log; and the metrics when
 *   the application has a `MeterRegistry` ([TickListeners]);
 * - serves the console page at `velle.console.path`.
 *
 * Every operation passes through one [VelleApi], so REST, GraphQL, and the ticks
 * take turns on the system and its store connection.
 */
@AutoConfiguration(before = [GraphQlAutoConfiguration::class, TaskSchedulingAutoConfiguration::class], after = [DataSourceAutoConfiguration::class])
@Conditional(VelleAutoConfiguration.SystemDeclared::class)
@EnableConfigurationProperties(VelleProperties::class)
@Import(VelleRestController::class, VelleConsole::class)
class VelleAutoConfiguration {

    private val log = LoggerFactory.getLogger(VelleAutoConfiguration::class.java)

    /** The runtime behind a generated system declared as a bean. */
    @Bean
    @ConditionalOnMissingBean(VelleSystem::class)
    @ConditionalOnBean(GeneratedSystem::class)
    fun velleSystem(generated: GeneratedSystem): VelleSystem = generated.system

    /**
     * The one API over the system. Connecting the store happens here, before the first
     * request, so the API never serves a system half connected: the DataSource's
     * database names the dialect, and every unit of work takes a connection from it.
     */
    @Bean
    @ConditionalOnMissingBean
    fun velleApi(system: VelleSystem, props: VelleProperties, dataSource: ObjectProvider<DataSource>, store: ObjectProvider<SqlStore>, clock: ObjectProvider<Clock>): VelleApi {
        val declared = store.ifAvailable
        if (declared == null && props.store.enabled) dataSource.ifAvailable { ds -> attach(system, ds, props.store.createSchema) }
        else if (declared == null) log.info("Velle runs in memory: velle.store.enabled is false")
        return VelleApi(system, clock.getIfAvailable { Clock.systemUTC() })
    }

    /** The database names the dialect; the store takes a connection from [dataSource] per unit of work and holds none between. */
    private fun attach(system: VelleSystem, dataSource: DataSource, createSchema: Boolean) {
        val product = dataSource.connection.use { it.metaData.databaseProductName }
        val store = when {
            product.contains("PostgreSQL", ignoreCase = true) -> PostgresStore(system.model, dataSource)
            product.contains("SQLite", ignoreCase = true) -> SqliteStore(system.model, dataSource)
            else -> throw IllegalStateException("Velle has no SQL dialect for '$product'; declare a velle.sql.SqlStore bean of your own, connected to the system")
        }
        if (createSchema) store.createSchema()
        system.connect(store, store)
        log.info("Velle store: {} through the SQL store, {} tables", product, system.model.shapes.size)
    }

    /** Resolves the actor once per request and leaves it on the request for the REST controller and the GraphQL interceptor. */
    @Bean
    @ConditionalOnBean(ActorResolver::class)
    fun velleActorFilter(resolver: ActorResolver): OncePerRequestFilter = object : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            resolver.actor(request)?.let { request.setAttribute(ACTOR_ATTRIBUTE, it) }
            chain.doFilter(request, response)
        }
    }

    /** The GraphQL schema built from the model; Boot's own schema-file source backs off. */
    @Bean
    @ConditionalOnClass(GraphQlSource::class)
    @ConditionalOnMissingBean(GraphQlSource::class)
    fun velleGraphQlSource(api: VelleApi, props: VelleProperties): GraphQlSource =
        GraphQlSource.builder(VelleGraphQL.schema(api, props.api.ticks))
            .configureGraphQl { it.defaultDataFetcherExceptionHandler(VelleGraphQL.ExceptionHandler) }
            .build()

    /** Carries the request's actor into the GraphQL context, where every fetcher reads it. */
    @Bean
    @ConditionalOnClass(WebGraphQlInterceptor::class)
    fun velleActorInterceptor(): WebGraphQlInterceptor = object : WebGraphQlInterceptor {
        override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
            (request.attributes[ACTOR_ATTRIBUTE] as? Ref<*>)?.let { actor ->
                request.configureExecutionInput { _, builder -> builder.graphQLContext(mapOf(VelleGraphQL.ACTOR to actor)).build() }
            }
            return chain.next(request)
        }
    }

    /**
     * Who hears what no caller is told. Every [TickListener] bean the application declares
     * is registered on the system; with none, [LoggingTickListener]. [MetricsTickListener]
     * is added beside them when the application has a `MeterRegistry` ([Metrics]). The
     * listeners are told by the runtime itself, so a tick from the scheduler, from the
     * console, or from the application's own call reaches them alike (Reference §21).
     */
    @Bean
    fun velleTickListeners(system: VelleSystem, declared: ObjectProvider<TickListener>): SmartInitializingSingleton = SmartInitializingSingleton {
        val all = declared.orderedStream().toList()
        val own = all.filter { it !is StarterTickListener }
        (own.ifEmpty { listOf(LoggingTickListener()) } + all.filter { it is StarterTickListener }).forEach(system::listen)
        log.info("Velle listeners: {}", system.tickListeners.map { it::class.simpleName })
    }

    /** The metrics listener, when Micrometer is on the classpath and the application has a registry. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
    class Metrics {
        @Bean
        @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry::class)
        fun velleMetricsTickListener(registry: io.micrometer.core.instrument.MeterRegistry): StarterTickListener = MetricsTickListener(registry)
    }

    /**
     * The ticks, as tasks on Spring's scheduler. Each schedule under `velle.schedules`
     * becomes one cron-triggered task, read in the schedule's zone, on the application's
     * `TaskScheduler`: the one Boot configures under `spring.task.scheduling.*`, or the
     * application's own. A tick therefore shows in Actuator's `scheduledtasks`, is
     * observed with the application's other tasks, and waits for shutdown as they do.
     * Scheduling is enabled here, so an application that only ticks needs no
     * `@EnableScheduling` of its own; one that has it loses nothing. `velle.ticker.enabled: false`
     * skips all of this on one instance and changes nothing else: the ticks endpoint, the
     * GraphQL mutation, and the generated `tick<Schedule>()` calls still work there.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(SchedulesConfigured::class)
    @ConditionalOnProperty(prefix = "velle.ticker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    class Ticker {

        private val log = LoggerFactory.getLogger(Ticker::class.java)

        /**
         * Registers one task per schedule. A schedule the spec lacks, or a cron that does not parse,
         * stops the application at startup. The configured name is matched to the spec's schedule
         * without regard to case, because an environment variable (`VELLE_SCHEDULES_WEEKLY_CRON`)
         * reaches the map as `weekly`.
         */
        @Bean
        fun velleTicks(api: VelleApi, props: VelleProperties): SchedulingConfigurer = SchedulingConfigurer { registrar: ScheduledTaskRegistrar ->
            val named = props.schedules.keys.associateWith { key -> api.schedules.firstOrNull { it.equals(key, ignoreCase = true) } }
            val unknown = named.filterValues { it == null }.keys
            if (unknown.isNotEmpty()) throw IllegalStateException("velle.schedules names $unknown; this system has ${api.schedules}")
            val twice = named.values.groupBy { it }.filterValues { it.size > 1 }.keys
            if (twice.isNotEmpty()) throw IllegalStateException("velle.schedules names $twice more than once: ${named.filterValues { it in twice }.keys}")
            for ((key, s) in props.schedules) {
                val schedule = named.getValue(key)!!
                val zone = s.zone ?: ZoneId.systemDefault()
                val trigger = try {
                    CronTrigger(s.cron, zone)
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("velle.schedules.$schedule: ${e.message}", e)
                }
                registrar.addTriggerTask({ tick(api, schedule) }, trigger)
                log.info("Velle tick {} scheduled: {} in {}", schedule, s.cron, zone)
            }
        }

        /**
         * One run of a schedule: `api.tick`. The listeners hear how it went; this only keeps
         * the scheduler alive when the tick itself, or a listener, throws, so the next run happens.
         */
        private fun tick(api: VelleApi, schedule: String) {
            try {
                api.tick(schedule)
            } catch (e: Exception) {
                log.error("Velle tick $schedule failed", e)
            }
        }
    }

    /**
     * `velle.schedules` has an entry. A map property has no value of its own, only its
     * entries do, so `@ConditionalOnProperty` never sees it; this binds the map instead.
     */
    class SchedulesConfigured : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val bound = Binder.get(context.environment).bind("velle.schedules", Bindable.mapOf(String::class.java, Any::class.java))
            return if (bound.isBound && bound.get().isNotEmpty()) ConditionOutcome.match("velle.schedules names ${bound.get().keys}")
            else ConditionOutcome.noMatch("velle.schedules is not set")
        }
    }

    /** The application declared its system: a generated system or the runtime's `VelleSystem`. */
    class SystemDeclared : AnyNestedCondition(org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN) {
        @ConditionalOnBean(GeneratedSystem::class) class Generated
        @ConditionalOnBean(VelleSystem::class) class Runtime
    }

    companion object {
        /** The request attribute the actor filter sets: a `Ref<*>` to the actor's record. */
        const val ACTOR_ATTRIBUTE = "velle.actor"

        /** The request's actor, when a resolver found one. */
        fun actorOf(request: HttpServletRequest): Ref<*>? = request.getAttribute(ACTOR_ATTRIBUTE) as? Ref<*>
    }
}
