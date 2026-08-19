# OQ42 — Trusting the domain derivation: the soundness audit and the calibration campaign

**Status:** open
**In plain terms:** The compiler now ships a working serialization-domain derivation (`evaluation.md` U3, settled 2026-08-18): for every act, it computes the queue keys the act's work must wait its turn on. The derivation's design rule is fail-closed — every read and write either gets a key or honestly widens to "everything touching this shape" — and it reproduces every worked example plus the billing spec. But parts of it rested on argument rather than audited structure, and the ruling that made the width warning an advisory was explicitly provisional. This question is where the derivation earns full trust: verify the arguments, sweep it against realistic specs at scale, and re-check the advisory dial. Item 2 (body-side correlation routes) is done and item 4 (the width warning's severity) is resolved — the advisory stays, with its consequences recorded in `docs/concurrency.md` — both as of 2026-08-18. Item 3's generated sweep is built and item 1's audit is complete as of 2026-08-19 (`working-docs/audit-symmetric-evaluation.md`: one hole found and fixed — the reassignment's incoming reference — and one ruling raised and resolved: captures are readable at admission, so capture-dependent keys stand). All four items now carry closed records; what keeps the question open is disposition only — the sweep's noted extensions, and the precision follow-ups in `TODO.md`. It is the concurrency-facing member of the same calibration campaign as OQ15/OQ16.
**Opened by:** the serialization-domain settlement, 2026-08-18 — these are the trust residuals that settlement named and deferred

---

## 1 — The symmetric-evaluation argument, audited — **done, 2026-08-19**

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

Item 3's key evaluator handed the audit one construct already: a derived path key can hop through a refinement *capture* — membership's `ReopenTicket` domain carries `reopenTicket.ticket.closedBy`, where `closedBy` lives on the `ClosedTicket` refinement — so the key is only evaluable with the row's membership and capture in hand. The audit should rule on what that means for the enqueue-time contract: is such a key evaluable exactly when it matters, or does it need a widening?

**Audited, 2026-08-19** — the record is `working-docs/audit-symmetric-evaluation.md`, with every construct verdict pinned by a probe spec in `SymmetricEvaluationAuditTest`. The claim holds across the matrix, in one of its three forms (named exactly / fell to Unknown and widened / covered by the mirrored access plus write-keying) — except one construct: reassigning a reference keyed the row the field *left* but not the row it now *names*, whenever the act's own reference was not correlatable (transient acts, ambiguous-inverse shapes). Found by the transient-reassignment probe, fixed in `walkAssignment` the same day, and visible in the real specs: membership's `AssignTicket` contract gained `[assignTicket.agent]`, enrollment's `AssignAdvisor` gained `[assignAdvisor.advisor]`. The capture-hop question above was raised as the audit's R1 and resolved the same day, by the author's test "if captured fields are readable when the rule fires, then they should be readable": the runtime's ordering verifies the condition (captures evaluate at membership entry before any firing; leaver captures retract after exit rules ran), so captures are committed state and capture-dependent keys are legitimate — `evaluation.md` U3 now states the admission-time evaluation clause, captures included, and `DomainKeys` resolves capture hops. What remains recorded is precision work only (spurious widths the audit found sound but avoidable).

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

## 3 — The property-style commutation sweep — **built, 2026-08-19**

The derivation's guarantee is universal: two envelopes conflict *iff* their domains intersect. The "only if" half is what the runtime acts on — every pair of commits the derivation calls disjoint is a pair it will run with no queue between them, trusting the results cannot interfere. A soundness bug is therefore a pair *wrongly* called disjoint, and it fails silently: nothing crashes, the two commits simply race, and whether anyone sees a wrong state depends on timing.

A *pair*, throughout this item, is two concrete commits against one prepared system. "A `Deposit` of 10 into account 1" and "a `Deposit` of 20 into account 2" is a pair — one the derivation calls disjoint, because the two `deposit.account` keys evaluate to different rows. Aim the second deposit at account 1 instead and that is a *different* pair: the same two act shapes, now conflicting, because the keys land on the same row. The verdict is per concrete pair, not per act shape, so the space the guarantee quantifies over is every two acts × every assignment of rows and values × every prior state.

`CommutationTest` is the harness built to falsify wrong disjoint-calls: for a pair the derivation calls disjoint, running the two commits in either order against the reference evaluator must produce identical final state — if the order changes the outcome, the pair interferes, and calling it disjoint was unsound. Until this item landed, it checked five hand-chosen points in that space.

**The problem, in one specific example.** The failing spec is item 2's — a `Transfer` whose two account references defeat the inverse inference, and an audit whose only correlated read of transfers lives in a rule body:

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

On 2026-08-18, before item 2's fix landed, three things were true of this spec at once:

1. The derivation gave the `Transfer` act *no keys at all* — no reader `correlatable` knew about consulted transfers, so its commit envelope contended with nothing — and gave `AuditRequest` the domain `{auditRequest.account}`. A transfer and an audit of the *same* account: called disjoint.
2. That call was unsound: commit a `Transfer` of amount 10 out of an account and an `AuditRequest` for the same account — transfer-then-audit ends with `outbound` at 10, audit-then-transfer with it at 0. The orders do not commute.
3. The entire test suite was green. `CommutationTest` held the deposits, transfer, branch-cap, and uniqueness pairs — every one of them correctly derived — and the transfer-and-audit pair appeared in no test, because a pair only gets hand-picked where its author already suspects trouble.

