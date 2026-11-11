# apis/

Rules for changes under this directory.

## Code changes follow ub-mode

Before writing or changing any code under `apis/`, load the `ub-mode`
skill installed at `.claude/skills/ub-mode/SKILL.md` (Dave, 2026-11-11)
and follow it: first make it work, then make it right, then follow
practice. Where ub-mode says to commit and push, this repo's rule holds
instead: commit and push only when Dave asks.

## The layers

`json` is the spec-agnostic API over a `VelleSystem` (`VelleApi`, `Wire`,
`Describe`), `graphql` the schema built from the model, `spring` the Spring
Boot starter. The caller's side is `wiki/Serving-a-system.md`, the
construction `wiki/API-layer.md`.

Decisions to keep consistent:

- No lock in the API layer. Every call runs in `api.scope {}`, which sets
  the clock and opens the system's per-thread session; the store serializes
  on queue keys.
- The wall clock is set on the system before each call.
- Reads are 401 when the spec declares authorization and no actor resolved;
  a spec without authorization serves the engineer's view.
- `where` is a Velle predicate string on every transport; `orderBy` is
  required; paging is by cursor.
- A refusal is data: REST 422 with the rejection. Malformed input is 400,
  an unknown record 404.
- The REST and GraphQL tick exists for development only; never an
  architectural driver.

Spring Boot 4 facts that cost time: `DataSourceAutoConfiguration` lives in
`org.springframework.boot.jdbc.autoconfigure`, `GraphQlAutoConfiguration` in
`org.springframework.boot.graphql.autoconfigure`, `AutoConfigureMockMvc` in
`org.springframework.boot.webmvc.test.autoconfigure`; Jackson 3 is
`tools.jackson.databind.ObjectMapper`; Spring 7 needs Kotlin
`javaParameters.set(true)`; Testcontainers 2 artifacts are
`testcontainers-postgresql` and `testcontainers-junit-jupiter`; a
class-level `@ConditionalOnBean` with two classes is AND, use
`AnyNestedCondition`; two `@SpringBootApplication` test apps in one module
scan each other, use `@SpringBootConfiguration @EnableAutoConfiguration`;
the starter keeps `spring-boot-jdbc` compileOnly so an app without a
database runs in memory; a Kotlin factory class instantiated by Spring needs
`kotlin("reflect")`.
