# Transactions

Where commits begin and end when rules trigger other rules: the default, the `after commit` boundary declaration, and what remains open.

"Transaction" is not a Velle keyword and never becomes one — the **commit** (README §4) is the language's only unit of state change, and boundaries are expressed in commit vocabulary. Open questions here are numbered OQ16–20, continuing from `investigate_state.md`; OQ6 there keeps the umbrella question (act identity, multi-instance commits, what one commit encompasses).

## The default: one commit, rules included

A rule triggered `on commit` fires *inside* the commit that triggered it — cascades included. If rule R1's effects cause a transition rule R2 watches, R2 fires inside the same commit, and so on transitively. The whole consequence set becomes durable as one state transition, or not at all: an unexpected error anywhere rolls back everything, including the originating act.

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

A $50 `Deposit` against a −$20 account is one state transition: balance updated, deposit marked applied, restoration recorded, notification queued. If any firing fails, the deposit never happened; retrying the act re-causes every transition intact. The author declared none of this — plain rules join by default.

What grounds the default:

- **A firing's body is atomic.** Guard soundness demands a mutation and its witness enter the state together (README §18); a body is never partially applied. No boundary can cut through a body.
- **The transition law** (the tick law's sibling): *a transition is not data — a consequence of entering or leaving a refinement either fires inside the commit that caused the transition, or the obligation must first be reified as data (the guard); there is no third place for the trigger to live.* A transition like `leaving Delinquent` exists only at the commit that caused it; current state can't reconstruct it afterward ("restored" and "never delinquent" are indistinguishable — the fact that made `when leaving` irreducible, README §13). A rule detached from that commit that then fails is therefore *unrecoverable*: no sweep can find work whose trigger was never data. Joined by default is the only reading under which a plain rule is reliable at all.
- **One writer extends across the joined commit.** Two rules anywhere in the same joined cascade assigning the same field is the one-writer error (README §12), caught transitively: the compiler knows the full cascade graph statically (derived trigger sets, README §11, applied one level up), so "can these firings share a commit?" is answerable at compile time, fail-closed.
- **Crash windows don't exist inside a commit.** The gap between trigger and firing that motivated durability guards (README §18) only exists at boundaries. A plain rule needs no guard; a guard on a plain rule is dead machinery (diagnostics below).

## Declaring a boundary: `after commit`

A boundary is where a new commit begins — where an unexpected error rolls back *to the boundary* instead of to the act. It is declared by a preposition swap on the trigger source:

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

- **`on commit`** — the firing is part of the act's commit.
- **`after commit`** — the firing becomes *its own commit*, immediately following the act's. The act stands even if the firing fails; the backstop heals the gap.

Read aloud: "when a signup is unsynced, after the commit, and hourly." The signup is never hostage to CRM uptime; a failed call lands no witness, the guard stays armed, and the `Hourly` backstop retries — *immediately, and self-healing hourly*. "How far behind can the CRM be?" is answered by a schedule name in the header.

What a Product Owner reads off a header: `on commit` — part of the act; `after commit` — follows the act, healed on the stated cadence; `on Nightly` — follows by its nature.

### Boundaries arise exactly three ways

1. **By declaration** — `after commit`, the only place a spelling is needed, because `commit` is the only trigger source where joining is otherwise the default.
2. **Inherently, at schedule sources.** A tick is its own commit; by the time `on Hourly` fires, the triggering act's commit is long gone. There is nothing to join, so schedule entries carry no preposition distinction.
3. **Forcibly, at external effects.** An API call happens in the world; no boundary drawn around state can contain it (its only state-visible trace is its witness, README §18). A rule containing an external effect can never join — writing one as a plain rule is a compile error: "this call's failure would lose work with no heal path — declare `after commit`, gate the rule on a dischargeable state, and add a backstop schedule; or state that loss is acceptable."

### A boundary obligates the apparatus

`after commit` and the eventual apparatus — a dischargeable guard (what makes retry safe), a backstop schedule (how far behind, how it heals), a witness (what *done* means) — are declaration and proof, checked against each other both ways, the disarm-proof pattern:

- **Boundary without apparatus** is the stranding error (the transition law): "this rule's firing can be lost at the declared boundary, and its trigger is not data — gate it on a dischargeable state and add a backstop schedule, or remove the boundary."
- **Apparatus without boundary** is dead machinery — a joined firing can't be dropped, so the guard and backstop protect nothing. The diagnostic mirrors dead tolerances (README §19): "this rule's guard and backstop serve no boundary — did you mean `after commit`?"

The full product sentence usually includes a retry budget — "retry, but give up after three and tell someone." An attempt count is cross-commit memory, so it is data (the tick law): reify each try (`SyncAttempt`), express failure outcomes as refinements (errors-are-refinements, README §2), guard the retry on `count(SyncAttempt for this) < 3`, and let a separate rule watch the exhausted state. Hand-written per "No guard sugar" (README §18).

### Ordering across a boundary

An `after commit` firing's commit begins after its triggering commit completes, so its writes are ordered *after* every joined write of the same cascade. One-writer treats it as a separate commit, where last-in-wins across commits is already defined (README §12).

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

**Liens must see consequences.** A `forbidden` lien (README §13) rejects "any change that would cause the exit" — and the exit-causing change can arrive as a downstream firing's effect rather than the act itself: a `VoidPayment` names only a payment and a reason, yet `ApplyVoid`'s effect moves the invoice's derived balance and would exit `SettledInvoice`, which a `Receipt`'s lien forbids from two relationships away. The lien must *see* that consequence before it becomes real — the compiler's derived trigger sets make the check static. Whether seeing leads to *refusing the act* (the commit unwinds) or *producing a refusal fact* (nothing unwinds; the gate simply doesn't fire and a refusal shape records why) is the open modeling question, OQ20.

