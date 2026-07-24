# Stress test: time — the "as of a moment" question

In Velle a value is described, not computed at a point in time — so a derived value like `Order.total: sum of items.amount` isn't a number, it's a fact that is implicitly *as of* some moment. When the things it depends on change over time (line items arrive, transactions post, an invoice is issued), the value only means something relative to a moment. This doc works the one question that forces: **as of *whose* moment** is a derived value read — and how much of that, if any, has to surface in the language versus staying a deferred runtime detail.

Method as everywhere else: don't invent syntax speculatively; resolve what a worked case forces. Where a spelling appears below (`as-of`, `record`/entity), treat it as *illustrative shorthand for a semantic claim*, not proposed surface — the section that killed it says so explicitly.

---

## The forcing case

```
shape Order {
    items: many LineItem
    total: sum of items.amount
}
```

Line items are still arriving. What is `total`?

Declaratively it was never a single number — it's a function of moment: `total as-of T` = the sum of items whose occurrence ≤ T. Nothing mutates; the *set* of items grows, but each `LineItem` is an immutable fact with its own moment. So "every value is implicitly as-of a moment" is just true. The real question is the next one: **as-of *whose* moment?**

---

## First answer: as-of = the observer's own moment

One rule appears to cover every case:

> **As-of = the observer's own moment.**

- A **live query** ("total right now") observes from the present → `order.total` = sum as-of now → drifts up as items arrive.
- A **persisted fact** observes from *its own occurrence*, permanently → an `AuditLogEntry` born at T that reads `order.total` gets the sum as-of T, and since the fact's moment never moves, that read never drifts → it's a snapshot.

Same rule both times. The "freeze" is not an operation you perform — it's the *consequence* of a fact having a fixed moment and derived reads being relative to the reader's moment. This is "nothing is frozen because nothing was ever live," made mechanical.

**Apparent corollary (later shown false):** what you want frozen you make an own derived property (pinned to your moment); what you want live you leave as a relationship traversal (read fresh by whoever asks). Freeze/live = placement, no keyword.

---

## Push 1 — `Account.balance` breaks "birth-for-records"

The structural twin of the Order:

```
shape Account {
    transactions: many Transaction
    balance: sum of transactions.amount    -- own derived property, growing many-relationship
}
```

Field-for-field identical to `OrderSnapshot.total` — an own derived property that is a `sum of` a growing relationship. As-of-observer says an own-property reads as-of the fact's birth. Account was born at T0 (opening). So `account.balance` = sum as-of T0 = **the opening balance, forever** — pinned to the one moment it is guaranteed meaningless.

And you can't demote it to a traversal: `balance` must be an own-property (refinements query it, `where balance < 0`; other facts reference it) **and** it must be live. So:

- **"own-property → frozen, traversal → live" is false.** Two structurally identical shapes want opposite temporal semantics, and nothing in their structure distinguishes them.

**Why the doc's sample hid this:** every example in `example_rules.md` — `AuditLogEntry`, `LedgerEntry`, `OrderSnapshot` — is a **record**: a fact a rule produces *to remember a moment*. For records, as-of-birth is correct and looks universal. `Account` is the first **entity**: a thing that *is*, persists, accumulates, lives in the present. The sample was 100% records, which is why the single rule looked complete.

**Provisional rescue:** the missing distinction is **things that are** (entities, as-of *now*) vs. **things that remember** (records, as-of *birth*). Put it once per shape (a shape *kind*), derive as-of from the kind, never write it per field. Aligned with a real ontological split, not a per-field modifier.

**Also turned up by this push:**
- **`now` relocates, doesn't dissolve.** A live "as of now" observes from a moment that is no fact's occurrence. So there is an irreducible `now` — but cornered: `now` is never a *value stored in a field* (that illusion is dead); it is the **deictic frame of a live observation**, the "here" of asking. A better result than killing it — it says what `now` always was.
- **Explicit as-of has real depth.** "Each customer's total *as of the moment that customer was flagged*" makes the moment **per-row and correlated** — a temporal *join*, not a literal. Still opt-in and rare, but if it ever gets syntax it isn't trivial syntax.

