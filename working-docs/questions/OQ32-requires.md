# OQ32 — `requires` (post-v0 re-derivation)

**Status:** deferred — post-v0; re-derive rather than restore from old notes
**In plain terms:** re-derive the rule modifier that demands a condition hold (as opposed to `where`, which filters), and sync it into the README.

---

Cut from the spec by the v0 scope statement (README §22; `grammar.md` names the same list). Re-derive the rule-modifier keyword, distinct from `where`. Its atomicity mechanism (lock, transaction, optimistic retry) stays a compiling concern, left to whatever fits the target.
