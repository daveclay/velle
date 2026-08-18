# OQ42 — Trusting the domain derivation: the soundness audit and the calibration campaign

**Status:** open
**In plain terms:** The compiler now ships a working serialization-domain derivation (`evaluation.md` U3, settled 2026-08-18): for every act, it computes the queue keys the act's work must wait its turn on. The derivation's design rule is fail-closed — every read and write either gets a key or honestly widens to "everything touching this shape" — and it reproduces every worked example plus the billing spec. But parts of it rested on argument rather than audited structure, and the ruling that made the width warning an advisory was explicitly provisional. This question is where the derivation earns full trust: verify the arguments, sweep it against realistic specs at scale, and re-check the advisory dial. Item 2 — body-side correlation routes — is done as of 2026-08-18; items 1, 3, and 4 remain. It is the concurrency-facing member of the same calibration campaign as OQ15/OQ16.
**Opened by:** the serialization-domain settlement, 2026-08-18 — these are the trust residuals that settlement named and deferred

---

## 1 — The symmetric-evaluation argument, audited

When an envelope touches shape T and a watcher over a different base B consults T, `Domains.kt` enumerates the affected subjects through recognized correlations (reverse: an inferred inverse, `for this`, `f == this`; forward: B's own to-one field into T) and falls to Unknown — widen everything — when none is recognized. Completeness of that enumeration is argued, not proven: the claim is that any affected subject the enumeration misses lives in some other envelope that evaluates the same watcher and records the mirrored access, so the conflicting pair still intersects (helped by writes keying the written row's correlatable references). The audit is a per-construct case analysis of that claim, to the same standard as the walker's own "every step keys or widens" — either it holds everywhere or the gap gets a widening.

**Example.** The declarations both cases run on (trimmed from the payments spec):

```
shape Order {
    amount: decimal
    netPaid: decimal = sum(chargeAttempts where SuccessfulCharge, amount) - sum(refunds, amount)
}

shape SettledOrder = Order where netPaid >= amount

shape ChargeAttempt {
    order: one Order
    amount: decimal
}

expose shape ChargeResponse {
    attempt: one ChargeAttempt
    outcome: text
    respondedOn: timestamp on create
}

shape CompletedAttempt = ChargeAttempt where exists ChargeResponse for this {
    outcome: text = latest(ChargeResponse where attempt == this by respondedOn).outcome
}

shape SuccessfulCharge = CompletedAttempt where outcome == "approved"

expose shape Refund {
    order: one Order
    amount: decimal
}

shape Receipt {
    attempt: one ChargeAttempt
    sentOn: DateTime
}

rule SendReceipt when SuccessfulCharge {
    Receipt from { attempt: this, sentOn: now }
}

shape SettlementReversal {
    order: one Order
    noticedOn: DateTime
}

rule NoteSettlementReversal when leaving SettledOrder {
    SettlementReversal from { order: this, noticedOn: now }
}
```

