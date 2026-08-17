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
- **[OQ31](questions/OQ31-committer-suppliable-fields.md)** — committer-suppliable fields (commit-function signatures) · `expose`-construct detail
- **[OQ37](questions/OQ37-delete.md)** — delete as a described mutation: statement, existence-dependency check, gating, referential completeness · investigation open (`investigate-delete.md`)
- **[OQ38](questions/OQ38-bag-relationships.md)** — duplicates in a relationship: a multiplicity-bearing `many`? · born from OQ30-R5; edge-shape-with-quantity may be the whole answer
- **[OQ39](questions/OQ39-inline-part-creation.md)** — inline part creation: multi-part acts through the generated commit function · born from OQ30-R7; the input-closure question
- **[OQ40](questions/OQ40-serialization-domains.md)** — serialization domains: what must serialize, what may parallelize, who says · born from OQ36-U3; the derived queue key; OQ16's cross-transaction sibling · lean: derived queue keys + contention map; wide domains discharge via correlation, cadence, or `tolerates contention` (error-vs-advisory dial open)
- **[OQ41](questions/OQ41-compilation-artifacts.md)** — the compilation-artifact family: class diagrams, state-flow, contention map, sequence diagrams · born from OQ40's reframe; worked deposit/lending-cap sequence diagrams; tests and diagrams are one derivation rendered twice

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
| OQ36 — the universal-transaction contract | `evaluation.md`, "The universal transaction" (U1–U5: snapshot, atomicity, serialization, permanence, no side doors — precise guarantee + in-practice prose per clause, the proof-spends table, the exclusions); pointers in README §11/§22; glossary updated. U3's serialization-domain refinement (the derived queue key) spun off as OQ40 |
| OQ30 — author-named `many` fields: the commit story | README §6 (ownership frame; "Committing and assigning collections"; "Renamed and derived collections"), `grammar.md` (propType, `empty`, setExpr, assignment note, operator line), `checks.md` (V19–V20; F2/F4/V1 extensions), `evaluation.md` (stored edge sets; boundary duplicate refusal). Residue rulings OQ30-R1–R6 promoted with it; R5 spun off OQ38 (bags), R7 spun off OQ39 (inline part creation) |
| OQ20 — commit-refusal | not a primitive: refusal is compiled boundary code from `never` (README §21); the who-may-commit residue retired to engineer wrapper code (README §22; `investigate_runtime.md` §1) |
| OQ21 — construct set · OQ24 — harness boundary | README §22's scope statement |
| OQ22 — grammar | `grammar.md` (normative) |
| OQ23 — builtin surface | README §5, §10 |
| OQ25 — operational semantics & check catalog | `evaluation.md`, `checks.md` (normative) |
