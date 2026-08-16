# OQ38 — Duplicates in a relationship: a multiplicity-bearing `many`?

**Status:** open — not designed; born from OQ30-R5 (2026-08-16)
**In plain terms:** `many` is a set, and a duplicate reference at the commit boundary is refused (OQ30-R5, now README §6). But some domains genuinely mean "two of the same thing" in a relationship. Does that deserve a lightweight spelling — some modifier on `many` giving bag semantics — or is the answer always the edge shape with a quantity field?
**See:** README §6 ("Committing and assigning collections": set semantics, the boundary refusal, the graduation point) · OQ30 settled — `QUESTIONS.md` settled table

---

OQ30-R5's boundary refusal is deliberately loud: a duplicate reference is either a caller bug or a multiplicity claim `many` cannot express. This OQ holds the second case — the caller *meant* it.

Two candidate answers:

- **The graduation point is the answer** (status quo). Multiplicity that matters is data on the edge — an order line with `quantity`, an enrollment fact — and the R5 refusal diagnostic points there. If this holds under real specs, this OQ dissolves into working-as-intended.
- **A modified `many`** — bag semantics as a declared property of the relationship (spelling open; some modifier on `many`). What it would owe before adoption:
  - **Ordering stance.** A bag is still unordered — only multiplicity is added. Anything more is a list, which the ordering-comes-from-data law forbids (README §5: ordering from declared datums, never position).
  - **The boundary contract flips**: duplicates accepted, meaningfully — the R5 refusal becomes conditional on the field's declaration.
  - **`+`/`-` change meaning** (OQ30-R2): `+` of an existing member now increments; does `-` remove one occurrence or all? Bag subtraction needs a stated answer.
  - **Fan-out loses free idempotence** (OQ30-R3/R4): writing the same member twice is two writes of the same value — still convergent, but the coarse conflict analysis should be re-checked against bags.
  - **The inferred inverse of a bag edge**: does the target side see multiplicity, and what does `count(...)` count — members or occurrences?
  - **The standing suspicion**: every real multiplicity has a *reason* (a quantity, a role, a time), and a bare count is usually the degenerate edge shape — the graduation-point argument again, now aimed at the construct itself.

Lean: keep the refusal pointing at the edge-shape spelling, let v0 calibration run, and adopt a modifier only if real specs keep hitting the refusal with a genuinely data-free multiplicity.