## Open questions

(Numbering continues from `investigate_state.md`; OQ tags are never reused.)

### OQ16. Semantics inside the joined commit

The boundaries are settled (default joined; `after commit` / schedules / external effects); what the joined region still owes is its precise semantics:

- **Which transitions rules fire on** — endpoint vs. stepwise. R2 fires because R1's effects changed state, so R2 evaluates against a state mid-commit; if entry/exit are judged endpoint-to-endpoint, a refinement entered and exited within one commit *never happened* (a transient-membership blip at commit granularity — the §17 question at a new grain); if rules fire on stepwise transitions, intermediate states are semantically real. Endpoint semantics lands in stratification territory — which rules fire depends on final state, which depends on which rules fire. Known ground in Datalog (stratified negation); the prior-art item in `TODO.md` is load-bearing here.
- **Termination.** A joined cascade must terminate to be a commit. The cascade graph is static, so cycles are detectable, but convergence can be value-dependent (a deposit rule producing deposits) — undecidable in general. The folds precedent (README §19) applies: prove termination structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices as a termination proof is unexamined.
- **Transitive one-writer, precisely.** The check extends across the joined cascade; the exact overlap analysis (two rules reachable from one act via different transition paths) needs specification.
- **Firing-vs-triggering atomicity residue** stays with OQ6 (act identity, multi-instance commits).

### OQ17. Rejection scope

If a lien or validation refusal unwinds the act (commit-refusal), what exactly unwinds, what the committer is told, and whether rejection can be partial ("accept the deposit, refuse only the tier change") — declarable policy or always incoherent? Reified refusal ("Validation rejection is data") dissolves most of this — nothing unwinds and "what the committer is told" is a shape — leaving only the genuinely commit-refusing subset, which OQ20 delimits.

### OQ18. External effects mid-cascade

When the call succeeds and a later link fails (or the reverse), the witness records what happened — but what does the *cascade* do: halt and self-heal via backstop, compensate (README §13's `compensate`, itself unsettled — OQ7), or refuse to compile without a declared policy? Interacts directly with OQ7's `compensate` re-derivation.

### OQ19. Boundary grammar and policy residue

- **Mixed-list grammar** — does `after commit, Hourly` read its preposition per-entry, or does the clause split (`after commit on Hourly`)?
- **Tick granularity** — does every rule fired by one tick join that tick's single commit, or does each firing (or each `each` iteration) get its own boundary? The granularity half of OQ13's pass.
- **Nesting** — an `after commit` rule's own downstream cascade joins *its* commit by default, so a cascade is a tree of commits, each rooted at a declared, inherent, or forced boundary. Consequences unexplored.
- **`tolerates loss`** — the fire-and-forget escape for external effects (an analytics ping the PO shrugs about); extending the `tolerates` vocabulary beyond fold hazards (README §19) is unexamined.
- **`never` invariants** — a spec-level declaration over refinement combinations (`never (Account where balance >= 0 and suspended)`) as *verification* of a chosen boundary: the compiler proves the written boundaries honor the stated observable-state fact. Independently valuable, §1's consistency-checker category; where it lives is undecided.
- **Legibility at scale** — whether boundary-by-preposition plus apparatus stays readable in realistic specs; the calibration question rung recognition deferred to worked examples (README §20).

### OQ20. Is commit-refusal primitive, or derivable from reified refusal?

A full validate-and-reject flow needs no transaction machinery, which raises whether the language needs commit-refusal at all above the type/trust boundary (level 1 of the invalid layering — OQ5's territory, where it's irreducible). Sub-threads: can `forbidden` desugar to compiler-derived gates on every exit-causing writer plus a refusal witness — keeping consequence-visibility while dropping the unwind — and is that faithful to what a PO's "you can't edit an issued invoice" means, or does the no-sugar reasoning (README §18) cut against the desugaring here too? Does any business case require that a well-shaped act *not enter the state* (compliance/data-retention pressures — "we may not store this request at all" — may be the real residue)? If both modelings stay expressible, is the choice per-lien, per-act-shape, or per-boundary (OQ5's who-may-commit declaration being the natural home)?