---

## Push 2 — `Invoice` breaks the shape-level binary entirely

Is record-vs-entity a clean per-shape binary, or can one shape straddle it?

```
shape Invoice {
    lineItems: many LineItem
    total:         sum of lineItems.amount        -- LIVE while drafting
    totalAsIssued: total as-of issuedOn            -- FROZEN at issuance   (spelling illustrative)
}
```

While drafting, `total` must be live. Once issued, the amount actually billed must freeze — a sent invoice can't drift because someone later edits a line. So **one shape** needs a live property **and** a frozen one, *simultaneously*. Impossible if record-vs-entity is a property of the shape. The binary is dead.

And the freeze point — `issuedOn` — is neither birth nor now. It's a **third moment**, a domain event mid-life. "Birth-for-records / now-for-entities" never had a slot for it.

### What the straddle forces

Temporal-kind is not a property of the **shape**. It's a property of the **read**. A derived read is either **live** (tracks its source) or **pinned** to a referenced occurrence. "Record" vs "entity" was never a kind of thing — it was a *statistical accident of which reads a shape happened to contain*: all-pinned looks like a record, all-live looks like an entity, `Invoice` has both and looks like neither. So the Push-1 rescue (declare the shape's kind) **fails** — wrong granularity. The unit is the read.

### Why this is not `capture` reborn

Per-read freezing was rejected earlier as capture-in-disguise. It isn't, *if* the freeze is spelled as a reference to a **named occurrence** (`issuedOn`) rather than a clock read (`now`). That is exactly the legitimate, timeless thing the dissolution established. `capture`/`now` was illegitimate because it read a clock; pinning to an occurrence is legitimate because it names an event — same reason `loggedOn: when this occurred` was fine. The unit moved from shape to read but never left the world of occurrence-references.

### The default, and the reversal it forces

If freezing is the marked case, the default is **live** — and that is the *principled* default, because "a derived property is timelessly true whenever evaluated" is the original Velle bet. A frozen-default would contradict the language's own axiom; a live-default *is* the axiom. `Account.balance` confirms it from the other side: frozen-default silently returns the opening balance (a wrong answer, no error); live-default's failure mode is a snapshot you forgot to pin, which drifts and fails loudly in testing.

This **reverses** the earlier "snapshot for free" claim. Freezing is *not* free — it is the marked, explicit case. Paid only where a source is actually mutable.

### Why every example fooled us — the real tell

- `LedgerEntry.baseAmount: payment.amount times rate` — Payment and the historical rate are **immutable facts**; live and pinned give the identical answer, so no pin is needed and it is still correct.
- `AuditLogEntry.loggedOn: when this occurred` — a reference to its own moment; a moment doesn't drift.

Every example "froze for free" **because its sources were immutable** — live *equals* frozen when nothing changes. The record/entity split and the "snapshot for free" intuition were artifacts of a sample with no mutable sources. The instant a source is genuinely mutable — `Order.total`, `Account.balance`, `Invoice.total` — the choice becomes real, unavoidable, and **per-read**.

---

## Push 3 — is `as-of` a construct? No. The time-variance isn't on the scalar.

Across the pushes above `as-of` hardened into a spelling — `total as-of issuedOn` — as if it were an operator applied to a scalar read to set its moment. **Retracted as syntax.** Nothing forced that spelling; it was borrowed from temporal databases, which is the "syntax not yet forced, don't guess" trap.

The operator is probably *wrong* even if something surfaces, because the scalar is not where the time-variance lives. In `total: sum of items.amount`:

- `.amount` on any given `LineItem` is immutable — a LineItem is a fact.
- The **only** time-varying input is *which* items are in the relationship — the **membership of the `many`**.

