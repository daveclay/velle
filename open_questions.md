# Open Questions

The language's open questions, sorted by what the current milestone needs. The milestone: get Velle to the point where a validator/transpiler can be written against it and produce an executable runtime to continue testing with. Settled results are promoted into `README.md` as they land; supporting examples the questions lean on are collected in the appendix at the bottom. OQ tags are stable — numbers are never renumbered or reused, so existing references (`OQ14`, `OQ16`) stay valid, and gaps in the sequence are questions already settled.

**Required for v0 — all settled.** The five questions that stood between the spec and an implementable validator/transpiler are closed; v0 is fully specified and the milestone is now implementation. Where each retired to: the construct set (OQ21) and the harness boundary (OQ24) into the scope statement heading README §22 — §3–§21 as written; `expose <Shape> using MockHarness` transpiling to per-shape input functions plus a generated `main`; tick and clock control as generated functions plain Kotlin calls (scheduled events and user acts are both external input); refusals naming what they violated; reading via generated typed accessors. The builtin surface (OQ23) into README §5 and §10 — Kotlin-grounded scalars, `java.time` temporals, receiver-dependent duration steps, the closed function list, `Money` as a preview of post-v0 extensible data types. The grammar (OQ22) into `grammar.md` — the normative whole-surface grammar, all nine decision points decided. And the operational semantics and check catalog (OQ25) into `evaluation.md` and `checks.md` — the evaluation model with its five signed v0 spike choices (in-memory state, synchronous FIFO `after commit`, declaration-order firing with a depth backstop, no automatic retry, transaction-end `never` tripwire), and the 23-entry validator catalog whose V14–V16 are the coarse fail-closed slices of OQ15–16 below.


**Deferrable past v0 — calibration and residue.** None of these block the build. OQ14–16 are precisely the questions v0 exists to answer empirically — v0 ships coarse fail-closed versions and real specs calibrate them.

*What one commit is*

- **OQ16 — Order must not matter.** Can the compiler prove sibling firings commute, and that a transaction quiesces? The hard cases are data-dependent — aliasing, values, termination — where fail-closed rejects legitimate specs; calibration and the discharge vocabulary are the work. *v0 stance: ship the easy static checks (literal-path write-write and read-write conflicts, DAG-or-disarmed-cycle quiescence), fail closed on everything else; calibration is what running v0 against realistic specs is for.*

*The input boundary*

- **OQ17 — Rejection scope.** If a refusal unwinds the act, what exactly unwinds and what is the committer told? *v0 stance: the settled harness boundary (README §22's scope statement) fixes a minimal answer — a refusal names the violated `never` and nothing commits; the general question stays open here.*
- **OQ20 — Is commit-refusal primitive?** Largely answered: refusal is compiled boundary code sourced from `never` (README §21). Remaining: refusal conditions not expressible over the act's own data (caller identity, ambient policy) — who-may-commit territory at the `expose` boundary (README §22, "External input mechanisms"). *v0 stance: v0 ships `expose ... using MockHarness` (README §22's scope statement), which carries no caller identity or who-may-commit conditions; the residue stays with real mechanism design.*

*Guard ergonomics and folds*

- **OQ14 — Diagnostic-led guard adoption.** Is the canonical guard form pleasant enough for a compiler diagnostic to demand? *v0 stance: answered by using v0, not before it — the fold diagnostics ship writing the canonical form and authors' reactions are the data.*
- **OQ15 — Ordered folds and firing order at a tick.** No honest discharge yet exists for a tick-cadence order-dependent fold. The derivation-side answer is a predecessor recurrence — expressible today, needing only a well-foundedness proof (stratify the definition graph, certify its cycles), not new grammar; ordering ties are the author's modeling problem, not the language's. *v0 stance: ship stratification plus the strict-descent certificate whitelist, fail closed; commit-cadence remains the served spelling for the mutation form.*

## What one commit is

### OQ16. Order must not matter — can the compiler prove it?

**Settled direction: firing order within a transaction is never specified, and never matters in a valid spec.** Velle states timeless facts, not runtime call sequences; the runtime may fire sibling rules in any order, or in parallel, and every order must produce the same outcome — the same final state and the same set of produced facts. Two cases, sharply different:

- **Where data flows, order is causality, not policy.** If R3's condition matches on the commit R2's firing produces, the dependency graph orders them — the ordering README §15 already calls free. Nothing to prove; nothing was chosen.
- **Where no data flows, order must provably not matter.** Sibling firings — one commit matching R2 and R3 with no dependency between them — must *commute*. A spec whose outcome depends on an unstated order is **inconsistent**: it describes two different systems and never says which. That is a whole-spec compile error in README §1's deepest sense — the spec fails to be a self-coherent description — reported as one connected diagnostic naming the rules involved, exactly like one-writer.

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

