rootProject.name = "velle"

// The architecture's separation (README §22, "The extension framework"):
// :compiler — the Velle language + compiler/validator + runtime engine
// :output   — the transpiled, developer-owned output (generated surface + its tests)
include("compiler", "output")
