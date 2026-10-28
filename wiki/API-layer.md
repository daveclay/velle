# The API layer

The API layer is the code between a running system and the web: a class that performs every operation a caller can ask of any spec, the transports that map HTTP requests onto it, and the Spring Boot starter that wires both into an application. This page describes how that code is built, class by class, and what one request passes through. After reading it an engineer can follow a request from the HTTP socket to the runtime and back, replace any one layer, or add a transport of their own. [Serving a system](Serving-a-system) describes the same code from the outside: what the endpoints take and answer.

## The situation

The billing application in `examples/billing/app/` needs its system reachable from a web browser, from scripts, and from a scheduler, over a PostgreSQL database, with the person calling known to every act and read. The team wants that done once for every spec, not once per act, and wants each piece replaceable: a different transport later, a different store, a different way of knowing who is calling.

## The layers

```
examples/billing/app   BillingApplication, OverdueController        the application: one bean, its own endpoints
apis/spring            VelleAutoConfiguration, VelleRestController,  the Spring Boot starter: HTTP in, the store,
                       ActorResolver, VelleProperties, VelleConsole  the actor, the schedules, the console
apis/graphql           VelleGraphQL                                  a GraphQL schema built from the model
apis/json              VelleApi, Wire, Describe                      every operation, in JSON-shaped values
compiler               VelleSystem, Model, Parser, Cursor            the runtime and the compiled spec
```

- Each module depends on the one below it and on nothing beside it. `apis/json` imports no web framework and no GraphQL library. `apis/graphql` imports graphql-java and `apis/json`, and knows nothing of Spring. `apis/spring` imports Spring Boot and both.
- Every operation, from REST, GraphQL, the scheduler, or the application's own code, goes through one `VelleApi` instance. There is no second path to the runtime.

## apis/json: the operations

`VelleApi` in `apis/json/src/main/kotlin/velle/api/VelleApi.kt` is the whole surface. Its constructor and the one primitive every operation runs inside:

```kotlin
class VelleApi(val system: VelleSystem, private val clock: Clock? = Clock.systemUTC()) {

    val wire = Wire(system)
    val description: Map<String, Any?> by lazy { describe.describe() }

    fun <T> scope(body: () -> T): T {
        clock?.let { system.setTime(it.instant()) }
        return system.scope(body)
    }
```

- `system` is the runtime: the compiled model over a store ([Store API](Store-API)), the in-memory one until another is connected.
- `scope` runs one operation. Operations run side by side: each one holds a working set of records of its own on its thread, and the store underneath opens one unit of work per read and per commit. Two commits that revolve around one record take turns on that record's queue key ([evaluation](evaluation), "serialization domain"); everything else runs together. Nothing in this class locks.
- `clock` sets the system's time before each operation, so `today` and `now` in the spec mean the wall clock. A test passes null and sets the time itself.
- `system.scope` opens one scope for the operation. Records are read through handles that are valid inside a scope and die with it, which is why every operation finishes building its JSON before `scope` returns.
- `description` is the model as data, computed once. `wire` is public because a transport that must read or write a value itself, as the GraphQL scalars do, uses the same codec.

The operations, each a public function on `VelleApi`:

| function | what it does |
|---|---|
| `commit(act, body, actor)` | checks the act exists and, when it is declared `by`, that an actor came; turns the body into the runtime's input through `Wire.input`; commits; answers accepted with the reference, or refused with the reason and the rejection as data |
| `read(root, key, viewer)` | finds the record by the store's key; not found when gone, not a member of the refinement, or not visible to the viewer; answers `Wire.record` |
| `readEach(refs, viewer)` | the records behind several references in one scope, null in place of any the viewer may not see; what a GraphQL collection field calls |
| `query(root, where, orderBy, size, after, viewer)` | parses `where` with `Parser.parsePredicate`, the order with `-` for descending, the page and its cursor; runs `system.query`; answers the items and the next cursor through `Cursor.encode` |
| `count(root, where, viewer)` | the same query, counted |
| `tick(schedule)` | `system.tick`; answers the time and the rejections |
| `refTo(shape, key)` | a reference from a shape name and a key, for a resolver building the actor |

Three things are checked before the scope opens: the act or shape exists (not found), the act's actor is present when `by` demands one (unauthorized), the body and the query parse (bad request). Each is an `ApiException` with a `Kind`, and a transport maps the kind onto its own status. A `never` refusing a commit is not an exception; it is the answer.

