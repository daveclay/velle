# Transactions

Where transaction boundaries sit when a rule's effects produce commits that match other rules' conditions: the default, the `after commit` boundary declaration, and what remains open.

Open questions here are numbered OQ16–20, continuing from `investigate_state.md`; OQ6 there keeps the umbrella question (act identity, multi-instance commits).

## Two words, kept apart: commit and transaction

A **commit** is Velle's conceptual unit: one mutation entering the single state (README §4) — an external act arriving, a scheduled tick, or a rule firing's effects landing. Rules are never triggered *by rules*: a rule reacts to its condition — a shape or refinement — newly holding at some commit (README §11). There is no call graph; there is only state, commits mutating it, and conditions matching. When rule R1 fires, its effects are a **new commit**, and that commit may match the conditions of rules R2 and R3 exactly the way an external act's commit would.

A **transaction** — this doc's descriptive term, never a Velle keyword — is the all-or-nothing envelope around a set of commits: which commits stand or fall together when something goes wrong. The two are different axes. A transaction *contains* commits — an act's commit plus the commits its consequences produce — it does not merge them into one; transition semantics stay per-commit inside it. How an envelope is implemented (a real database transaction, a queue with idempotent replay) is compilation's business (README §1); what Velle owes is the observable contract: what stands together, what may fail apart, and what heals the gap.

## The default: one transaction

Every rule fires within the transaction of the commit that matched its condition — transitively, with no declaration needed:

```
shape Account {
    balance: Money
}

shape Deposit {
    account: one Account
    amount: Money
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied
shape Delinquent = Account where balance < 0

rule ApplyDeposit when UnappliedDeposit {
    account.balance = account.balance + amount
    this.applied = true
}

rule RestoreService when leaving Delinquent {
    ServiceRestoration from { account: this, restoredOn: now }
}

rule NotifyRestored when ServiceRestoration {
    RestorationEmail from { account: account, queuedOn: now }
}
```

A \$50 `Deposit` against a −$20 account is **one transaction containing four commits**: the `Deposit` act; `ApplyDeposit`'s effects (at this commit the transition out of `Delinquent` occurs); `RestoreService`'s `ServiceRestoration`; `NotifyRestored`'s `RestorationEmail`. An unexpected error at any of them rolls back the whole envelope — the deposit never happened, and retrying the act re-produces the same sequence of commits intact.

Because each firing is its own commit, transitions inside a transaction are ordinary commit-local transitions (README §11) — nothing new to define. A mid-transaction blip is real: if a later commit in the same transaction (a reinstatement fee, say) drops the balance negative again, the account genuinely left and re-entered `Delinquent`, and rules watching either transition fire — the same stance §17 already takes ("commit-triggered rules observe every membership the commit stream produces").

When one commit matches several conditions at once, the order of the resulting firings is deliberately **never specified** — Velle states timeless facts about a system, not runtime call sequences, so the runtime may fire them in any order (or in parallel) and a valid spec must mean the same system either way. What makes that sound is a proof obligation on the spec, not a scheduler contract: an author *can* write a spec whose outcome depends on an unstated order, and catching that inconsistency is the compiler's job — OQ16, the doc's main open question.

What grounds the default:

