# Time & Immutability

Current state of velle (as of 7/25) - describes "facts" of a system using `shape` and `rule`. These definitions are true regardless of _when_ they occur. However, the answer at runtime to these statements could be different depending on _when_ a definition is "executed" given real data.

## Example

```
shape Invoice {
    lineItems: many LineItem
    issued: Date
    currentTotal: sum(lineItems.amount)
    totalWhenIssued: sum(lineItems.amount) "when issued"
}
```

An `Invoice` has two "total" values: a "current" total and a "total" that is the sum of the `amount` of all the `lineItems` at the time the `Invoice` was `issued`.

## Problem

Velle currently doesn't have an explicit way to distinguish these.

## Hints at a Solution

Velle is intended to avoid having to solve "runtime" computer science problems like variables and functions. The language is intended to capture a dialog between a Product Owner and an Engineer.

The difference of `currentTotal` vs `totalWhenIssued` is a Product Owner concern, so it is appropriate to capture using Velle.

`shape` often _looks_ like it can describe immutable ledger-like models that capture the state at a given time. Also, `currentTotal: sum(lineItems.amount)` looks like it describes a "running calculation". 

It is not clear that Velle actually describes the difference between the two - and it's important enough to warrant some way to describe the difference.

## Questions

- Does answering this question rely on some notion of "committing" or "saving" some data at some point in time where values are "captured"?
- How much does that start to bring in concepts of data storage, where we might pollute Velle with runtime execution concerns?

## Proposed direction: `expr on Refinement` capture

`currentTotal: sum(lineItems.amount)` reads intuitively as a live calculation and should stay exactly that. What's missing is a way to capture a calculation at a moment in time — and the whole design decision is what that moment *anchors to*. Two candidates:

1. **An arbitrary point in time** — `sum(lineItems.amount) as of issued`. Reads nicely but is a trap: answering it requires reconstructing what `lineItems` contained at any past date, which means full history storage — exactly the runtime concern this doc worries about, and the same implicit-timestamp assumption already removed from `latest`/`first`.
2. **A moment the system witnesses** — the instant a refinement's predicate becomes true. Capturing at a witnessed transition needs no history at all; it's the same moment a `rule ... on` already fires at.

So: anchor capture to refinements and reuse `on`, which already means "at the moment of membership" everywhere else in the language:

```
shape Issued = Invoice where issued exists

shape Invoice {
    lineItems: many LineItem
    issued: Date?
    currentTotal: Money = sum(lineItems, amount)
    totalWhenIssued: Money = sum(lineItems, amount) on Issued
}
```

Read aloud: "totalWhenIssued is the sum of line item amounts, on becoming Issued." The expression is evaluated once, at the moment the instance enters the refinement, and is a fixed fact thereafter. Before that moment the property is simply absent — an unissued invoice *has no* issued total.

This answers the doc's first question directly: yes, it relies on a notion of committing a value at a moment — but Velle already has that notion, and it's `produces`. `expr on Refinement` desugars into a hidden `rule ... on Refinement produces <evidence shape>` whose field values are computed at production time — produced shapes are already immutable facts recorded at a moment. No new storage concept enters the language, which answers the second question: nothing here is a runtime execution concern.

### Variants considered and set aside

- `sum(...) when issued` — closest to natural speech, but overloads the *field* `issued` as an *event* ("when issued became present"), a pun the compiler would have to bless field-by-field. Anchoring to a named refinement is one rule with no special cases.
- A leading keyword, `captured sum(...) on Issued` — held in reserve if bare `expr on Refinement` collides with postfix schedule-`on` in the grammar, or if captures should be visually scannable in large shapes.
- The ledger form — `sum(lineItems where addedOn <= issued, amount)` — needs zero new syntax *when the facts carry dates*, and remains the right answer for ad-hoc historical questions (see finding 1 below for why it is a genuinely different concept, not a redundant spelling).

## Stress test: freight shipping with fluctuating fuel surcharges

A deliberately layered example: captures over live derivations over `latest(...)` on *related* shapes, with a rule on the same refinement a capture anchors to.

