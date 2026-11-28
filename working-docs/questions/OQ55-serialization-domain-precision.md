# OQ55 — Serialization domains: the widths realistic specs produce

**Status:** open (2026-10-14) — the derivation is sound (OQ40, OQ42 settled; probes in `SymmetricEvaluationAuditTest`) and the width warning stays advisory (`patterns.md`, "A wide domain: the two authors"); this question is precision
**In plain terms:** on both realistic specs, nearly every rule that reads a series through the subject's inferred inverse widens to a whole-shape queue (forty-one A5 advisories on peptides, fifty-seven on pharmacy), in exactly the domains where the key is obvious: per experiment, per greenhouse, per trial, per prescription. A pipeline whose every reading serializes system-wide is not what the spec describes. The fix is a derivation that sees the key, not a change to the ruling.
**See:** `evaluation.md` U3 · `checks.md` A5 · `patterns.md` "A wide domain: the two authors" · `SymmetricEvaluationAuditTest`, `SiblingConfluenceAuditTest` · `compiler/src/main/kotlin/velle/Domains.kt`, `DomainKeys.kt` · `CommutationSweepTest`

---

## 1. The series read rooted at an inferred inverse (peptides S12, pharmacy P11)

`Validator.advisories` reports "consults X through 'assays' with no keyable owner" for a rule whose condition reads a series through the subject's inferred inverse: `assays`, `assayReadings`, `climateReadings`, `treatments`, `phytotoxicityReports`, `weatherObservations` on peptides; `verifications`, `fills`, `recalls`, `quarantines` on pharmacy. Every commit touching that shape joins one queue. **Wanted:** the derivation keys a series read rooted at the subject's inferred inverse on the subject.

## 2. A sibling join's second binding (pharmacy P11)

Fixed 2026-11-27. The derivation reads each source of a collection on the record the collection is written on (`walkCollection` in `compiler/src/main/kotlin/velle/Domains.kt`; `PathResolutionDisagreementTest`, "the serialization domain reads each source of a collection on the record the collection is written on, as the running system does").

## 3. The symmetric-evaluation audit's recorded precision opportunities (P2–P4, 2026-08-19)

- **P2.** A `this.`-rooted path inside a collection `where`. Does not reproduce on 2026-11-27: `PathResolutionDisagreementTest`, "the serialization domain reads a field of this inside a filter on the subject".
- **P3.** Refinement-name collection filters and `is Refinement` atoms on the condition side. Does not reproduce on 2026-11-27: `PathResolutionDisagreementTest`, "the serialization domain reads a filter that names a refinement on that refinement's own properties, as the validator does" and "the serialization domain follows an is test on a reference into the refinement it names".
- **P4.** Multi-hop reverse routes widen; composing recognized single hops (response → attempt → order) would key instead. This is payments' `ChargeResponse` width.

## 4. Deletion-caused drift

A delete keys the deleted row and re-uses creation-shaped relevance at shape granularity (advisory A5 only). **Wanted:** propagate deletion-caused drift precisely.

## 5. The sweep, and the ruling's own rider

- Commutation-sweep extensions (`CommutationSweepTest`; OQ42 item 3's noted residue): randomized value exploration for the value-boundary bugs the fixed small worlds miss, commit-versus-tick-firing pairs over `scheduledRuleDomains`, and triples.
- The advisory ruling (`checks.md` A5; `patterns.md`, "A wide domain: the two authors") is held deliberately revisitable: stress-test it against realistic specs once OQ16 resolves. Peptides and pharmacy are the first two specs to run it against.

## 6. Work items

- [ ] Teach the domain derivation to key a series read rooted at the subject's inferred inverse (S12).
- [ ] Compose recognized single hops of a multi-hop reverse route into a key (§3, P4).
- [ ] Teach the derivation to propagate deletion-caused drift precisely (today a delete re-uses creation-shaped relevance at shape granularity).
- [ ] Commutation-sweep extensions (`CommutationSweepTest`): randomized value exploration, commit-versus-tick-firing pairs over `scheduledRuleDomains`, triples.
- [ ] Stress-test the A5 advisory ruling against realistic specs once OQ16 resolves (`patterns.md`, "A wide domain: the two authors").
