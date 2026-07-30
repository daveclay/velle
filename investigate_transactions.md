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

An account sits at −$20 and a $50 `Deposit` is committed. The deposit's commit fires `ApplyDeposit`; the balance mutation moves the account from −$20 to $30; that diff is exactly what `leaving Delinquent` means, so `RestoreService` fires; the `ServiceRestoration` it produces is itself the condition `NotifyRestored` watches, so that fires too. One external act; a chain of consequences two and three removes from it, none of which the depositor's act mentions and all of which the compiler can already see coming (the derived trigger set, README §11, read forward).

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

`leaving Delinquent` and entering `GoodStanding` can be *the same diff* — one deposit trips both. If the cascade is one commit, this is precisely the one-writer error, caught transitively along the cascade graph. If each firing is its own commit, both writes are legal and some order decides — but *what* order? The two firings are siblings fired by the same diff, and nothing yet defines their sequence. Undefined sibling order resurrects exactly the ambiguity one-writer exists to kill; either the check extends across cascades, or sibling order becomes defined. There is no third option.

**4. `forbidden` liens must see consequences.** A lien rejects "any change that would cause the exit" (README §13). If the exit-causing change arrives not as the act itself but as a downstream firing's effect — the act is innocent; its consequence trips the lien — rejection must reach back and refuse the original act, because rejection is meaningless against an already-durable commit. Whatever the durability model, *validation* has to run over the whole consequence closure before anything is accepted. This is the strongest pull toward closure-shaped semantics, and it's independent of crash behavior entirely.