So `total` needs no moment. The **relationship** does. Pin "the line items as of issuance" — the members whose occurrence ≤ that moment — and `total` reads live off an immutable set and freezes *for free*, exactly as `LedgerEntry.baseAmount` did off immutable sources. Freezing was never a scalar operation; it is **inherited from a set that stopped changing.**

Candidate primitive, therefore: **membership of a relationship as of an occurrence** — one thing, from which every derived scalar inherits freeze-or-live structurally, with *no* per-read operator. An `as-of` on a number would be derived convenience at best, possibly nothing. And "when a fact entered a relationship" is itself just another occurrence — so this stays entirely inside the occurrence concept.

---

## The reframe — the spec carries no moment; evaluation supplies it

The whole discussion above treated "as of a moment" as a *language* question: does the grammar need temporal machinery? Step back and it isn't one. Look at the two fields again:

```
total:         sum of items.amount        -- true whenever checked
totalAsIssued: total as-of issuedOn       -- true whenever checked   (spelling still illustrative)
```

Both are **timelessly true statements**. Neither says "evaluate me at time T." The moment-dependence lives entirely one level down: given real data and a moment of evaluation, each yields a number, and the numbers differ by *when* you look. The *shape stays true regardless of whose moment* — only the resulting **values** differ with time.

So the moment is never in the spec. **Reading is the "as of."** Every derived value is automatically "as of whenever it is evaluated," for free, because a relationship stated over facts is true whenever checked. The language does not need to *say* "as of" — evaluation already supplies a moment (normally the present).

That collapses "how much surfaces in the language" toward **nothing**, with one residue: a field may reference *a specific occurrence* instead of the implicit reading moment. And referencing an occurrence is referencing a fact — which the language already does. So `total` and `totalAsIssued` differ only in *which moment they name*: `total` names the reader's, implicitly ("current membership"); `totalAsIssued` names `issuedOn`, explicitly, as data. Same construct — a relationship — one pointing at the implicit deictic moment, one at a named occurrence. No temporal-query machinery falls out. This is the "leaves nothing over" result reached head-on rather than by exhaustion: the earlier "live vs. pinned" finding is just *implicit-reader-moment vs. named-occurrence*, and both are ordinary moment-agnostic statements.

---

## The principle underneath — what is forced is *imperative* mutation's absence, NOT immutable facts

A first pass here claimed the reframe rests on immutability and that Velle "already took the immutability stance by omission." That overclaims — it conflates two mutations and two immutabilities.

- Two **mutations**: *imperative* (`payment.amount := 90` — overwrite at execution time) vs. *declarative* (assert a new fact: "`payment.amount` **is** 90 **as of** T2" — no verb, no execution, just another stated truth).
- Two **immutabilities**: (i) *no information is destroyed* — the past stays addressable (append-only); (ii) *a fact is a point* — one value forever, not a timeline.

What the grammar's silence actually forbids is only **imperative** mutation — no verb to overwrite. That is genuinely forced, the no-`if` / describe-don't-execute family. It says nothing about (ii). And what the core bet ("timelessly true whenever evaluated") actually requires is only **(i)**: whatever the spec can ask about must stay answerable — the demand-driven runtime obligation. **(i) is compatible with mutation.** (ii) was smuggled in.

Reify moment and a fact becomes *value-as-a-function-of-moment*:

```
payment.amount as of T1  =  100
payment.amount as of T2  =   90
```

The fact genuinely changed — real mutation, denying (ii) — yet every read stays timelessly true, because "whenever evaluated" now carries a moment that disambiguates; and the change was *asserted*, not executed, so describe-don't-execute is intact. Coherence never needed (ii). Sense-(ii) immutability is entailed **only if you decline to reify moment** — then there is no coordinate to disambiguate a changed value, and the sole coherent world left is "change = a new, distinct fact." **Immutability is required iff we do not address moment/as-of**, not otherwise.

So it is a **fork, not a foundation** — and it decides *where the complexity lives*:

