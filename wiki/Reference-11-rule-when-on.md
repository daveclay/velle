# 11. `rule ... when ... on ...`

A top-level reaction attached to a refinement — replaces `if`/`else` branching and imperative "then do X" sequencing for state-driven behavior. The header separates the rule's *condition* from its *trigger source*, one keyword each:

```
rule <name> when [leaving] <condition> [on|after <trigger>, ...] { <effects> }
```

```
rule SendReceipt when SettledInvoice {
    Receipt for invoice sentOn: now
}
```

`when Refinement` names *what* the rule reacts to — the condition: a refinement, entered (or left — see `## Exit triggers`, §13). `on` names the *trigger source*: `on commit` — the rule evaluates as a consequence of any commit that can affect its condition — or a named schedule (`on Daily`, see `## Schedule triggers`, §17). `after commit` swaps the preposition to start a new transaction (see "Transactions and `after commit`", below). `on commit` is the default when the clause is omitted (as above), the same single-well-defined-default category as properties being required unless marked `?`. The echo of BDD's given/when/then is deliberate: *given* the declared shapes, *when* the condition holds, *on* commit or tick, then the effects (`## then`).

Neither clause says *how* detection gets implemented. Whether the underlying mechanism is a check made at write-time, an event stream, a runtime data-structure instantiation, or some mix of these for the same rule is left open by the declaration itself; the only contract that has to hold regardless of mechanism is that the effect happens if and only if the subject is or becomes a member of the refinement, exactly once per newly-satisfying instance — "newly-satisfying" is commit-relative: the commit that makes the condition become true is the occurrence the rule fires for. The contract is per trigger source: from `on commit`, the firing subjects are the *entrants* — instances whose membership flips at the triggering commit; from a schedule source, the tick evaluates the condition against current state and every *current member* is a subject — re-checking, not transition-watching — which is why tick-cadence rules pair with guards: "already handled" across ticks must be data (`## Schedule triggers`, the tick law; `## Run-once guards`). Picking and implementing the actual detection mechanism is a compiling concern (`## 1. Principles`), not part of what the rule means.

## Rules ground in commits

**A system never does anything on its own** — it only reacts to an external event, a commit; a scheduled tick is just another commit, whose changed datum is `today` (`## Schedule triggers`). Every rule firing therefore traces to a commit — but `on commit` names no *particular* commits, and the condition can be a drift refinement (`Delinquent = Account where balance < 0`) whose data no act shape mentions. Which commits can fire it? The author never says — and doesn't have to, because **drift is always commit-mediated**: `balance < 0` becomes true only when some commit changes data the predicate reads. The static-path and one-writer requirements (`## Assignment (mutation in place)`) mean the compiler knows every assignment targeting `balance` and every act that feeds it, so the trigger set is derived: the author declares the **what** (`when` — the business condition) and the **source** (`on` — commit, ticks, or both); the compiler computes the **which** (the exact commits) and proves the rule **reachable**. A new writer of `balance` added next year automatically extends the trigger set with no edit to the rule — correct, because the business condition didn't change.

Two compiler obligations fall out. **The unfireable-rule error**: if a rule's trigger set is empty — no commit in the spec, and no tick in its `on` clause, can cause entry into its condition — the rule can never fire, and that's a whole-spec compile error, not a dead-code shrug. The sharp instance is time: `OverdueInvoice = Invoice where due < today` depends on `today`, which no act commit changes — a rule `when OverdueInvoice` with no schedule in its `on` clause observes entry at `Invoice` creation (committed already-overdue) but never entry *by aging*, and the diagnostic says exactly that: "entry into `OverdueInvoice` via the passage of time is unobserved — add a schedule to `on`, or this rule under-fires." **The PO-facing answer to "when does this run?"**: the derived trigger set is impact analysis read backward — forward, "if this commit lands, these rules may fire"; backward, "this rule fires as a consequence of: withdrawals, deposits, the daily tick."

## Entry and exit are commit-local transitions

"Newly-satisfying" is well-defined with no hidden bookkeeping, but only *per commit*: a commit is a discrete moment, and evaluating its consequences means pre-state and post-state are both transiently available. `when Delinquent` means *the commit that made it true* — false before, true after; `when leaving` (`## Exit triggers`) is the same transition reversed. Consequences:

- **Episodes are free at commit granularity.** September's re-entry into `Delinquent` is a new entering commit, so the rule fires again — once per episode, with no guard apparatus, and the outcomes accumulate as history. Guards are what you buy for *durability* (crash recovery) and *cross-tick memory*, not for entry itself (`## Run-once guards`).
- **Bare act triggers are sound.** `when CorrectEmail` fires once per correction commit — the commit is the unit of firing. The rule is never tick-evaluated, so no sweep can re-apply old corrections.
- **A tick is a commit whose changed datum is `today`.** So `on commit, Daily` is one mechanism, not two: an invoice *aging* into `OverdueInvoice` is a commit-local transition over the tick — the same entry semantics as a payment-reversal commit.

## Transactions and `after commit`

A rule's body is **exactly one commit** — every effect statement lands together or none do; a body is never partially applied. That makes guard soundness structural (`## Run-once guards`) and makes `then` a compilation ordering with no observable intermediate states (`## then`). A firing's effects are a *new* commit, which may match further rules' conditions the same way an external act's commit would.

