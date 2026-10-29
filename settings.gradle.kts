rootProject.name = "velle"

// The architecture's separation (Reference §22, "The extension framework"):
// :compiler                   — the Velle language + compiler/validator + runtime engine
// :examples:<system>:output   — each example spec's transpiled, developer-owned output
// :stores:sql                 — a generic, model-driven store over any SQL database (the
//                               framework role): the mapping, the reads, the commit
// :stores:sqlite              — that store over SQLite (its dialect), used by every
//                               example's tests to run over real storage
// :stores:postgres            — the same store over PostgreSQL; its tests need Docker
// :extensions:<lib>           — an extension type library (Reference §5): Alice's `Money`
// :extensions:<lib>-sql       — a store binding library for it: Bob's SQL binding for `Money`
// :apis:json                  — the API layer: a system's operations in wire terms (JSON-shaped
//                               values), model-driven, naming no transport
// :apis:graphql               — a GraphQL schema built from the model, over that layer
// :apis:spring                — the Spring Boot starter: REST and GraphQL endpoints, the store
//                               from the application's DataSource, scheduled ticks, the console
// :examples:<system>:app      — each example system as a Spring Boot application over PostgreSQL
include(
    "compiler",
    "apis:json",
    "apis:graphql",
    "apis:spring",
    "examples:billing:app",
    "examples:membership:app",
    "examples:payments:app",
    "examples:partition-drift:app",
    "examples:enrollment:app",
    "examples:loyalty:app",
    "examples:orders:app",
    "examples:gallery:app",
    "examples:moderation:app",
    "examples:peptides:app",
    "examples:shipping:app",
    "examples:pharmacy:app",
    "examples:vendors:app",
    "examples:batches:app",
    "examples:platelayout:app",
    "examples:bookings:app",
    "stores:sql",
    "stores:sqlite",
    "stores:postgres",
    "extensions:finance",
    "extensions:finance-sql",
    "examples:billing:output",
    "examples:membership:output",
    "examples:payments:output",
    "examples:partition-drift:output",
    "examples:enrollment:output",
    "examples:loyalty:output",
    "examples:orders:output",
    "examples:gallery:output",
    "examples:moderation:output",
    "examples:peptides:output",
    "examples:shipping:output",
    "examples:pharmacy:output",
    "examples:vendors:output",
    "examples:batches:output",
    "examples:platelayout:output",
    "examples:bookings:output",
)