The two rules are what make membership matter: each puts a watcher on its condition, and the watcher's evaluation inside a commit envelope is the read that must be keyed. The recognized case first: an envelope commits a `ChargeResponse` for attempt A. `SendReceipt`'s watcher is over base `ChargeAttempt` (through `SuccessfulCharge` → `CompletedAttempt`) and consults `ChargeResponse` through `exists ChargeResponse for this` — a reverse correlation the enumeration recognizes (`for this` names the response's `attempt` field), so the affected subject is A itself, read off the response being committed, and the watcher's reads are keyed at A. No argument needed; the enumeration names the subject outright.

The argued case is the *other* watcher in the snippet: `NoteSettlementReversal`'s, over base `Order`. Creating that `ChargeResponse` also moves order O's `SettledOrder` membership — which that watcher must notice, or a reversal goes unrecorded — because `netPaid` sums `chargeAttempts where SuccessfulCharge`. But the route from the touched shape back to the affected subject is two hops (response → `attempt` → `order`), and the recognized correlations are single-hop, so the enumeration reaches attempt A and stops short of O. Here the symmetric-evaluation argument is what claims safety: any *concurrent* envelope racing on O — say a `Refund` commit against the same order — evaluates the same `SettledOrder` watcher when it touches the shapes involved, and *its* recorded reads (keyed at O through its own recognized correlations, or widened to Unknown) intersect the `ChargeResponse` envelope, helped by that envelope's write keying the response's correlatable `attempt` reference. The two halves of the race meet even though one half never named O.

The audit is the per-construct walk of that claim: for each way a condition can consult a foreign shape — `exists … for`, an inverse collection (bare or behind a derived property), a scan filtered by `f == this`, a sibling join, an aggregate scan — show that the mirrored access lands on every affected subject in every pairing, or add a widening where it does not.

## 2 — Body-side correlations in `correlatable` — **done, 2026-08-18**

A write to a row also keys the row's to-one references *when readers can correlate on them* — but `correlatable` collected correlation routes from watcher *conditions* only. A rule **body** that reads `exists T for x`, or scans `T where f == x`, is a correlated reader that collection missed, so a write to such a T row could fail to key `x`.

**Implemented:** `Domains.kt` now also collects routes from every rule body (`bodyCorrelationFields`) — `exists T for x` and `(T for x)` on any target, inverse-collection reads anywhere along a path, scans filtered by `f == <path>`, and the derived properties and refinements a body reaches — and `correlatable` consults that union alongside the condition routes. A `for` whose target type cannot be resolved routes every to-one field of the consulted shape: wide, never wrong. Guarded by `DomainsTest` ("a correlated read in a rule body keys the writer") and a `CommutationTest` pair, with the test verified to fail without the fix. Regenerating the example specs changed no output — every body read they contain was already covered by an inferred inverse or a condition route.

Implementing it sharpened where the gap actually bit, which is worth recording. Three kinds of writer consult `correlatable`: the act's own creation, the row an assignment writes — and rows created by rule bodies, which were never at risk, because the walker keys every reference of a body-created row unconditionally. Most acts were safe too: a shape referenced by exactly one field of a non-transient shape carries an inferred inverse collection, and `correlatable` accepts inferred inverses outright. So the bug needed a writer whose row has *no* inferred inverse at the referenced target — two references into the same shape, exactly the case where the inverse inference declines to name a collection — read only by a rule body.

**Example.** The spec the new tests hold:

```
shape Account {
    balance: decimal initially 0
}
expose Account

expose shape Transfer {
    source: one Account
    target: one Account
    amount: decimal
}

expose shape AuditRequest {
    account: one Account
}

shape AuditReport {
    request: one AuditRequest
    outbound: decimal
}

rule ReportAudit when AuditRequest {
    AuditReport from { request: this, outbound: sum(Transfer where source == this.account, amount) }
}
```

`Account` infers no `transfers` collection — two `Transfer` fields target it, so the name would be ambiguous — and no watcher *condition* consults `Transfer`; the only reader correlating transfers to an account is `ReportAudit`'s body. Before the fix, `correlatable(Transfer, source, Account)` answered no, so a `Transfer` commit keyed nothing at all: the derivation called a transfer and an audit of the *same* account disjoint, and the audit's `sum` could read while the transfer inserts, with no queue forcing an order — the phantom conflict the write-keying exists to prevent. With body routes collected, the transfer commit keys `transfer.source`, the audit keys `auditRequest.account`, and the same-account pair takes turns. (`target` stays unkeyed, correctly: nobody correlates on it.)

## 3 — The property-style commutation sweep

`CommutationTest` is the falsification harness — derivation-disjoint envelope pairs must produce identical final state in either order against the reference evaluator — but its pairs are hand-picked. Grow it into a generated sweep: enumerate act pairs per example spec, evaluate their derived keys on concrete inputs, and check every pair the derivation calls disjoint. A counterexample is a soundness bug; the sweep is the derivation's regression net while item 1 lands.

**Example.** Today the test holds pairs like two deposits to different accounts: the derivation keys each at its own account row, calls the pair disjoint, and the harness runs deposit-A-then-B and deposit-B-then-A on fresh systems, comparing final states by an id-insensitive structural fingerprint. The generated sweep does the same thing exhaustively: for payments, enumerate the act pairs (`Refund` × `ChargeResponse`, `CardUpdate` × `Refund`, `ManualCharge` × `ChangeShippingAddress`, …), instantiate concrete rows to fire them against (two customers, two orders, an attempt each), evaluate each act's derived keys on those concrete inputs, and for every pair whose key sets are disjoint — the refund on order 1 against the charge response on order 2, but *not* against one on order 1 — run both orders and demand identical fingerprints.

The item-2 gap (now closed) shows what a caught counterexample looks like: before the fix, the derivation called the same-account transfer-and-audit pair from item 2 disjoint, and the two run orders disagree — the audit report snapshots 10 when the transfer ran first and 0 when it ran second — so a sweep enumerating that spec's act pairs would have failed, pointing at the pair. That pair is a hand-picked `CommutationTest` case now; the sweep is the same hunt made exhaustive. One honest limit to record: order-swap is a necessary condition, not the full concurrency semantics — a bug whose two sequential orders happen to produce structurally identical final states slips the net, which is why the sweep is the regression floor under item 1's audit, not a substitute for it.

## 4 — The advisory dial, re-checked

A5 (an uncorrelated read widens the domain; the compiler warns and `tolerates contention` answers) is an advisory by ruling (2026-08-18), explicitly provisional: the comprehension framing that produced the obligation leans *required*, and ceremony-avoidance won the default only because a wide domain's failure is throughput, not a wrong value. Calibration against realistic specs decides whether the dial stays — the same campaign as OQ15/OQ16's, watching for the author who ships an unintentionally single-threaded system that a required error would have caught.

**Example.** The failure the *required* side fears:

```
shape OrderNumber {
    order: one Order
    n: int
}

rule AssignOrderNumber when Order {
    OrderNumber from { order: this, n: count(OrderNumber) + 1 }
}
```

`count(OrderNumber)` correlates to nothing — it must see every row — so the domain of every order commit widens to the whole `OrderNumber` shape, and the entire order intake serializes through one queue. Under the current ruling the compiler warns, names the read and the rule, and lists the discharges (correlate the read, move the rule to a schedule, declare `tolerates contention`); an author skimming past the warning ships a system whose throughput ceiling is one commit at a time, discovered in production. A required error would have stopped that at compile time.

The failure the *advisory* side fears is the mirror image: a low-volume back-office act — say a support-only `CardUpdate` whose condition happens to scan wide — where the width is real but harmless, and a required error forces `tolerates contention` ceremony onto a declaration whose contention will never be observed. The calibration campaign watches realistic specs for which failure actually occurs: silent single-threading (move the dial to required) or tolerance boilerplate on benign widths (the advisory stays).

## What is *not* here

The design is settled and does not reopen: derived-only queue keys (no author declaration), model facts as the narrowing move, `tolerates contention` as the width acceptance, the contention map and commit-function contract as the surfaces, the cadence discharge, and cross-store delivery staying the engineer's mechanism choice (`evaluation.md` U3 and its exclusions). Anything found by items 1–3 is a bug against that design, not a redesign.
