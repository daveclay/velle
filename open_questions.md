# Open Questions

The language's open questions. Settled results are promoted into `README.md` as they land; supporting examples the questions lean on are collected in the appendix at the bottom. OQ tags are stable — numbers are never renumbered or reused, so existing references (`OQ5`, `OQ16`) stay valid, and gaps in the sequence are questions already settled.

**What one commit is**

- **OQ16 — Order must not matter.** Can the compiler prove sibling firings commute, and that a transaction quiesces? The hard cases are data-dependent — aliasing, values, termination — where fail-closed rejects legitimate specs; calibration and the discharge vocabulary are the work.
- **OQ19 — `never`'s establishment model.** The construct is adopted (README §21); what remains is what "established" requires between declaration and use — the discharge vocabulary under the recommended `frozen`-playbook reading.

**The input boundary**

- **OQ5 — Marking a shape as "external input".** Who may commit a shape; which fields the committer supplies vs. which are internal — including supplied `id`s at trust/legacy boundaries.
- **OQ17 — Rejection scope.** If a refusal unwinds the act, what exactly unwinds and what is the committer told?
- **OQ20 — Is commit-refusal primitive?** Does any business case require that a well-shaped act not enter the state at all?

**Rule anatomy and guard ergonomics**

- **OQ7 — Rule anatomy, remaining threads.** What an exit rule may read; latency vocabulary; `on commit of <Shape>` narrowing.
- **OQ14 — Diagnostic-led guard adoption.** Is the canonical guard form pleasant enough for a compiler diagnostic to demand?
- **OQ15 — Ordered folds and firing order at a tick.** No honest discharge yet exists for a tick-cadence order-dependent fold.

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
- **Discharge vocabulary.** The aliasing case suggests OQ19's `never` invariants are not just verification but *proof inputs*: `never (Customer where referrer == this)` turns the collision condition into a proven impossibility the disjointness analysis may use — the author states a data invariant, the prover spends it. Whether declared invariants feed the confluence and one-writer analyses — and what else belongs in the discharge toolbox (conditioning one sibling on the other's outcome to make the dependency real; some not-yet-designed decreasing-measure spelling for cycles) — is calibration's concrete form.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (the parcel-splitting example above) — undecidable in general. The folds precedent (README §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined.

### OQ19. `never` — the establishment model

The construct is adopted (README §21): a top-level keyword over the predicate grammar (named-refinement form included), enforced at transaction end, proven inductively for rule-maintained invariants, compiled into the boundary for input-constrained ones, and spendable as a proof input (OQ16). What remains is the **establishment model** — what kind of statement a `never` is between declaration and use:

- *Verify-only* — a theorem the compiler proves from the rest of the spec. Fails twice: the proof-input role is empty (anything verifiable was derivable without the declaration), and the input-constrained class has nothing spec-side to verify.
- *Assume-only* — an unchecked axiom the prover spends. Its truth traces to nothing; out of character for the language.
- *The `frozen` playbook* (recommended) — declared once; the compiler derives a discharge obligation per potential violator: author-side fixes for rule-maintained writers (condition the act, restore within the transaction), automatically-emitted guardrails for the boundary, validation obligations at legacy mappings. Once every obligation is discharged the invariant is **established** — neither merely checked nor merely assumed — and only then spendable by other analyses.