By default, an act's commit and every commit its consequences produce share one all-or-nothing envelope — a *transaction*, a descriptive term, never a keyword: an unexpected error anywhere rolls back the whole set, the act included, and retrying the act re-produces the same commits. The grounding is **the transition law**, the tick law's sibling (`## Schedule triggers`): *a transition is not data — it exists only at the commit that caused it; a rule reacting to it either fires within that commit's transaction, or the obligation must first be reified as data (a guard); there is no third place for the trigger to live.* Current state can't reconstruct a past transition ("restored" and "never delinquent" are indistinguishable — `## Exit triggers`), so a rule outside the causing transaction that fails is unrecoverable: no sweep can find work whose trigger was never data. Sharing the transaction by default is the only reading under which a plain rule is reliable at all. What the envelope guarantees — snapshot reads, atomic landing, serialization of conflicting commits, permanence, no side-door writes — is the **universal-transaction contract** (U1–U5), stated normatively in `evaluation.md`; realizing it over real storage is the engineer's obligation, and how they deliver it is theirs.

A rule opts out with a preposition — **`after commit`**: its firing becomes a new transaction, entered only after the triggering transaction has durably committed. The act stands even if the firing fails; the declared backstop heals the gap:

```
rule SyncToCrm when UnsyncedSignup after commit, Hourly {
    CrmSync from { signup: this, syncedOn: now }
}
```

What a PO reads off a header: `on commit` — part of the act; `after commit` — follows the act, healed on the stated cadence; `on Nightly` — follows by its nature. Boundaries arise only three ways: **declared** (`after commit`); **inherent** (schedule sources — a tick is a fresh commit and any originating act's transaction is long gone, so no preposition distinction exists for schedules); **forced** (external effects — an API call happens in the world, so a rule containing one can never share the triggering transaction, and writing it plain is a compile error demanding `after commit` plus the apparatus — or **`tolerates loss`** on the rule, the fire-and-forget escape for effects the business shrugs about (an analytics ping), signing the named hazard exactly as fold hazards are signed (`## Self-referential folds and tolerates`)). Boundaries also **nest**: an `after commit` firing's own consequences share *its* transaction by default, so a cascade is a tree of transactions — each subtree an all-or-nothing envelope rooted at a declared, inherent, or forced boundary, with the gaps between subtrees healed by each boundary's apparatus.

**Who asked does not cross the boundary, except as data.** A rule inside an act's transaction may read the act's actor (§22, "The actor"): in the act's own scope, or, for a rule whose subject is a product of the transaction, through a `by` clause in its header (`rule LogQuarantine when Quarantine by Staff? { ... }`), typed by which commits can reach its trigger (`checks.md` V31). An `after commit` or scheduled rule has nobody asking — its prompt firing and its backstop firing follow the same commit, and handing them different values would make the data depend on whether a crash happened — so it may not declare `by`; it reads the actor a durable act stored, `this.actor` when the act is its subject or `this.request.actor` through a reference, on every path alike, because the memory is data.

**A boundary obligates the apparatus, and the check runs both ways** (`## Run-once guards`): an `after commit` rule without a dischargeable guard and a backstop schedule is the stranding error — "this firing can be lost at the declared boundary, and its trigger is not data"; a guard-plus-backstop on a plain rule is dead machinery — "serves no boundary; did you mean `after commit`?" — since inside a transaction there is no gap for a firing to be lost in.

**The gap between the world and the state is irreducible — a business problem, not a language gap.** No system of any kind can call a charge-card API and be *guaranteed* the record of the attempt lands in the database — the call and its record live in different worlds, and no envelope contains both. Velle cannot prevent an author from writing a spec exposed to that gap; it can only make the exposure honest. The idiom is ordering, not atomicity: **commit the intent before the effect.**

```
shape ChargeIntent {
    order: one Order
    requestedOn: DateTime initially now
}

shape ChargeResolution {
    intent: one ChargeIntent
    resolvedOn: DateTime
}

shape UnchargedOrder   = Order where not exists ChargeIntent for this
shape UnresolvedCharge = ChargeIntent where not exists ChargeResolution for this

rule RecordChargeIntent when UnchargedOrder {         -- pure data — durable before any call
    ChargeIntent from { order: this }
}

rule ResolveCharge when UnresolvedCharge after commit, Hourly {
    -- the processor interaction happens here; what it learns lands as the resolving fact
    ChargeResolution from { intent: this, resolvedOn: now }
}
```

An unresolved intent is honest state: "a charge may have gone out; its outcome is unknown." The rule making the call cannot promise its own record lands after a successful call — nothing can — but the intent already stands, so a crash strands no knowledge: the backstop revisits the *intent*, and resolving it is a business process, not a blind retry — ask the processor what became of this attempt, record the outcome, or reverse the charge; which of those, and on what cadence, is the author's product decision. Nothing here is new machinery: the intent is a reified act, the unresolved state is a canonical guard (`## Run-once guards`), the resolution is evidence, and the sweep is a backstop schedule. What the pattern requires from the data is identity — recognizing *our* attempt among the processor's records — which the data carries: the intent's `id`, or a generated key (`processorKey: text initially randomUUID` — `## Scalars`). A retry budget — "retry, but give up after three and tell someone" — is memory across transactions, so it too is data: reify each try (`ResolutionAttempt`), express failure outcomes as refinements, guard the retry on `count(ResolutionAttempt for this) < 3`, and let a separate rule watch the exhausted state.

**Firing order within a transaction is never specified — and must provably not matter.** Velle states timeless facts, not call sequences: when one commit matches several conditions, the runtime may fire them in any order, or in parallel. Where data flows, the dependency graph orders firings — causality, not policy (`## then`). Where it doesn't, sibling firings must commute, and a spec whose outcome depends on an unstated order is *inconsistent* — a whole-spec compile error naming the rules involved, exactly like one-writer (write-write conflicts *are* one-writer at transaction scope; read-write conflicts and transition interference complete the check). The confluence and quiescence proofs backing this are open (OQ16).
