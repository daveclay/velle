# OQ54 — The exhaustiveness engine: V9, every request answered, `states of`

**Status:** open (2026-10-14) — README §8 names refinement exhaustiveness as a compiler goal; nothing has built it, and three settled designs lean on it
**In plain terms:** can the compiler prove that a set of refinements covers every instance of a shape in every reachable state? Three things wait on the answer: a transient request must provably get a response (V18 today proves only a complement pair); a declared state partition (`states of Invoice = Draft | Issued | Paid | Voided`, README §22) is that proof made into a declaration; and deletion's positive-exhaustive sentence ("deletable only in Draft") was ruled to ride the partition declaration.
**See:** README §8 (the goal), §22 "State partition declaration" · `checks.md` V18 (the complement slice) · README §8 "Frozen fields" (`undeletable`; the gate is negative only) · OQ43 §2 (V18's reach over transient parts)

---

## 1. Every request gets a response (V18's full form)

A transient act that nothing answers in some reachable state is ignored with no record it ever arrived. The auction-house pass that validated the transient-acts decision found the trap: a pause feature arrives months later, touching only `OpenAuction`'s declaration, and the refusal rules that enumerated reasons go stale while every line keeps compiling.

```
act Auction {
    item: text
    minimumIncrement: decimal
    currentPrice: decimal
    closed: boolean initially false
    paused: boolean initially false
}

shape OpenAuction   = Auction where not closed and not paused    -- revised: `and not paused` is new
shape ClosedAuction = Auction where closed

transient act PlaceBid {
    auction: one Auction
    bidder: text
    amount: decimal
}

rule AcceptBid when (PlaceBid where auction is OpenAuction and amount >= auction.currentPrice + auction.minimumIncrement) {
    auction.currentPrice = amount
}

rule RefuseBidClosed when (PlaceBid where auction is ClosedAuction) {
    BidRefusal from { auction: auction, bidder: bidder, amount: amount, reason: "auction is closed", refusedOn: now }
}

rule RefuseBidLow when (PlaceBid where auction is OpenAuction and amount < auction.currentPrice + auction.minimumIncrement) {
    BidRefusal from { auction: auction, bidder: bidder, amount: amount, reason: "below minimum increment", refusedOn: now }
}
```

A paused auction (`closed == false, paused == true`) is neither `OpenAuction` nor `ClosedAuction`. A bid on it matches no rule, and because the request is not kept, nothing afterwards shows it happened. The trap is not fixable by style rule: "always write the complement" collapses per-reason refusals into one mumble, so enumerated partitions are the correct authoring style, which is why coverage must be proved, not prescribed. The diagnostic the proof should produce is a business statement:

```
error: a PlaceBid can arrive that no rule responds to.
  When an auction has closed = false and paused = true, it is neither OpenAuction
  nor ClosedAuction, so none of AcceptBid, RefuseBidLow, RefuseBidClosed match.
  PlaceBid is transient — it is not kept after its commit — so such a bid would
  be ignored, and no record that it arrived would exist anywhere.
  Add a rule for bids in this situation, or widen an existing rule's condition
  (for example RefuseBidClosed: `auction is ClosedAuction` → `auction is not OpenAuction`).
```

What v0 has (`checks.md` V18): coverage is proven when some answer is the bare act shape, or two answers' predicates are syntactic complements; everything else, per-reason enumerations included, is rejected, and the prescribed idiom is one complement rule whose reason is a conditional value. The pharmacy pass lived inside that slice: a derived boolean on the act (`dispensable`) as the partition key, and a third answer chained off the refusal record instead of a third partition.

**The item:** grow V18 beyond the complement slice once the engine exists. Whether a closure's parts owe their own coverage or the container's answer covers the submission is OQ43 §2.

## 2. `states of`: the partition declaration, and the sentence deletion handed it

README §22 carries the construct. One requirement was attached by the deletion rulings (README §8 "Frozen fields", `undeletable`): the deletion gate is negative and state-scoped (`undeletable` on the state where deletion is denied), and with richer state the negative spelling becomes N declarations that silently under-cover the moment a state is added:

```
shape Draft     = Listing where status == "draft"
shape Submitted = Listing where status == "submitted" { undeletable }
shape Published = Listing where status == "published" { undeletable }

-- a year later, someone adds:
shape Archived  = Listing where status == "archived"
-- Archived listings are deletable — by omission, not decision. No proof fails,
-- no diagnostic fires.
```

Ruled: no second polarity. The positive-exhaustive sentence rides the partition declaration, which must therefore carry per-state deletion permission beside per-state write permission, so "deletable only in Draft" is checkably exhaustive and a new state is born undeletable unless the declaration grants:

```
states of Listing = Draft | Submitted | Published | Archived   -- candidate construct, not designed
    deletable in Draft
```

Until it lands, richer-state "only if" sentences are N `undeletable` declarations, and the under-coverage hazard is the documented, accepted gap.
