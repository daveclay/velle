# OQ40 — Serialization domains: what must serialize, what may run in parallel, and who says

**Status:** open
**In plain terms:** OQ36's U3 demands transactions behave as if they ran one at a time — but a backend that serializes *everything* will never scale. Part of engineering is knowing when to acquire a lock and, specifically, what value to lock on: two deposits to two accounts should run in parallel; two deposits to the *same* account must be serial. Can Velle derive that lock key — the serialization domain — at its own layer of abstraction, and does the author ever need syntax for it?
**Opened by:** OQ36 draft review, 2026-08-16 (the U3 refinement thread)

---

## The refinement of U3

Serial-*equivalence* is what the proofs spend; the total order is the observational fiction, never an implementation demand. Two envelopes with disjoint footprints commute — every interleaving is equivalent to either serial order, so running them in parallel is invisible to any observer the language defines. U3's honest form is therefore two clauses, not one: **conflicting envelopes serialize** (with real time respected between them — last-in-wins was only ever meaningful for conflicting pairs), and **commuting envelopes are unconstrained**. What "conflicting" means precisely is this question's first deliverable, and it must count *predicate* reads, not just row reads: the guard read `not exists DepositApplication for this` conflicts with the witness create it watches for — the classic phantom — and that intersection is exactly what forces same-account deposits serial. Miss it and snapshot-isolated concurrent envelopes both see the guard armed: the double deposit U3 names.

## Velle already knows the footprint

Nothing new needs collecting — the inputs are the analyses already built. Per act: the reachable read set (`investigate_runtime.md` §2 — the transitive closure over the rule graph from the trigger shape), the static write set (README §12 — every assignment is a literal path), and the predicate read summaries relevance gating runs on (`investigate_runtime.md` §6). Shape-level conflict is the trigger-coincidence machinery re-aimed at *pairs of envelopes* instead of pairs of rules. The step past shape level is the one that matters here: footprints are usually **keyed by paths from the act** — everything a `Deposit` envelope reads or writes roots through `this.account` — and when every path in the footprint factors through one instance, that instance *is* the domain. The lock key, derived per exposed act, at the spec's abstraction: "deposits serialize per account" is a business sentence, and it falls out of the data graph the spec already draws.

## What the compiler could emit

The natural surface is the one §10 of `investigate_runtime.md` already built: per generated commit function, the typed store layer states "serializes on: `account`" — kdoc plus, plausibly, a typed handle the engineer's lock/`SELECT FOR UPDATE`/partition-key code takes as a parameter. The engineer reads the key off the contract instead of re-deriving it by hand from the rule graph — and a spec edit that widens a footprint *changes the emitted domain*, surfacing as a visible contract change rather than a silently stale lock choice. Fail-closed at the coarse end: a footprint containing a scan-shaped read (a `never` over all of a shape, an aggregate over an unkeyed collection, anything relevance gating marks `opaque`) widens the domain — to the shape, or to global — with a diagnostic naming the widening read, the same coarse-then-calibrate arc as V1's collection-path rule and §6's reverse-path narrowing. Established `never`s narrow it the way they already narrow one-writer: an invariant is what proves two paths can't reach the same instance.

## The syntax question

Three candidate positions, undecided:

- **Derived only, never declared.** The domain is a consequence of the footprint, like the trigger set (README §11: the author declares *what*, the compiler computes *which*). An author declaration could only be unsound (narrower than the footprint — under-locking) or redundant (wider — which is the engineer's freedom below the contract anyway, no spec vocabulary needed). Under this position OQ40 adds zero syntax; the whole deliverable is derivation plus the emitted contract.
- **Author-stated facts that *feed* the derivation.** When the derived domain is coarser than the business knows it needs to be, the fix is not a lock annotation but a model statement the prover can spend — a `never` establishing disjointness, or the state-partition declaration (README §22) — pressure identical to relevance gating's. Syntax pressure lands on constructs already queued, not on new lock vocabulary.
- **A tolerance-family escape.** If real specs surface unavoidable global domains the author knowingly accepts (a genuinely global invariant), the honest spelling might be a signed acceptance in the `tolerates` family — "this act serializes globally, and the author knows" — rather than a silent wide default.

Current lean: the first position, with the second as its pressure valve — matching "compiling means validating" (the domain is a fact about the spec, so it is computed, not asserted) and the design-philosophy stance that structural derivation beats checked discipline. The third only earns a place if calibration against realistic specs finds domains that are wide, correct, and worth signing. That calibration is the same empirical campaign as OQ16's — this question is OQ16's cross-transaction sibling: V16 asks whether sibling firings *within* one envelope commute; OQ40 asks which *envelopes* commute with each other, over the same commutation machinery.

## Threads

- **Ticks and sweeps.** Each firing at a tick is already its own transaction (README §17) — a sweep's firings have per-member domains and parallelize across records for free, which is most of what "batch throughput" needs. The tick's member *scan* is a snapshot read over the swept shape: whether it conflicts with concurrent act envelopes on that shape (delaying the scan or the acts) or reads a settled snapshot and lets stragglers heal at the next tick is exactly the guard-self-healing design already present — likely the latter, worth stating.
- **Impact on OQ36.** U3 restates as: serial-equivalence with real time respected between conflicting envelopes; disjoint-domain envelopes explicitly licensed to run in parallel; the domain derivation and its emitted contract owned here. U3's snapshot-isolation warning becomes the phantom example above.
- **Cross-store domains.** A domain that spans resolvers (an envelope touching two engineer stores) is where "what value to lock on" stops being one `synchronized` — the contract can still *name* the domain; delivering serialization across stores stays the engineer's distributed-territory problem (OQ36's exclusion list, unchanged).
