# The sibling-confluence audit (OQ16, the completeness leg)

**Status:** complete, 2026-08-20 — five check gaps found and fixed (F1–F5), one real order-dependence found in `examples/payments/payments.velle` and resolved by a spec decision (C1 — held for ratification, see §1), one test-generator bug fixed (T1), and four precision improvements landed so the widened checks don't reject the example corpus (P1–P4).

**The obligation.** `evaluation.md` ("Processing one commit", step 6) claims: *"Ordering within step 6 is never observable in a valid spec."* The claim has the shape of Newman's lemma from term rewriting — if every pair of adjacent steps commutes (local confluence: run the two in either order and reach the same state) and every run terminates, all orders reach the same result — with V1/V15 as the pairwise leg and V16 as the termination leg. This audit verifies that the coarse checks actually deliver the sentence: every way two unordered sibling firings can produce different outcomes must be either rejected by a check or proven harmless by the semantics.

Throughout this document, **sibling firings** (shortened to "siblings") means: two rule firings triggered by the same commit, where neither firing caused the other — so the runtime is free to run them in either order. "Sibling" never refers to a data relationship. (Defined in `GLOSSARY.md`.)

Method, per the OQ42 precedent (`audit-symmetric-evaluation.md`): enumerate the divergence channels from the step relation, then **pin every verdict with an executable probe**. The runtime gained a test-only ordering knob (`VelleSystem.firingOrder`, applied to step 6's firing list and to the after-commit queue's drain) so each probe runs the same scenario under two orders and diffs an id-insensitive fingerprint — captures and recorded failures included. A divergence on a spec the validator accepts is a soundness bug by definition. Probes live in `compiler/src/test/kotlin/velle/SiblingConfluenceAuditTest.kt`, one letter per probe; the roster below is the complete list, and every "probe X" mention in this document resolves here. The whole-corpus leg re-grows every example spec's world under a reversed order and demands identical fingerprints (`CommutationSweepTest`, "every example world is firing-order independent").

**The probe roster** (each probe is a standalone minimal spec; "diverges" means the two firing orders produce different final states, which the validator must therefore reject; "confluent" means both orders produce the same final state, which the validator must accept; V15 is the confluence check, cataloged in `checks.md`):

| Probe | Scenario | Result | Appears in |
|-------|----------|--------|------------|
| A | a rule body reads a field a sibling assigns | diverges; rejected (V15) | channel 2 |
| B | a rule body reads, through a derived property, whether a sibling-created instance exists | diverges; rejected (V15) | channel 3 |
| C | a rule body aggregates over instances a sibling creates, through an inferred inverse collection | diverges; rejected (V15) | channel 4 |
| D | a rule body reads an `on update` timestamp that a sibling's write advances | diverges; rejected (V15) | channel 5 |
| E | a captured value's expression reads a field a sibling assigns | diverges; rejected (V15) | channel 11 |
| F | two after-commit followers of one commit, one reading a field the other writes | diverges; rejected (V15) | channel 12 |
| G | a sibling's creation flips a watched refinement's predicate | diverges; rejected (V15) | channel 9 |
| H | a rule's *condition* (not its body) reads a field a sibling writes | confluent; accepted | channels 7, 13 |
| I | two siblings write two different fields — the independent baseline | confluent; accepted | baseline only, no channel row |
| J | finding C1 distilled: an in-transaction compensating release racing a pinned refusal (two tests: the pre-fix spelling diverges and is rejected with V15; the ratified `after commit` spelling is confluent and accepted) | both, see scenario | finding C1; §1 |

**The step relation** (pinned from `evaluation.md` and `Runtime.applyCommit`): a state is the store mid-transaction plus the pending firings; a step is one firing's commit. Two facts carry the whole analysis. **Subjects are pinned**: each commit's entrant/leaver sets are computed from one pre/post diff before any sibling runs, so a sibling's write cannot change who fires at this commit — the flip it causes fires rules at its own commit, which is causality, not a race. **Bodies read the evolving state**: a sibling's earlier write is visible to a later sibling's body, which is what makes every channel below real rather than a snapshot artifact.

## The channel matrix

