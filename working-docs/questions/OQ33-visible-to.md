# OQ33 — `visible to` (post-v0 re-derivation)

**Status:** deferred — post-v0
**In plain terms:** who may see a field — how a role is defined, and whether a field with no declared visibility is hidden by default.

---

Re-derive field-level visibility (`visible to Role, Role`), cut by the v0 scope statement (README §22). Carries its open sub-questions: how a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — with external-RBAC integration as the compiling-side alternative; and whether an undeclared-visibility field is fail-closed by default (a language decision), plus enforcing that (compiling).
