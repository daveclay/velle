# 19. Self-referential folds and `tolerates`

Static analysis splits assignment right-hand sides into three classes; only the last needs machinery.

**1. Act-only** — reads nothing but the triggering act (`customer.email = corrected`). Idempotent; re-firing is harmless.

**2. Recomputing** — reads state, but *not the target itself*: a deterministic formula over current data. Convergent — same inputs, same value — so exactly-once is moot on any trigger. Per-commit recompute is an incrementally maintained materialization (semantically a derived property that happens to be stored); tick recompute is a *snapshot* — "the score as of last night," deliberately stable between ticks, a business concept live derivation can't express. Both valid, no guard:

```
-- "every commit, update this value": materialized derivation
rule UpdateRating when Review {
    product.rating = sum(product.reviews, stars) / count(product.reviews)
}

-- "every night, recalculate this value": snapshot
rule RecalculateRiskScore when Account on Nightly {
    this.riskScore = <formula over payments, balance, history>
}
```

Where a fold is expressible as a recompute, the incremental spelling (`rating = (rating * n + stars) / (n + 1)`) and the recompute spelling are the same description with different *execution strategies* — and incremental maintenance is what `## Principles` assigns to compilation, not description. Both spellings stay legal (flexible, not restrictive); surfacing the twin as guidance is rung recognition's advisory job (`## State-change patterns`).

**3. Self-referential — a denormalized fold.** The RHS reads the target's own current value, so the next value is a function of the previous one (`value′ = f(value, commit data)`): the stored field denormalizes a *fold over the commit sequence*. `balance = balance + amount` is only the simplest instance — streaks, peaks, and moving averages are all folds.

**The compiler never determines the algebra of `f` — it doesn't need to.** Two decidable facts replace it:

- **Self-reference is syntactic.** The RHS reads the field it assigns — reliably decidable; this is what triggers the analysis.
- **Exposure is structural, per hazard.** *Duplication exposure*: an unguarded fold can be applied twice for one commit (the crash/replay window, and trivially under a `, Hourly` backstop). A fold gated on a dischargeable state cannot. *Reordering exposure*: a commit-cadence rule folding one act at a time cannot reorder — commits are serialized, so fold order *is* commit order; a tick-cadence rule's pending records fire separately at the tick (`## Sweeps`) with no defined order among the firings.
- **Insensitivity is a fail-closed whitelist, per axis** — the compiler proves safety only for known forms, along the two axes independently:

| fold | duplication-safe? | order-safe? |
|---|---|---|
| `max(x, amount)`, `min`, set-union, boolean-or | provably yes | provably yes |
| `x + amount` (`sum`, `count`) | no | provably yes (commutative) |
| streak, moving average, "reset on miss" | no | no |

**An obligation exists exactly where a rule is exposed to a hazard its fold is not provably insensitive to.** An undischarged obligation is a compile error — fail-closed, mandated. The diagnostic is a demand for a stated policy, not an accusation of a bug: "this value's correctness depends on each qualifying commit being folded exactly once — state how that's ensured." Legitimate use cases carry the obligation *correctly* — a streak really does break under replay — so legit-but-unprovable and dangerous-but-unprovable get the same diagnostic; the difference is only that the legit author has an answer to give. Four ways to give it:

1. **Guard** — make exactly-once true (`## Run-once guards`).
2. **Derivation twin / recompute** — make it irrelevant: the value re-derives from source records.
3. **Reconciliation sweep** — bound the drift: the fold stays cheap and unguarded on the hot path; a periodic recompute (`viewCount = count(PageView for this)` nightly) restores truth on a cadence (`## State-change patterns`' reconciliation rung).
4. **`tolerates <hazard>`** — accept the named risk, declared where the risk lives (the field for fold hazards; the rule for `loss`):

```
shape Article {
    viewCount: long tolerates duplication
}
```

The vocabulary is closed and mirrors the hazards one-for-one — `tolerates duplication` and `tolerates reordering` on fields; `tolerates loss` on a rule whose external effect may be dropped (`## rule`, "Transactions and `after commit`") — so checking is mechanical set-coverage: exposed hazards, minus those discharged by a guard, an ordering, a reconciliation, or a tolerance, must be empty. A dead tolerance is itself a diagnostic ("this rule cannot experience reordering — did you mean duplication?"): the author never needs the exposure model in their head; the compiler names the specific risk, and the author fixes it or signs it by name. The declaration is self-vetting because it *is* the business claim — `balance: Money tolerates duplication` is visibly absurd in exactly the way that forces the author to confront what they're signing.

**The showcase trio** — same trigger, same cadence, three different verdicts:

```
-- error: the canonical guard example minus its guard. The Hourly tick re-folds
-- every Deposit that exists — the happy path double-counts by construction.
rule ApplyDeposit when Deposit on commit, Hourly {
    account.balance = account.balance + amount
}
-- "correctness depends on each Deposit being folded exactly once, and nothing
--  ensures it. Gate the rule on a dischargeable state (see UnappliedDeposit),
--  define balance as a derivation over deposits, add a reconciliation sweep,
--  or declare `tolerates duplication` on the field."

-- silent: whitelisted fold — fold it twice, ten times, out of order; same value
rule TrackLargest when Deposit on commit, Hourly {
    account.largestDeposit = max(account.largestDeposit, amount)
}

-- legit and unprovable: the diagnostic fires correctly on code the author is
-- right to want. Commit cadence → reordering exposure is zero by construction;
-- the fold owes duplication only, and a guard serves it fully.
rule TrackStreak when Payment {
    account.streak = if onTime then account.streak + 1 else 0
}
```

**Order-dependent folds at a tick: the predecessor recurrence.** A tick-cadence order-dependent fold (a nightly streak sweep) is exposed to reordering that nothing on the rule can discharge: one tick's firings have no defined order (`## Sweeps`), and declared tolerance would be a false statement for a streak. Velle deliberately has no ordering clause on rules — firing order is never language surface — because the order such a fold needs is business data, and it belongs in the data model. The served spelling is a **predecessor recurrence** over ordered history: a derived `previous` (`latest ... by` the ordering datum) plus sanctioned self-reference one hop through it (`## Derived properties`), so each record derives its running value from its predecessor's, and the current total is a selector read. Concretely, the streak:

```
shape Payment {
    account: one Account
    onTime: boolean
    receivedOn: timestamp on create
    previous: one Payment? = latest(Payment where account == this.account and receivedOn < this.receivedOn by receivedOn)
    streakAfter: integer =
        if not onTime then 0
        else if previous is some then previous.streakAfter + 1
        else 1
}

shape Account {
    streak: integer = if exists Payment for this
                      then latest(Payment for this by receivedOn).streakAfter
                      else 0
}
```

No stored field, no guard, no obligation — and more than a fold: the per-record value is readable history ("the streak as of each payment"), and order-sensitivity is structurally impossible, since the order comes from the declared datum rather than from when firings happen to run. Late-arriving records show the recurrence is also the more truthful description: a backdated payment slots into the chain at its datum's position and every successor re-derives, where a stored fold would have folded it last — arrival order — and kept the wrong value with no repair. What the recurrence owes is **well-foundedness** — every chain of definition references must reach a base case in finitely many steps — checked by stratification plus a certificate whitelist (`checks.md` V14): the acyclic part of the definition graph passes free; each static cycle needs a certificate — strict descent on a creation-fixed datum plus a base case, or acyclicity supplied by a `never` prohibition (`## never`) — and no certificate is a compile error: the check rejects what it cannot prove rather than warning, and never attempts fixpoint semantics. A tie on the ordering datum where the result depends on it is the model's problem — the author declares a further ordering datum; `id` cannot serve, being deliberately unordered (`## Scalars`). Growing the certificate whitelist is open calibration, one vocabulary shared with the cascade-quiescence proof (OQ16). The mutation form's spelling is commit-cadence, and that is a semantic statement, not a limitation: commits are serialized, so fold order *is* commit order — arrival order — the right description exactly where arrival order is the business order.
