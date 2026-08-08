rootProject.name = "velle"

// The architecture's separation (README §22, "The extension framework"):
// :compiler                   — the Velle language + compiler/validator + runtime engine
// :examples:<system>:output   — each example spec's transpiled, developer-owned output
include("compiler", "examples:billing:output", "examples:membership:output")
