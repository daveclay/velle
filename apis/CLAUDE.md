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
`Describe`), `graphql` the schema built from the model, `mcp` the MCP
server built from the model for AI chat clients, `spring` the Spring Boot
starter. The caller's side is `wiki/Guides/Serving-a-system.md`, the
construction `wiki/Guides/API-layer.md`.

Decisions to keep consistent:

- No lock in the API layer. Every call runs in `api.scope {}`, which sets
  the clock and opens the system's per-thread session; the store serializes
  on queue keys.
- The wall clock is set on the system before each call.
- Reads are 401 when the spec declares authorization and no actor resolved;
  a spec without authorization serves the engineer's view.
- `where` is a Velle predicate string on every transport; `orderBy` may be
  left off, and the records then come in the order of their keys, the same
  on every run; paging is by cursor.
- A refusal is data: REST 422 with the rejection. Malformed input is 400,
  an unknown record 404.
- The REST and GraphQL tick exists for development only; never an
  architectural driver. MCP offers no tick.
- A dry run (`VelleApi.check`) is offered on every transport alike: REST
  `?dryRun=true`, the GraphQL `dryRun` argument, the MCP `@dryRun` input.

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

MCP facts that cost time: the library is the official MCP Java SDK,
`io.modelcontextprotocol.sdk:mcp-core` with `mcp-json-jackson3` (2.2.0),
chosen 2026-11-25 for its servlet transport and Jackson 3 binding, with no
Spring AI. `mcp-spring-webmvc` stopped at 0.18, so the starter registers the
SDK's `HttpServletStatelessServerTransport` as a servlet itself.
`McpStatelessServerTransport` lives in `io.modelcontextprotocol.spec`, not
`server`. The SDK validates every tool's input schema when the server is
built, with the validator the Jackson 3 module ships. Velle names start
with a capital letter, so no act clashes with the tools `read`, `query`,
`count`.