- **A rule's body is atomic: one firing's body is exactly one commit.** Every effect statement in the body lands together, or none of them do — a body is never partially applied, and no boundary can cut through one. `then` (README §15) orders effects *within* that single commit — an ordering commitment for compilation, never an observable intermediate state. Guard soundness follows for free: a mutation and its witness are statements of one body, so they enter the state together (README §18) — the crash-between-them window is structurally impossible. (A body containing an external effect is the one thing this can't cover — the call happens in the world, outside any commit — which is exactly why such rules are forced to a boundary and a witness, below.)
- **The transition law** (the tick law's sibling): *a transition is not data — it exists only at the commit that caused it. A rule reacting to a transition either fires within that commit's transaction, or the obligation must first be reified as data (the guard); there is no third place for the trigger to live.* Current state can't reconstruct a past transition ("restored" and "never delinquent" are indistinguishable — the fact that made `when leaving` irreducible, README §13), so a rule outside the causing transaction that fails is *unrecoverable*: no sweep can find work whose trigger was never data. Inside the transaction, failure rolls back the cause too, and retrying the act re-causes the transition. Sharing the transaction by default is the only reading under which a plain rule is reliable at all.
- **Crash windows don't exist inside a transaction.** The gap between a condition matching and the rule firing — the gap that motivates durability guards (README §18) — only exists at transaction boundaries. A plain rule needs no guard; a guard on a plain rule is dead machinery (diagnostics below).

## Declaring a boundary: `after commit`

A boundary is where a new transaction begins — where an unexpected error rolls back *to the boundary* instead of to the act. It is declared by a preposition swap on the trigger source:

```
shape Signup {
    email: text
    submittedOn: Date initially now
}

shape CrmSync {
    signup: one Signup
    syncedOn: Date
}

shape UnsyncedSignup = Signup where not exists CrmSync for this

rule SyncToCrm when UnsyncedSignup after commit, Hourly {
    CrmSync from { signup: this, syncedOn: now }
}
```

- **`on commit`** — the firing's commit belongs to the triggering commit's transaction.
- **`after commit`** — the firing's commit begins a **new transaction**, entered only after the triggering transaction has durably committed. The act stands even if the firing fails; the backstop heals the gap.

Read aloud: "when a signup is unsynced, after the commit, and hourly." The signup is never hostage to CRM uptime; a failed call lands no witness, the guard stays armed, and the `Hourly` backstop retries — *immediately, and self-healing hourly*. "How far behind can the CRM be?" is answered by a schedule name in the header.

What a Product Owner reads off a header: `on commit` — part of the act; `after commit` — follows the act, healed on the stated cadence; `on Nightly` — follows by its nature.

### Boundaries arise exactly three ways

1. **By declaration** — `after commit`, the only place a spelling is needed, because `commit` is the only trigger source where sharing the transaction is otherwise the default.
2. **Inherently, at schedule sources.** A tick is a fresh commit; by the time `on Hourly` fires, the triggering act's transaction is long gone. There is nothing to share, so schedule entries carry no preposition distinction.
3. **Forcibly, at external effects.** An API call happens in the world; no envelope drawn around state can contain it (its only state-visible trace is its witness, README §18). A rule containing an external effect can never share the triggering transaction — writing one as a plain rule is a compile error: "this call's failure would lose work with no heal path — declare `after commit`, gate the rule on a dischargeable state, and add a backstop schedule; or state that loss is acceptable."

### A boundary obligates the apparatus

`after commit` and the eventual apparatus — a dischargeable guard (what makes retry safe), a backstop schedule (how far behind, how it heals), a witness (what *done* means) — are declaration and proof, checked against each other both ways, the disarm-proof pattern:

- **Boundary without apparatus** is the stranding error (the transition law): "this rule's firing can be lost at the declared boundary, and its trigger is not data — gate it on a dischargeable state and add a backstop schedule, or remove the boundary."
- **Apparatus without boundary** is dead machinery — inside a transaction a firing can't be dropped, so the guard and backstop protect nothing. The diagnostic mirrors dead tolerances (README §19): "this rule's guard and backstop serve no boundary — did you mean `after commit`?"

The full product sentence usually includes a retry budget — "retry, but give up after three and tell someone." An attempt count is memory across transactions, so it is data (the tick law): reify each try (`SyncAttempt`), express failure outcomes as refinements (errors-are-refinements, README §2), guard the retry on `count(SyncAttempt for this) < 3`, and let a separate rule watch the exhausted state. Hand-written per "No guard sugar" (README §18).

### Ordering across a boundary

An `after commit` firing's transaction begins after its triggering transaction completes, so its commits are ordered *after* every commit of that transaction. Across transactions, last-in-wins is already defined (README §12). *Within* a transaction, order is never specified and must provably not matter — OQ16.

## What is not a boundary

**Conditioned acceptance is a definition.** "Don't accept the order unless the payment authorizes" is not a transaction statement — it is the definition of *accepted*, owned by the errors-are-refinements pattern:

```
shape Order {
    placedOn: Date initially now
}

shape AuthResponse {
    order: one Order
    approved: boolean
    respondedOn: Date
}

shape PendingOrder  = Order where not exists AuthResponse for this
shape AcceptedOrder = Order where exists (AuthResponse where order == this and approved)
shape DeclinedOrder = Order where exists (AuthResponse where order == this and not approved)
```

Fulfillment rules hang off `AcceptedOrder`, never `Order`. The order landing as a fact ("placed, authorization pending") is honest; nothing downstream can act until membership says so. Exhaustiveness checking (README §8) proves the partition covers every order.

**Validation rejection is data.** There is nothing to throw and nowhere to throw it — Velle has no stack and no functions, so exception propagation doesn't exist; a validation failure is a shape or a refinement membership, never a control-flow event. A validate-and-reject flow needs no transaction machinery:

```
shape ReceiptedInvoice = Invoice where exists Receipt for this

shape ApplicableVoid = VoidPayment where not payment.invoice is ReceiptedInvoice
shape RefusedVoid    = VoidPayment where payment.invoice is ReceiptedInvoice

rule ApplyVoid when ApplicableVoid {
    payment.voided = true
}

rule RecordRefusal when RefusedVoid {
    VoidRefusal from { void: this, reason: "invoice is settled and receipted", refusedOn: now }
}
```

The act lands (the request happened — audit for free: `count(RefusedVoid)`), the consequence never fires for the refused subset, the refusal lands as a fact the caller reads back (delivery is compilation's job). `ApplicableVoid`/`RefusedVoid` partition `VoidPayment`, exhaustiveness-checked — the forgotten `catch` block becomes a compile-time uncovered-subset error. Three levels of "invalid," only the first pre-commit: **(1)** can't inhabit the shape's type at all — rejected below the language, at the trust/input boundary (OQ5); **(2)** well-shaped, business-invalid — rejection-as-data, above; **(3)** well-shaped, valid, consequence forbidden — the lien case, next.

**Liens must see consequences.** A `forbidden` lien (README §13) rejects "any change that would cause the exit" — and the exit-causing change can arrive as a downstream firing's commit rather than the act itself: a `VoidPayment` names only a payment and a reason, yet `ApplyVoid`'s effect moves the invoice's derived balance and would exit `SettledInvoice`, which a `Receipt`'s lien forbids from two relationships away. The lien must *see* that consequence before its transaction stands — the compiler's derived trigger sets (README §11) make the check static. Whether seeing leads to *refusing the act* (the transaction unwinds) or *producing a refusal fact* (nothing unwinds; the gate simply doesn't fire and a refusal shape records why) is the open modeling question, OQ20. *(Since resolved for immutability: `forbidden` is retired — the write-gate reformulation `frozen` (README §8, "Frozen fields") makes the check static writer-disjointness plus act partitions, with no unwind at all; `investigate_evidence_policies.md`.)*

## Open questions

(Numbering continues from `investigate_state.md`; OQ tags are never reused.)

### OQ16. Order must not matter — can the compiler prove it?

**Settled direction: firing order within a transaction is never specified, and never matters in a valid spec.** Velle states timeless facts, not runtime call sequences; the runtime may fire sibling rules in any order, or in parallel, and every order must produce the same outcome — the same final state and the same set of produced facts. Two cases, sharply different:

- **Where data flows, order is causality, not policy.** If R3's condition matches on the commit R2's firing produces, the dependency graph orders them — the ordering README §15 already calls free. Nothing to prove; nothing was chosen.
- **Where no data flows, order must provably not matter.** Sibling firings — one commit matching R2 and R3 with no dependency between them — must *commute*. A spec whose outcome depends on an unstated order is **inconsistent**: it describes two different systems and never says which. That is a whole-spec compile error in §1's deepest sense — the spec fails to be a self-coherent description — reported as one connected diagnostic naming the rules involved, exactly like one-writer.

An author can absolutely write inconsistent Velle:

```
-- both fire from the same AccountReview commit
rule AdjustTier when AccountReview {
    account.tier = <formula over history>
}

rule RecordTier when AccountReview {
    review.tierAtReview = account.tier     -- before or after the adjustment? unstated.
}
```

`tierAtReview` differs depending on which firing runs first — the spec is ambiguous about its own meaning. The diagnostic demands the intent, not an ordering: "`tierAtReview` depends on the unstated order of `AdjustTier` and `RecordTier` — state what it means" (read the pre-adjustment inputs the formula reads, or make the dependency real by conditioning on the adjusted state).

The check decomposes along familiar lines: **write-write** conflicts — two siblings assigning the same field (the `account.tier` example from earlier drafts) — are one-writer (README §12) extended to transaction scope; **read-write** conflicts — one sibling writes what another reads in a body or condition — are the example above; **transition interference** — one sibling's commit enters a refinement another sibling's commit exits — makes the set of mid-transaction transitions order-dependent, so transition-watching rules would see different histories. Traversal order (depth-first vs. level-by-level) stops being a question at all: once outcomes are order-independent, every traversal is a valid compilation.

**Open:**

- **The analysis itself.** Commutativity/confluence checking is charted territory — term rewriting's critical pairs and Newman's lemma (local confluence + termination ⇒ confluence, which ties this proof to quiescence below), CHR confluence tests, Datalog evaluation strategies. What Velle's version is — and how coarse it can be before it rejects legitimate specs — is the work. Fail-closed is given (uncertainty errors, README §12's stance); calibration is not.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (a deposit rule producing deposits) — undecidable in general. The folds precedent (README §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined.

### OQ17. Rejection scope

If a lien or validation refusal unwinds the act (commit-refusal), what exactly unwinds, what the committer is told, and whether rejection can be partial ("accept the deposit, refuse only the tier change") — declarable policy or always incoherent? Reified refusal ("Validation rejection is data") dissolves most of this — nothing unwinds and "what the committer is told" is a shape — leaving only the genuinely commit-refusing subset, which OQ20 delimits.

### OQ18. External effects mid-cascade

When the call succeeds and a later commit's firing fails (or the reverse), the witness records what happened — but what does the rest of the consequence chain do: halt and self-heal via backstop, compensate via the evidence-subject pattern (README §13, "Compensation is a pattern, not a keyword" — the `compensate` keyword is retired, `investigate_evidence_policies.md`), or refuse to compile without a declared policy?

### OQ19. Boundary grammar and policy residue

- **Mixed-list grammar** — does `after commit, Hourly` read its preposition per-entry, or does the clause split (`after commit on Hourly`)?
- **Tick granularity** — do all rules fired at one tick share the tick's transaction, or does each firing (or each `each` iteration) get its own? The granularity half of OQ13's pass.
- **Nesting** — an `after commit` rule's consequences share *its* transaction by default, so a cascade is a tree of transactions, each rooted at a declared, inherent, or forced boundary. Consequences unexplored.
- **`tolerates loss`** — the fire-and-forget escape for external effects (an analytics ping the PO shrugs about); extending the `tolerates` vocabulary beyond fold hazards (README §19) is unexamined.
- **`never` invariants** — a spec-level declaration over refinement combinations (`never (Account where balance >= 0 and suspended)`) as *verification* of chosen boundaries: the compiler proves the written boundaries honor the stated observable-state fact. Independently valuable, §1's consistency-checker category; where it lives is undecided.
- **Legibility at scale** — whether boundary-by-preposition plus apparatus stays readable in realistic specs; the calibration question rung recognition deferred to worked examples (README §20).

### OQ20. Is commit-refusal primitive, or derivable from reified refusal?

A full validate-and-reject flow needs no transaction machinery, which raises whether the language needs commit-refusal at all above the type/trust boundary (level 1 of the invalid layering — OQ5's territory, where it's irreducible). The `forbidden` sub-thread is **answered** (`investigate_evidence_policies.md`): it doesn't desugar to gates-plus-refusal — it was the wrong construct entirely. A PO's "you can't edit an issued invoice" is a write-gate on a state, not an exit-gate on evidence (the exit-gate fails outright on monotone, act-entered states), and the write-gate reformulation — refinement-body `frozen` (README §8, "Frozen fields") — is enforced by static writer-disjointness plus mandated act partitions: no unwind, no runtime refusal, nothing left of the lien machinery. What remains of OQ20 is its own residue: does any business case require that a well-shaped act *not enter the state* (compliance/data-retention pressures — "we may not store this request at all")? If so, is that declared per-act-shape or per-boundary (OQ5's who-may-commit declaration being the natural home)?
