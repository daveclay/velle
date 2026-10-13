# OQ33 — `visible to` (post-v0 re-derivation)

**Status:** deferred — post-v0
**In plain terms:** who may see a field — how a role is defined, and whether a field with no declared visibility is hidden by default.

---

Re-derive field-level visibility (`visible to Role, Role`), cut by the v0 scope statement (README §22). Carries its open sub-questions: how a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — with external-RBAC integration as the compiling-side alternative; and whether an undeclared-visibility field is fail-closed by default (a language decision), plus enforcing that (compiling).

Note (2026-10-12, OQ51, settled): a search spells visibility as a parameter of the read (`shopper: one Shopper` on a parameterized refinement, with `not ageRestricted or shopper.verifiedAdult` as a conjunct), which is the viewer this question asks how to define. The two should be designed together: a per-field `visible to` and a per-read viewer parameter may be one construct seen from the field and from the read. The read side adds the record-level half: under a read surface the caller composes, a gate written into one named refinement is bypassed by composing against the base shape, so visibility must be declared per shape and per field against a viewer that is a parameter of the whole read, and enforced by the surface on every composed read.
