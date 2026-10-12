# OQ33 — `visible to` (post-v0 re-derivation)

**Status:** deferred — post-v0
**In plain terms:** who may see a field — how a role is defined, and whether a field with no declared visibility is hidden by default.

---

Re-derive field-level visibility (`visible to Role, Role`), cut by the v0 scope statement (README §22). Carries its open sub-questions: how a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — with external-RBAC integration as the compiling-side alternative; and whether an undeclared-visibility field is fail-closed by default (a language decision), plus enforcing that (compiling).

Note (2026-10-12, from `investigate-search.md`, [OQ51](OQ51-search.md)): a search spells visibility as a parameter of the read (`shopper: one Shopper` on a parameterized refinement, with `not ageRestricted or shopper.verifiedAdult` as a conjunct), which is the viewer this question asks how to define. The two should be designed together: a per-field `visible to` and a per-read viewer parameter may be one construct seen from the field and from the read.
