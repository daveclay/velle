# Open Questions

The language's open questions. Settled results are promoted into `README.md` as they land; supporting examples the questions lean on are collected in the appendix at the bottom. OQ tags are stable — numbers are never renumbered or reused, so existing references (`OQ5`, `OQ16`) stay valid, and gaps in the sequence are questions already settled.

**What one commit is**

- **OQ21 — Record identity.** Does an instance have identity beyond its stored values? What makes a retry "the same act"?
- **OQ16 — Order must not matter.** Can the compiler prove sibling firings commute, and that a transaction quiesces?
- **OQ19 — Boundary grammar and policy residue.** Mixed-list grammar, nesting, `tolerates loss`, `never` invariants, legibility.

**The input boundary**

- **OQ5 — Marking a shape as "external input".** Who may commit a shape; which fields the committer supplies vs. which are internal.
- **OQ22 — Declaring commit metadata.** A spelling for `createdAt`/`updatedAt` as readable-never-writable facts of the commit trace.
- **OQ17 — Rejection scope.** If a refusal unwinds the act, what exactly unwinds and what is the committer told?
- **OQ20 — Is commit-refusal primitive?** Does any business case require that a well-shaped act not enter the state at all?

**Rule anatomy and guard ergonomics**

- **OQ7 — Rule anatomy, remaining threads.** What an exit rule may read; latency vocabulary; `on commit of <Shape>` narrowing.
- **OQ13 — The `each`/multi-schedule pass.** Does the disarm proof extend to `each` bodies and multi-cadence `on` lists — and is an `each` iteration its own transaction?
- **OQ14 — Diagnostic-led guard adoption.** Is the canonical guard form pleasant enough for a compiler diagnostic to demand?
- **OQ15 — Ordered folds and batch ordering.** No honest discharge yet exists for a batched order-dependent fold.

## What one commit is

### OQ21. Record identity

Spun out of OQ6 when the rest of it settled: a commit is exactly **one act instance** — an author who wants several things to arrive together declares a container shape and commits that single instance, with the parts as related shapes (README §4) — which keeps "can these triggers coincide?" answerable from trigger-shape overlap alone (the one-writer check, README §12). What the settlement exposes is identity: a guard cannot dedupe *repeated acts* — two identical `Deposit` commits are two distinct instances and two firings, each exactly once (README §19's dependency). Nothing yet says what identifies a record: whether an instance has identity beyond its stored values, whether two indistinguishable instances are one fact or two, and what would make an external submitter's retry "the same act" rather than a new one (an idempotency key as ordinary data the author declares, or something the language owes). The same question reaches outward at reconciliation: the intent-before-effect pattern (README §11, "Transactions and `after commit`") resolves an unresolved external-effect intent by asking the processor what became of it — which means recognizing *our* attempt among the processor's records, an identity the data must carry. Ties into occurrence ordering/reification (README §21, "Exit from act-entered refinements").

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

**Open:**

- **The analysis itself.** Commutativity/confluence checking is charted territory — term rewriting's critical pairs and Newman's lemma (local confluence + termination ⇒ confluence, which ties this proof to quiescence below), CHR confluence tests, Datalog evaluation strategies. What Velle's version is — and how coarse it can be before it rejects legitimate specs — is the work. Fail-closed is given (uncertainty errors, README §12's stance); calibration is not.
- **Quiescence.** A transaction completes when no rule's condition is newly matched — it must quiesce, and Newman's lemma needs termination for confluence to follow from local checks. Cycles in the static condition graph are detectable (derived trigger sets), but convergence can be value-dependent (a deposit rule producing deposits) — undecidable in general. The folds precedent (README §19) applies: prove quiescence structurally (a DAG, or cycles broken by disarming guards), fail closed on the rest. Whether the disarm proof suffices is unexamined.

### OQ19. Boundary grammar and policy residue

