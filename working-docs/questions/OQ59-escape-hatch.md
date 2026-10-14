# OQ59 — The escape hatch: open codegen

**Status:** deferred — post-v0; README §22 carries the item ("Escape hatch / override syntax"), and the demands below came from the project's earliest notes, kept here when those notes were retired (2026-10-14)
**In plain terms:** what happens when the product owner asks for the one thing the spec cannot say. Every framework's escape hatch fails the same way: as soon as one unsupported case arrives, the engineer abandons the framework, because the generated code only ever worked for the cases the framework foresaw. Velle's generated output must be open to customization without becoming a one-way dump.
**See:** README §22 "Escape hatch / override syntax", "The extension framework", "Compiled guardrails" · `compiler/src/main/kotlin/velle/StoreGen.kt` (the generated typed store with the engineer's overrides, the one escape hatch built so far)

---

The demands, as first written:

- **Codegen is not a blind, one-way dump.** Velle should interact with the engineer's customizations in a structured way: a way for an engineer to demarcate customizations and extensions that the transpiler can read, comprehend, and validate. If a customization is incompatible with the spec, the transpiler raises an error rather than silently overwriting or silently coexisting.
- **Regeneration is selective.** The engineer picks which generated files to override and which to regenerate; the escape hatch is not one-way. This needs a way to demarcate and introspect generated code modules.
- **An AI implementer is a first-class case.** Generated files can be modified by a person or by an AI given rules about implementing transpiled code (override within these files, structured file layouts).
- **Custom right-hand-side functions carry metadata.** If the validator proves properties of rule bodies (idempotence, the fold whitelist), a custom function must declare what the validator needs to know; this is the extension framework's registration contract (README §22).

What exists today, for calibration: the generated output is developer-owned and regenerated whole (README §22, "External input"); the typed store surface is the one place a hand-written override is a designed posture, the framework serving every question and the engineer overriding the hot one with tuned SQL (`StoreGen.kt`; `examples/billing/output/src/test/kotlin/velle/TypedStoreTest.kt`). The question is what the same posture looks like for the rest of the generated surface, and how an override survives the next `gradle generate`.