The check decomposes along familiar lines: **write-write** conflicts — two siblings assigning the same field — are one-writer (README §12) extended to transaction scope; **read-write** conflicts — one sibling writes what another reads in a body or condition — are the example above; **transition interference** — one sibling's commit enters a refinement another sibling's commit exits — makes the set of mid-transaction transitions order-dependent, so transition-watching rules would see different histories. Traversal order (depth-first vs. level-by-level) stops being a question at all: once outcomes are order-independent, every traversal is a valid compilation.

**Where the analysis runs out of statics — the calibration cases.** The `tierAtReview` example is *easy*: same trigger shape, literal paths to one field. The hard cases are where the conflict's existence depends on runtime data, which static analysis cannot see. Fail-closed means none of these are ever *missed* — they are rejected — so each is a legitimate spec the author must restructure until safety is provable, and the open work is how much of that burden calibration can remove:

- **Instance aliasing through relationships.** Write-write detection works on paths, but paths name *routes*, not instances:

  ```
  rule PromoteBuyer when QualifiedPurchase {
      customer.tier = "gold"
  }

  rule PromoteReferrer when (QualifiedPurchase where customer.referrer is some) {
      customer.referrer.tier = "advocate"
  }
  ```

  Both fire from one purchase commit, writing `tier` of two *differently-reached* instances. They collide exactly when `customer.referrer == customer` — a customer who referred themselves. Whether that configuration can ever exist is a fact about the data, not the declarations; deciding it statically is the aliasing problem, undecidable in general. The rejection is honest, and the diagnostic can even name the collision condition — which points at the discharge below.

- **Value-dependent transition interference.** Two siblings write *different* fields — one-writer is silent — but a refinement reads both: one writes `balance`, another writes `creditLimit`, and `Overextended = Account where balance > creditLimit` watches the pair. Whether the two orderings produce different mid-transaction transition histories depends on the actual numbers in flight; statically there is only "both inputs of one predicate written by unordered siblings — *potential* interference."

- **Value-dependent quiescence.** The static condition graph sees only a cycle:

  ```
  rule SplitOversizedParcel when (Parcel where weight > 30) {
      Parcel from { shipment: shipment, weight: weight / 2 }
      Parcel from { shipment: shipment, weight: weight / 2 }
  }
  ```

  The cascade in fact terminates — halving falls below 30 — but proving it needs an arithmetic measure argument: termination proving, the halting problem in miniature.

**Open:**