Reads and the viewer: once a spec has any `by` or `visible to` clause, `model.hasAuthorization` is true and every read with no viewer is unauthorized. A spec with no authorization serves the engineer's own view, which sees everything. The viewer is a `Ref<*>`; the operation resolves it to the runtime's handle with `system.idOf` inside its scope.

`Wire` in `apis/json/src/main/kotlin/velle/api/Wire.kt` is the value codec. Outbound, `record(id, scope, viewer)` builds a record's map: `shape` and `key` first, then every member of the scope from `system.get` and `system.getAs`, a hidden field as null, and on a shape one `is<Refinement>` flag per refinement. `out(value, type)` turns a runtime value into a JSON value: dates to ISO text, an extension type's value to its canonical text, an `integer` to a whole number. Inbound, `input(act, body)` splits the body into the runtime's `fields` and `parts` by the act's closure edges from `model.closures`, reads each scalar by the declared type through `scalar`, each reference through `reference`, and each nested object through `nested`, which handles `"@shape"` on a union operand, `"@new"` and `{"new": n}` for a sibling, and the part lists. It refuses a body that names the actor, a part's back-reference, or an unknown field.

`Describe` in `apis/json/src/main/kotlin/velle/api/Describe.kt` walks the model once and produces the description `GET /api` answers: each exposed act with its fields, their types, whether each is a value, a reference, or a nested record, and its parts; each readable shape and refinement with its members, its flags, and its visibility, and `velle: true` on `Tick`, the shape Velle declares and no act commits ([§17](Reference-17-Schedule-triggers)); the schedules, which are every rule trigger other than `commit`; and the authorization flag. Readable means every shape and refinement whose base is not a transient act ([§4](Reference-04-State-and-commits)). The GraphQL builder and the console both read this description and nothing else about the model.

## apis/graphql: a schema from the model

`VelleGraphQL` in `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`:

```kotlin
object VelleGraphQL {
    const val ACTOR = "velle.actor"
    fun schema(api: VelleApi, ticks: Boolean = false): GraphQLSchema = SchemaBuilder(api, ticks).build()
    fun graphQL(api: VelleApi, ticks: Boolean = false): GraphQL = ...
    object ExceptionHandler : DataFetcherExceptionHandler { ... }
    fun actorOf(env: DataFetchingEnvironment): Ref<*>? = env.graphQlContext.get<Ref<*>?>(ACTOR)
}
```

- `schema` builds the whole schema in one pass of a private `SchemaBuilder` over the model. It needs the `VelleApi` and not just the model because every data fetcher it registers calls one of the operations above.
- `ACTOR` is the key under which a transport places the request's actor in the GraphQL context. Every fetcher reads it with `actorOf` and passes it as the actor or the viewer. The schema itself never sees an HTTP request.
- `ExceptionHandler` turns an `ApiException` into a GraphQL error whose `extensions.kind` is the exception's kind, so a client can tell a bad request from an unauthorized one.

What the builder makes, per model element:

- For each readable shape or single-base refinement, an object type with a field per member and per flag. A reference member's fetcher calls `api.readEach` for the records behind it, so one query walks the graph. A cross-base refinement, a static union ([§9](Reference-09-Composing-refinements)), becomes a GraphQL union whose type resolver answers from the record's `shape`.
- For each readable root, three query fields: one by key, one paged query taking `where`, `orderBy`, `first`, and `after`, and one count. Their fetchers are `api.read`, `api.query`, and `api.count`.
- For each exposed act, one mutation whose input type mirrors the act's fields as `Describe` lists them: a scalar by type, a plain single-shape reference as `ID`, a reference that may name a union operand or a sibling as `RefInput`, a field named in `with` as an input holding `existing` or exactly one `new<Operand>`, and a part collection as a list of nested input types. The fetcher turns the input back into the JSON form `Wire.input` reads, with `asNew` becoming `"@new"`, and calls `api.commit`. The outcome type carries the acceptance, the reference, the record read back, or the refusal.
- One scalar per wire scalar that is not a GraphQL builtin, and one per extension type in the model under its own name. Each scalar validates through `api.wire.scalar` and passes the wire text through unchanged, so the value a client sends is the text REST would take.
- A `tick` mutation when `ticks` is on.

## apis/spring: the starter