**5. External effects can't be inside any boundary.** An API call is an outcome commit whose only db-visible trace is its witness (README §18). No boundary drawn around state can make a call into the world atomic with it — the call happens *out there*. A cascade containing an external effect therefore contains a boundary no semantics can remove: the classic saga problem, arriving on schedule. The witness pattern is already the answer's shape (the call's occurrence is recorded as data; guards make retry safe); what's unresolved is what the *rest* of the cascade does when the call succeeds and a later link fails, or vice versa.

## Model A: the cascade is one commit

The accepted commit is the **consequence closure**: the act plus every transitively-fired effect, evaluated until nothing more fires, becomes durable as a single state transition — or is rejected as one.

What it buys:

- **No partial cascades.** A crash yields "the deposit never happened" — retryable, no wedged intermediate state where the balance is positive but service is still suspended.
- **`forbidden` works naturally.** The lien check is just part of closure evaluation; rejection rejects the act, and the depositor hears "refused," never "landed but stuck."
- **No interleaving anomalies.** No other commit can land between `ApplyDeposit` and `RestoreService` and observe the account non-delinquent-but-suspended.
- **One-writer extends cleanly.** The `tier` collision above is a compile error naming both rules — the same whole-spec, effects-at-a-distance diagnostic the language already prefers.

What it costs, or opens:

- **The fixpoint question.** `RestoreService` fires because `ApplyDeposit`'s effect changed state — its predicate evaluation must see post-`ApplyDeposit` state while the closure is still open. So rules inside the closure evaluate against intermediate states that officially "don't exist." Worse: if entry/exit diffs are computed endpoint-to-endpoint, a refinement entered at step 2 and exited at step 4 of the same closure *never happened* — a transient-membership blip at closure granularity (the same question §17 answers per-rule for commit-vs-tick, at a new grain, currently with no clause to state a choice in). But if rules fire on *stepwise* diffs instead, intermediate states are semantically real after all, and the closure is just durability packaging around Model B. Which diffs are the real ones is the crux, and picking "endpoint" lands Velle in stratification territory — which rules fire depends on the final state, which depends on which rules fire. Known ground in Datalog (stratified negation); the prior-art mining item (`TODO.md`) becomes directly load-bearing here.
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

- **One uniform commit story.** No closure/fixpoint machinery; entry and exit stay commit-local diffs exactly as settled (README §11); a rule can't tell whether its trigger was an external act or another rule's effect — pleasing symmetry.
- **Crash windows are explicit, and the machinery for them already exists.** The gap between firings is real, so the guard patterns were built for precisely this: `on commit, Hourly` backstops, disarm proofs, reconciliation sweeps. Nothing new to invent — "self-healing" is already the language's answer to dropped work.
- **External effects stop being special.** Every link boundary is a boundary; the saga shape is the native shape.

What it costs:

- **Partial cascades are durable, observable business states.** Balance restored, service still suspended, for an unbounded window unless a backstop is declared. Sometimes that's honest (eventual consistency is a real business policy); it needs to be *chosen*, not defaulted into.
- **`forbidden` breaks — or forces closure validation anyway.** By the time the lien-tripping firing runs, the originating act is durable; rejection can't reach it. Either `forbidden` weakens to "the downstream firing fails" (leaving the cascade wedged mid-chain — visibly worse than rejection), or commit acceptance must *predictively* evaluate the consequence closure before accepting — at which point Model B has conceded constraint 4 and runs closure validation while keeping chained durability. Notable: that concession may be the actual design.
- **Sibling order is undefined.** The `tier` example: two firings from one diff, two separate commits, no defined sequence — last-in-wins with no fact of the world to supply "last." Model B must either define sibling order (declaration order? `then` promoted to a cross-rule position? both smell like imperative sequencing creeping back in) or extend one-writer across cascades anyway — again conceding the transitive analysis.
- **Interleaving.** An unrelated external act can land between links and observe (or mutate) the mid-cascade state. Every refinement evaluated during the window sees a world the initiating act's author never imagined as observable.

## Where the models converge

Both need the firing-atomic floor. Both need the static cascade graph and analyses over it — termination/cycle checking, and the transitive one-writer *analysis* (only the verdict differs: compile error vs. defined order). Both need closure-scoped *validation* for `forbidden` to mean anything. Both need the witness pattern at external boundaries. Both need a story for membership blips at whatever grain internal states are unobservable.

That convergence suggests the real design space isn't "A or B" but **where the boundaries fall and who says so** — a hybrid: firing-atomic always; closure-atomic by default up to the first boundary that *must* exist (an external effect); chained beyond it, with the guard machinery making each link crash-safe. The genuinely open residue is then small and nameable:

- **"Has happened" vs. "will happen" is PO-legible.** When the teller says "deposit accepted," is service restored, or promised? A Product Owner can answer that per consequence, and Velle already has the pattern of making exactly this kind of choice visible in one clause (`on commit` vs. `on Nightly`, README §17 — immediacy as per-rule policy). Atomic-with-the-act vs. eventually-after-the-act may be the same species of declaration: a transaction boundary as a *declared, business-visible* policy at a cascade edge, not inferred machinery. Candidate framing only — no syntax proposed, and the default matters more than the clause (silent-eventual is a footgun; silent-atomic makes every cascade a big transaction whether wanted or not).
- **Rejection scope is business-visible.** "Your deposit was refused" vs. "your deposit landed but the restoration is stuck" are different products. Whatever unwinds on a lien or validation failure must be statable or derivable — never an implementation accident.
- **Everything else is compilation.** Isolation, locking, retries, idempotency keys, whether the closure runs synchronously in one database transaction or as an orchestrated queue — all of it, provided the declared observability and durability semantics hold (README §1).

## Open questions

(Numbering continues from `investigate_state.md`; OQ tags are never reused.)

### OQ16. Where are the commit boundaries in a cascade?

The model choice — closure, chain, or the hybrid above. Sub-threads, each forced by a settled constraint: which diffs rules fire on (endpoint vs. stepwise — the fixpoint/stratification question; Datalog prior art applies); sibling firing order, or transitive one-writer instead; termination proof for cascade cycles (is the disarm proof sufficient?); membership blips at the unobservable grain (the §17 transient-membership question at cascade granularity). Subsumes the cascade bullet of OQ6; OQ6 retains act identity, multi-instance commits, and firing-vs-triggering-commit atomicity.

### OQ17. Rejection scope — `forbidden` and validation across cascades

A lien tripped by a downstream consequence must refuse the originating act (constraint 4), which requires closure-scoped validation under *any* durability model. What exactly unwinds, what the committer is told, and whether rejection can ever be partial ("accept the deposit, refuse only the tier change") — and if so, whether that's a declarable policy or always incoherent.

### OQ18. External effects mid-cascade

The unremovable boundary. When the call succeeds and a later link fails (or the reverse), the witness records what happened — but what does the *cascade* do: halt and self-heal via backstop, compensate (README §13's `compensate`, itself unsettled — OQ7), or refuse to compile a cascade whose atomic prefix contains an external effect without a declared policy? Interacts with OQ7's `compensate` re-derivation directly.

### OQ19. Is atomicity a declarable, per-edge policy?

Whether "restored atomically with the deposit" vs. "restored promptly after the deposit" deserves first-class spelling at a cascade edge, the way `on commit` vs. `on Nightly` already spells immediacy per rule — and what the default is when undeclared. Fail-closed instincts say silent-eventual is unacceptable; whether silent-atomic (closure by default) survives realistic specs is exactly the kind of calibration question rung recognition deferred to worked examples (README §20).