- **The analysis itself.** Commutativity/confluence checking is charted territory — term rewriting's critical pairs and Newman's lemma (local confluence + termination ⇒ confluence, which ties this proof to quiescence below), CHR confluence tests, Datalog evaluation strategies. What Velle's version is — and how coarse it can be before it rejects legitimate specs — is the work. Fail-closed is given (uncertainty errors, README §12's stance); calibration is not.
- **Discharge vocabulary.** The aliasing case is what showed `never` invariants (since adopted — README §21) are not just verification but *proof inputs*: `never (Customer where referrer == this)` turns the collision condition into a proven impossibility the disjointness analysis may use — the author states a data invariant, the prover spends it. Whether declared invariants feed the confluence and one-writer analyses — and what else belongs in the discharge toolbox (conditioning one sibling on the other's outcome to make the dependency real; some not-yet-designed decreasing-measure spelling for cycles) — is calibration's concrete form.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (the parcel-splitting example above) — undecidable in general. The folds precedent (README §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined.

## The input boundary

### OQ17. Rejection scope

If a refusal unwinds the act (commit-refusal), what exactly unwinds, what the committer is told, and whether rejection can be partial ("accept the deposit, refuse only the tier change") — declarable policy or always incoherent? Reified refusal ("Validation rejection is data," in the appendix) dissolves most of this — nothing unwinds and "what the committer is told" is a shape — leaving only the genuinely commit-refusing subset, which OQ20 delimits.

### OQ20. Is commit-refusal primitive, or derivable from reified refusal?

A full validate-and-reject flow needs no transaction machinery ("Validation rejection is data," in the appendix), and immutability needs no commit-refusal either (`frozen` — README §8, "Frozen fields"). The `never` adoption largely answered what remained (README §21): well-shaped acts that *must not enter the state* do exist — invariant-violating ones — and their refusal is **derived boundary code**, compiled from `never` declarations, landing below the language alongside can't-inhabit-the-type. Commit-refusal is not a language primitive. The compliance/data-retention case ("we may not store this request at all") is covered wherever the refusal condition is a predicate over the act's own data — that's just a `never` over the act shape, guardrail included. What remains is narrow: refusal conditions *not* expressible as predicates over the act's data — caller identity, ambient policy, rate — which is who-may-commit territory at the `expose` boundary; OQ5's resolution places it at the expose site or its configuration (README §22, "External input mechanisms"). This OQ is likely retired into that construct's design once who-may-commit is designed.

## Guard ergonomics and folds

### OQ14. Diagnostic-led guard adoption

Fold enforcement (README §19) means the compiler will be *proposing* guards to authors mid-error. With guard sugar dropped, the fix-it suggestion writes the canonical form itself, so the question is now exactly this: is the canonical form pleasant enough to be what a diagnostic asks an author to write? Ergonomics and enforcement aren't separable — a required guard that's miserable to write makes enforcement hostile; one that reads as the business rule makes it a teaching moment.

### OQ15. Ordered folds and firing order at a tick

Exposed by the fold analysis (README §19): a tick-cadence order-dependent fold owes a reordering obligation with no honest discharge. Concretely — a nightly streak sweep that passes every settled check:

```
shape Account {
    streak: integer initially 0
}

shape Payment {
    account: one Account
    onTime: boolean
    receivedOn: timestamp on create
    folded: boolean initially false
}

shape UnfoldedPayment = Payment where not folded

rule TrackStreak when UnfoldedPayment on Nightly {
    account.streak = if onTime then account.streak + 1 else 0
    this.folded = true
}
```

`streak` has one writer, the disarm proof holds (`folded` falsifies the trigger), and the guard discharges duplication — a crashed sweep re-fires only stragglers. What remains is exactly reordering. At one tick, an account with three pending payments — on-time, late, on-time by `receivedOn` — should settle at `streak == 1` (up, reset, up). But each firing at a tick is its own transaction with no defined order among the firings (README §16, §17), and every order is a different answer: fold the late payment first and the account ends at 2; fold it last and the account ends at 0. One data set, three describable outcomes — the spec describes several systems and never says which, the same incoherence OQ16 rejects for sibling firings *within* a transaction, here surfacing between the separate transactions of one tick. Declared tolerance is no discharge: `tolerates reordering` on `streak` would be a false statement rather than an accepted risk — the value is exactly order-sensitive, the case README §19 names as wrong for a streak.

Two ways out, and only one of them is grammar. **Keeping the mutation** needs an ordering clause giving one tick's firings a defined order (`on Nightly ordered by receivedOn`?) — new surface, not yet designed. **Dissolving the mutation** turns out to need no new construct at all: a fold over ordered history is expressible today as a *recurrence through a derived predecessor* — self-reference one hop through a relationship (README §7), the README §12 ledger stance applied to folds:

```
shape Payment {
    account: one Account
    onTime: boolean
    receivedOn: timestamp on create
    previous: one Payment? = latest(Payment where account == this.account and receivedOn < this.receivedOn)
    streakAfter: integer =
        if not onTime then 0
        else if previous is some then previous.streakAfter + 1
        else 1
}

shape Account {
    streak: integer = if exists Payment for this
                      then latest(Payment for this).streakAfter
                      else 0
}
```

Nothing here is new mechanism: `previous` is an ordinary derived to-one (`latest` ordering by the sole `timestamp on create` — the settled default, no `by` syntax even needed), `streakAfter` is sanctioned self-reference with existing narrowing, and the current value is a selector read. No stored field, no guard, no obligation — and *more* than a fold: `streakAfter` is readable history ("the streak as of each payment"), a chain `why` can walk. A dedicated `fold over ... ordered by` construct would therefore be sugar over this recurrence, not a primitive — the incremental/recompute relationship from README §19 again (one description, two spellings; rung recognition free to point at the twin) — and since its step expression would need an accumulator binding, the closest Velle would come to a lambda, it faces the no-sugar bar (README §18) with a real burden to meet.

What the recurrence still needs from the language is one proof, not grammar — **well-foundedness**, and it scopes smaller than it sounds. Velle's expression grammar cannot itself diverge: every README §10 predicate is finite text over finite data — no loop construct, no lambda, no recursive predicate mechanism — so any single expression terminates structurally. The only recursion in the language is *named definitions referencing named definitions*: a derived property's formula mentioning other derived properties, including its own one hop through a relationship (README §7), and refinements naming refinements. Evaluation is definition-unfolding, and unfolding is the one thing that can fail to bottom out — so the obligation is exactly: **the definition graph, instantiated over the data, must be well-founded**. That factors into charted territory — stratify, then certify:

- **The acyclic part of the definition graph is free.** Build the static dependency graph of definitions; where it's acyclic, every unfolding chain is finite and no analysis is needed — Datalog's stratification, covering the overwhelming majority of any real spec (`balance = amount - sum(payments, amount)` threatens nothing).
- **Each static cycle owes a certificate.** `streakAfter → previous → streakAfter` descends a strict comparison on a creation-fixed datum — provably finite. `root → parent → root` (README §7's own example) descends a stored relationship, so it needs an acyclicity guarantee, which a `never` invariant can supply (`never (Foo where parent == this)` for the direct case — an invariant spent as a proof input, README §21). No certificate is a compile error. A definitional cycle with no well-founded reading at all (`shape A = X where this is B` / `shape B = X where this is A`) is rejected by the same check — sparing the language from ever needing fixpoint semantics.

This is not the halting problem taken on: the general question stays undecidable and is never attempted — the check accepts certificates from a decidable whitelist and fails closed on the rest, legit-but-unprovable included, exactly the README §19 fold stance aimed at termination (OQ16's parcel cascade names the same limit for rule cascades). What's open is the whitelist's size — strict descent plus base case is clearly in; growing it (a decreasing-measure spelling, richer invariant-fed acyclicity) is the same calibration OQ16's discharge vocabulary already owns.

Ordering ties, by contrast, are **the author's problem, not the language's**. Two payments with the same `receivedOn` make `previous` (and any `latest`) ambiguous — but if the business's records can tie on the ordering datum, the *model* owes additional ordering criteria; that's a product decision, the same category as guard granularity (README §18, "No guard sugar"). The compiler's job is the usual one — fail closed where it can't prove the order total and the result depends on it — and the fix is model-side, never new machinery. `latest`/`first` are convenience helpers over the predicate grammar, not top-level language structures; whether the selector family grows richer ordering spellings is ordinary vocabulary expansion (README §22's selector-syntax item), not a problem with Velle.

Until the well-foundedness proof lands, commit-cadence remains the only fully-served spelling for the *mutation* form — the twin `when Payment` rule (README §19's showcase) buys order-safety structurally: commits are serialized, so fold order *is* commit order.

## Appendix: worked notes

Supporting examples the questions above lean on, not carried in the README.

### A cascade, concretely

*Context for OQ16 (sibling firing order) — the transaction shape the question reasons about. The nesting model itself is settled (README §11).*

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

A $50 `Deposit` against a −$20 account is **one transaction containing four commits**: the `Deposit` act; `ApplyDeposit`'s effects (at this commit the transition out of `Delinquent` occurs); `RestoreService`'s `ServiceRestoration`; `NotifyRestored`'s `RestorationEmail`. An unexpected error at any of them rolls back the whole envelope — the deposit never happened, and retrying the act re-produces the same sequence of commits intact.

Because each firing is its own commit, transitions inside a transaction are ordinary commit-local transitions — nothing new to define. A mid-transaction blip is real: if a later commit in the same transaction (a reinstatement fee, say) drops the balance negative again, the account genuinely left and re-entered `Delinquent`, and rules watching either transition fire — the same stance README §17 takes ("commit-triggered rules observe every membership the commit stream produces").

### Conditioned acceptance is a definition

*Context for OQ17 and OQ20 — the first of two patterns showing why most "reject the commit" sentences need no rejection machinery.*

"Don't accept the order unless the payment authorizes" is not a transaction statement — it is the definition of *accepted*, owned by the errors-are-refinements pattern:

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

### Validation rejection is data

*Context for OQ17 and OQ20 — the second pattern, and the reason those questions are down to a residue. Also referenced from README §8 ("Frozen fields").*

There is nothing to throw and nowhere to throw it — Velle has no stack and no functions, so exception propagation doesn't exist; a validation failure is a shape or a refinement membership, never a control-flow event. A validate-and-reject flow needs no transaction machinery:

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

The act lands (the request happened — audit for free: `count(RefusedVoid)`), the consequence never fires for the refused subset, the refusal lands as a fact the caller reads back (delivery is compilation's job). `ApplicableVoid`/`RefusedVoid` partition `VoidPayment`, exhaustiveness-checked — the forgotten `catch` block becomes a compile-time uncovered-subset error. Three levels of "invalid," only the first pre-commit: **(1)** can't inhabit the shape's type at all — rejected below the language, at the trust/input boundary, the `expose` surface (README §22, "External input mechanisms"); **(2)** well-shaped, business-invalid — rejection-as-data, above; **(3)** well-shaped, valid, consequence forbidden — resolved as the `frozen` write-gate (README §8, "Frozen fields"; OQ20 holds the residue).