`VelleAutoConfiguration` in `apis/spring/src/main/kotlin/velle/api/spring/VelleAutoConfiguration.kt` is one auto-configuration class. Its header:

```kotlin
@AutoConfiguration(before = [GraphQlAutoConfiguration::class], after = [DataSourceAutoConfiguration::class])
@Conditional(VelleAutoConfiguration.SystemDeclared::class)
@EnableConfigurationProperties(VelleProperties::class)
@Import(VelleRestController::class, VelleConsole::class)
class VelleAutoConfiguration {
```

- It is registered in `apis/spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, so any application with the starter on its classpath gets it without naming it.
- `after DataSourceAutoConfiguration` means the application's `DataSource` exists before the store is connected. `before GraphQlAutoConfiguration` means the schema bean below is in place before Spring's GraphQL support looks for one, so Spring's own schema-file loading backs off.
- `SystemDeclared` is the one condition for the whole class: the application has declared either a `GeneratedSystem` bean, which every generated system is, or a `VelleSystem` bean, the runtime built from spec text with no generated code. With neither, the starter does nothing.
- `VelleRestController` and `VelleConsole` are imported rather than scanned, so they exist only when the starter is active.

The beans it declares, in the order a request meets them:

```kotlin
@Bean @ConditionalOnMissingBean(VelleSystem::class) @ConditionalOnBean(GeneratedSystem::class)
fun velleSystem(generated: GeneratedSystem): VelleSystem = generated.system

@Bean @ConditionalOnMissingBean
fun velleApi(system: VelleSystem, props: VelleProperties, dataSource: ObjectProvider<DataSource>,
             store: ObjectProvider<SqlStore>, clock: ObjectProvider<Clock>): VelleApi {
    val declared = store.ifAvailable
    if (declared == null && props.store.enabled) dataSource.ifAvailable { ds -> attach(system, ds, props.store.createSchema) }
    return VelleApi(system, clock.getIfAvailable { Clock.systemUTC() })
}
```

- `velleSystem` unwraps the runtime from a generated system, so the rest of the starter works on `VelleSystem` alone.
- `velleApi` connects the store and makes the one `VelleApi`. The store is connected here, while the bean is being made, so no request ever reaches a system that is half connected. The private `attach` reads `databaseProductName` from one connection and returns it, picks `PostgresStore` or `SqliteStore` over the `DataSource`, creates the tables when `velle.store.create-schema` is on, and calls `system.connect(store, store)`. The store holds no connection of its own: each read and each commit takes one from the pool for exactly as long as it runs ([Store API](Store-API), "The unit of work"). Any other database stops the application with a message naming it.
- Three things in it are overridable by declaring a bean: a `SqlStore` of the application's own, already connected, which makes `attach` skip; a `Clock`, which a test fixes; and a `VelleApi` itself.
- With no `DataSource` and no `SqlStore`, the system runs over its in-memory store. The starter keeps JDBC as a compile-time dependency only, so an application without a database does not pull one in.

```kotlin
@Bean @ConditionalOnBean(ActorResolver::class)
fun velleActorFilter(resolver: ActorResolver): OncePerRequestFilter = object : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        resolver.actor(request)?.let { request.setAttribute(ACTOR_ATTRIBUTE, it) }
        chain.doFilter(request, response)
    }
}
```

- `ActorResolver` in `apis/spring/src/main/kotlin/velle/api/spring/ActorResolver.kt` is a one-function interface from an `HttpServletRequest` to a `Ref<*>` or null. The application declares one bean; without one the filter is not made and every request is anonymous.
- The filter runs once per request and leaves the reference on the request under the attribute `velle.actor`. It runs after the application's own security filters when those are ordered before it, which is where a resolver reading the authenticated principal gets it from.

```kotlin
@Bean @ConditionalOnClass(GraphQlSource::class) @ConditionalOnMissingBean(GraphQlSource::class)
fun velleGraphQlSource(api: VelleApi, props: VelleProperties): GraphQlSource =
    GraphQlSource.builder(VelleGraphQL.schema(api, props.api.ticks))
        .configureGraphQl { it.defaultDataFetcherExceptionHandler(VelleGraphQL.ExceptionHandler) }
        .build()

