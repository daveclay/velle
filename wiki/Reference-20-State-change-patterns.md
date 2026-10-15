# 20. State-change patterns

The same business sentence — "suspend delinquent accounts" — has several valid designs, chosen by what the business actually needs (flexible, not restrictive — `## Philosophy`). The spectrum runs **derivation → reconciliation → exactly-once events**, and the guard question only exists at the far end.

**1. Pure classification — no rule at all.** If "suspended" causes no external effect — it's a status the system reports — it isn't state, it's a predicate: `shape Suspended = Account where balance < 0`. Zero machinery, always exactly current, nothing to guard. The question that selects this rung: *is suspension a fact you compute, or an action you take?*

**2. Reconciliation — idempotent sweep + resettable current-state flag.** When suspension must be *stored* (independently meaningful: manual overrides, grace periods, an external system reads it) but only its current value matters:

```
shape Account {
    balance: Money
    suspended: boolean initially false
}

shape Delinquent = Account where balance < 0

rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}

rule RestoreService when (Account where suspended and balance >= 0) on Nightly {
    this.suspended = false
}
```

`this.suspended = true` writes a constant — idempotent, so exactly-once is moot: the sweep can run twice a night or crash mid-run and rerun; it converges. The answer here is not *guard the firing* but *make firing harmless* — a reconciliation loop, desired state enforced by an idempotent sweep. The flag is a *current-state* guard and it resets (`RestoreService` re-arms it), so re-entry is ordinary behavior. Latency = cadence, and transient blips are deliberately unobserved (`## Schedule triggers`).

**3. Exactly-once events.** When the moment matters or the record does:

```
rule SuspendService when Delinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
}
```

Commit-local entry makes re-entry free — September's second delinquency is a new entering commit, so the rule fires again, and the `ServiceSuspension`s accumulate as per-episode history. Add a guard (`## Run-once guards`) when the firing must be durable.

Off the end of the scale, the deposit case done reconciliation-style is `balance: Money = openingBalance + sum(...)` — the derived property, i.e., the ledger. The guard isn't the answer to mutation safety in general; it's the answer for the subset of designs where the author has chosen occurrence semantics, and the language's job is to make every rung expressible and validate whichever one the author picked — which the compiler does by classifying rungs (below).

## Episodes as data

Commit-triggered rules get per-episode *firing* free, but a tick rule that needs "this episode was handled" across ticks, or a business that quantifies over episodes ("third delinquency this year," episode duration), needs the episode reified — the tick law again: cross-tick memory must be data (`## Schedule triggers`). The pattern is a flag/resolution pair:

```
shape DelinquencyFlag {
    account: one Account
    flaggedOn: Date
}

shape DelinquencyResolution {
    flag: one DelinquencyFlag
    resolvedOn: Date
}

shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

-- a delinquent account with no open flag starts a new episode
rule OpenDelinquencyEpisode
    when (Delinquent where not exists OpenDelinquencyFlag for this) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- leaving Delinquent closes the open episode
rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag for this)
        resolvedOn: today
    }
}
```

Now `count(DelinquencyFlag where account == this) >= 3` is expressible, and any rule can guard per episode (`DelinquencyFlag where not exists ServiceSuspension for this`). Noteworthy in passing: the exit rule's singular reference `(OpenDelinquencyFlag for this)` is provably at-most-one *because of the entry rule's own guard* — a whole-spec singularity proof (`## Predicate expressions`' `for`-query rule, discharged by a guard elsewhere in the spec). The honest cost: three shapes and two rules of completely mechanical pattern — accepted, not sugared (`## Run-once guards`).

## All-or-nothing batches

There is no batch-transaction construct — and no business case has survived scrutiny that needs one. "Handle these records together, all or none" always dissolves into machinery already present, by one of these routes:

- **A static multi-record effect is one body — already atomic.** Double-entry bookkeeping: debit and credit are two statements of one body — one commit, both-or-neither (`## rule`):

```
rule PostTransfer when Transfer {
    LedgerEntry from { account: from, amount: -amount }
    LedgerEntry from { account: to,   amount: amount }
}
```

- **A dynamic per-member effect is a fan-out — atomic when commit-driven.** "When an order is paid, reserve stock for every line item, all or none": the gate is an aggregate refinement on the parent, and the effect is a per-record rule the entering commit fires N times — every firing standing or falling with the act, because commit-source consequences share its transaction (`## Sweeps`):

```
shape FulfillableOrder = PaidOrder where count(lineItems where not InStock) == 0

rule ReserveStock
    when (LineItem where order is FulfillableOrder and not exists Reservation for this) {
    Reservation from { lineItem: this }
}
```

- **"All at once" over N records is one fact wearing N disguises.** "Apply the new rate to every subscription at midnight" isn't a batch: commit one `PriceSchedule` record that price derivations read, and every price changes at that commit by construction. Likewise a batch-wide status: a per-entry `submittedOn` that is equal across all entries by definition is the *batch's* one fact, derived per entry (`submittedOn: Date? = (BatchSubmission for batch)?.submittedOn`) — all N memberships flip at the witness commit.
- **A set that must succeed or fail together is a business object.** Settlement batches, payroll runs: whenever a business says "together," the together-thing has a name — reify it (`## State and commits`' container idiom), and its handling is one record's firing: one transaction, one external act, one `id` to reconcile (intent-before-effect, `## rule`). When the batch is born whole — the parts arrive inline with the act itself — the act's closure declaration is this rung's input-side spelling (`## Relationships`, "Inline part creation"); the completeness gate (below) remains the spelling when the members arrive incrementally.
- **N distinct facts arriving incrementally get a completeness gate, not an envelope.** Per-entry results from a clearing house land one commit at a time; downstream rules hang off `CompleteResponse = BatchResponse where count(results) == count(batch.entries)` — conditioned acceptance: the partial state is honest and unobserved.

The principle underneath: **batches demand atomic *observation*, not atomic *writing*** — and observation is a predicate over a commit's post-state, which is already atomic. Where records "must change together," the shared thing is one fact and the N views are derivations; where facts "arrive together," a completeness refinement keeps observers off the partial set. What a direct "per member of the collection" spelling would add is ergonomics over the fan-out — sugar territory for the mapping construct (`## Open / unresolved`), not transaction machinery.

## The compiler recognizes the rungs

The spectrum is not just documentation — the compiler classifies which rung each rule sits on and validates it accordingly. The classification is *derived* from what's written, never declared. Much of the machinery is already settled elsewhere: the RHS three-class split is the rung classification for assignments (`## Self-referential folds and tolerates`), obligations arise only where non-idempotence demands one, and a reconciliation sweep is recognized as a discharge. Recognition adds on top:

- **Rung-specific proofs.** A sweep serving as reconciliation must be provably convergent and idempotent — re-running it against unchanged data changes nothing. A latch pair (set-rule / reset-sweep) must actually cover both directions, or the flag drifts from the condition it mirrors.
- **Advisory, not policing.** "This stored flag could have been a pure classification — no rule needed" is guidance the author may ignore; the *required* diagnostics remain only the fail-closed ones (one-writer, disarm, fold obligations).
- **Classification feeds the analyses.** Impact analysis and `why` can name the rung — "this value is a nightly snapshot," "this flag is reconciled, drift bounded by the sweep cadence" — so a reader learns the design intent from the tooling, not from convention.

How this behaves against realistic specs is untested — the classification boundaries and the advisory/required line are expected to be calibrated when realistic Velle examples get written; the principle is settled, the calibration isn't.
