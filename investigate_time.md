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

> **Superseded (7/26):** the desugaring claim above was retracted — a capture must *retract* when the instance leaves the refinement, while produced facts must persist, so capture cannot be sugar for `produces`. It is a small new primitive: a value remembered from the current membership's beginning. See *Where mutation leaves capture*, below.

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

shape Quoted    = Shipment where quoteRequestedOn exists
shape Delivered = Shipment where deliveredOn exists

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

**2. A rule reading a same-moment capture is already solved.** `SendQuote` fires `on Quoted` and reads `quotedTotal`, captured at that same transition. If capture desugars to a hidden rule-plus-produces, that's two effects at one moment with a data dependency — and `then`'s design (README §13) already says data-dependency ordering falls out of the input/output graph for free. Existing machinery; nothing new. *(Update 7/26: the desugar framing is retracted — see *Where mutation leaves capture*, below — but the conclusion stands on data-dependency ordering alone: the capture is an input to the rule, so capture-before-rule is implied.)*

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

> **Superseded (7/26):** the once-ness legality rule is dead. Under mutation, exit *retracts* a capture and re-entry *re-captures* — forced by keeping the description true, not silent dishonesty. Capture's semantics are now: present iff currently a member, value as of the moment the current membership began. What survives of this finding is a modeling distinction, not a compiler gate: if past occurrences matter to the business, use produced facts plus `latest(... by ...)`. See *Where mutation leaves capture*, below.

**5. Two compile guardrails fall out.**

First: `on` in capture position must name a refinement *of the containing shape*. Suppose the spec also defined a refinement of `Carrier`:

```
shape PriorityCarrier = Carrier where ratePerKg > 10

shape Shipment {
    ...
    quotedTotal: Money = liveTotal on PriorityCarrier   -- compile error
}
```

A `Shipment` is never a member of a `Carrier` refinement, so there is no moment of *this shipment* entering `PriorityCarrier` to capture at. Same base-shape rule that already governs composing refinements with `and`/`or` (README §8).

Second: the capture anchor must not name a schedule:

```
shape Shipment {
    ...
    dailyTotal: Money = liveTotal on Daily   -- compile error
}
```

A scheduled anchor would be a *repeating* capture, which can't be a scalar property. A recurring snapshot is already expressible as `each Carrier produces MonthlyRateReport ... on Daily` (README §14/§15). This reuses the existing prefix-`on`-vs-postfix-`on` distinction: data-driven moments can anchor captures, scheduled ticks can't.

---

# Continuing (7/26): mutation, re-enterable refinements, and the state/effect boundary

The capture question above led to a larger one: does Velle demand immutability at all, what do re-enterable refinements do to the self-consistency of a Velle description, and what does that imply is *missing* when compiling from the high-level description to runtime concerns? Several findings below supersede findings above (marked inline where they do).

## Does Velle demand immutability?

No — and it never did. Refinements are *pure predicates over current data*; derived properties "recompute from current data"; §16's `invoice with payments += payment` is an in-place update. The language quantifies over *now*. Many real systems mutate records in the database, and nothing in Velle's principles forbids describing them.

Immutability was never a language principle. It's a requirement of three specific constructs — `produces`-as-guard, capture, and `latest` — and the reason is precise: those are exactly the constructs that need **memory**. That observation organizes everything below.

A pure-predicate description is a complete description only of systems whose behavior depends solely on current state. Re-enterable refinements are precisely where that stops being true: two systems with *identical current data* can carry different obligations depending on history (was this invoice overdue before?). At that point the Velle text, read as predicates-over-now, underdetermines the system it claims to describe — unless the history that behavior depends on is itself represented.

## What re-enterability exposes in the current README

**1. §10's mechanism-independence claim quietly assumed monotonicity.** The rule contract says the effect fires "exactly once per newly-satisfying instance," and claims the detection mechanism — write-time check, scheduled sweep, event stream — is a free compile-time choice. Test with a transient: an invoice becomes `Overdue` at 2am, is paid at 3am, the sweep runs at 4am. The event stream fires the late-fee rule; the sweep never sees the membership. The contract as written says the transient *did* become a member, so strictly the sweep is a *wrong* implementation — mechanism freedom was only ever true for refinements an instance enters once and never leaves. Worse: whether a transient counts is a *business* question the Product Owner has an opinion about ("they paid before we noticed — no fee" vs. "overdue is overdue"), and the spec currently can't record that opinion — a compile-time mechanism choice silently decides it. That is exactly the pollution (business meaning leaking into compilation) the language exists to prevent.

