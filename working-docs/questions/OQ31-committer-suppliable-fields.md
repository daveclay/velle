# OQ31 — Committer-suppliable fields

**Status:** open — pure `expose`-construct design, deferred as a construct detail
**In plain terms:** which fields of an exposed shape does the caller supply and which are internal? The answer determines each generated commit function's signature.
**See:** README §5, §22 "External input (`expose`)" · `investigate_runtime.md` §1, §8 · `compiler/src/main/kotlin/velle/Codegen.kt` (`commitFn` — the v0 status quo this question would change)

---

Which fields the generated commit function accepts vs. which are internal: whether an ordinary `initially` field can be marked not-committer-suppliable the way `timestamp` fields inherently are (README §5). Determines the generated function's *signature* — this is what makes it `expose` design rather than mechanism configuration (`investigate_runtime.md` §1 dissolved the mechanism; this item detached and reattached to `expose`).

Its former siblings retired with `investigate_runtime.md` §8: supplied-vs-generated `id` and id minting are gone as questions — identity is the store's, Velle mints nothing persisted, and committer-side identity needs are business identifiers (ordinary fields), never `id`.

## The question in one shape

An exposed act whose fields the caller has no business supplying, alongside fields they plainly do. Everything the example needs is defined here; it references no spec under `examples/`.

```
shape Order {
    reference: text
    total: decimal
}

expose shape RefundRequest {
    order: one Order
    amount: decimal
    reason: text
    channel: text initially "web"            -- how the request arrived; a phone-bank gateway supplies "phone"
    requestedOn: timestamp on create         -- commit metadata, never committer-suppliable (README §5)
    approved: boolean initially false        -- workflow flag; its writer is rule ApplyApproval, below
    attemptCount: integer initially 0        -- payout-retry bookkeeping, advanced by the system's retry rules
    processorKey: text initially randomUUID  -- outward-facing key the system mints for the payment processor
}

-- Approval is a separate back-office act — a reviewer's decision, not
-- something the requester claims about their own request.
expose shape ApproveRefund {
    request: one RefundRequest
}

rule ApplyApproval when ApproveRefund {
    request.approved = true
}
```

`expose shape RefundRequest` generates the commit function the engineer's transport code calls to submit a refund request (README §22, "External input (`expose`)"). The question is that function's parameter list. The fields sort three ways:

- **Plainly the caller's** — `order`, `amount`, `reason`: the content of the request. Parameters under any reading.
- **Already never the caller's** — `requestedOn`: a `timestamp` field's value is a fact about the commit, populated by the commit itself (README §5), and v0's generated function already omits it (`commitFn` in `compiler/src/main/kotlin/velle/Codegen.kt` builds parameters from stored properties only; timestamp fields are a separate declaration kind and never appear).
- **The questionable middle — ordinary `initially` fields.** The v0 status quo makes every `initially` field an *optional* parameter: omitted, the initializer applies; supplied, the caller's value wins (`commitFn`, same file). For `channel` that is exactly right — the default covers the common case, and a gateway that knows better overrides it honestly. For the other three it hands the caller something internal:
  - `approved: boolean initially false` — the spec's own writer for this field is rule `ApplyApproval`, fired by a back-office act. A caller submitting `approved = true` at creation skips review entirely, and nothing in the spec says they may not.
  - `attemptCount: integer initially 0` — bookkeeping meant to start at zero and be advanced only by the system's retry machinery. A caller submitting `attemptCount = 3` starts the record mid-history, and any retry cap that reads the count is now wrong.
  - `processorKey: text initially randomUUID` — the point of the generator is that the *system* mints the key (README §5). A caller supplying their own value defeats the minting, including whatever uniqueness the mint was for.

`channel` is why the answer can't be the blanket rule "an `initially` field is internal": the same clause legitimately spells both "optional caller input with a default" and "internal field the caller must not touch," and only the author knows which a given field is. The distinction is per field, per exposure — which is what makes this a field-policy detail of the `expose` construct rather than a question about `initially` itself.

## A candidate solution: split the request from the record

Instead of marking fields on one shape, split the shape so the marking is unnecessary: the exposed shape carries the committer's fields plus *derived* properties for anything calculated at arrival, and every system-maintained stored field lives on an unexposed shape that a rule materializes. Each kind of field is then readable off its declaration form — nothing is implied, and no suppliability annotation exists anywhere. This is the `RefundRequest` example from the previous section, reworked:

```
shape Order {
    reference: text
    total: decimal
}

-- The request: pure input. Every stored field is the committer's, by
-- construction; the derived property is calculated, by declaration form.
expose transient shape RefundRequest {
    order: one Order
    amount: decimal
    reason: text
    channel: text
    requestKey: text
    fullRefund: boolean = (amount >= order.total)
}

-- The durable record the system keeps about the request. Unexposed: it can
-- only enter state as a rule's effect, so no committer exists for any of it.
shape RefundRecord {
    order: one Order
    amount: decimal
    reason: text
    channel: text
    requestKey: text
    fullRefund: boolean
    requestedOn: timestamp on create
    approved: boolean
    attemptCount: integer
    processorKey: text initially randomUUID
}

rule MaterializeRefundRecord when RefundRequest {
    RefundRecord from {
        order: order,
        amount: amount,
        reason: reason,
        channel: channel,
        requestKey: requestKey,
        fullRefund: fullRefund,
        approved: false,
        attemptCount: 0
    }
}

-- Approval is a separate back-office act, aimed at the durable record.
expose shape ApproveRefund {
    record: one RefundRecord
}

rule ApplyApproval when ApproveRefund {
    record.approved = true
}
```