- **Path A — punt on moment.** No reified moment; facts are points (sense-(ii) immutable); change is a new sibling fact plus a derived "current" (latest-wins / sum). *Nothing temporal surfaces in the language* — which is exactly the "spec carries no moment, leaves nothing over" reframe above. **That reframe silently assumed Path A.** It is true *given Path A*, not path-independently.
- **Path B — reify moment.** A property may carry a moment-varying value directly; `as of T` reads the value it held then; mutation is first-class and declarative. This puts moment *into the language* — the very machinery the reframe congratulated itself for avoiding.

Immutability is the **price of Path A**, not a property of Velle. What survives as forced: *no imperative-mutation verb* (keep it, it's real). What dies: *"immutable facts is a settled stance taken by omission"* — it is one arm of the open moment fork.

Runtime, either path: obligated only to keep *answerable whatever occurrences the spec actually references*. A spec that only ever reads "now" imposes no history requirement; the moment a field pins to a past occurrence (`totalAsIssued`) or provenance asks "which rule, when," that past must survive. Demand-driven, never a blanket "store everything." The language says what is *true*; the runtime decides what to *keep*.

---

## Where it stands

The load-bearing question mostly **dissolves** rather than resolving into machinery:

- The **spec carries no moment.** A shape is timelessly true regardless of whose moment its fields are read from; only the runtime *values* differ with time. Evaluation supplies the moment — reading *is* the "as of."
- So "how much temporal machinery surfaces" ≈ **nothing**, with one residue: a field may name a **specific occurrence** instead of the implicit reading moment. That is just referencing a fact.
- **live vs. pinned** = *implicit reader-moment vs. named occurrence* — both ordinary moment-agnostic statements, not two kinds of read needing a modifier. The choice is **per read, not per shape** (`record`/`entity` is not a Velle concept — an `Invoice` holds both a live `total` and a pinned `totalAsIssued`).
- The freeze, where wanted, is **inherited structurally** from a relationship membership pinned to an occurrence — not applied by a scalar operator. Whether even *that* needs surface is the remaining open hunt below.
- **The whole reframe assumed Path A of a moment fork** (punt on reifying moment → facts immutable, nothing surfaces). What is actually forced is only that Velle has **no imperative-mutation verb**; sense-(ii) immutable *facts* are the *price of Path A*, not an entailment. Path B (reify moment, mutable moment-varying facts, declarative) is live and un-refuted.

Died along the way:
- "own-property → frozen, traversal → live" (Push 1).
- record/entity as a shape-level kind (Push 2).
- "snapshot for free" (Push 2) — freezing is not free; but it is *structural*, not an explicit scalar operator (Push 3 / reframe).
- `as-of` as a scalar construct (Push 3).
- "as of a moment" as a *language*-level question at all (reframe) — it is a runtime coordinate the spec never names.

Relocated, not dead:
- **`now`** — dissolved as a stored value; survives only as the deictic frame of live observation, i.e. the moment evaluation supplies by default.

## Open — the next hunts

1. Can every freeze-point be named as **"the membership of some relationship as of some occurrence"**? If yes it closes with zero new scalar syntax — freezing is always structural. A freeze you want that *no* relationship-membership captures — a moment to pin to that no occurrence marks — would force a genuine new construct, and can't be answered before "what kinds of occurrence are there."
2. **Immutability-by-omission wants to be a stated principle** beside "no `if`" / "no rule chaining" — it is currently only implicit in the grammar's silence. Writing it down (and stating the runtime's minimal obligation: preserve exactly the history the spec references) is its own task.

Parked, and downstream of this:
- **What kinds of occurrence there are** (fact origination, entering a refinement, schedule tick, external assertion) and whether they are one kind — a fact *entering a relationship* is now on that list.
- **Reference vs. reaction** — timestamps refer to occurrences, triggers react to them; one concept consumed two ways, or two things.
- **Provenance and the executable-vs-spec fork** — both need occurrences to persist and accumulate; the immutable-by-omission / demand-driven-runtime picture is exactly the world they require.
