# OQ33 — `visible to` (post-v0 re-derivation)

**Status:** deferred — post-v0
**In plain terms:** who may see a field — how a role is defined, and whether a field with no declared visibility is hidden by default.

---

Re-derive field-level visibility (`visible to Role, Role`), cut by the v0 scope statement (README §22). Carries its open sub-questions: how a `Role` (e.g. `PatientRole`) is defined as a predicate over an implicit `viewer` — with external-RBAC integration as the compiling-side alternative; and whether an undeclared-visibility field is fail-closed by default (a language decision), plus enforcing that (compiling).

Note (2026-10-12, OQ51, settled): a search spells visibility as a parameter of the read (`shopper: one Shopper` on a parameterized refinement, with `not ageRestricted or shopper.verifiedAdult` as a conjunct), which is the viewer this question asks how to define. The two should be designed together: a per-field `visible to` and a per-read viewer parameter may be one construct seen from the field and from the read. The read side adds the record-level half: under a read surface the caller composes, a gate written into one named refinement is bypassed by composing against the base shape, so visibility must be declared per shape and per field against a viewer that is a parameter of the whole read, and enforced by the surface on every composed read.

**From the project's earliest notes (retired 2026-10-14), the shape of the authorization question this re-derivation has to answer.** There is an authenticated user, and it may be absent (a scheduled tick; an act arriving from an automated system). Roles and permissions are both static and declarative (an admin may see every order) and dynamic and data-dependent (the user who placed the order; the user the order names as its recipient, which is a different relationship from the placer). The checks happen on reads as much as on acts: a user requests order 1234, and whether they may see it depends on their role and on the data. The read side of this question above is where that lands.