What the split does, piece by piece:

- **The generated commit function requires every stored field on the exposed shape, and every stored field on the exposed shape is the committer's.** No suppliability policy is stated anywhere because there is nothing to state it about: `approved` and `attemptCount` are not on the shape the committer touches. The record shape doesn't even need `initially` for them — the materializing rule supplies `approved: false` and `attemptCount: 0` directly in its `from` block, which is exactly the "rule that adds the non-committer-supplied fields." Because a `from` block is a create effect, the values are part of the record's creation commit itself: there is no moment where the record exists without them, and no ordering question for other rules reading them at that commit.
- **Values calculated at arrival are derived properties *on the exposed shape*, and derivation is structurally not suppliable.** A derived property has no stored value, so there is nothing a committer could supply and the generated function excludes it by kind — and the declaration form says so in the language: `fullRefund: boolean = (amount >= order.total)` *states* "calculated," where `fullRefund: boolean initially ...` would only have implied "not yours." On a transient act this costs nothing relative to `initially`: the instance has exactly one evaluation moment — its own commit, against the state of that moment — so the distinction between a starting value and a derivation collapses, and the derived property does the once-evaluated enrichment work. The materializing rule copies it into the record like any other field (`fullRefund: fullRefund` in the `from` block), where it becomes ordinary stored history.
- **Two generation clauses cannot move into the rule, and stay on the record shape** — where, the shape being unexposed, they carry their one settled meaning with no committer in existence to be ambiguous about. `requestedOn: timestamp on create` is commit metadata no rule may assign (README §5), populated at the materializing commit. `processorKey: text initially randomUUID` stays because the generator is `initially`-position-only (README §5) — the system mints the key at the record's creation.
- **The act is `transient`** (README §4, "Transient acts"): the request is an input to the state, not a member of it, and its durable trace is `RefundRecord` — what the system did about it. The transient obligations come along and are satisfied here: nothing durable references the act (the record *copies* its fields), and the materializing rule answers every request by firing on the bare act shape. A spec that materializes conditionally (only valid requests get records) owes the complement — a refusal record or a `never` over the act.
- **`requestKey` is the correlation idiom** (README §4, "Correlating responses to requests"): the transient instance is gone at its transaction's close, so the record copies the caller-minted key, and the caller finds the outcome by it.
- **`channel` lost its default.** The exposed shape says `channel: text`, full stop; the engineer's wrapper supplies `"web"` when the client didn't say — transport mapping is the engineer's code (README §22, "External input"). The defaulted-caller-input case from the previous section moves out of the language entirely.
- **An ordinary (non-transient) variant of the same split exists** for an act that is itself a durable business fact: the act persists carrying only committer fields (plus `timestamp` fields, which state their unsuppliability rather than implying it), and the system-maintained workflow fields live on a companion shape the rule materializes *referencing* the act instead of copying it. The principle is the same either way: `initially` fields appear only on unexposed shapes.

Under the split, every kind of field is explicit by its declaration form, with no marker or policy clause anywhere:

| kind | where it lives | how the form says so |
|---|---|---|
| caller data | stored field on the exposed shape | required parameter of the commit function |
| calculated at arrival | derived property on the exposed shape | `= expression` — not stored, structurally unsuppliable |
| workflow starting value | the materializing rule's `from` block | supplied by the spec's own text |
| minted key | `initially randomUUID` on the unexposed record | generated at the record's creation; no committer exists |
| commit metadata | `timestamp on create` on the unexposed record | commit-populated by definition (README §5) |

Costs and residues:

- **Ceremony.** The record restates the request's fields one for one (`order`, `amount`, `reason`, `channel`, `requestKey`, `fullRefund` — six copied declarations plus six `from`-block lines here). For an exposed shape that is already naturally a durable record with a few `initially` bookkeeping fields — `Member` in `examples/membership/membership.velle` carries `balance`, `visitCount`, and `suspended` this way — the split manufactures a request shape and a copying rule per act. Whether that cost is ceremony or is the honest price of the input/state distinction is the crux of adopting this as *the* answer.
- **The solution leans on rule-side suppliability.** It works because a rule's `from` block may supply a value for any stored field of the shape it creates (`approved: false` above). Any future ruling that restricts which fields a rule may supply at creation must leave create effects whole, or this pattern dies with it.
- **Derivation on the act covers calculation, not staged computation.** A derived property reads the act's fields and the pre-state of its commit. An enrichment that would depend on the *effects* of other rules in the same transaction is not expressible this way — and serving it would take a rule-produced intra-transaction intermediary (`transient` on an unexposed shape), a construct with its own tension against "there is no call graph" (README §4). No current example needs one; the split stands without it.
- **What remains of the question if this is adopted:** whether `initially` (and any system-maintained stored field) on an *exposed* shape becomes a compile error, an advisory, or stays legal with today's optional-parameter behavior. The split makes the discipline *available*; only the check would make the ambiguity structurally impossible.
