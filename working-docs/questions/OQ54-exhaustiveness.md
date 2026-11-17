# OQ54 — The exhaustiveness engine: V9, every request answered, `states of`

**Status:** open (2026-10-14) — Reference §8 names refinement exhaustiveness as a compiler goal; nothing has built it, and three settled designs lean on it. D1 (opened 2026-11-17) awaits Dave.
**In plain terms:** can the compiler prove that a set of refinements covers every instance of a shape in every reachable state? Three things wait on the answer: a transient request must provably get a response (V18 today proves only a complement pair); a declared state partition (`states of Invoice = Draft | Issued | Paid | Voided`, Reference §22) is that proof made into a declaration; and deletion's positive-exhaustive sentence ("deletable only in Draft") was ruled to ride the partition declaration.
**See:** Reference §8 (the goal), §22 "State partition declaration" · `checks.md` V18 (the complement slice) · Reference §8 "Frozen fields" (`undeletable`; the gate is negative only) · `checks.md` V18 (a transient act's parts owe no answer of their own)

---

## 1. Every request gets a response (V18's full form)

*One slice is V18's since 2026-11-14: when the act carries a field of a type declared by its words (Reference §5, "A field that takes one of a few words"), answers that each test that field with `==` or `in` and name every word between them are coverage, and a word none names is reported by name (`checks.md` V18). A word test beside another condition stays with the engine below.*

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

**The item:** grow V18 beyond the complement slice once the engine exists. A closure's parts owe no coverage of their own; the act's answer covers what it carries (`checks.md` V18).

### D1. Whether rules over every kind of a transient act's parts answer the act

A batch arrives with rows of two kinds that take one shape, `CreateRow is Row` and `UpdateRow is Row`, in one list (OQ137, settled). The natural spelling is one rule per kind. Each fires once per row inside the batch's commit (Reference §9), and together they reach every row. V18 counts only an answer written over the batch, so it refuses the spec. Found 2026-11-17 by a stress pass of a domain spec, re-verified at commit 76cff78.

`working-docs/examples/survey/20-part-kind-rules-refused.velle`:

```
transient act Upload { note: text } with rows    -- a batch that is not kept; its parts arrive with it
shape Row { upload: one Upload, ident: text }    -- the shape both kinds take; it has no records of its own
shape CreateRow is Row { newKey: text }          -- one kind of row
shape UpdateRow is Row { key: text }             -- the other kind
shape Made { key: text }
rule HandleCreate when CreateRow { Made from { key: newKey } }
                                                 -- fires once per CreateRow inside the Upload's commit
rule HandleUpdate when UpdateRow { Made from { key: key } }
                                                 -- fires once per UpdateRow inside the Upload's commit
                                                 -- REFUSED today, V18: "a 'Upload' can arrive that no rule or
                                                 -- 'never' answers, and it is transient, so an unanswered one
                                                 -- would be ignored with no record that it arrived ..."
```

What validates today, `working-docs/examples/survey/20-part-kind-rules-workaround.velle`: the batch declares a view per kind, `createRows: many CreateRow = (CreateRow where upload == this)` and the same for updates (OQ136), and one rule over the bare `Upload` walks both with `for each`. The two rules per kind become two loops in one rule.

Do rules over every kind of a transient act's parts count as answering the act?

Recommendation: no, and keep V18 as it is. The rules over the kinds reach every row, but a batch with no rows is still a transient act that arrives and leaves no record, and `checks.md` V18 already says a closure's parts owe no coverage of their own because the act's answer covers what it carries. Counting the part rules as the act's answer would need a second condition, that the batch is never empty, which the author would have to state as `never (Upload where count(rows) == 0)` or the compiler would have to read from the closure. One rule over the bare act, walking the kinds, says the same thing and is the answer V18 already accepts. The cost is the spelling in the workaround file: two loops in one rule rather than two rules, and a view per kind on the batch.

A second question the same spec raises: is a rule over the taken shape itself, `rule R when Row`, an answer to `Upload`? Under the recommendation, no, for the same reason: it fires per row, not per batch.

Costs:

- Nothing is removed; nothing is added to the compiler.
- A product owner who thinks in rows, not batches, writes the rule over the batch. The wiki says why in one sentence beside V18: a batch with no rows is still a request.
- If the answer is yes instead, V18 gains a case: the act is answered when every kind that takes its parts' shape has an answer and a prohibition forbids the empty closure. That is a slice of the engine this file waits on, and the fail-closed check it adds is the empty-closure prohibition.

#### Proposed changes

- `wiki/Normative/checks.md` V18 and `wiki/Reference/Reference-04-State-and-commits.md` "Transient acts": one sentence, rules over the kinds of an act's parts are not the act's answer because a request with no parts is still a request; the answer is a rule over the act that walks them.
- `working-docs/examples/survey/20-part-kind-rules-refused.velle` and `-workaround.velle` stay as the pair that shows it, README lines in place.

## 2. `states of`: the partition declaration, and the sentence deletion handed it

Reference §22 carries the construct. One requirement was attached by the deletion rulings (Reference §8 "Frozen fields", `undeletable`): the deletion gate is negative and state-scoped (`undeletable` on the state where deletion is denied), and with richer state the negative spelling becomes N declarations that silently under-cover the moment a state is added:

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

## 3. Work items

- [ ] Decide D1.
- [ ] Grow V18 beyond the complement slice once V9's exhaustiveness engine exists.
- [ ] Check, once the engine exists, that a rule narrowing an element of a mixed collection with `is` (a `many` typed by a static union or a taken shape, or the inverse a taken shape's back-reference infers) names every shape the collection can hold, so a shape added later does not fall silently into an `else`; raised by OQ137 on 2026-11-16.
