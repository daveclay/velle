# Velle

Velle is a language for describing a system without saying how the system runs on a computer.

Velle captures the conversation between a product owner and an engineer. Reading a Velle spec describes the system, its conceptual models and its behaviors, without computer-science terms. The product owner and the engineer can read it together and agree on what the system does. From the spec, Velle generates a validated, executable runtime, a typed surface the engineer's own transport code calls, executable specifications, and the design artifacts a product owner uses: class, state-flow, cause-graph, and sequence diagrams.

Velle has no stack, functions, variables, scope, or closures. It has shapes, relationships, refinements, and rules, and it enforces business concepts at that layer. Everything below it, such as publishing to a message bus or calling a legacy endpoint, is left to existing languages and frameworks.

A spec runs as a web application by declaring its generated system as one Spring Boot bean: the starter connects it to the application's database, serves every act and shape over REST and GraphQL, runs its schedules, and serves a console ([Serving a system](https://github.com/daveclay/velle/wiki/Serving-a-system)). `examples/billing/app/` is the billing spec served that way over PostgreSQL.

```
shape Invoice {
    customer: one Customer
    due: Date
    total: decimal = sum(lineItems, amount)
    balance: decimal = total - sum(payments, amount)
}

shape OverdueInvoice = Invoice where balance > 0 and due < today

rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 7 days))
    on Weekly {
    Reminder from { invoice: this, sentOn: today }
}
```

Adapted from `examples/billing/billing.velle`.

## Documentation

The documentation lives in the [repository wiki](https://github.com/daveclay/velle/wiki), published from the `wiki/` folder of this repository on every push to `main`.

- **The Velle Language Reference**, one page per numbered section, cited everywhere as "Reference §N": from [§1 Principles](https://github.com/daveclay/velle/wiki/Reference-01-Principles) through [§22 Open / unresolved](https://github.com/daveclay/velle/wiki/Reference-22-Open-unresolved). Start at [§2 Philosophy](https://github.com/daveclay/velle/wiki/Reference-02-Philosophy).
- **The guides**: [Authorization](https://github.com/daveclay/velle/wiki/Authorization), the [Binding API](https://github.com/daveclay/velle/wiki/Binding-API), [extension types](https://github.com/daveclay/velle/wiki/Extension-types) and [functions](https://github.com/daveclay/velle/wiki/Extension-functions), the [Store API](https://github.com/daveclay/velle/wiki/Store-API), and [Serving a system](https://github.com/daveclay/velle/wiki/Serving-a-system).
- **The normative documents**: the [grammar](https://github.com/daveclay/velle/wiki/grammar), the [evaluation model](https://github.com/daveclay/velle/wiki/evaluation), the [validator's checks](https://github.com/daveclay/velle/wiki/checks), [patterns](https://github.com/daveclay/velle/wiki/patterns), [generated specifications](https://github.com/daveclay/velle/wiki/testgen), [diagrams](https://github.com/daveclay/velle/wiki/diagrams), [singular references](https://github.com/daveclay/velle/wiki/singular_references), and the [glossary](https://github.com/daveclay/velle/wiki/GLOSSARY).

Edit the documentation in `wiki/`, not on the wiki site: the publish workflow (`.github/workflows/wiki.yml`) replaces the wiki's content with the folder's on each push, so an edit made on the site is overwritten.

## Repository layout

- `compiler/` is the v0 implementation: lexer and parser, validator, runtime, and code generation, in Kotlin.
- `stores/sql/` is a model-driven store over any SQL database. `stores/sqlite/` is its SQLite dialect, which every example's tests run their flows through, and `stores/postgres/` its PostgreSQL dialect, which three of them also run through when Docker is available.
- `apis/json/` is the API layer: a running system's operations in JSON terms, built from the model, naming no transport. `apis/graphql/` builds a GraphQL schema from the model over it. `apis/spring/` is the Spring Boot starter: REST and GraphQL endpoints, the store connected from the application's `DataSource`, the actor resolved once per request, scheduled ticks, and the console page.
- `examples/<system>/` pairs each example spec (`<system>.velle`) with its generated, developer-owned `output/` module and the tests that drive it. `examples/billing/app/` is the billing system as a Spring Boot application over PostgreSQL.
- `editors/velle/` is the editor support for `.velle` files.
- `wiki/` is the documentation, published to the repository wiki.
- `working-docs/` holds the open design questions and work items (`QUESTIONS.md` and one file per question).

## Building

```
./gradlew test                                        # the whole suite
./gradlew :examples:billing:output:generate           # regenerate one example's surface and specs
./gradlew generate                                    # regenerate every example
./gradlew :examples:billing:app:bootRun               # the billing application over PostgreSQL (starts it in Docker); console at http://localhost:8090/velle
```

The PostgreSQL tests, including the application's, run when Docker is available and are skipped otherwise.
