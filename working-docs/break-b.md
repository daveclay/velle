# Breaking Design B: an auction house

Adversarial validation of the transient-acts decision (`investigate-transient.md`, Design B: *a transient act exists only within its own commit's transaction — an input to the state, not a member of it*). Method as in the retired `break_velle.md`: a real domain, worked case by case, each case chosen to attack a specific weak point. The domain is an auction house because nearly everything about it is B-hostile: bids need history, closing is evidence, settlement is asynchronous, and clients double-submit.

Uses B's proposed syntax (`expose transient shape ... using ...`), which is not implemented — nothing here runs; verdicts are by analysis. Verdict scale: **HOLDS** (B handles it, ceremony win), **TAX** (B handles it at a cost), **GAP** (B needs a new obligation or fails).

## The base spec — B's sweet spot

An auction with a current price, taking bids while open. The bid is the archetypal message: decided synchronously, at arrival, against current state.

```
expose shape Auction {
    item: text
    minimumIncrement: decimal
    currentPrice: decimal
    closed: boolean initially false
} using MockHarness

shape OpenAuction = Auction where not closed

expose transient shape PlaceBid {
    auction: one Auction
    bidder: text
    amount: decimal
} using MockHarness

rule AcceptBid when (PlaceBid where auction is OpenAuction and amount >= auction.currentPrice + auction.minimumIncrement) {
    auction.currentPrice = amount
}

shape BidRefusal {
    auction: one Auction
    bidder: text
    amount: decimal        -- the act's payload, copied
    reason: text
    refusedOn: DateTime
}

rule RefuseBid when (PlaceBid where not auction is OpenAuction or amount < auction.currentPrice + auction.minimumIncrement) {
    BidRefusal from { auction: auction, bidder: bidder, amount: amount, reason: "closed or below increment", refusedOn: now }
}
```

Both rules' conditions are evaluated exactly once — at the bid's only commit. No `UnhandledPlaceBid`, no outcome anchors, no drift: when the auction closes next week, there is no old `PlaceBid` in the state for the closure to re-partition. Compare the Design-A spelling of the same thing: two evidence shapes, an `unhandled` operator, a disarm obligation per rule. **Verdict: HOLDS** — this is the case B exists for, and the ceremony win is total.

## Case 1 — a request no rule answers

A pause feature arrives months later:

```
expose shape PauseAuction { auction: one Auction } using MockHarness
expose shape ResumeAuction { auction: one Auction } using MockHarness

rule ApplyPause when PauseAuction { auction.paused = true }
rule ApplyResume when ResumeAuction { auction.paused = false }

shape OpenAuction = Auction where not closed and not paused    -- revised
```

The base spec's single `RefuseBid` happens to survive this revision, because its condition is a *complement* (`not auction is OpenAuction or ...`) — whatever `OpenAuction` comes to mean, the complement follows. But the complement spelling is exactly what a good author moves away from, because one catch-all rule can only say one thing; refusals want to be split *per reason* so each carries the right message. The realistic pre-revision spelling is the three-way enumeration:

```
shape ClosedAuction = Auction where closed

rule RefuseBidClosed when (PlaceBid where auction is ClosedAuction) {
    BidRefusal from { auction: auction, bidder: bidder, amount: amount,
                      reason: "auction is closed", refusedOn: now }
}

rule RefuseBidLow
    when (PlaceBid where auction is OpenAuction and
          amount < auction.currentPrice + auction.minimumIncrement) {
    BidRefusal from { auction: auction, bidder: bidder, amount: amount,
                      reason: "below minimum increment", refusedOn: now }
}
```

In the two-state world this is total: every bid is on an auction that is open (accepted or refused-low) or closed (refused-closed). Then the pause revision lands — touching only `OpenAuction`'s declaration — and a paused auction (`closed == false, paused == true`) is now *neither* `OpenAuction` nor `ClosedAuction`. A bid on it matches no rule: not `AcceptBid` (not open), not `RefuseBidLow` (not open), not `RefuseBidClosed` (not closed). The enumeration went stale while the untouched refusal rules kept compiling — nobody edited a line that broke.

**The gap, in plain terms.** A bid arrives on a paused auction, and no rule responds to it. Because a transient request is not kept, the system afterward holds *no evidence the request ever happened* — nothing to query, nothing to alert on, nothing for support to find when the bidder calls asking why their bid disappeared. The failure isn't that the system did the wrong thing with the request; it's that it did **nothing, and kept no record that there was ever something to do**. If the request had been kept (as all acts are today), the miss would at least be visible: unprocessed bids sitting in the state, waiting for someone to notice.

(The trap is not fixable by style rule: "always write the complement" collapses per-reason refusals into one mumble, so enumerated partitions are the *correct* authoring style — which is precisely why coverage must be proved, not prescribed.)

This is B's one genuinely new obligation: **a transient act must provably match at least one rule in every reachable state**. When the proof fails, the author should see the problem stated whole, in the spec's own vocabulary:

```
error: a PlaceBid can arrive that no rule responds to.
  When an auction has closed = false and paused = true, it is neither OpenAuction
  nor ClosedAuction, so none of AcceptBid, RefuseBidLow, RefuseBidClosed match.
  PlaceBid is transient — it is not kept after its commit — so such a bid would
  be ignored, and no record that it arrived would exist anywhere.
  Add a rule for bids in this situation, or widen an existing rule's condition
  (for example RefuseBidClosed: `auction is ClosedAuction` → `not auction is OpenAuction`).
```

Everything in that message is a business statement: which request, in which situation, answered by nobody, remembered by nothing. Mechanically, the proof is a totality/exhaustiveness check over the act's partitions — the refinement-exhaustiveness checker README §8 names as a compiler goal and v0 hasn't built, kin to the `states of` partition construct. Until it exists, fail closed: a transient act whose rule coverage isn't provable is a compile error.

Worth saying plainly: **this obligation is desirable independent of the gap that forced it.** It is the compiler asking a product question the spec should answer anyway — *"what should happen when a bid arrives while the auction is paused?"* — and refusing to proceed until someone decides. Today's persist-semantics lets that question go unasked (the unmatched act just accumulates); B turns an unasked product question into a compile error, which is the language's whole philosophy applied to coverage.

**Verdict: GAP** — B is unsound without the every-request-gets-a-response obligation, and that obligation lands on unbuilt (and in general hard) machinery. Note A does not have this problem *structurally* (the act persists as evidence of the miss), though it doesn't diagnose it either.

## Case 2 — history acts: second-price auctions

The business changes: winner pays the *second-highest* bid. Now bid history is load-bearing — `max`, `count`, ordering over past bids. A transient bid can't be aggregated later; the state must carry records:

```
shape BidRecord {
    auction: one Auction
    bidder: text
    amount: decimal
    placedOn: timestamp on create
}

rule AcceptBid when (PlaceBid where auction is OpenAuction and amount > <current high>) {
    BidRecord from { auction: auction, bidder: bidder, amount: amount }
}

-- second price, read off the records:
--   winningPrice: decimal = <second-largest amount over bidRecords>
```

Two observations. First, the aggregation itself exposes a v0 gap unrelated to B (no second-largest selector — `latest`/`first`/`max` don't compose to it; noted for the selector-vocabulary item). Second and more important: the *alternative* — leaving `PlaceBid` durable and aggregating over the acts — puts its partitions back on mutable state and reopens drift, which is Design A's territory with all its anchors. Materializing `BidRecord` is not overhead B imposes; it's the author declaring *which slice of the message is durable business data* — and the record is plain data with no partitions, so nothing drifts.

**Verdict: TAX**, and arguably a clarifying one: one shape and one copy per history-bearing act, in exchange for the durable/transient split being explicit.

## Case 3 — evidence acts: closing the auction

Today's idiom makes the close act itself the evidence: `ClosedAuction = Auction where exists CloseAuction for this`. Under B, if `CloseAuction` is transient that predicate is illegal — it reads the act after its transaction, which is exactly what the reference/read ban catches statically. The B spelling materializes the state change:

```
expose transient shape CloseAuction {
    auction: one Auction
    closedBy: text
} using MockHarness

rule ApplyClose when (CloseAuction where auction is OpenAuction) {
    auction.closed = true
    Closure from { auction: auction, closedBy: closedBy, closedOn: now }
}
```

The flag carries the state; the `Closure` record carries who/when (audit). Both were available before — B just forces the choice to be explicit rather than letting the act do double duty silently. The static ban does real work here: an author who marks an evidence-act transient gets an error at the predicate, not a mystery.

**Verdict: TAX** — and the ban converts a subtle modeling mistake into a compile error, which is B behaving as designed.

## Case 4 — double-submit: idempotent ingestion

The bidder's client retries; the same bid arrives twice. Under persist-semantics you could dedup against past acts (`not exists (PlaceBid where ...)`). Under B **past acts don't exist to dedup against** — the guard must read something durable, which means outcomes must carry a client-supplied key:

```
expose transient shape PlaceBid {
    auction: one Auction
    bidder: text
    amount: decimal
    requestKey: text            -- client-supplied idempotency key
} using MockHarness

rule AcceptBid when (PlaceBid where ... and not exists (BidRecord where requestKey == this.requestKey)) {
    BidRecord from { auction: auction, bidder: bidder, amount: amount, requestKey: requestKey }
}
```

Workable — the idempotency-key pattern real APIs already use — but note what returned: key threading through act and outcomes, and a guard conjunct per handling rule. Some of the ceremony B removed comes back wherever ingestion must be idempotent. Also the correlation question sharpens: `requestKey` needs uniqueness the client controls, and a duplicate arriving *while both copies are in flight* is two transactions — last-in-wins on the guard, fine here, but the general story belongs to the correlation-key design item.

**Verdict: TAX**, concentrated exactly where the validation plan predicted (correlation keys).

## Case 5 — async decisions: fraud review on big bids

A bid over $10,000 can't be accepted until the fraud service answers — the decision itself is asynchronous, and B demands the act be fully handled in its own transaction. This looked like B's hard wall ("such an act can't be transient"). It isn't — the handler materializes the *undecided state* synchronously, and the decision machinery hangs off the durable intent:

```
rule HoldBigBid when (PlaceBid where amount > 10000) {
    BidReview from { auction: auction, bidder: bidder, amount: amount, requestKey: requestKey }
}

shape PendingReview = BidReview where not exists FraudVerdict for this

expose shape FraudVerdict {                    -- the service's answer: a later, ordinary act
    review: one BidReview
    approved: boolean
} using MockHarness

rule ApplyReviewedBid when (BidReview where exists (FraudVerdict where review == this and approved)) {
    BidRecord from { auction: auction, bidder: bidder, amount: amount, requestKey: requestKey }
}
```

The transient act *was* fully handled at its commit — its handling produced the pending review. Everything asynchronous happens to the durable intent, which is the intent-before-effect pattern the language already canonizes (README §11). The same move answers external effects (notify the winner: materialize a `SettlementIntent`; the `after commit` + backstop machinery hangs off it, since an `after commit` rule can never be triggered by the act itself — the act is gone).

**Verdict: HOLDS** — "materialize, then decide/effect" dissolves the in-transaction-only restriction generally. This was the case I expected to break B, and it didn't.

## Case 6 — constructs that must be statically meaningless

Sweeping the rest of the grammar against a transient act, each of these must become a compile error, or B leaks:

- a **refinement of a transient act used outside its commit** — covered by the read ban (Case 3);
- **`when leaving R`** where R refines a transient act — there are no exits, only the one evaluation;
- a **tick or `after commit` trigger** on a transient act's refinement — the tick/queue arrives after the act is gone;
- an **inferred inverse collection** (`auction.placeBids`) — would be an aggregate over nonexistent instances;
- a **capture or derived property** reading the act from a *state* shape's refinement (`captured closedBy = latest(CloseAuction ...)`) — same read ban, one hop removed;
- **`(TransientShape for x)`** in any value position outside the act's own transaction.

None of these is a design problem — each is a one-line consequence of "not part of the state" — but together they are the checklist B's validator slice must ship with, and they are what makes B *safe*: every misuse the auction domain tempted us into surfaced as a static error, not silent misbehavior.

**Verdict: HOLDS**, contingent on shipping the full ban list.

## The scorecard

| case | verdict | residue |
|---|---|---|
| synchronous accept/refuse | HOLDS | the sweet spot; total ceremony win over A |
| a request no rule answers | **GAP** | needs the every-request-gets-a-response proof — the §8 exhaustiveness checker, fail-closed until built |
| history acts | TAX | materialize a record; durable/transient split becomes explicit |
| evidence acts | TAX | materialize flag + audit record; read ban catches the mistake statically |
| double-submit | TAX | correlation keys return some ceremony; feeds the key-design item |
| async decisions / external effects | HOLDS | "materialize, then decide" — intent-before-effect, already canonical |
| grammar sweep | HOLDS | contingent on the six-item static ban list |

B survives its hostile domain better than expected: the wall cases (async, effects) dissolve into one uniform move — **copy forward into a durable record/intent** — which is the same idiom every TAX case uses. B's real exposure is the request no rule answers: today an unhandled request at least sits in the state where someone can find it; under B it is gone, with no record it ever arrived. The guard — proving every request gets a response in every reachable state — is an exhaustiveness proof the compiler doesn't have yet. That single gap is the make-or-break work item.

Findings feed `investigate-transient.md`'s validation plan and `TODO.md`.