@Bean @ConditionalOnClass(WebGraphQlInterceptor::class)
fun velleActorInterceptor(): WebGraphQlInterceptor = object : WebGraphQlInterceptor {
    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        (request.attributes[ACTOR_ATTRIBUTE] as? Ref<*>)?.let { actor ->
            request.configureExecutionInput { _, builder -> builder.graphQLContext(mapOf(VelleGraphQL.ACTOR to actor)).build() }
        }
        return chain.next(request)
    }
}
```

- `velleGraphQlSource` is the schema from `apis/graphql` handed to Spring's GraphQL support, which serves it at `/graphql`. An application that declares its own `GraphQlSource` replaces it.
- `velleActorInterceptor` is the bridge between the filter and the schema: it copies the request attribute into the GraphQL context under the key the fetchers read. Spring's GraphQL engine has no view of the servlet request, so this interceptor is the one place the two meet.

```kotlin
@Configuration(proxyBeanMethods = false) @Conditional(SchedulesConfigured::class)
@ConditionalOnProperty(prefix = "velle.ticker", name = ["enabled"], havingValue = "true", matchIfMissing = true) @EnableScheduling
class Ticker {
    @Bean
    fun velleTicks(api: VelleApi, props: VelleProperties): SchedulingConfigurer = SchedulingConfigurer { registrar ->
        val unknown = props.schedules.keys - api.schedules.toSet()
        if (unknown.isNotEmpty()) throw IllegalStateException("velle.schedules names $unknown; this system has ${api.schedules}")
        for ((schedule, s) in props.schedules)
            registrar.addTriggerTask({ tick(api, schedule) }, CronTrigger(s.cron, s.zone ?: ZoneId.systemDefault()))
    }
}
```

- `Ticker` is present when `velle.schedules` has an entry. It enables Spring's scheduling and registers one cron task per schedule with Spring's `ScheduledTaskRegistrar`, so the tasks run on the application's `TaskScheduler`, the one Boot configures under `spring.task.scheduling`, and appear wherever Spring lists its scheduled tasks. Each task is `api.tick`, in a scope of its own beside the requests. Spring calls the configurer once every singleton exists, so the store is connected before the first tick.
- `SchedulesConfigured` decides by binding the map, because a map property has no value of its own for `@ConditionalOnProperty` to see. `velle.ticker.enabled: false` skips `Ticker` on one instance and nothing else: `VelleApi.tick` still serves the endpoint, the mutation, and the generated calls there.
- `TickerShutdownDefaults` (`apis/spring/src/main/kotlin/velle/api/spring/TickerShutdownDefaults.kt`) is an `EnvironmentPostProcessor` named in `META-INF/spring.factories`. When `velle.schedules` has an entry it adds `spring.task.scheduling.shutdown.await-termination: true` and a thirty-second period beneath every property source of the application's, so a restart lets a sweep in flight finish before the `DataSource` closes and the log does not report a tick failure for a restart. The application's own setting of either property wins.
- `velleTickListeners` registers on the `VelleSystem` every `TickListener` bean the application declares, or `LoggingTickListener` when it declares none, and `MetricsTickListener` beside them when a `MeterRegistry` bean exists (`apis/spring/src/main/kotlin/velle/api/spring/TickListeners.kt`). The runtime tells them after each tick and after each act whose after-commit firings were refused or failed, so every transport's tick reaches them; `Ticker.tick` itself only catches, so a throwing tick or listener never stops the scheduler. The ticks endpoint and the GraphQL mutation stay a development affordance, off by default.
- A configured name is matched to the spec's schedule without regard to case, because an environment variable reaches the map in lower case. A schedule named in `velle.schedules` that the spec does not have, or named twice in two spellings, or a cron expression that does not parse, stops the application at startup. A `never` that rejects a firing is logged with the rejection's message, so a tick's refusals reach a human ([§21](Reference-21-never)).

`VelleRestController` in `apis/spring/src/main/kotlin/velle/api/spring/VelleRestController.kt` is thin on purpose. Each endpoint is one line that reads the actor from the request attribute and calls the matching `VelleApi` function; `commit` sets 200 or 422 from the outcome's `accepted`; one `@ExceptionHandler` maps `ApiException.Kind` onto 400, 404, and 401. Nothing about any spec is in it.

`VelleProperties` in `apis/spring/src/main/kotlin/velle/api/spring/VelleProperties.kt` binds `velle.*`: `api.path` and `api.ticks`, `store.enabled` and `store.create-schema`, `ticker.enabled`, `schedules` as a map from schedule name to a cron expression, written alone or as `cron` and `zone`, `console.enabled` and `console.path`.

`VelleConsole` in `apis/spring/src/main/kotlin/velle/api/spring/VelleConsole.kt` serves one resource, `apis/spring/src/main/resources/velle/console.html`, at `velle.console.path`, after substituting the API path and the ticks flag into two placeholders. The page is plain HTML and JavaScript with no build step. On load it fetches `GET /api` and keeps the description; every view is rendered from it: the shape list and act list from `shapes` and `acts`, a table's columns from a shape's members, a form's inputs from an act's fields and their kinds, the tick buttons from `schedules`. Every data call is one of the REST endpoints, with one optional header the developer typed, kept in the browser's local storage, added to each request. The console is a client of the REST API and has no other access.

## One request, end to end

`POST /api/acts/SubmitPayment` with `{"invoice": 1, "amount": "1200.00"}` and the header `X-Velle-Actor: Staff:1`, in an application with a resolver that reads that header:

1. The servlet filter chain runs the application's security filters, then `velleActorFilter`. The resolver returns a reference to `Staff` 1, and the filter sets it as the request attribute.
2. Spring MVC routes to `VelleRestController.commit`, which reads the attribute and calls `api.commit("SubmitPayment", body, actor)`.
3. `VelleApi.commit` checks the act is exposed and that an actor is present if the act is declared `by`. Then it sets the clock and opens a scope.
4. `Wire.input` reads the body against the act's declaration: `invoice` is a `one Invoice`, so the number becomes a reference; `amount` is `Money`, so the text is read by the extension type's reader.
5. `system.commit` runs the transaction: the store opens a write unit of work and takes the envelope's queue keys, so a second payment on the same invoice waits here; then the door check, every `never`, the rules on `commit`, the store's write ([evaluation](evaluation)).
6. The result is accepted with a reference, or refused with a rejection. `Wire.rejection` projects the rejection for the actor. The scope closes, the unit of work ends, and the queue keys are released.
7. The controller answers 200 or 422 with the map as JSON.

The same request as the GraphQL mutation `submitPayment(input: {invoice: "1", amount: "1200.00"})` differs only in steps 2 and 4: the interceptor copies the actor into the GraphQL context, graphql-java coerces the `Money` scalar through `api.wire.scalar`, and the mutation's fetcher rebuilds the JSON map and calls the same `api.commit`.

## Replacing a layer

- **Another transport**, a message queue or a command-line tool: depend on `apis/json`, hold one `VelleApi`, and map each incoming message onto one of its functions. The description tells the transport what exists. Map `ApiException.Kind` onto whatever the transport's errors are, and treat a refusal as a normal answer.
- **Another store**: declare a `SqlStore` bean already connected, or any store, and connect it to the system yourself before declaring the `VelleSystem` bean. The starter sees the `SqlStore` bean and does not attach.
- **Another schema**: declare a `GraphQlSource` bean. The REST endpoints and the actor filter are unaffected.
- **Another identity**: the `ActorResolver` is the whole of it. Behind Spring Security it reads the principal and looks the staff record up through `api.refTo` or through the application's own typed read.
- **No web at all**: `VelleApi` and `VelleGraphQL` run without Spring, which is how `apis/json/src/test/kotlin/velle/api/VelleApiTest.kt` and `apis/graphql/src/test/kotlin/velle/api/graphql/VelleGraphQLTest.kt` run them.

## What the starter checks at startup

- The application stops when `velle.schedules` names a schedule the spec does not have, with the message naming both lists.
- The application stops when the `DataSource` is a database with no SQL dialect, with the message naming the database and the `SqlStore` bean to declare instead.
- The application stops when no `GeneratedSystem` or `VelleSystem` bean is declared and something depends on `VelleApi`, since the starter then declares nothing.

## Related pages

- [Serving a system](Serving-a-system), the same code from the caller's side: the endpoints, the JSON, the schema, the console.
- [Store API](Store-API), the contract `attach` connects: `SqlStore` and its dialects.
- [Binding API](Binding-API), the generated typed surface the application's own endpoints call inside `api.scope`.
- [Authorization](Authorization), what the actor and the viewer mean once resolved.
- [evaluation](evaluation), what `system.commit` and `system.tick` do inside the scope.
