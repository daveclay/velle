# OQ14 — Diagnostic-led guard adoption

**Status:** open — calibration; answered by using v0, not before it
**In plain terms:** when the compiler demands the author write a run-once guard, is the required form pleasant enough that the demand reads as teaching rather than hostility?
**v0 stance:** the fold diagnostics ship writing the canonical form and authors' reactions are the data.
**See:** README §19 · TODO.md's spec-writing item (the empirical answer)

---

Fold enforcement (README §19) means the compiler will be *proposing* guards to authors mid-error. With guard sugar dropped, the fix-it suggestion writes the canonical form itself, so the question is now exactly this: is the canonical form pleasant enough to be what a diagnostic asks an author to write? Ergonomics and enforcement aren't separable — a required guard that's miserable to write makes enforcement hostile; one that reads as the business rule makes it a teaching moment.
