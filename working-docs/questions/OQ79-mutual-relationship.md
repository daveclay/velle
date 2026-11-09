# OQ79 — A relationship that goes both ways

**Status:** opened 2026-11-09 from OQ76's D5, at Dave's direction, once OQ76 settled. Not designed.
**In plain terms:** friendship is mutual. `friends: many Profile` on `Profile` gives the author one stored edge set and an inferred inverse, "who lists me", which is not the same set: Alice listing Bob does not make Bob list Alice. A business that means a mutual relationship has to write both edges or a rule that mirrors each one. Whether Velle gets a declaration for a relationship that is its own inverse, and what the inferred inverse becomes under it, is this question. It changes what `friends` means, not how a walk over it ends (Reference §7, "Walks").
**See:** Reference §6 (relationships; the inferred inverse; V19, both sides declared) · Reference §7 "Walks" (a walk follows whatever the relationship is) · `examples/loyalty/loyalty.velle` (`referrer`, a one-way relationship, for contrast)

---

## 1. What is allowed today

A `many` to the own shape is one stored edge set, owned by the record that lists. The inverse is inferred under the pluralized shape name and is a different set. Nothing says the two sets are one.

## 2. What to decide

### D1. Whether a mutual relationship is a declaration

A spelling on the field, with the inferred inverse replaced by the field itself. Alternatives: no declaration, and the mirror written as a rule per spec.

### D2. What a commit of one edge means

Whether committing Alice's `friends` with Bob writes Bob's side too, and what refuses or records a one-sided commit.