```
shape Carrier {
    name: text
    ratePerKg: Money
    surcharges: many FuelSurcharge
    currentSurcharge: decimal = latest(surcharges by effectiveOn).rate
}

shape FuelSurcharge {
    rate: decimal
    effectiveOn: Date
}

shape Package {
    weight: decimal
}

shape Shipment {
    carrier: one Carrier
    packages: many Package
    quoteRequestedOn: Date?
    deliveredOn: Date?

    liveTotal: Money = sum(packages, weight) * carrier.ratePerKg * (1 + carrier.currentSurcharge)

    quotedTotal: Money = liveTotal on Quoted
    billedTotal: Money = liveTotal on Delivered
    priceDrift:  Money = billedTotal - quotedTotal
}

shape Quoted    = Shipment where quoteRequestedOn is some
shape Delivered = Shipment where deliveredOn is some

rule SendQuote on Quoted produces QuoteSent {
    QuoteSent from {
        shipment: this
        amount: quotedTotal      -- reads the capture made at this same moment
        sentOn: now
    }
}
```

### Findings

**1. Capture freezes the value, transitively — and backdating makes that observable.** `quotedTotal` reaches through `carrier.currentSurcharge`, a `latest(...)` over a *different shape's* collection. The capture must close over the entire live dependency graph at that instant — a surcharge row added to the carrier later must not move `quotedTotal`. The sharp edge: if someone later adds a *backdated* surcharge (`effectiveOn` before the quote date), the captured value and the ledger reconstruction (`latest(surcharges where effectiveOn <= quoteRequestedOn by effectiveOn)`) now disagree. That's not a bug — it's two different Product Owner concepts: *what we told the customer then* vs. *what we now believe was true then*. The capture form is the first; the ledger form remains the only way to say the second. This is the crispest argument that both forms need to exist — they are distinct concepts, not one syntax with two spellings.

**2. A rule reading a same-moment capture is already solved.** `SendQuote` fires `on Quoted` and reads `quotedTotal`, captured at that same transition. If capture desugars to a hidden rule-plus-produces, that's two effects at one moment with a data dependency — and `then`'s design (README §13) already says data-dependency ordering falls out of the input/output graph for free. Existing machinery; nothing new.

**3. Captured properties are inherently optional, with a narrowing obligation.** A shipment delivered without ever being quoted has `billedTotal` but no `quotedTotal`, ever — nothing orders the two moments. So a capture's type is effectively `Money?`, and `priceDrift` is absent unless both captures exist. Compiler obligation: `this is Quoted` should narrow `quotedTotal` to present, even though `Quoted`'s predicate never mentions it — capture-on-R implies present-when-R. Same family as the existing `.`-vs-`?.` narrowing.

**4. Re-entry resolved as a legality rule: once-able moments only.** Customer adds a package after quoting; sales wants a revised quote. `Quoted` never lapses (`quoteRequestedOn` stays present), so there's no re-entry to re-fire the capture — and that's correct: a moment that can happen *more than once* was never a captured property. It's a produced shape:

```
rule IssueQuote on QuoteRequested produces Quote for request {
    Quote from { shipment: this.shipment, amount: liveTotal, quotedOn: now }
}

-- on Shipment:
quotedTotal: Money? = latest(quotes by quotedOn).amount
```

`expr on Refinement` is legal exactly where a `produces`-style once-per-instance guard holds. Repeatable moments must be shapes plus `latest(... by ...)` — the language pushes toward the honest model rather than silently re-capturing.

**5. Two compile guardrails fall out.** `on` in capture position must name a refinement *of the containing shape* — `liveTotal on PriorityCarrier` inside `Shipment` is incoherent. And it must not name a schedule: `liveTotal on Daily` would be a repeating capture, which can't be a scalar property — a recurring snapshot is already expressible as `each Carrier produces MonthlyRateReport ... on Daily` (README §14/§15). Reuses the existing prefix-`on`-vs-postfix-`on` distinction: data-driven moments can anchor captures, scheduled ticks can't.

### Status

The syntax held up under the stress test. Semantic commitments to carry into the README if adopted: transitive value-freezing (finding 1), capture-implies-presence narrowing (finding 3), once-able-moments-only legality (finding 4), and the two guardrails (finding 5). Not yet settled: whether the capture belongs on the base shape (as written here) or on the refinement itself (which would give presence typing for free but requires refinements to grow bodies).
