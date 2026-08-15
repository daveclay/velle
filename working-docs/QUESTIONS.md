# Open questions — the index

One line per question here; the discussion lives in `questions/OQ<n>-*.md`, where each file's header states the question in plain terms before the dense part begins. OQ tags are stable — numbers are never renumbered or reused, so references like `OQ16` stay valid forever, and gaps in the sequence are questions already settled (table at the bottom).

**When a question gets a tag:** any open design question with discussion worth keeping, or referenced from more than one doc, gets a tag and a file *at birth* — wherever it surfaced (investigation residue, a break-doc gap, a TODO parenthetical). A question that fits whole in one TODO line stays a TODO line.

**Lifecycle:** opened (tag + file) → discussion accumulates in the file → settled → the result promotes to the README or normative docs, the file is deleted, and the entry moves to the settled table with a pointer to where it retired.

The current milestone is calibrating v0 against realistic specs. Nothing here blocks the build; OQ15–16 are precisely the questions v0 exists to answer empirically.

## Open

- **[OQ15](questions/OQ15-ordered-folds.md)** — ordered folds: what makes an order-dependent fold at a tick safe? · calibration; recurrence spelling exists, certificate whitelist grows
- **[OQ16](questions/OQ16-order-independence.md)** — can the compiler prove sibling firing order never matters? · calibration; coarse V15/V16 shipped, fail-closed
- **[OQ17](questions/OQ17-rejection-scope.md)** — rejection scope: what unwinds, what is the caller told, can refusal be partial? · minimal v0 answer settled
- **[OQ26](questions/OQ26-correlation-keys.md)** — correlation keys for transient acts · works by convention; ergonomics undesigned
- **[OQ27](questions/OQ27-erasure.md)** — erasure and retention (right-to-be-forgotten) · language-side resolved via OQ37-R10 (`? initially required` + ordinary `= none` rules); residue: compilation's unretrievability guarantee
- **[OQ28](questions/OQ28-bootstrap-backfill.md)** — bootstrap/backfill: a new rule against pre-existing state · not designed
- **[OQ29](questions/OQ29-sum-types.md)** — sum types / union shapes · workaround exists (two optionals + xor `never`)
- **[OQ30](questions/OQ30-many-fields.md)** — author-named `many` fields: the commit story · decide syntax vs official restriction
- **[OQ31](questions/OQ31-committer-suppliable-fields.md)** — committer-suppliable fields (commit-function signatures) · `expose`-construct detail
- **[OQ36](questions/OQ36-universal-transaction.md)** — the universal-transaction contract, stated precisely · proofs rest on it
- **[OQ37](questions/OQ37-delete.md)** — delete as a described mutation: statement, existence-dependency check, gating, referential completeness · investigation open (`investigate-delete.md`)

## Deferred — post-v0 re-derivations (README §22's list)

- **[OQ32](questions/OQ32-requires.md)** — `requires`, re-derived
- **[OQ33](questions/OQ33-visible-to.md)** — `visible to` field visibility, re-derived
- **[OQ34](questions/OQ34-schedule-definition.md)** — schedule definition (carries event-anchored timeouts, data-derived durations)
- **[OQ35](questions/OQ35-mixins.md)** — cross-shape structural mixins (§22 item missing; restore it)

## Settled

| tag | retired to |
|---|---|
| OQ1–4, 6, 8–13, 18 | settled before this index existed; results live in the README |
| OQ5 — external input | README §22 "External input (`expose`)" |
| OQ7 — exit reads | README §13; two residual threads became §22 items (latency vocabulary; `on commit of` narrowing) |
| OQ14 — guard adoption | resolved by author judgment: the canonical guard form (README §18) stands as what fold diagnostics (§19) demand — no sugar added |
| OQ19 — boundary/apparatus legibility | became a calibration rider on the spec-writing item (TODO.md) |
| OQ20 — commit-refusal | not a primitive: refusal is compiled boundary code from `never` (README §21); the who-may-commit residue retired to engineer wrapper code (README §22; `investigate_runtime.md` §1) |
| OQ21 — construct set · OQ24 — harness boundary | README §22's scope statement |
| OQ22 — grammar | `grammar.md` (normative) |
| OQ23 — builtin surface | README §5, §10 |
| OQ25 — operational semantics & check catalog | `evaluation.md`, `checks.md` (normative) |