Residue under the playbook reading: the **discharge vocabulary** — what counts as discharging, per kind of site — and whether the diagnostic-led flow stays tractable (OQ16's calibration). Also pending: formally retiring OQ20 against the compiled-guardrail ruling, folded into OQ5's boundary-declaration design.

## The input boundary

### OQ5. Marking a shape as "external input"

Mutation shapes arrive from outside the system — a user action, an API call — and nothing in the spec currently says so:

```
shape CorrectEmail {        -- committed by whom? a user? another rule? an API?
    customer: one Customer
    corrected: text
}
```

An author may eventually want to declare the distinction (e.g. only externally-committed shapes cross a trust/validation boundary; a `visible to`-style clause may want to constrain who can commit one). Deferred — for now a mutation is just another shape.

**The anchor use case** — an externally-submitted shape saved whole as a record, with `createdAt`/`updatedAt`:

```
shape Review {
    product: one Product
    stars: Number
    body: text
}
```

Committing a `Review` *is* saving the record — persistence needs no rule (README §12, "No act-level sugar"). What remains of the use case is the timestamps: `createdAt`/`updatedAt` are commit metadata, declared as `timestamp on create` / `on update` fields (README §5) — inherently never committer-suppliable. Where the author wants a timestamp *as model data* — business rules over `submittedOn`, correctable later — the spelling is `submittedOn: Date initially now` (README §5); what remains for this OQ is whether such an ordinary field can be marked internal (not committer-suppliable), the way `timestamp` fields inherently are.

So the boundary declaration this OQ contemplates has three parts: **(1)** who may commit the shape, **(2)** which fields the committer supplies vs. which are internal (an `initially` field is the natural candidate), and **(3)** what commit metadata is readable in predicates and derivations — answered for timestamps (`timestamp` fields, README §5); whether the commit trace is queryable beyond them rides with `why`/provenance (README §22).

Folded in from OQ21 (retired): **supplied vs. generated `id`.** By default the implementation generates an instance's `id` (README §5); at a trust/legacy boundary it is explicitly supplied — a legacy table's primary key, an upstream system's reference. Who may supply one, and when supplying is required, is part (2)'s declaration; the mapping-side mechanics belong to the Mapping item (TODO.md).

### OQ17. Rejection scope

If a refusal unwinds the act (commit-refusal), what exactly unwinds, what the committer is told, and whether rejection can be partial ("accept the deposit, refuse only the tier change") — declarable policy or always incoherent? Reified refusal ("Validation rejection is data," in the appendix) dissolves most of this — nothing unwinds and "what the committer is told" is a shape — leaving only the genuinely commit-refusing subset, which OQ20 delimits.

### OQ20. Is commit-refusal primitive, or derivable from reified refusal?

A full validate-and-reject flow needs no transaction machinery ("Validation rejection is data," in the appendix), and immutability needs no commit-refusal either (`frozen` — README §8, "Frozen fields") — which raises whether the language needs commit-refusal at all above the type/trust boundary (level 1 of the invalid layering — OQ5's territory, where it's irreducible). What remains is the residue: does any business case require that a well-shaped act *not enter the state* (compliance/data-retention pressures — "we may not store this request at all")? If so, is that declared per-act-shape or per-boundary (OQ5's who-may-commit declaration being the natural home)?

## Rule anatomy and guard ergonomics

### OQ7. Rule anatomy and timing — remaining threads

The core anatomy — condition, trigger source, entry/exit transitions, the tick law, firing reliability under the transaction model — is settled (README §11, §13, §17, §18). Still open:

- **What an exit rule may read.** README §13 is being re-derived under commit-local transitions; the settled part is the constraint — captured properties retract at the very moment a `when leaving` rule fires, so reading them is a compile error — but the full account of what the body may reference (current data, durable evidence produced during membership) needs its own pass. §13 carries a tentative marker pointing here.
- **Latency vocabulary** — `on` expresses the evaluation *source*, not latency *requirements*. Is "immediate by default, named schedule otherwise" enough, or do deadlines ("within 24h") deserve first-class expression the compiler validates against declared cadences?
- **`on commit of <Shape>` narrowing** — "only withdrawals suspend, not fee assessments." Expressible and occasionally meaningful, but it can silently miss entry paths; per flexible-not-restrictive it would be allowed *with* the compiler reporting exactly which entry paths go unobserved. Not yet designed.

### OQ14. Diagnostic-led guard adoption

Fold enforcement (README §19) means the compiler will be *proposing* guards to authors mid-error. With guard sugar dropped, the fix-it suggestion writes the canonical form itself, so the question is now exactly this: is the canonical form pleasant enough to be what a diagnostic asks an author to write? Ergonomics and enforcement aren't separable — a required guard that's miserable to write makes enforcement hostile; one that reads as the business rule makes it a teaching moment.

### OQ15. Ordered folds and firing order at a tick

Exposed by the fold analysis (README §19): a tick-cadence order-dependent fold (a nightly streak sweep) owes a reordering obligation with no honest discharge — the pending records fire separately at the tick with no defined order among the firings (README §16), declared tolerance is wrong for a streak, and the two missing spellings are both grammar, not analysis: an ordering clause giving one tick's firings a defined order (`ordered by`?), and ordered folds in the derivation grammar (which would let a streak be a derivation over ordered history, dissolving the mutation entirely — README §22's derived-value grammar and selector-syntax items are adjacent). Until one exists, commit-cadence is the only fully-served spelling for order-dependent folds.

## Appendix: worked notes

Supporting examples the questions above lean on, not carried in the README.

### A cascade, concretely

*Context for OQ16 (sibling firing order) and OQ19 (nesting) — the transaction shape those questions reason about.*

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

The act lands (the request happened — audit for free: `count(RefusedVoid)`), the consequence never fires for the refused subset, the refusal lands as a fact the caller reads back (delivery is compilation's job). `ApplicableVoid`/`RefusedVoid` partition `VoidPayment`, exhaustiveness-checked — the forgotten `catch` block becomes a compile-time uncovered-subset error. Three levels of "invalid," only the first pre-commit: **(1)** can't inhabit the shape's type at all — rejected below the language, at the trust/input boundary (OQ5); **(2)** well-shaped, business-invalid — rejection-as-data, above; **(3)** well-shaped, valid, consequence forbidden — resolved as the `frozen` write-gate (README §8, "Frozen fields"; OQ20 holds the residue).

