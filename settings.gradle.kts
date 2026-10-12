rootProject.name = "velle"

// The architecture's separation (README §22, "The extension framework"):
// :compiler                   — the Velle language + compiler/validator + runtime engine
// :examples:<system>:output   — each example spec's transpiled, developer-owned output
// :stores:sqlite              — a generic, model-driven store backend (the framework role),
//                               used by every example's tests to run over real storage
include(
    "compiler",
    "stores:sqlite",
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
)
