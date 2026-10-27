package velle.api.spring

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Import
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.web.filter.OncePerRequestFilter
import reactor.core.publisher.Mono
import velle.GeneratedSystem
import velle.Ref
import velle.VelleSystem
import velle.api.VelleApi
import velle.api.graphql.VelleGraphQL
import velle.postgres.PostgresStore
import velle.sql.SqlStore
import velle.sqlite.SqliteStore
import java.time.Clock
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
 * - ticks each schedule the application gives a cron expression under `velle.schedules`;
 * - serves the console page at `velle.console.path`.
 *
 * Every operation passes through one [VelleApi], so REST, GraphQL, and the ticks
 * take turns on the system and its store connection.
 */
@AutoConfiguration(before = [GraphQlAutoConfiguration::class], after = [DataSourceAutoConfiguration::class])
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

    /** Ticks each schedule the application gave a cron expression, one at a time, logging what a `never` rejected. */
    @Bean
    @ConditionalOnProperty(prefix = "velle", name = ["schedules"], matchIfMissing = false)
    fun velleTicker(api: VelleApi, props: VelleProperties): SmartInitializingSingleton = SmartInitializingSingleton {
        val unknown = props.schedules.keys - api.schedules.toSet()
        if (unknown.isNotEmpty()) throw IllegalStateException("velle.schedules names $unknown; this system has ${api.schedules}")
        val scheduler = ThreadPoolTaskScheduler().apply { poolSize = 1; setThreadNamePrefix("velle-tick-"); initialize() }
        for ((schedule, cron) in props.schedules) {
            scheduler.schedule({
                try {
                    val outcome = api.tick(schedule)
                    val rejections = outcome["rejections"] as List<*>
                    if (rejections.isEmpty()) log.info("Velle tick {} ran", schedule)
                    else log.warn("Velle tick {} ran; {} firing(s) rejected:\n{}", schedule, rejections.size,
                        rejections.joinToString("\n\n") { (it as Map<*, *>)["message"].toString() })
                } catch (e: Exception) {
                    log.error("Velle tick $schedule failed", e)
                }
            }, CronTrigger(cron))
            log.info("Velle tick {} scheduled: {}", schedule, cron)
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
