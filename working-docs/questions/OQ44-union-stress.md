# OQ44 — Stress-testing cross-base unions

**Status:** open (2026-09-13) — the construct is built and exercised by one cooperative example; nothing adversarial has been run against it
**In plain terms:** where does a union of unrelated shapes break the checks that assume one base shape, the runtime, the generated output, or the diagrams? Each case below is a spec to write and a verdict to record: the check holds, it holds at a cost, or it has a gap.
**See:** README §6 (a static union as a relationship target — the polymorphic association) · README §9 (`or` across unrelated bases; the unchanged property rule; what a union body and a union rule may do) · README §10 (narrowing by `is <Operand>`) · `checks.md` F2, F3, V1, V3, V11, V13, V17, A2 · `examples/gallery/gallery.velle` (the cooperative spec) · `compiler/src/test/kotlin/velle/UnionTest.kt`

---

## 1. Why this needs a pass of its own

The implementation resolved a refinement to a *set* of base shapes and taught the load-bearing paths to iterate it: member lookup, inverse inference, membership evaluation, watchers, reachability, co-firing, one-writer disjointness, the generated views. Every other check that keys on a single base was left as it was, and where it reads `baseOf` it now gets `null` for a cross-base refinement and skips — fail-open, against the language's fail-closed stance. Narrowing covers one shape of predicate. The generated surface assumes a cross-base refinement chains to a named static union. None of that has been pushed.

## 2. Gaps known by inspection

Found while implementing; each wants a spec that demonstrates it and a ruling.

1. **A cross-base refinement with no static union behind it.** `shape NeedsAttention = OverdueTicket or Invoice` composes refinements of different bases. It is a legal rule subject, but no view is generated for it (there is no base and no static union to name the view after), and the generated per-rule spec names an `UnknownView` — the generated test does not compile. Rule: give such a composite a view of its own (shared members only), or refuse it as a rule subject and demand a named static union with a `where`.
2. **A union-typed edge set.** `tags: many Content`. Inverse inference marks the inverse as many-to-many on every operand, the runtime's m2m inverse scan reads the owner's edge set, and the boundary converts each handle by acceptance — all by construction, none exercised.
3. **A closure over a union-typed inverse.** `expose Post with comments` where `Comment.target: one Content`: V22 must resolve the inverse to the part shape and the back-reference, and the closure commit must populate `target` with the pending `Post`. Nested: a union-typed field inside a part.
4. **Deletion against a union-typed referrer.** Deleting a `Post` that a required `Comment.target` points at: V24's three discharges (delete the referrer, `? initially required`, copy) over a union field; `when deleted Content` as a trigger; the absorbing read of a union field whose target is gone.
5. **Freezes, captures, and gates on a refinement over a union.** `shape Popular = Content where ... { frozen body }` — `frozen` names stored fields of "the base," and there is none; V5 today skips it silently. Same for a `captured` there (V6, V7 boundedness) and `undeletable` (V27). Rule per check: refuse, or reason per operand.
6. **A `never` over a union.** `never (Content where count(comments) > 100)`: the runtime enforces it at transaction end; V10's induction and spend-tracking skip it. Is it spendable? Should it be?
7. **A singular reference over a union subject.** `(Popular for x)`: V12's at-most-one proof skips cross-base refinements.
8. **The escalation chain as a union.** `source: one Alert or Escalation` with a derived walk to the root alert: V14 well-foundedness meets a union-typed self-reference. The needed certificate — a creation-fixed reference to a pre-existing instance is acyclic — is not in the whitelist; `never (Escalation where source == this)` rules out length-one cycles only. Rides OQ16's certificate calibration; record the diagnostic as it stands.
9. **Transition interference.** V15's third leg between a union rule and an operand rule both flipping one refinement's membership.

## 3. Narrowing forms not covered

Each is a compile-error today where it should license the read, or an unguarded read where it should not.

- `not (target is Post)` in a conjunction, and `if not (target is Post) then ...`.
- Nested conditionals: narrowing inside a branch already narrowed.
- Three-or-more-operand unions: the else-branch is the residual union, not one operand.
- Narrowing inside a collection filter (`comments where target is Post and target.body == ""`) and of an `as` alias.
- Narrowing on a collection filter's *source path*: `if trial is GreenhouseTrial then count(trial.greenhousePlots where lastObservedDay < this.dayMark) else ...` — the name check resolves the source against the union and finds no element, and the read-summary collector (`Model.collectExpr`) has no narrowing at all, so the branch goes opaque and V15 pairs the rule with everything. Found by `examples/peptides/peptides.velle` (`CollectionTask.plotsPending`, spelled around by comparing each operand's plots to the union-typed reference; `break-peptides.md` S14).
- Nested static unions (`shape Media = Content or Video`): `is Content` on a `Media`-typed value, complement narrowing through the nesting, a field typed `one Media`.
- Degenerate declarations: `Post or Post`, `Content or Post` — what `basesOf` and the generated views do.

## 4. Everything downstream of the model

- **Hydration and the store.** Exercised 2026-09-14 (`examples/gallery/output/src/test/kotlin/velle/GalleryOverSqliteTest.kt`: the flows over SQLite through both the generic protocol and the generated `GalleryStore`, plus a cross-process round trip). Verdict: the protocol holds — `Ref.Persisted` carrying the concrete shape is exactly what the runtime needs — at a cost the store pays: a bare key cannot name the row behind a union-typed column (keys are unique per table), so the store keeps the concrete shape beside it, the join read matches on both, and the pre-filter's correlation through a union-typed back-reference binds the subject's shape (`stores/sqlite/`, `SqliteStore`; `investigate_runtime.md` §11). A union-typed edge set (case 2 above) rides the same discriminator in the junction table, still unexercised by any spec.
- **Contention (A5).** A narrowed read is attributed to every operand that declares the name — an over-approximation; and a `never` reading through a union-typed field widens to system-wide, which may be the existing treatment of `never` reads rather than anything union-specific. Decide whether either width is real.
- **Diagrams.** `--diagrams` on the gallery spec: the class diagram has no union notation, the state-flow generator groups `Popular` onto an axis of *one* base, the rule graph's cause edges through a union-typed field.
- **`for` beside an operand-typed field.** `Comment for post` where `Comment` has both `target: one Content` and `post: one Post`: V13's two-field ambiguity, by acceptance.
- **Accidental refusals.** `expose Content` and `Content from { ... }` are refused today as "unknown shape" (F1) because a union is not in the shape table. Right verdict, wrong diagnostic: name the union and say why.

## 5. Method

One adversarial spec per numbered case, either as a `UnionTest` case (a diagnostic expected) or under `examples/union-stress/` (a runtime behavior expected), with the verdict recorded here in the HOLDS / TAX / GAP vocabulary of `break-b.md` (`GLOSSARY.md`). Gaps that need a ruling get one here; rulings promote to the README and `checks.md` as they settle, and the TODO items under "Cross-base unions" close as the checks tighten.
