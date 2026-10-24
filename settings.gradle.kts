rootProject.name = "velle"

// The architecture's separation (Reference §22, "The extension framework"):
// :compiler                   — the Velle language + compiler/validator + runtime engine
// :examples:<system>:output   — each example spec's transpiled, developer-owned output
// :stores:sqlite              — a generic, model-driven store backend (the framework role),
//                               used by every example's tests to run over real storage
// :extensions:<lib>           — an extension type library (Reference §5): Alice's `Money`
// :extensions:<lib>-sqlite    — a store binding library for it: Bob's SQLite binding for `Money`
include(
    "compiler",
    "stores:sqlite",
    "extensions:finance",
    "extensions:finance-sqlite",
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