**2. §10 and §11 disagree about what "once" means.** The prose says once per *newly-satisfying* (per entry). The `produces` guard desugars to `not exists Receipt for this` — once per *lifetime*. For monotone refinements the two coincide, which is why the contradiction has been invisible. Re-entry splits them: the prose says fire again; the guard says don't. Note how §11's own guard-granularity example (`AccountFlag`) escaped this — by reifying each flagging as a *new instance*, so "re-entry" never happens to any single instance. The existing idiom was already routing around re-entry by turning occurrences into facts.

**3. Exit events are missing.** With mutation, leaving a refinement is a business-meaningful occurrence ("no longer delinquent → restore service"), and Velle has no `on leaving R`. The workaround — react to entering the complement — is wrong: a newly created, never-delinquent account also "enters" `Compliant`. *Became compliant* and *was always compliant* are indistinguishable from current state alone. Same root cause: no memory.

## Thought experiment: un-issuing an invoice

Take `shape Issued = Invoice where issued exists` with `totalWhenIssued: Money = sum(lineItems, amount) on Issued`. A user deletes the `issued` date. From the description's perspective, that invoice has no `totalWhenIssued`. Can the runtime always be brought back into agreement with the description ("made true")?

Yes — up to the effect boundary. Stratify everything downstream of `Issued`:

- **Derived state** (`currentTotal`, refinement memberships, anything `= expr`): automatically true, always. Nothing is retracted; it's *recomputed* — being a function of current state is the definition of the layer.
- **Captured state** (`totalWhenIssued`): retractable by decision. "An unissued invoice *has no* issued total" entails that un-issuing deletes the capture — coherent, and the right semantics. But note this is a semantic choice: exit retracts, re-entry re-captures — which quietly turns capture into "value as of the most recent membership entry." (This becomes capture's official semantics — see below.)
- **Effects** (produced shapes and the external actions they stand for): here true-making by retraction **fails**, and no runtime cleverness fixes it. If `rule SendReceipt on Issued produces Receipt` already fired, deleting the `Receipt` makes the description true and the record a lie about the world — the email *was sent* (and on re-issue, the vanished guard sends a second one). Keeping it leaves the description asserting evidence for a membership that no longer holds. An effect is not a function of current state — it's history, and history can't be recomputed. The only coherent move is the one accounting discovered: don't erase, *append a compensating fact* (a voided-receipt record, a credit memo). README §17's "Reversal" bullet, arrived at as logical necessity rather than policy preference: irreversible effects + a description that must stay true ⇒ compensation, not deletion.

**A spec is fully coherent under un-issuing iff every rule downstream of `Issued` either hasn't fired or has a declared compensation.**

### Runtime cost of truth maintenance

The cascade through *state* is real but is entirely a **materialization choice** — a compile concern, invisible to the description, properly so. Computed-on-read: retraction costs nothing (nothing stored), reads pay. Materialized: the un-issue write pays O(dependents) — this is incremental view maintenance, known technology. Async: writes are cheap, but stored state contradicts the description in a window. All three compile the same Velle. One asymmetry: the capture *must* be stored — non-derivability from current state is its entire point — so it is the one thing genuinely deleted, but it's one value on one record.

Two sharpenings:

1. **The "moment where the record is inconsistent" (issued deleted, capture still present) is a missing atomicity contract, not an inevitability.** Transactions make intermediate states unobservable. But nothing in Velle says which clusters of state must change as *one observable step* — and a PO plausibly cares ("no report may ever show an unissued invoice with an issued total"). Missing vocabulary: the granularity of observable consistency.
2. **The genuinely hard cascade is the effect layer, not the millions of derived values.** Un-issuing flips memberships on related records whose rules already fired, whose effects already escaped. The state cascade is a performance problem (solvable); the effect cascade is a semantics problem (compensation policy — not solvable by machinery).

## The punchline: mutation relocates the ledger, it doesn't eliminate it

If any rule, guard, capture, or `latest` depends on history, and the store mutates in place, a correct compilation *must synthesize* the history the spec didn't describe: an entry/exit event log, occurrence identities for guards to scope to, write-path transition detection (sweeps are incorrect for non-monotone refinements, per above), snapshot-consistent predicate evaluation, and an atomic check-membership-then-write-evidence step (the `produces` guard is exactly-once only under an atomicity assumption stated nowhere). This is the industry's actual trajectory: mutable rows grow audit tables, triggers, and CDC streams precisely because history-dependent behavior over mutating stores forces the event log back into existence — unnamed.

So the design axis is not *immutability: yes/no*. It is: **is history part of the description, or part of the implementation?** Relocated into the implementation, the ledger exists but the spec can't name it — `why`/provenance can't cite it, POs can't query it, and its retention (a real business concern: audits, GDPR) is decided by nobody.

Velle's coherent permissive position: **mutation is fine wherever no construct remembers; wherever history is load-bearing, it must be reified or the refinement provably monotone.** And monotonicity isn't provable from predicates alone — `issued exists` is enter-once only if `issued`, once set, is never unset. That's a **correction policy**, and it's PO vocabulary, not runtime vocabulary: "a quote is never edited — you issue a new one; a customer's email is just corrected in place." Nobody currently gets to say that in Velle, yet it's the fact from which the compiler would derive which refinements are monotone, which rules can use sweeps, where hidden history must be synthesized, and what transients mean.

Corollary: when a deletion has downstream meaning, the language's own constructs steer it back into being a fact. If un-issuing is a thing the business does, it's an *event* ("the invoice was retracted") with reactions ("notify the customer, void the receipt") — modeled as mutation, all of that lives in hidden cascade machinery the spec can't see; modeled as a fact (`InvoiceRetraction for invoice`), the cascade itself becomes ordinary describable rules.

## Where mutation leaves capture (`expr on Refinement`)

**1. Capture survives with revised semantics.** Not "evaluated once at entry, fixed forever" but: *present iff the instance is currently a member of R; its value is the expression as evaluated at the moment the current membership began.* Absent before entry, fixed during membership, retracted on exit, re-captured on re-entry. Fully coherent under mutation — a function of (current membership, when this membership began) — so truth maintenance always has a well-defined target. The original "once and forever" version is the special case where R is monotone. This also makes capture *transient-safe*: a membership that comes and goes leaves no anomaly.

**2. The once-ness legality rule (finding 4, above) is dead.** Re-entry isn't illegal or silently dishonest — it's *forced* by keeping the description true. What remains is a modeling distinction, not a compiler gate: `expr on R` means only the *current* membership's value matters; if the business cares about past occurrences ("show every quote we sent"), that was never a property — it's produced facts plus `latest(... by ...)`. The PO's choice of meaning.

**3. The desugaring claim is retracted.** The proposal above claimed capture is "no new concept — sugar for a hidden `rule ... on R produces` evidence shape." The effect boundary kills that: produced facts are effect-layer (durable, unretractable, deletion-is-lying); the capture is state-layer (*must* retract on exit). You can't build a thing that must be deleted out of a thing that must never be. Capture is a genuinely new primitive — the first construct in Velle whose stored state tracks membership. The honest answer to this doc's original question: yes, one small new concept enters the language — *a value remembered from the current membership's beginning* — strictly more than a predicate, strictly less than history.

**4. `on R` was never one mechanism.** The same trigger drives two constructs with opposite lifecycle disciplines, split exactly along the state/effect boundary:

| | `expr on R` (capture) | `rule ... on R produces E` |
|---|---|---|
| layer | state | effect |
| on exit | retracts | persists — deletion would lie about the world |
| on re-entry | re-captures freely | doesn't re-fire (lifetime evidence guard) unless evidence is scoped to a reified occurrence |
| when wrong | recompute | compensate (Reversal) |

This table is the yield of the investigation: the state/effect stratification is the load-bearing structure, and every question encountered — retraction, re-entry, once-ness, un-issuing, transients — divides cleanly along it.

## Worked example: mutating `issued` and the effect boundary

The table above, dramatized — the doc's own `Issued`/`Invoice` example plus one notification rule:

```
shape Invoice {
    lineItems: many LineItem
    issued: Date?
    totalWhenIssued: Money = sum(lineItems, amount) on Issued
}

shape Issued = Invoice where issued exists

shape IssuedNotification {
    invoice: one Invoice
    total: Money
    sentOn: DateTime
}

rule NotifyCustomer on Issued produces IssuedNotification {
    IssuedNotification from {
        invoice: this
        total: totalWhenIssued
        sentOn: now
    }
}
```

The timeline:

**Jan 5** — `issued` is set. Capture fires: `totalWhenIssued = $500`. Rule fires: email sent, and `IssuedNotification { total: $500, sentOn: Jan 5 }` now exists as the evidence guard.

**Jan 8** — a user deletes `issued`. Truth maintenance does what it can: the invoice leaves `Issued`, `totalWhenIssued` retracts. But `IssuedNotification` is effect-layer — the email is in the customer's inbox — so it persists. The spec now contains evidence of a notification *about a membership that doesn't hold*, carrying a `total: $500` the invoice no longer has. Tolerable as history — but nothing in the language marks it as history; it's a fact sitting there, indistinguishable from live coherent state.

**Jan 20** — a line item was added in the meantime; the invoice is re-issued. Capture re-fires correctly: `totalWhenIssued = $650`. The rule is where mutation draws blood, because there are only two possible behaviors and **both are wrong**:

- **Keep the evidence** (what the guard `not exists IssuedNotification for this` actually does): the rule is suppressed. The customer is never told about the $650 invoice — they act on a $500 email for an invoice that now says $650. The runtime is perfectly "consistent" by the guard's definition and the business outcome is broken.
- **Delete the evidence on Jan 8** (to make the description true when membership was retracted): re-issue correctly sends the $650 email — but the system now has no record the $500 email ever went out. The customer holds an email the system says was never sent. `why`/provenance is broken, and if the customer disputes ("you told me $500"), the spec has nothing to point at.

Stale suppression or falsified history — the whole effect-boundary dilemma in one rule, and current Velle can neither express a preference between them nor acknowledge the choice exists. The guard's real problem: it's scoped to the invoice's *lifetime* when the business meaning is per-*issuance* — and mutation gives it nothing better to scope to, because the issuance-occurrence isn't a thing in the spec; it's a date field that got overwritten.

Which is exactly the reification fix from the punchline section above — make the occurrence a fact and every problem dissolves without deleting anything:

```
shape Issuance {
    invoice: one Invoice
    issuedOn: Date
    total: Money
}

rule NotifyCustomer on Issuance produces IssuedNotification for issuance {
    IssuedNotification from {
        issuance: this
        sentOn: now
    }
}
```

Two `Issuance` facts, two notifications, each guard scoped to its own occurrence; the Jan 5 notification stays true history ($500 *was* sent, about *that* issuance); un-issuing, if the business does it, becomes its own fact with its own reactions. The original spec isn't illegal Velle — that's the point. It compiles, reads naturally, and quietly contains a business-breaking ambiguity that only surfaces the day someone deletes `issued`.

## Open questions

All on the effect side; capture carries only the atomicity obligation.

- **Correction policy per property** — the missing PO-level declaration ("never edited — reissued" vs. "corrected in place") from which refinement monotonicity, legal detection mechanisms, and hidden-history synthesis would all be derived.
- **Transient observation intent** — does a membership that begins and ends unobserved obligate a rule's effect? A business question with no syntax; currently decided silently by mechanism choice.
- **Exit events** — no `on leaving R`; entering the complement conflates *became* with *always was*.
- **Occurrence reification for per-entry rules** — a guard needs something to scope evidence to, and mutation provides nothing; per-entry rules on re-enterable refinements still require occurrence facts (the `AccountFlag` pattern).
- **Atomicity granularity** — which clusters of state (e.g. membership + its captures) must change as one observable step.
- **Retention of synthesized history** — if the compiler must keep hidden history for guards/captures, how long it's kept has business meaning (audit, GDPR) and no home in the spec.

## Status

Capture's settled semantics: present iff currently a member of the anchoring refinement, value as of the moment the current membership began — a new state-layer primitive, distinct from effect-layer `produces`. Commitments that survive from the first phase of the investigation: transitive value-freezing (finding 1, including the capture-vs-ledger-reconstruction distinction), capture-implies-presence narrowing (finding 3), and the two guardrails (finding 5). Superseded along the way (marked inline): the `produces` desugar claim and finding 4's once-ness legality rule.

Not yet settled: whether the capture belongs on the base shape (as written here) or on the refinement itself (which would give presence typing for free but requires refinements to grow bodies) — plus everything under Open questions.