Nothing mechanical stood between that bug and production; it was found by reading `correlatable`, not by running anything. That is the problem: hand-picked pairs share the blind spots of the hand that picked them, and a wrongly-disjoint pair lives exactly where nobody suspected one. The item is to remove the hand: enumerate act pairs per example spec, evaluate their derived keys against the concrete rows they commit so the *harness* computes the disjointness verdict, and order-swap every pair it calls disjoint. A counterexample is a soundness bug; the sweep is the derivation's regression net while item 1 lands.

**Example.** The deposits spec `CommutationTest` already holds:

```
shape Account {
    balance: decimal initially 0
}
expose Account

expose shape Deposit {
    account: one Account
    amount: decimal
}

shape DepositApplication {
    deposit: one Deposit
    appliedOn: DateTime
}

shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

The derivation gives a `Deposit` commit the domain `{deposit.account}`. The hand-picked test instantiates two accounts, commits a deposit to each, and checks the pair the derivation calls disjoint: the two keys evaluate to different account rows, so running deposit-A-then-B and deposit-B-then-A on fresh systems must end in identical final states, compared by an id-insensitive structural fingerprint. (Two deposits to the *same* account evaluate to the same row — a conflicting pair, which carries no commutation obligation.) The generated sweep does the same thing exhaustively, and against the bigger specs: for payments, enumerate the act pairs (`Refund` × `ChargeResponse`, `CardUpdate` × `Refund`, `ManualCharge` × `ChangeShippingAddress`, …), instantiate concrete rows to fire them against (two customers, two orders, an attempt each), evaluate each act's derived keys on those concrete inputs, and for every pair whose key sets are disjoint — the refund on order 1 against the charge response on order 2, but *not* against one on order 1 — run both orders and demand identical fingerprints.

**Built, 2026-08-19** — `CommutationSweepTest` plus `DomainKeys.kt`:

- `DomainKeys` is the concrete key evaluator — and doubles as the reference implementation of the queue-key contract the generated commit functions carry: resolve each path key to the row it names through the commit's supplied references (and settled state for further hops), each value key to the committed value; two envelopes conflict when any resolved token is shared. Anything it cannot resolve — a widening, an unreadable hop, an uncarried value — makes the envelope conflict with everything: wide, never wrong.
- The sweep runs per spec over the harness's own specs plus every `examples/*.velle`: grow a deterministic small world (three passes over the exposed acts, every row salted structurally unique, the clock advancing between world commits but never between a pair's two commits), synthesize two commit variants per act (text fields drawn from the spec's own compared literals, so enum-guarded fields get values the nevers accept), let `DomainKeys` compute every pair's verdict, and order-swap every pair it calls disjoint against the id-insensitive fingerprint (fields minted by `initially randomUUID` masked — they differ between rebuilds by construction).
- Coverage on the day it landed: 332 disjoint pairs order-swapped across eight specs (115 in billing alone). Skips are counted and named per spec, never silent: wide acts carry no commutation obligation (payments' seven wide acts match its generated contracts' "system-wide width" exactly), and a key the evaluator cannot resolve — one construct found: a path key hopping through a refinement *capture*, `reopenTicket.ticket.closedBy` — degrades to conflicting.
- Verified against the known bug: with item 2's fix reverted, the sweep fails on the audits spec naming the transfer-and-audit pair. The one that got away is now caught mechanically.

Out of this build, deliberately: randomized value exploration (value-boundary bugs that fixed small worlds miss), commit-versus-tick-firing pairs over `scheduledRuleDomains`, and triples. And the honest limit stands: order-swap is a necessary condition, not the full concurrency semantics — a bug whose two sequential orders happen to produce structurally identical final states slips the net, which is why the sweep is the regression floor under item 1's audit, not a substitute for it.

## 4 — The advisory dial, re-checked — **resolved: the advisory stays, 2026-08-18**

The "dial" is A5's severity setting, with two positions: *advisory* — an uncorrelated read widens the domain, the compiler warns, and the spec still compiles — or *required* — the same width refuses to compile until the author discharges it (correlate the read, move the rule to a schedule, or declare `tolerates contention`). It was set to advisory by the original ruling (2026-08-18) as explicitly provisional; this item was where the position got re-examined.

**Resolved:** the dial stays at advisory. Working the item surfaced that the ruling is really a choice between two authors — the one who *intends* a wide domain (serialization is the feature: sequential numbering cannot run two at a time, and `tolerates contention` marks the global queue deliberate in one line) and the one who lands in a wide domain by *accident* (a global lock hiding in a guard that reads like a config check, shipped past a skimmed warning). Both situations, with their worked specs and their discharges, are recorded permanently in `docs/concurrency.md` — the home for consequences of settled decisions — along with the reasoning that keeps the default at advisory: a wide domain's failure is throughput, not a wrong value, so failing open costs less here than anywhere else in the tolerance family.

The ruling stays revisitable, but the re-examination no longer lives in this question: stress-testing the advisory against realistic specs follows once the OQ15/OQ16 calibration questions resolve, and it is that stress test that could still move the dial.

## What is *not* here

The design is settled and does not reopen: derived-only queue keys (no author declaration), model facts as the narrowing move, `tolerates contention` as the width acceptance, the contention map and commit-function contract as the surfaces, the cadence discharge, and cross-store delivery staying the engineer's mechanism choice (`evaluation.md` U3 and its exclusions). Anything found by items 1–3 is a bug against that design, not a redesign.
