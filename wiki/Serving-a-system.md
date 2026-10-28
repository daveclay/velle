# Serving a system

A running Velle system is reached from outside through the API layer: one set of operations, built from the spec's model, that commits an act, reads a record, runs a query, counts, fires a tick, and describes the model. Nothing in it is written per act or per shape. Over that layer sit a REST surface, a GraphQL schema, and a Spring Boot starter that connects the system to the application's database, resolves who is calling, runs the schedules, and serves a console page. After reading this page you can put a spec behind a web API on Spring Boot over PostgreSQL by declaring one bean, and you know what each endpoint takes and answers.

## The situation

The billing team's invoicing system, `examples/billing/billing.velle`, has to be reachable from the web application, from the finance team's scripts, and from the collections screen. Sign-ups, line items, and payments arrive as HTTP requests. The collections screen lists overdue invoices. The weekly reminder sweep has to run on Monday mornings. Nobody wants to write a controller per act, a serializer per shape, or a scheduler per rule, and nobody wants a second place where the rules live.

## The application

The whole application is one class, `examples/billing/app/src/main/kotlin/velle/billing/BillingApplication.kt`:

```kotlin
@SpringBootApplication
class BillingApplication {

    @Bean
    fun billing(): BillingSystem = BillingSystem(startTime = Instant.now())
}

fun main(args: Array<String>) {
    runApplication<BillingApplication>(*args)
}
```

Its build, `examples/billing/app/build.gradle.kts`, depends on the generated system and the starter:

```kotlin
implementation(project(":apis:spring"))
implementation(project(":examples:billing:output"))
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("org.postgresql:postgresql")
```

Its configuration, `examples/billing/app/src/main/resources/application.yaml`, names the schedules and where the API lives:

```yaml
velle:
  api:
    path: /api
    ticks: true
  schedules:
    Hourly: "0 0 * * * *"
    Weekly:
      cron: "0 0 6 * * MON"
      zone: America/New_York
  console:
    path: /velle
```

- `BillingSystem()` is the generated system ([Binding API](Binding-API)). Declaring it as a bean is the one thing the application does for Velle.
- The starter, `apis/spring`, finds that bean and does the rest: it connects the system to the application's `DataSource` through the SQL store ([Store API](Store-API)), creates the tables, serves every act and shape over REST and GraphQL, ticks the schedules, and serves the console.
- `spring-boot-starter-jdbc` and the PostgreSQL driver are the application's: they decide which database it talks to. The starter reads the database's name from the connection and picks the SQLite or PostgreSQL dialect.
- `velle.schedules` says when each schedule the spec names ticks: a cron expression, alone or with the time zone it is read in. `Hourly` runs at the top of every hour in the server's own zone. `Weekly` runs at 06:00 on Monday in New York. A schedule with no entry never ticks on its own.
- The ticks are tasks on Spring's task scheduler. The `spring.task.scheduling` settings apply to them, and the Actuator `scheduledtasks` endpoint lists them beside the application's own scheduled tasks.
- A shutdown waits up to thirty seconds for a sweep that is running, so a restart does not cut a firing off under a closing database. The starter sets Boot's `spring.task.scheduling.shutdown` properties for that when it has schedules. Your own setting of them wins.
- `velle.ticker.enabled: false` keeps one copy of the application from running the schedules, and changes nothing else: the ticks endpoint and the console's buttons still work on it. Two copies ticking the same schedule change the data once ([Concurrency, "Scheduled rules"](Concurrency#scheduled-rules)), but each runs the whole sweep, so when the application runs as several copies, turn the ticker off on all but one.
- `velle.api.ticks` lets a caller fire a tick over HTTP. It is for a console or a test, and off by default.

Run it with `./gradlew :examples:billing:app:bootRun`. Spring Boot starts the PostgreSQL in `examples/billing/app/compose.yaml` and points the `DataSource` at it. The console is at `http://localhost:8090/velle`.

## Your own cadence

A schedule can tick when your code says instead of on a cron expression the starter reads: when a file arrives, when an operator presses a button, or on a `@Scheduled` method of your own. Leave the schedule out of `velle.schedules` and tick it yourself:

```kotlin
@Scheduled(cron = "0 0 6 * * MON", zone = "America/New_York")
fun weekly() { billing.tickWeekly() }
```