| # | Channel | Verdict | Pinned by |
|---|---------|---------|-----------|
| 1 | Write-write, same field | **Covered** — V1, with the new instance-aliasing discharge: routes differing by one to-one self-hop write provably different instances when a spendable `never (S where hop == this)` forbids the hop pointing home | ValidatorTest promotion probes; the tier rules in `examples/loyalty/loyalty.velle` (`PromoteBuyer`) |
| 2 | Body reads a field a sibling assigns | **Covered** — V15 read-write leg | probe A |
| 3 | Body existence read over a shape a sibling creates (reachable through a derived-property hop; `exists` is predicate-position) | **F1 — was a gap, fixed**: the leg compared assigned fields against `ReadSummary.fields` only | probe B (diverges at runtime; now rejected) |
| 4 | Body aggregate over a sibling-created shape (inferred inverse collections) | **F1, fixed** — `collShapes` joined the leg's vocabulary | probe C |
| 5 | Body reads a timestamp any sibling write advances (`on update`) | **F1, fixed** — `collFields` joined, and a rule's effect set now includes the stamps its writes advance | probe D |
| 6 | Body summary flagged `opaque` | **F1, fixed** — opaque now means "reads anything", per the summary's own soundness contract | by code (no legal opaque-without-other-diagnostic spelling found; fail-closed regardless) |
| 7 | Condition-only read of a sibling's write | **Safe by pinning** — subjects fixed per commit; the flip fires at its own commit as causality. The old leg rejected this; the rejection was pure over-rejection and is removed (P1) | probe H (confluent at runtime, accepted); rule `ThankVip` in `examples/loyalty/loyalty.velle` |
| 8 | Transition interference, field-driven (two siblings write inputs of a watched refinement's predicate) | **Covered** — the V15 third leg | ValidatorTest interference probes |
| 9 | Transition interference, creation-driven (a sibling's creation flips the watched predicate) | **F2 — was a gap, fixed**: creations now flip through consults, at the consult's polarity | probe G |
| 10 | Same-direction sibling effects on one watched predicate (two prerequisites of a conjunction) | **Safe** — membership lands at whichever effect completes the predicate, in both orders; the polarity analysis (P2) proves the direction and skips the pair | rules `ReserveStock` × `RequestInitialCharge` in `examples/payments/payments.velle`; the certification section of `examples/loyalty/loyalty.velle`; ValidatorTest disjoint-siblings probe |
| 11 | Captured values (one sibling causes the entry, another writes what the captured expressions read) | **F3 — was a gap, fixed**: capture-carrying refinements are observers in their own right, and a capture-value leg checks the captured reads against sibling effects | probe E |
| 12 | Two after-commit followers of one commit (queue order is the sibling order that appended them) | **F4 — was a gap, fixed**: after/after pairs sharing a trigger get the pairwise check on the FULL summary — their conditions re-check at drain, so condition reads matter there | probe F |
| 13 | The firing set itself | **Safe by pinning** — the set of (rule, subject) firings at a commit is computed before any sibling runs | by the step relation; probe H |
| 14 | `never` timing | **Safe by design** — invariants check settled post-state once, at transaction end [S5] | by the step relation |
| 15 | Termination (Newman's second leg) | **Covered coarsely** — V16's DAG-or-disarmed-cycles plus the runtime depth backstop; the audit fixed its edge vocabulary (F5) but did NOT prove the disarm proof sufficient — that residue stays open in OQ16 | ValidatorTest V16 probes |

## Findings

**F1 (soundness — fixed).** The V15 read-write leg's vocabulary was assigned-fields versus `ReadSummary.fields`. Everything else a body can read that a sibling can change — existence of created instances, aggregates through inferred inverses, `on update` timestamps, and summaries whose own contract says "widen on opaque" — was invisible. Probes B–D diverge at runtime and validated clean before the fix. The leg now compares the full effect set (assignments plus the stamps they advance, plus creations) against the full read vocabulary, with the fresh-instance filter of P3 keeping it honest about which creations can actually move which consults.

**F2 (soundness — fixed).** The transition-interference leg counted only field writes as membership flips. A creation entering a watched predicate through a consult (probe G: `Hot = Account where f < 10 and exists Evidence for this`, one sibling creates the Evidence while another writes `f` past the bound) diverged and validated clean. Creations now flip through consults at the consult's polarity, and creating the watched refinement's own base shape is entry unless the predicate provably excludes fresh instances.

**F3 (soundness — fixed).** A captured value is persisted observation: `captured snap = f` evaluates at the entry commit, so a sibling assigning `f` while another causes the entry makes the stored value order-dependent (probe E) — with no rule watching anything. Capture-carrying refinements are now observers by themselves, and a dedicated leg checks the captured expressions' reads against sibling effects.

**F4 (soundness — fixed).** The after-commit queue drains in the order siblings appended to it — an order that was itself a step-6 choice — and each entry's condition re-checks at drain. Two after-commit followers of one commit where one reads what the other writes diverged (probe F) and validated clean; `evaluation.md` [S2]'s invisibility claim rested entirely on each entry's own guard, which guards against re-firing, not against each other. After/after pairs sharing a trigger commit now get the pairwise check on the full summary.

**F5 (soundness — fixed).** `affects()` — which builds V16's condition-graph edges, gates V3 reachability, and feeds the analysis of whether two rules can fire at the same commit (`canCoFire`) — matched created shapes against raw `existsShapes` entries, so a consult recorded under a refinement's name never matched the created base shape; `collFields` (timestamps) and `opaque` were missing too. All widened; the widening is what forced precision work P1–P4, since coarser edges alone would have rejected the example corpus.

**C1 (calibration — a real order-dependence in payments, resolved by a spec decision; ratification asked for in §1 below).** The widened checks flagged `ReleaseStockOnExhaustion` against the address-change handlers — all in the payments example spec, `examples/payments/payments.velle` (the release rule and `ExhaustedOrder`; `ApplicableAddressChange`, `ApplyAddressChange`, and `RecordAddressRefusal` in the address-change section) — and probe J (`SiblingConfluenceAuditTest`, "J - C1, an in-transaction release racing a pinned refusal diverges") reproduces the interference as a runnable divergence, so it is genuine, not a coarse over-rejection: when one commit both makes the release rule's condition true and makes the order ready to ship while an address-change request is still unhandled, the release and a `RecordAddressRefusal` firing are siblings of that commit. If the release fires first, the order drops out of ready-to-ship in the middle of the transaction, the request becomes applicable again, and `ApplyAddressChange` fires — while the already-pinned refusal still lands. The change is **applied and refused**. If the refusal fires first, the request is refused and nothing else happens. The handled-once act partition (see `GLOSSARY.md`) protects a request across transactions, but not across the commits inside one transaction. The resolution, the alternatives considered, and the full before/after spelling are in §1.

**T1 (test generator — fixed).** For after-commit guarded rules, `SpecGen` asserted the subject left the *named* trigger refinement whenever that refinement's definition carried any `where` — wrong when the disarm lives in the rule's inline guard (`ReleaseStockOnExhaustion` in `examples/payments/payments.velle` leaves its inline guard, not `ExhaustedOrder`). The membership assert now requires the body to disarm the named refinement's own conjuncts, the same criterion the commit-cadence path already used.

**Precision work (each required to keep the example corpus validating under the widened checks, each sound):**

- **P1 — condition-read pinning.** The old read-write leg used condition-plus-body reads; conditions are pinned (channel 7), so the on-commit leg now reads bodies only. This is the audit's one narrowing, and it is semantics-backed: probe H demonstrates confluence at runtime.
- **P2 — polarity.** Signed consult analysis: `exists` under negation, refinement absorption, count comparisons. A creation moves a predicate only in the polarity it is consulted at, so a rule gains a subject only when a commit can flip its condition in the firing direction (`canCoFire`, replacing bare shared-commit-kind coincidence in the sibling legs), and same-direction sibling pairs (channel 10) are skipped. Consult names stay raw (`SuccessfulCharge`, not its base) because fresh-enterability is per-refinement; membership tests on references and refinement composition contribute internals but never their own name — a fresh instance is unreferenced.
- **P3 — the fresh-instance argument.** A freshly created instance cannot satisfy a positive `exists ... for this` over a shape the same commit does not create — nothing can reference it yet (the V12/V18 family's argument aimed at consult analysis). This is what makes creating a pending `ChargeAttempt` provably unable to move any consult of `SuccessfulCharge`, and a fresh instance provably unable to LEAVE anything.
- **P4 — entrant/leaver exclusivity.** The episodes discharge: a rule entering a condition whose conjuncts include every conjunct of the condition another rule leaves cannot co-fire with it — one instance cannot enter the subset and leave the superset at one commit, and when every shared gain-kind is an own-column write to the shared base, the written instance is the only candidate on both sides. This is what licenses README §20's open/close episode pair.

## Decisions needing your ratification

### 1. The stock release now runs as its own transaction after the exhausting commit (finding C1)

All code below is from `examples/payments/payments.velle`. The cast: an order reserves stock at creation (`StockReservation`); three failed charge attempts exhaust the order, and exhaustion releases the reserved stock (`ReservationRelease`) as the compensating action. Separately, a request to change the shipping address is applied when the order is not ready to ship, and refused (recorded as a fact, `AddressChangeRefusal`) when it is:

```velle
shape ApplicableAddressChange = UnhandledAddressChange where not order is ReadyToShip
shape RefusedAddressChange    = UnhandledAddressChange where order is ReadyToShip

rule ApplyAddressChange when ApplicableAddressChange {
    order.shippingAddress = newAddress
    AddressChangeApplication from { change: this, appliedOn: now }
}

rule RecordAddressRefusal when RefusedAddressChange {
    AddressChangeRefusal from { change: this, reason: "order is ready to ship", refusedOn: now }
}
```

The release rule as it was — firing inside the same transaction as the commit that exhausted the order:

```velle
shape ExhaustedOrder = Order where count(FailedCharge where order == this) >= 3

rule ReleaseStockOnExhaustion
    when (ExhaustedOrder where
          exists StockReservation for this and
          not exists (ReservationRelease where reservation.order == this)) {
    ReservationRelease from { reservation: (StockReservation for this), releasedOn: now }
}
```

The problem (finding C1, reproduced by probe J in `SiblingConfluenceAuditTest`): releasing the reservation flips the order out of `ReadyToShip`. When the release and an address-change refusal are triggered by the same commit, running the release first re-opens `ApplicableAddressChange` mid-transaction, so the change is applied — and then the refusal, already triggered, lands anyway. One order of firing applies and refuses the same request; the other order only refuses it. That is exactly the order-dependence the language promises cannot happen, and the widened checks now reject this spelling (V15).

The decision taken — the release now follows the exhausting commit as its own transaction:

```velle
rule ReleaseStockOnExhaustion
    when (ExhaustedOrder where
          exists StockReservation for this and
          not exists (ReservationRelease where reservation.order == this))
    after commit, Nightly {
    ReservationRelease from { reservation: (StockReservation for this), releasedOn: now }
}
```

The release still happens at the moment the transaction boundary completes (with the nightly schedule as the healing backstop), but by then the refusal is already on record, so the address change can never re-enter `ApplicableAddressChange` — the interference becomes structurally impossible rather than order-dependent. Probe J's companion test ("J - C1 resolved") shows this spelling is firing-order independent and validates clean.

Alternatives considered and not taken: releasing only on a schedule tick (weakens the compensation story — stock stays held until the next tick); declaring `never` over the applied-and-refused double outcome (turns the bad firing order into a transaction rollback — the outcome is still order-dependent, just loud instead of silent). The decision is also recorded in the comment above the rule in `payments.velle`.

## The Newman argument, honestly stated

What the checks now deliver: for a spec that validates, every unordered pair of same-commit firings either provably cannot co-fire (`provablyDisjoint`, `canCoFire`, entrant/leaver exclusivity), or has no read-write, transition, or capture channel between its effects (channels 1–12); subjects are pinned per commit (channels 7, 13); after-commit followers are pairwise checked (channel 12); and every run terminates by V16 plus the depth backstop. Local commutation of every adjacent pair plus termination gives global confluence by Newman's lemma — the same final state and produced facts under every step-6 order.

What is argued but not proven: the induction from pairwise-sibling commutation to full-cascade commutation (a sibling's whole cascade versus another's) leans on the recursion structure — each cascade's internal ordering is causality, and cross-cascade interaction is again sibling pairs at some commit — and on V16's termination, whose disarm-proof sufficiency remains unexamined (OQ16's standing quiescence bullet). The empirical net under both assumptions is the order-permutation sweep, which runs identity-versus-reversed over every example world on every build; reversal covers all two-sibling orders, and larger firing sets are exercised only pairwise-adjacently — N-permutation sampling is recorded residue.

## What this audit does and does not establish

It establishes that every enumerated divergence channel is rejected, discharged, or safe-by-semantics, each verdict pinned by a probe that demonstrates the channel's reality at runtime where it is real; that the example corpus validates under the widened checks with all discharges earning their keep on real constructs; and that the one genuine order-dependence it surfaced (C1) was in the spec, not the checker — the fail-closed design working as claimed. It does not prove the cascade induction or disarm sufficiency (OQ16 residue), it samples orders by reversal rather than exhaustively, and its polarity/fresh-instance analyses are deliberately coarse (sums are unsigned, `as`-aliased consults fall back to unattributed fills) — each a place calibration can sharpen without touching the model.
