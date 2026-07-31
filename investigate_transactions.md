# Transactions

How rules that trigger other rules might require transaction boundaries.

This picks up the cascade thread of `investigate_state.md` OQ6 ("What, exactly, is one commit?") and gives it its own investigation. OQ6 keeps the umbrella question; this doc owns the specific one: when one commit's effects cause entry into a refinement another rule is watching, and that rule's effects cause another, **where do the atomic boundaries sit in the chain** — and how much of the answer is *description* (business-visible, needing Velle vocabulary) versus *compilation* (isolation levels, locking, write-ahead logs — none of Velle's business, per README §1)?

"Transaction" is used here as an investigation term, not a proposed keyword. Velle deliberately has no transaction vocabulary: a commit is already the atom of state change (README §4), and for a single act with a single rule firing, that's the whole story — pre-state and post-state are well-defined at the commit, the firing's effects are part of accepting it, done. The word only earns attention at the place where "the atom of state change" stops being obviously singular: cascades.

## The cascade, concretely

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

An account sits at −$20 and a $50 `Deposit` is committed. The deposit's commit fires `ApplyDeposit`; the balance mutation moves the account from −$20 to $30; that is exactly the transition `leaving Delinquent` names, so `RestoreService` fires; the `ServiceRestoration` it produces is itself the condition `NotifyRestored` watches, so that fires too. One external act; a chain of consequences two and three removes from it, none of which the depositor's act mentions and all of which the compiler can already see coming (the derived trigger set, README §11, read forward).

The question in one sentence: **is that one state transition, or four?**

## What settled machinery already forces

Constraints first, models after — each of these is already committed to elsewhere in the spec, so any answer must satisfy all of them.

**1. Firing atomicity is the floor.** Guard soundness already demands that a mutation and its witness enter the state together (README §18; OQ6 flagged this as "the question in miniature"): a crash between `account.balance = ...` and `this.applied = true` re-arms the guard — the double-deposit bug the disarm proof exists to kill. So the minimum atomic grain is *one rule firing's body*: a body is never partially applied. Whatever a transaction boundary turns out to be, it can't cut through a body. (`then` doesn't weaken this: it orders effects *within* the atomic body — its "intermediate moments" are ordering commitments for compilation, not observable states, unless an external effect is involved — below.)

**2. The cascade graph is static.** Derived trigger sets mean the compiler knows, for every rule, exactly which commits can fire it (README §11). A rule's effects are themselves state changes the same computation applies to, so "which rules can this rule's effects trip" is the same analysis one level up — the whole cascade graph is known at compile time. This is the same move one-writer made: whatever semantics get chosen here must be *statically checkable*, fail-closed, not a runtime discovery.

**3. One-writer's unit is "one commit" — so its scope is decided here.** README §12 errors when one commit could fire two assignments to the same field. Consider:

```
shape GoodStanding = Account where balance >= 0

rule DowngradeTier when leaving Delinquent {
    account.tier = "standard"
}

rule UpgradeTier when GoodStanding {
    account.tier = "preferred"
}
```

Leaving `Delinquent` and entering `GoodStanding` can happen at *the same commit* — one deposit trips both. If the cascade is one commit, this is precisely the one-writer error, caught transitively along the cascade graph. If each firing is its own commit, both writes are legal and some order decides — but *what* order? The two firings are siblings fired by the same commit, and nothing yet defines their sequence. Undefined sibling order resurrects exactly the ambiguity one-writer exists to kill; either the check extends across cascades, or sibling order becomes defined. There is no third option.

**4. `forbidden` liens must see consequences.** A lien rejects "any change that would cause the exit" (README §13) — and the exit-causing change can arrive as a downstream firing's effect rather than as the act itself:

```
shape Invoice {
    amount: Money
    payments: many Payment
    balance: Money = amount - sum(payments where not voided, amount)
}

shape Payment {
    invoice: one Invoice
    amount: Money
    voided: boolean initially false
}

shape SettledInvoice = Invoice where balance <= 0

shape VoidPayment {
    payment: one Payment
    reason: text
}

rule ApplyVoid when VoidPayment {
    payment.voided = true
}

rule SendReceipt when SettledInvoice {
    Receipt from { invoice: this, sentOn: now }
    when leaving SettledInvoice: forbidden      -- a receipt, once sent, stays true:
                                                -- a settled invoice can't be unsettled
}
```

An agent commits `VoidPayment` against a payment on a settled, receipted invoice. The act is innocent — it names a payment and a reason, nothing more. `ApplyVoid`'s effect flips `voided`; the invoice's derived `balance` moves from $0 to $75; that *is* an exit from `SettledInvoice`, and the `Receipt`'s lien forbids it — from two relationships away, on a shape the act never mentions. Letting the exit through makes `forbidden` decorative, and silently dropping the `ApplyVoid` firing leaves a durable `VoidPayment` whose declared consequence never happened — the record lies. If rejection means *the commit is refused*, then refusal must reach the `VoidPayment` act itself, and validation has to run over the whole consequence closure before anything is accepted — the strongest pull toward closure-shaped semantics, independent of crash behavior entirely. But "the record lies" holds only when the refusal goes unrecorded — reifying the refusal as data is a third outcome that keeps every fact honest without unwinding anything ("Validation failures are data," below). What this constraint irreducibly demands is narrower than closure durability: the lien must *see* the consequence before it becomes real — whether seeing leads to refusing the act or to producing a refusal fact is a modeling choice (OQ20).

**5. External effects can't be inside any boundary.** An API call is an outcome commit whose only db-visible trace is its witness (README §18). No boundary drawn around state can make a call into the world atomic with it — the call happens *out there*. A cascade containing an external effect therefore contains a boundary no semantics can remove: the classic saga problem, arriving on schedule. The witness pattern is already the answer's shape (the call's occurrence is recorded as data; guards make retry safe); what's unresolved is what the *rest* of the cascade does when the call succeeds and a later link fails, or vice versa.

## Model A: the cascade is one commit

The accepted commit is the **consequence closure**: the act plus every transitively-fired effect, evaluated until nothing more fires, becomes durable as a single state transition — or is rejected as one.

What it buys:

- **No partial cascades.** A crash yields "the deposit never happened" — retryable, no wedged intermediate state where the balance is positive but service is still suspended.
- **`forbidden` works naturally.** The lien check is just part of closure evaluation; rejection rejects the act, and the depositor hears "refused," never "landed but stuck."
- **No interleaving anomalies.** No other commit can land between `ApplyDeposit` and `RestoreService` and observe the account non-delinquent-but-suspended.
- **One-writer extends cleanly.** The `tier` collision above is a compile error naming both rules — the same whole-spec, effects-at-a-distance diagnostic the language already prefers.

What it costs, or opens:

- **The fixpoint question.** `RestoreService` fires because `ApplyDeposit`'s effect changed state — its predicate evaluation must see post-`ApplyDeposit` state while the closure is still open. So rules inside the closure evaluate against intermediate states that officially "don't exist." Worse: if entry and exit are judged endpoint-to-endpoint, a refinement entered at step 2 and exited at step 4 of the same closure *never happened* — a transient-membership blip at closure granularity (the same question §17 answers per-rule for commit-vs-tick, at a new grain, currently with no clause to state a choice in). But if rules fire on *stepwise* transitions instead — each firing's changes observed as they land — intermediate states are semantically real after all, and the closure is just durability packaging around Model B. Which transitions are the real ones is the crux, and picking "endpoint" lands Velle in stratification territory — which rules fire depends on the final state, which depends on which rules fire. Known ground in Datalog (stratified negation); the prior-art mining item (`TODO.md`) becomes directly load-bearing here.
- **Termination.** A closure must terminate to be a commit at all. The cascade graph is static, so cycles are detectable — but not all cycles are infinite:

  ```
  rule Reward when LargeDeposit {
      Deposit from { account: account, amount: amount * 0.01 }   -- a deposit that could be Large...
  }
  ```

  Whether this converges depends on values, which is undecidable in general. The folds precedent (README §19) says what to do: prove termination structurally for known forms (a DAG; a cycle broken by a disarming guard — the disarm proof already shows the re-trigger predicate goes false), and fail closed on the rest, with the diagnostic demanding the author restructure or the language grow a bound. Whether the disarm proof is *sufficient* as a termination proof is unexamined.
- **External effects can't join.** A closure containing an outcome commit either splits at the call (the call and its witness form a second, dependent unit — the chain sneaks back in through the front door) or the language forbids external effects at mid-cascade positions (probably too restrictive: `NotifyRestored` above is exactly a mid-cascade external effect and a completely ordinary business ask).

## Model B: every firing is its own commit

"Rules ground in commits" read literally: a firing's effects are just another commit, which triggers downstream rules the same way an external act does. The cascade *is* a chain of commits.

What it buys:

- **One uniform commit story.** No closure/fixpoint machinery; entry and exit stay commit-local transitions exactly as settled (README §11); a rule can't tell whether its trigger was an external act or another rule's effect — pleasing symmetry.
- **Crash windows are explicit, and the machinery for them already exists.** The gap between firings is real, so the guard patterns were built for precisely this: `on commit, Hourly` backstops, disarm proofs, reconciliation sweeps. Nothing new to invent — "self-healing" is already the language's answer to dropped work.
- **External effects stop being special.** Every link boundary is a boundary; the saga shape is the native shape.

What it costs:

- **Partial cascades are durable, observable business states.** Balance restored, service still suspended, for an unbounded window unless a backstop is declared. Sometimes that's honest (eventual consistency is a real business policy); it needs to be *chosen*, not defaulted into.
- **A dropped transition-triggered firing is unrecoverable.** Suppose R2 fires only on a transition R1's effects cause (`when leaving Delinquent`, where R1 holds the only write path into `balance`). Under chain semantics, an unexpected failure of R2's commit strands the system permanently: the transition existed only at the commit that caused it, and current state cannot reconstruct it — "restored" and "never delinquent" are indistinguishable from current data alone (the same fact that made `when leaving` irreducible, README §13). No sweep can find the pending work, because sweeps read current state and the trigger was never data. Nothing in the system will ever re-trigger R2. Call it **the transition law**, the tick law's sibling: *a transition is not data — a consequence of entering or leaving a refinement either fires inside the commit that caused the transition, or the obligation must first be reified as data (the guard); there is no third place for the trigger to live.*
- **`forbidden` breaks — or forces closure validation anyway.** By the time the lien-tripping firing runs, the originating act is durable; rejection can't reach it. Either `forbidden` weakens to "the downstream firing fails" (leaving the cascade wedged mid-chain — visibly worse than rejection), or commit acceptance must *predictively* evaluate the consequence closure before accepting — at which point Model B has conceded constraint 4 and runs closure validation while keeping chained durability. Notable: that concession may be the actual design.
- **Sibling order is undefined.** The `tier` example: two firings from one commit, two separate commits of their own, no defined sequence — last-in-wins with no fact of the world to supply "last." Model B must either define sibling order (declaration order? `then` promoted to a cross-rule position? both smell like imperative sequencing creeping back in) or extend one-writer across cascades anyway — again conceding the transitive analysis.
- **Interleaving.** An unrelated external act can land between links and observe (or mutate) the mid-cascade state. Every refinement evaluated during the window sees a world the initiating act's author never imagined as observable.

## Where the models converge

Both need the firing-atomic floor. Both need the static cascade graph and analyses over it — termination/cycle checking, and the transitive one-writer *analysis* (only the verdict differs: compile error vs. defined order). Both need consequence-visible *validation* for `forbidden` to mean anything — closure-scoped if refusal unwinds the act, gate-scoped if refusal is reified as data ("Validation failures are data," below). Both need the witness pattern at external boundaries. Both need a story for membership blips at whatever grain internal states are unobservable.

That convergence suggests the real design space isn't "A or B" but **where the boundaries fall and who says so** — a hybrid: firing-atomic always; closure-atomic by default up to the first boundary that *must* exist (an external effect); chained beyond it, with the guard machinery making each link crash-safe. The genuinely open residue is then small and nameable:

- **"Has happened" vs. "will happen" is PO-legible.** When the teller says "deposit accepted," is service restored, or promised? A Product Owner can answer that per consequence, and Velle already has the pattern of making exactly this kind of choice visible in one clause (`on commit` vs. `on Nightly`, README §17 — immediacy as per-rule policy). Atomic-with-the-act vs. eventually-after-the-act may be the same species of declaration: a transaction boundary as a *declared, business-visible* policy at a cascade edge, not inferred machinery. Candidate framing only — no syntax proposed, and the default matters more than the clause (silent-eventual is a footgun; silent-atomic makes every cascade a big transaction whether wanted or not).
- **Rejection scope is business-visible.** "Your deposit was refused" vs. "your deposit landed but the restoration is stuck" are different products. Whatever unwinds on a lien or validation failure must be statable or derivable — never an implementation accident.
- **Everything else is compilation.** Isolation, locking, retries, idempotency keys, whether the closure runs synchronously in one database transaction or as an orchestrated queue — all of it, provided the declared observability and durability semantics hold (README §1).

## Capture, then call: eventual on purpose

The strongest evidence that boundaries belong to the Product Owner is a use case where the *right* answer is a boundary, deliberately: capture a record atomically, then make a follow-up call to a flaky external API — where the call is allowed to fail and be retried.

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
    CrmSync from { signup: this, syncedOn: now }    -- outcome commit: the call's witness
}
```

Committing the `Signup` *is* the capture — persistence needs no rule (README §12, "No act-level sugar"). The CRM call is an outcome commit whose only db-visible trace is its `CrmSync` witness (README §18): a failed call lands no witness, the guard stays armed, and the `Hourly` backstop retries until one does — *immediately, and self-healing hourly*. Every piece is settled machinery; nothing new was needed to spell the eventual design. The signup is never hostage to CRM uptime, the retry cadence is visible in the header, and "how far behind can the CRM be?" is answered by the schedule name. This is also OQ18's halt-and-self-heal answer working as intended for the lag case: the boundary between the two links is not a failure mode to engineer away but the product itself.

Two things the case sharpens:

- **It is a counterexample to closure-by-default.** Model A's default reading would fold the CRM call into accepting the signup — a CRM outage rejects signups, precisely the product nobody asked for. But flip the domain and the same cascade shape flips polarity: an order whose follow-up call is a payment *authorization* should probably not be accepted while the call can't be made — the PO wants acceptance conditioned on the outcome. Same shape, opposite correct boundary, purely on business grounds — so no global default can be right, and the per-edge, PO-answerable question ("if the follow-up can't happen right now, does the act still land?") is exactly what OQ19 asks whether to make declarable.
- **"Allowed to fail" needs failure to be data.** Retry-until-success is only half the product sentence; the other half is usually "give up after three tries and tell someone." An attempt count is cross-tick memory, so it must be data (the tick law, README §17): reify each try (`SyncAttempt`), express failure outcomes as refinements (the errors-are-refinements pattern, README §2), guard the retry on `count(SyncAttempt for this) < 3`, and let a separate rule watch the exhausted state. All expressible today, and squarely the mechanical-pattern territory "No guard sugar" accepted as hand-written; whether flaky-call-with-retry-budget is common enough to deserve recognition as a named rung (README §20) is the same calibration question rung recognition already deferred to worked examples.

## Validation failures are data

There is nothing to throw and nowhere to throw it. Velle has no stack and no functions, so "an Exception propagating up until something catches it" doesn't survive translation into the language at all — the catch/finally apparatus is stack machinery. The README already replaced its cousin for interaction outcomes: an act's resulting states — success, error, retry — are ordinary refinements (§2, errors-are-refinements). Validation belongs to the same regime: **a validation failure is a shape or a refinement membership, never a control-flow event.**

A PO stating a validation rule and a rejection flow is then ordinary modeling — the constraint-4 lien case, remodeled:

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

Three observations:

- **The rejection flow is exhaustiveness-checked.** `ApplicableVoid` and `RefusedVoid` partition `VoidPayment` — exactly what the refinement exhaustiveness/overlap goal proves (README §8; the `states of` candidate, §21). The forgotten `catch` block becomes a compile-time hole: a `VoidPayment` subset no rule handles is a reportable gap, not a silent drop.
- **The attempt is honest data.** The agent *did* request the void; recording the request and its refusal describes the world truthfully — and buys audit for free: "how often do agents try to void receipted invoices?" is `count(RefusedVoid)`, answerable only because refusal was modeled as data. How the refusal reaches the agent synchronously (the form says no) is a delivery concern — compilation reads the `VoidRefusal` back to the caller; the language's job ends at the fact.
- **No transaction machinery is involved.** Nothing unwinds because nothing needs unwinding: the act lands (a fact), the consequence never fires for the refused subset (ordinary refinement evaluation at the commit — the settled entry semantics), the refusal lands (another fact). The only atomicity consumed is the firing-atomic floor. A PO can model a full validate-and-reject flow without the language ever having possessed a rollback.

**What this does to constraint 4.** The lien's "reject the commit" reading assumed refusal must precede durability. Reified refusal is the alternative that keeps every fact and needs no boundary — which suggests `forbidden` itself might be *definable* as derived machinery rather than primitive unwind: the compiler already knows every writer whose effect could cause the guarded exit (the derived trigger set), so the lien could desugar to gates on each of those writers plus a refusal witness — one declaration by the author, the repetition derived, the same economy `forbidden` was buying, minus the transactional unwind. Whether that's faithful to what a PO means by "you can't edit line items on an issued invoice" — or whether some refusals genuinely mean *the act must not enter the state* — is OQ20.

**The layering that remains.** Three levels of "invalid," only one of which touches transactions:

1. **Not even the shape.** A submission that can't inhabit the act's type (text where `Money` goes, a missing required field) isn't a malformed `VoidPayment` — it's not a `VoidPayment` at all. There is nothing coherent to record; rejection here is below the language, at OQ5's trust/input boundary, and is the one place refusal is pre-commit by construction.
2. **Well-shaped, business-invalid.** The territory above — rejection-as-data, settled machinery, no boundaries required.
3. **Well-shaped, valid, consequence forbidden.** The lien case — expressible either as commit-refusal (transactional, closure-validated) or as reified refusal (gate-scoped, no unwind). Which is primitive and which is derived is the open question.

## Writing the boundary

If boundaries are Product Owner decisions, an author needs a way to write them — visibly, in the spec, where a reviewer or a PO reads them back. This doc has accumulated three decisions a boundary could encode; the first move is noticing that one of them is already spelled, and was never a transaction statement at all:

**"Don't accept the act unless the follow-up succeeds" is a definition, not a boundary.** The order/payment-authorization polarity from the capture-then-call section dissolves into refinement modeling the same way validation did:

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

Fulfillment rules hang off `AcceptedOrder`, never `Order`. The PO's "don't accept the order unless authorized" was never about atomicity — it's the *definition of accepted*, and the errors-are-refinements pattern (README §2) already owns it. The order landing as a fact ("an order was placed, authorization pending") is honest and useful; nothing downstream can act on it until membership says so. Exhaustiveness checking proves the pending/accepted/declined partition covers every order.

What remains genuinely boundary-shaped is the pair from OQ19: **(a)** does a consequence join the act's state transition or follow it, and **(b)** if it follows, how far behind may it fall and how does it heal. Candidate spellings, stressed against the principles:

1. **A header keyword** — `rule RestoreService when leaving Delinquent atomic { ... }` / `eventual`. Rejected-leaning: "atomic" names the mechanism, not the business meaning; a single bit can't carry (b) at all — "eventual" without a heal path and a lag bound is exactly the silent-eventual footgun; and, per below, the bit duplicates information the rule's own apparatus already states better.
2. **A declaration on the act shape** — `Deposit` marking its own closure atomic. Rejected: the act doesn't know its consequences — rules attach elsewhere, later (the derived-trigger-set direction of dependency); the decision belongs where the consequence is written.
3. **A spec-level invariant** — a `never` declaration over refinement combinations: `never (Account where balance >= 0 and suspended)` — "there is no observable moment of good standing with suspended service." Attractive, and squarely in §1's compiler-as-consistency-checker category: the PO states the observable-state fact, and the compiler *derives* that `RestoreService` must join the deposit's transition to satisfy it — erroring if an external effect in the path makes it unsatisfiable. But as the primary spelling it's indirect (the reader re-derives which rules got welded together), and it's really a *verification* construct: the check that whichever boundary was written honors the stated business fact. Worth pursuing on its own merits; not the answer to "what does the author type at the edge."
4. **The apparatus is the spelling.** The lean. "Eventual" was never one bit of information — it's a guard (what makes retry safe), a backstop cadence (how far behind, how it heals), and a witness (what *done* means). All three are already refinement- and header-visible. Side by side, the two products are already two different spellings:

```
-- "restoration is part of accepting the deposit" — joined: bare rule, no apparatus
rule RestoreService when leaving Delinquent {
    ServiceRestoration from { account: this, restoredOn: now }
}

-- "capture always; sync follows, self-healing hourly" — chained: `after commit`
-- declares the boundary, and the apparatus proves it safe ("Declaring the boundary")
shape UnsyncedSignup = Signup where not exists CrmSync for this

rule SyncToCrm when UnsyncedSignup after commit, Hourly {
    CrmSync from { signup: this, syncedOn: now }
}
```

The eventual rule cannot help but announce itself: its trigger is a dischargeable state, its header carries a healing cadence, its witness names completion. No annotation could say more, and every piece is load-bearing — nothing is ceremony, which is what the no-sugar stance (README §18) demands of a spelling. The PO's "how far behind can the CRM be?" is answered by a schedule name in a header; "what makes retrying safe?" by the guard predicate; "what counts as done?" by the witness shape. A bare rule, by contrast, reads as *joined*: part of the transition, no gap, no heal path because none is needed.

Under this lean, the fail-closed demand-a-decision from OQ19 gets concrete:

- **Internal (pure-state) consequences may default to joined.** Joining holds no availability hostage — it's all local state; the cost is transaction size, a compiling concern. Authors who want internal lag (snapshots, reconciliation sweeps) already choose visibly tick-flavored spellings (README §19–20). So silent-atomic survives for internal edges — and silent-eventual exists nowhere, because eventual always wears its apparatus.
- **External-effect rules can never join** (constraint 5), so a *bare* rule whose body contains an outcome commit is a compile error, and the diagnostic asks for exactly the missing decisions: "this call's failure would lose work with no heal path — declare the boundary ("Declaring the boundary," below), gate the rule on a dischargeable state, and add a backstop schedule; or state that loss is acceptable." The escape for genuine fire-and-forget (an analytics ping the PO shrugs about) wants a tolerance in the `tolerates` family — `tolerates loss`, signing the risk by name on the rule or witness — but extending that vocabulary beyond fold hazards is unexamined.

The default this implies is now settled the strong way (OQ16): all rules fire within a single transaction unless boundaries are explicitly set — decided not just by the spelling argument above but by recoverability (the transition law): a plain rule chained into its own transaction and failing would be *unrecoverable*, since its transition trigger was never data. What the settled default still owes is the closure semantics for the joined region (OQ16's sub-threads: endpoint-vs-stepwise transitions, termination, transitive one-writer).

### Declaring the boundary: the `on` clause

The settled default sharpens where a declaration is needed at all. Within one transaction there is no gap between trigger and firing — so for a plain rule, the crash window that motivated durability guards (README §18) no longer exists: a firing can't be "dropped" without the whole act rolling back. A boundary — a place where a gap is real, where an unexpected error rolls back *to the boundary* instead of to the act — now arises in exactly three ways:

- **Inherently, at schedule triggers.** A tick is its own commit; by the time `on Hourly` fires, the triggering act's transaction is long gone. `on <schedule>` always implies a new boundary — there is nothing left to join, so no annotation is possible or needed.
- **Forcibly, at external effects.** Constraint 5 — the call happens in the world and can't join any boundary.
- **By declaration.** The one place an author needs a spelling — and it's only ever needed for the `commit` source, since that's the only trigger source where joining is otherwise the default. The chosen spelling is a preposition, not an annotation: **`after commit`**.

```
rule SyncToCrm when UnsyncedSignup after commit, Hourly {
    CrmSync from { signup: this, syncedOn: now }
}
```

`on commit` — the firing is part of the act's commit; `after commit` — the firing becomes *its own commit*, immediately following the act's. Read aloud: "when a signup is unsynced, after the commit, and hourly." An unexpected error executing the rule rolls back to this boundary: the signup survives, the sync doesn't, and the backstop finds it. Two prepositions carry the entire distinction, in vocabulary the language already owns (§4's commit) — the word "transaction" never enters the language, keeping this doc's opening promise that it was an investigation term, not a proposed keyword. The distinction only exists for `commit`: a schedule entry's boundary is inherent, so `Hourly` reads the same in either list.

**The clause and the apparatus check each other** — the disarm-proof pattern again, declaration plus proof obligation:

- A declared boundary *without* the apparatus is the stranding error (the transition law): "this rule's firing can be lost at the declared boundary, and its trigger is not data — gate it on a dischargeable state and add a backstop schedule, or remove the boundary."
- The apparatus *without* a boundary is dead machinery under the default — a joined firing can't be dropped, so the guard and backstop protect nothing — and the diagnostic mirrors dead tolerances (README §19): "this rule's guard and backstop serve no boundary — did you mean `after commit`?"

This refines the earlier lean rather than preserving it intact: **the apparatus is the proof, not the declaration.** The declaration is one clause in the header, in the position where trigger-source policy already lives (`on commit` vs. `on Nightly`); the apparatus is what the declaration obligates; the compiler holds the two consistent in both directions. What a PO reads off the header: `on commit` — part of the act; `after commit` — follows the act, healed on the stated cadence; `on Nightly` — follows by its nature.

A declared boundary also settles the ordering Model B couldn't: the new transaction begins after its triggering transaction commits, so a detached write is ordered *after* every joined write of the same cascade — one-writer treats it as a separate commit, where across-commit last-in-wins is already defined (README §12).

Unsettled beneath the settled concept: the small grammar of the mixed list — whether `after commit, Hourly` reads its preposition per-entry or the clause splits (`after commit on Hourly`); whether every rule fired by one tick joins that tick's *single* commit or each firing (or each `each` iteration) gets its own boundary — the granularity half of OQ13's pass; and nesting — an `after commit` rule's own downstream cascade joins *its* commit by default, so a cascade is really a tree of commits, each rooted at a declared, inherent, or forced boundary.

## Open questions

(Numbering continues from `investigate_state.md`; OQ tags are never reused.)

### OQ16. Where are the commit boundaries in a cascade?

The models, defined at a glance:

- **Closure** (Model A) — the act plus every firing it transitively triggers is *one commit*: durable as a single state transition or rejected as one; intermediate states never observable, never durable; a crash means the act never happened.
- **Chain** (Model B) — *every firing is its own commit*: the act lands first, each consequence lands separately after it; intermediate states are durable and observable, other commits can interleave between links, and a crash leaves a partial cascade for the guard/backstop machinery to heal.
- **Hybrid** — firing bodies always atomic (the floor); closure-atomic from the act up to the first boundary that *must* exist (an external effect) or that the author spelled as eventual ("Writing the boundary"); chained beyond it, each chained link crash-safe via its guard.

**Settled direction: the hybrid is the frame, and the default is one transaction.** External edges are forced-chained (constraint 5), declared-eventual edges are chained by their apparatus, firings are atomic regardless. That collapses the A-vs-B choice into one residual question: *what does it mean when an author writes a plain rule* — `rule RestoreService when leaving Delinquent { ... }`, internal effects only, no guard, no backstop schedule — *and says nothing about boundaries?* Answer: all rules fire within a single transaction unless boundaries are explicitly set. Two arguments, one about spelling and one about recoverability:

- **Elimination (spelling).** A consequence that runs *after* its triggering commit needs three things to be safe: a guard (so retrying can't double-apply it), a backstop schedule (so a crash between the commits gets healed), and a witness (so "already done" is checkable data). A consequence that runs *inside* its triggering commit needs none of those — there is no gap to crash in, so there is nothing to heal. The plain rule has none of that machinery; if plain rules meant "runs after," every one of them would be an eventual consequence with no retry safety and no heal path — precisely the silent-eventual failure the language refuses to allow. (And choosing "runs after" as the default would force "runs inside" to need an annotation to distinguish itself from bare, resurrecting exactly the `atomic` keyword already rejected.)
- **Recoverability (the transition law — the decisive one).** If R2 is triggered *only* by a transition R1's effects cause, and R2 runs in its own transaction and fails unexpectedly, nothing in the system will ever re-trigger it: the transition existed only at the commit that caused it, current state can't reconstruct it, and no sweep can find work whose trigger was never data (Model B's "dropped transition-triggered firing" cost, above). Inside one transaction, R2's failure fails the whole thing — the act rolls back, the transition never happened, and retrying the act causes it again intact. Failure recovery collapses to "retry the act," the one retry the system's boundary already owns. Chained-by-default would instead make every plain rule a latent permanent stranding.

The cost of the answer is that closure semantics must actually be solved for the joined region — which is what the sub-threads below are.

Sub-threads, each forced by a settled constraint: which transitions rules fire on (endpoint vs. stepwise — the fixpoint/stratification question; Datalog prior art applies); sibling firing order, or transitive one-writer instead; termination proof for cascade cycles (is the disarm proof sufficient?); membership blips at the unobservable grain (the §17 transient-membership question at cascade granularity). Subsumes the cascade bullet of OQ6; OQ6 retains act identity, multi-instance commits, and firing-vs-triggering-commit atomicity.

### OQ17. Rejection scope — `forbidden` and validation across cascades

A lien tripped by a downstream consequence must be *seen* before the consequence is real (constraint 4). If refusal means unwinding the act, that requires closure-scoped validation under any durability model — and then: what exactly unwinds, what the committer is told, and whether rejection can ever be partial ("accept the deposit, refuse only the tier change"), and if so whether that's a declarable policy or always incoherent. Reified refusal ("Validation failures are data") dissolves most of this — nothing unwinds, and "what the committer is told" is a shape — leaving only the genuinely commit-refusing subset, which is OQ20's question to delimit.

### OQ18. External effects mid-cascade

The unremovable boundary. When the call succeeds and a later link fails (or the reverse), the witness records what happened — but what does the *cascade* do: halt and self-heal via backstop, compensate (README §13's `compensate`, itself unsettled — OQ7), or refuse to compile a cascade whose atomic prefix contains an external effect without a declared policy? Interacts with OQ7's `compensate` re-derivation directly.

### OQ19. How is the boundary written?

Settled direction, recorded in "Writing the boundary" and "Declaring the boundary": conditioned acceptance is refinement modeling (the `AcceptedOrder` definition, not a boundary); the default is one transaction (OQ16); a new boundary is *declared by a preposition swap on the commit source* — **`after commit`**, glossed as "the firing becomes its own commit" — is *inherent* at schedule sources (a tick's firing can never join the act's long-gone commit), and is *forced* at external effects; the eventual apparatus (dischargeable guard + backstop + witness) is the proof obligation a boundary imposes, not the declaration itself, and the compiler checks clause and apparatus against each other both ways (boundary without apparatus = stranding error; apparatus without boundary = dead machinery, "did you mean `after commit`?"). The word "transaction" never becomes a keyword. Remaining threads: the mixed-list grammar (`after commit, Hourly` — per-entry preposition or a split clause); tick granularity (one commit per tick, per firing, or per `each` iteration — with OQ13); boundary nesting (the cascade as a tree of commits); the loss-tolerance vocabulary (`tolerates loss` — extending `tolerates` beyond fold hazards is unexamined); the `never`-invariant construct as verification of a chosen boundary (independently valuable, §1's consistency-checker category — where does it live?); and legibility at realistic spec sizes, the calibration question rung recognition deferred to worked examples (README §20).

### OQ20. Is commit-refusal primitive, or derivable from reified refusal?

"Validation failures are data" shows a full validate-and-reject flow needing no transaction machinery: validity refinements partition the act (exhaustiveness-checked), consequences gate on the valid subset, refusal is a produced fact. That raises the question of whether the language needs commit-refusal at all above the type/trust boundary (level 1 of the layering — OQ5's territory, where it's irreducible). Sub-threads: can `forbidden` desugar to compiler-derived gates on every exit-causing writer plus a refusal witness — keeping constraint 4's consequence-visibility while dropping the unwind — and is that faithful to what the PO's "you can't edit an issued invoice" means, or does the no-sugar reasoning (README §18) cut against the desugaring here too? Does any business case require that a well-shaped act *not enter the state* (compliance/data-retention pressures — "we may not store this request at all" — may be the real residue)? And if both modelings stay expressible, is the choice per-lien, per-act-shape, or per-boundary (OQ5's who-may-commit declaration being the natural home)?