- `billing.tickWeekly()` is the generated call for the schedule `Weekly` ([Binding API, "Ticking a schedule"](Binding-API#ticking-a-schedule)). It returns the tick's report and throws `TickRejected` when a `never` refused a firing. An application serving a spec without generated code calls `api.tick("Weekly")` on the `VelleApi` bean instead.
- A schedule with no entry under `velle.schedules` is never ticked by the starter, so it ticks only when your code says. Keep a schedule in one place or the other: under `velle.schedules` and in your code too, it ticks twice.
- `@Scheduled` needs `@EnableScheduling` on a configuration class of yours. The starter turns it on only when `velle.schedules` has an entry.
- The listener in the next section hears your ticks as it hears the starter's, and the ticks endpoint and the console's buttons work as before.

## Hearing a tick

A tick has no caller to tell when a rule's firing was refused by a `never` or failed because the database did. The same is true of a rule behind an `after commit` boundary ([§18](Reference-18-Run-once-guards)). Velle tells a listener instead, and the starter registers one for you.

By default the listener is the log: a tick that ran is one info line with its firings per rule, and a refused or failed firing is a warning carrying the rendered message, so it reaches a human without any code of yours. To hear it yourself, declare a bean:

```kotlin
@Bean
fun pager(): TickListener = object : TickListener {
    override fun ticked(report: TickReport, rejections: List<Rejection>) {
        if (rejections.isNotEmpty() || report.failures.isNotEmpty()) page(report, rejections)
    }
    override fun afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>) {
        page(act, rejections, failures)
    }
}
```

- `ticked` is called after every tick, once the tick is on record, with the report and the firings a `never` refused. `report.firings` counts what landed per rule; `report.failures` names each firing that failed, with the rule, the record it ran for, and the cause.
- `afterCommit` is called only when an act's after-commit firings were refused or failed, with the act's record and both lists. The code that committed the act has the same lists on its result; the listener is for whoever is not reading that result.
- Your bean replaces the log. Declare several and all are told. A listener that throws undoes nothing: the tick's row and its firings stand, and the exception reaches whoever called the tick after every listener has been told.
- With a `MeterRegistry` in the application, Actuator's for example, the starter also counts: `velle.ticks` by schedule and outcome, `velle.tick.firings`, `velle.tick.rejections`, and `velle.tick.failures` by schedule and rule, and `velle.after_commit.rejections` and `velle.after_commit.failures` by rule.

The listener is told by the Velle system itself, so it hears a tick from the scheduler, from the console's button, and from your own call to the generated `tickWeekly()` alike. The ticks endpoint and the console's button exist for development; in a deployment the cadence is the scheduler's and the listener is how anyone hears of it.

## REST

Every endpoint hangs from `velle.api.path`, `/api` here. `{Shape}` is a shape or a refinement.

| request | answer |
|---|---|
| `GET /api` | the model: every act and what it takes, every readable shape and its members, the schedules, whether the system has authorization |
| `POST /api/acts/{Act}` | commits the act with the JSON body as its input: 200 when accepted, 422 when a `never` refused it, 400 when the body is malformed |
| `GET /api/shapes/{Shape}/{key}` | one record by the store's key: 404 when there is none, it is not a member of the refinement, or the caller may not see it |
| `GET /api/shapes/{Shape}?where=&orderBy=&size=&after=` | the records that satisfy `where`, in the order `orderBy` names, a page at a time |
| `GET /api/shapes/{Shape}/count?where=` | how many |
| `POST /api/ticks/{Schedule}` | runs every rule on the schedule now, when `velle.api.ticks` is on; for development and the console, not for a deployment's cadence |

A session against the billing application:

```
POST /api/acts/SignUp
{"name": "Ada Lovelace", "email": "ada@example.com"}
→ 200 {"accepted": true, "ref": null}

GET /api/shapes/Customer?orderBy=name
→ 200 {"items": [{"shape": "Customer", "key": 1, "name": "Ada Lovelace", "email": "ada@example.com",
                  "signedUpOn": "2026-10-26T14:02:11Z", "largestPayment": "0.00",
                  "correctEmails": [], "invoices": []}], "next": null}

POST /api/acts/BillCustomer
{"customer": 1, "due": "2026-11-25"}
→ 200 {"accepted": true, "ref": null}

POST /api/acts/LineItem
{"invoice": 1, "description": "Design work", "price": "1200.00", "quantity": 1}
→ 200 {"accepted": true, "ref": {"shape": "LineItem", "key": 1}}

GET /api/shapes/Invoice/1
→ 200 {"shape": "Invoice", "key": 1, "customer": {"shape": "Customer", "key": 1}, "due": "2026-11-25",
       "total": "1200.00", "balance": "1200.00", "status": "open",
       "lineItems": [{"shape": "LineItem", "key": 1}], "payments": [], ...
       "isPaidInvoice": false, "isOverdueInvoice": false, "isIssuedInvoice": false, ...}

POST /api/acts/SubmitPayment
{"invoice": 1, "amount": 0}
→ 422 {"accepted": false, "reason": "...", "rejection": {"never": 3, "targetShape": "SubmitPayment",
       "declaration": "never (SubmitPayment where amount <= 0)", "message": "...", ...}}

GET /api/shapes/OverdueInvoice?where=balance > 1000&orderBy=-due&size=20
→ 200 {"items": [...], "next": "bjoyMDI2LTEx..."}
```

- A transient act ([§4](Reference-04-State-and-commits)) answers `"ref": null`. The record its rule made is found by a query, as the customer is above.
- A durable act answers with a reference to the record it became: the shape and the store's key. A reference is the same pair wherever it appears, in an answer and in a request.
- A `one` field in a request takes the key alone when the field's type is one shape, or `{"shape": "Fermentation", "key": 7}` when the type is a union of shapes ([§9](Reference-09-Composing-refinements)). A field named in the act's `with` takes a nested object instead of a key, with `"@shape"` naming which operand it is when the type is a union ([§6](Reference-06-Relationships), "A reference supplied by value"). A part collection is a list of nested objects. A value another nested value points at carries `"@new": 1`, and the pointing field holds `{"new": 1}`.
- A `Date`, `DateTime`, `time`, and `uuid` is its ISO text. A `duration` is ISO-8601 text such as `PT36H`, or the spec's spelling, `3 days`. `bytes` is base64. An `integer` is a whole number, every other number a decimal. A value of an [extension type](Extension-types) is its own text, `"1200.00"` for `Money`, and a number is accepted where the type reads one.
- A record carries every member of the shape, including derived properties and inferred inverse collections, and one `is<Refinement>` flag per refinement of the shape, as the generated model object does.
- `where` is a predicate in the spec's own language over the members of the shape ([§10](Reference-10-Predicate-expressions)): `balance > 0 and due < today`. `orderBy` names one or more orderable members, `-due` for descending, and is required because a page without an order has no next page. `after` is the `next` value of the previous page, opaque.
- A refusal is an answer, not an error. The rejection beside the reason is the same data the generated commit function returns ([§21](Reference-21-never)), projected for the actor as a read would be.

## GraphQL

The same operations are a GraphQL schema at `/graphql`, built from the model by `apis/graphql/src/main/kotlin/velle/api/graphql/VelleGraphQL.kt`. For the billing spec the schema has, among others:

```graphql
type Invoice {
  shape: String!
  key: ID!
  customer: Customer!
  due: Date!
  total: Money!
  balance: Money!
  status: String!
  lineItems: [LineItem!]!
  isPaidInvoice: Boolean!
  isOverdueInvoice: Boolean!
}

type Query {
  invoice(key: ID!): Invoice
  invoices(where: String, orderBy: [String!]!, first: Int, after: String): InvoicePage!
  invoiceCount(where: String): Int!
}

type Mutation {
  lineItem(input: LineItemInput!): LineItemOutcome!
  submitPayment(input: SubmitPaymentInput!): SubmitPaymentOutcome!
}

input LineItemInput {
  invoice: ID!
  description: String!
  price: Money!
  quantity: Int!
}

type LineItemOutcome {
  accepted: Boolean!
  ref: Reference
  record: LineItem
  reason: String
  rejection: Rejection
}
```

```graphql
mutation {
  submitPayment(input: {invoice: "1", amount: "1200.00"}) { accepted reason }
}

query {
  invoice(key: "1") { status balance isPaidInvoice receipts { receiptEmails { queuedOn } } }
  overdueInvoices(where: "balance > 1000", orderBy: ["-due"], first: 20) { items { due balance customer { name } } next }
}
```

- One object type per readable shape and refinement, with every member and flag the REST record has. A reference field resolves to the record it points at, so a query walks the data graph.
- A static union ([§9](Reference-09-Composing-refinements)) is a GraphQL union of its operands. A field typed by one is queried with `... on Fermentation { }`.
- `Date`, `DateTime`, `Time`, `UUID`, `Duration`, `Bytes`, `Decimal`, and `Long` are scalars carrying the same text and numbers as REST. Each extension type is a scalar under its own name, `Money` here.
- One mutation per act, taking an input type shaped as the act's input class is ([Binding API](Binding-API)). A `one` field is an `ID`, or a `RefInput` with `shape`, `key`, and `new` where the field's type is a union or the field may point at a sibling value. A field named in `with` takes `{existing: {...}}` or exactly one `new<Operand>: {...}`. A nested value may carry `asNew: 1` for a sibling to point at with `{new: 1}`.
- The outcome is the acceptance or the refusal as data, with the record read back after an accepted durable act. A malformed input is a GraphQL error before anything runs, and a refused query is a GraphQL error carrying `extensions.kind`.

## Who is calling

An act declared `by` ([Authorization](Authorization)) is committed by the request's actor, and once a spec says who may see its records, every read is for a viewer. The starter asks the application once per request, through one bean:

```kotlin
@Bean
fun actors(): ActorResolver = ActorResolver { request ->
    request.getHeader("X-Velle-Actor")?.let { header ->
        val (shape, key) = header.split(':', limit = 2)
        Ref.Persisted(shape, StoreKey(key.toLong()))
    }
}
```

This one, from `apis/spring/src/test/kotlin/velle/api/spring/VendorsApp.kt`, reads a header. An application behind Spring Security reads the authenticated principal instead and looks up the staff record. Velle never authenticates: the resolver is where the application's identity becomes a reference to a record on the books.

- The resolver runs once per request, in a filter, before the REST controller or the GraphQL engine. Both read what it found.
- An act declared `by` with no actor resolved is refused with 401. An act without `by` ignores the actor.
- A read on a system with any `by` or `visible to` clause with no actor resolved is refused with 401. There is no anonymous view: `visible to anyone` is for every viewer the resolver provides. A system with no authorization serves the engineer's own view, which sees everything.
- A record the viewer may not see is 404 by key and absent from a query. A field the viewer may not see is null. A refusal's rejection is projected for the actor the same way.

## The application's own code

The generic endpoints do not replace the engineer's code; they sit beside it. `examples/billing/app/src/main/kotlin/velle/billing/OverdueController.kt` is an endpoint written against the generated typed surface:

```kotlin
@GetMapping("/overdue")
fun overdue(): List<Overdue> = api.scope {
    val today = LocalDate.ofInstant(billing.now, ZoneOffset.UTC)
    billing.read.overdueInvoices.orderBy { due.ascending }.list().map { inv ->
        Overdue(inv.key.value as Long, billing.read(inv.customer).name, inv.due, inv.balance.toString(),
                ChronoUnit.DAYS.between(inv.due, today))
    }
}
```

- `billing.read.overdueInvoices` is the generated typed query ([Binding API](Binding-API)); the result is the generated model object with `Money` as `Money`.
- `api.scope { }` runs the read as the generic endpoints run theirs: in one scope of the runtime, with a working set of records of its own and one read unit of work on the store, so it never sees a commit half landed. Other requests run at the same time; two commits that revolve around one record take turns on the store's queue keys ([Store API](Store-API), "The unit of work").
- The system's clock is the wall clock. Before each operation the starter sets it, so `today` and `now` in the spec mean today and now.

## The console

`/velle` serves one page, `apis/spring/src/main/resources/velle/console.html`, that reads `GET /api` and builds itself: every shape as a table with the query's `where` and order, every record as a page of its members with its references as links, every act as a form, every schedule as a button when ticks are exposed. A header the page sends with every request, kept in the browser, lets a developer act as someone, `X-Velle-Actor: Staff:1` with the resolver above. Nothing in the page is written per spec.

## In memory and over SQLite

An application with no `DataSource` runs the system over its in-memory store, which is how the starter's own tests run the vendors spec (`apis/spring/src/test/kotlin/velle/api/spring/VendorsOverHttpTest.kt`). A `DataSource` whose URL is `jdbc:sqlite:...` runs it over SQLite through the same store (`apis/spring/src/test/kotlin/velle/api/spring/BillingOverSqliteTest.kt`). The whole stack over PostgreSQL in a container is `examples/billing/app/src/test/kotlin/velle/billing/BillingApplicationTest.kt`, which runs when Docker is available.

A system with no generated code is served the same way: declare a `VelleSystem` built from the spec text as the bean, as `VendorsApp.kt` does. The API layer works from the model alone.

## What the compiler checks

The API layer runs no spec that did not validate. What it refuses at the boundary, before the runtime:

- **400** a body that names a field the act has no stored field for, claims the actor or a part's back-reference, or writes a value the field's type does not read, naming the field and the reason.
- **400** a query whose `where` does not parse, names a member the shape has no member of, or compares members of different types, or whose order is missing or not orderable, with the validator's diagnostics ([checks.md, F1 and F2](checks#f1--unknown-name)).
- **400** a read of a transient act or a refinement of one, which is never readable ([§4](Reference-04-State-and-commits)).
- **401** an act declared `by`, or any read on a system with authorization, with no actor resolved.
- **404** an unknown act, shape, refinement, or schedule, and a record that is gone, not a member, or not shown to the viewer.
- **422** a `never` refusal, with the rejection as data ([§21](Reference-21-never)).

## Related pages

- [The API layer](API-layer), how the endpoints on this page are built: the operations, the schema builder, the starter's beans, and one request end to end.
- [Binding API](Binding-API), the generated Kotlin the application's own code calls, and the shape of an act's input the JSON mirrors.
- [Store API](Store-API), the store the starter connects from the `DataSource`, and the guarantees it keeps.
- [Authorization](Authorization), the actor and the viewer the resolver supplies.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), the language `where` is written in.
- [§22 Open / unresolved](Reference-22-Open-unresolved), "The extension framework", the architecture these layers sit in.
