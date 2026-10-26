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
include(
    "compiler",
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
)