- **Mixed-list grammar** — does `after commit, Hourly` read its preposition per-entry, or does the clause split (`after commit on Hourly`)?
- **Nesting** — an `after commit` rule's consequences share *its* transaction by default, so a cascade is a tree of transactions, each rooted at a declared, inherent, or forced boundary. Consequences unexplored.
- **`tolerates loss`** — the fire-and-forget escape for external effects (an analytics ping the PO shrugs about); extending the `tolerates` vocabulary beyond fold hazards (README §19) is unexamined.
- **`never` invariants** — a spec-level declaration over refinement combinations (`never (Account where balance >= 0 and suspended)`) as *verification* of chosen boundaries: the compiler proves the written boundaries honor the stated observable-state fact. Independently valuable, README §1's consistency-checker category; where it lives is undecided.
- **Legibility at scale** — whether boundary-by-preposition plus apparatus stays readable in realistic specs; the calibration question rung recognition deferred to worked examples (README §20).

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

Committing a `Review` *is* saving the record — persistence needs no rule (README §12, "No act-level sugar"). What remains of the use case is the timestamps: `createdAt`/`updatedAt` are commit metadata, not author-maintained fields — declaring them is its own question (OQ22, below). Where the author wants a timestamp *as model data* — business rules over `submittedOn` — the spelling is `submittedOn: Date initially now` (README §5); what remains for this OQ is whether such a field can be marked internal (not committer-suppliable).

So the boundary declaration this OQ contemplates has three parts: **(1)** who may commit the shape, **(2)** which fields the committer supplies vs. which are internal (an `initially` field is the natural candidate), and **(3)** what commit metadata is readable in predicates and derivations (→ OQ22).

### OQ22. Declaring commit metadata (`createdAt`/`updatedAt`)

Spun out of OQ6. Every change to a record traces to a commit ("a system never does anything on its own"), so *when an instance was created* and *when it last changed* are already facts of the commit trace — and neither should be an author-maintained field:

- `createdAt` can't be client-supplied (trust) and can't be `= now` (shape-body `=` is a live derivation — it would change constantly). It's a fact *about the commit*, not about the data.
- `updatedAt` maintained by hand would mean every rule that assigns any field of the shape also assigns `updatedAt = now` — boilerplate that lies the first time a rule forgets it (though one-writer at least forces the writers' triggers disjoint).

Author-maintained timestamp fields are a workaround from systems where the commit log isn't first-class; in Velle they should be **readable, never writable**. Needed: a way to declare them in a shape — a marker for a property that reads commit metadata, a fourth kind alongside stored, derived, and `initially`. Open: the spelling; which metadata exists (creation commit, last-write commit — whole-instance or per-field); and whether the commit trace is queryable in predicates and derivations beyond these (the trace `why` and impact analysis walk).

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

### OQ13. The `each`/multi-schedule pass

Whether the disarm proof extends cleanly beyond the simple rule shape it was settled on. Two directions to check: multi-cadence `on` lists (`ApplyDeposit ... after commit, Hourly` — the disarm proof must hold under every trigger source for the durability backstop to be safe), and `each` bodies, where the guard predicate lives inside the selector, so the proof obligation is per iterated instance, not per rule firing:

```
rule SendReminder on Daily {
    each (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days)) {
        Reminder from { invoice: this, sentOn: today }
    }
}
```

No conclusions yet; needs worked examples. Rule-level transaction granularity is settled — each firing at a tick is its own transaction (README §17) — but whether each `each` *iteration* is likewise its own transaction is part of this pass: per-iteration guards (README §16) pull toward per-iteration transactions (one member's failure shouldn't unwind the rest of the sweep), while a body being "exactly one commit" pulls the other way.

### OQ14. Diagnostic-led guard adoption

Fold enforcement (README §19) means the compiler will be *proposing* guards to authors mid-error. With guard sugar dropped, the fix-it suggestion writes the canonical form itself, so the question is now exactly this: is the canonical form pleasant enough to be what a diagnostic asks an author to write? Ergonomics and enforcement aren't separable — a required guard that's miserable to write makes enforcement hostile; one that reads as the business rule makes it a teaching moment.

### OQ15. Ordered folds and batch ordering

Exposed by the fold analysis (README §19): a batched order-dependent fold (a nightly streak sweep) owes a reordering obligation with no honest discharge — declared tolerance is wrong for a streak, and the two missing spellings are both grammar, not analysis: an ordering clause giving a tick-cadence batch a defined iteration order (`ordered by`?), and ordered folds in the derivation grammar (which would let a streak be a derivation over ordered history, dissolving the mutation entirely — README §21's derived-value grammar and `latest`-ordering items are adjacent). Until one exists, commit-cadence is the only fully-served spelling for order-dependent folds.

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

