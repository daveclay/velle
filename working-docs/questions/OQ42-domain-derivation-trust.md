# OQ42 — Trusting the domain derivation: the soundness audit and the calibration campaign

**Status:** open
**In plain terms:** OQ40 settled with a working serialization-domain derivation: the compiler computes, for every act, the queue keys its work must wait its turn on. The derivation's design rule is fail-closed — every read and write either gets a key or honestly widens to "everything touching this shape" — and it reproduces every worked example plus the billing spec. But three places currently rest on argument rather than audited structure, and the ruling that made the width warning an advisory was explicitly provisional. This question is where the derivation earns full trust: verify the arguments, sweep it against realistic specs at scale, and re-check the advisory dial. It is the concurrency-facing member of the same calibration campaign as OQ15/OQ16.
**Opened by:** OQ40 settlement, 2026-08-18 (the residuals named in its settlement note)

---

## The four items

1. **The symmetric-evaluation argument, audited.** When an envelope touches shape T and a watcher over a different base B consults T, `Domains.kt` enumerates the affected subjects through recognized correlations (reverse: an inferred inverse, `for this`, `f == this`; forward: B's own to-one field into T) and falls to Unknown — widen everything — when none is recognized. Completeness of that enumeration is argued, not proven: the claim is that any affected subject the enumeration misses lives in some other envelope that evaluates the same watcher and records the mirrored access, so the conflicting pair still intersects (helped by writes keying the written row's correlatable references). The audit is a per-construct case analysis of that claim, to the same standard as the walker's own "every step keys or widens" — either it holds everywhere or the gap gets a widening.

2. **Body-side correlations in `correlatable`.** A write to a row also keys the row's to-one references *when readers can correlate on them* — but `correlatable` today collects correlation routes from watcher *conditions* only. A rule **body** that reads `exists T for x` is a correlated reader the collection misses, so a write to such a T row could fail to key `x`. Extend the collection to body expressions (and derived-property expressions reached from bodies).

3. **The property-style commutation sweep.** `CommutationTest` is the falsification harness — derivation-disjoint envelope pairs must produce identical final state in either order against the reference evaluator — but its pairs are hand-picked. Grow it into a generated sweep: enumerate act pairs per example spec, evaluate their derived keys on concrete inputs, and check every pair the derivation calls disjoint. A counterexample is a soundness bug; the sweep is the derivation's regression net while items 1–2 land.

4. **The advisory dial, re-checked.** A5 (an uncorrelated read widens the domain; the compiler warns and `tolerates contention` answers) is an advisory by ruling (2026-08-18), explicitly provisional: the comprehension framing that produced the obligation leans *required*, and ceremony-avoidance won the default only because a wide domain's failure is throughput, not a wrong value. Calibration against realistic specs decides whether the dial stays — the same campaign as OQ15/OQ16's, watching for the author who ships an unintentionally single-threaded system that a required error would have caught.

## What is *not* here

The design is settled and does not reopen: derived-only queue keys (no author declaration), model facts as the narrowing move, `tolerates contention` as the width acceptance, the contention map and commit-function contract as the surfaces, the cadence discharge, and cross-store delivery staying the engineer's mechanism choice (`evaluation.md` U3 and its exclusions). Anything found by items 1–3 is a bug against that design, not a redesign.
