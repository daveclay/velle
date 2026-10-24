# 19. Self-referential folds and `tolerates`

Some stored values are updated from their own previous value: a balance that grows by each deposit, a count that goes up by one, a streak that resets on a miss. Velle calls such a value a *fold*, because the stored number folds every commit so far into one value. A fold is correct only if each commit is folded in exactly once and, for some folds, in the right order. This page shows when an assignment is a fold, which folds the compiler accepts as written, and the four ways to answer when it does not.

## Three kinds of right-hand side

An [assignment](Reference-12-Assignment) is one of three kinds, by what its right side reads:

**Reads only the triggering act.** `customer.email = corrected`. Running it twice writes the same value twice.

**Reads other data, but not the field being written.** A formula over current data. Running it twice gives the same answer, so it is safe on any trigger:

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

`UpdateRating` keeps a stored value current at every commit; it could equally be a [derived property](Reference-07-Derived-properties). `RecalculateRiskScore` runs on a [schedule](Reference-17-Schedule-triggers) and gives "the score as of last night," a value that holds still between runs. `rating` could also be written incrementally, as `rating = (rating * n + stars) / (n + 1)`. Both spellings are legal; the compiler may point out the twin ([checks.md, A1](checks#a1--rung-recognition)).

**Reads the field being written.** `balance = balance + amount`. The next value depends on the previous one, so the stored field is a fold. Streaks, highest-ever values, and moving averages are folds too. The rest of this page is about this kind.

## The two hazards

A fold can go wrong in two ways:

- **Duplication.** The same commit is folded in twice. An unguarded rule can fire again for a record it already handled: after a crash and replay, or on every run of a backup schedule such as `on commit, Hourly`. A rule with a [run-once guard](Reference-18-Run-once-guards) cannot.
- **Reordering.** Commits are folded in a different order than the business meant. A rule that fires `on commit` folds one act at a time, in commit order, so it cannot reorder. A scheduled rule handles all its pending records at the tick, in no defined order ([§16](Reference-16-Sweeps)).

The compiler does not analyze your formula. It recognizes a short list of forms known to be safe, per hazard:

| fold | duplication-safe? | order-safe? |
|---|---|---|
| `max(x, amount)`, `min`, set-union, boolean-or | provably yes | provably yes |
| `x + amount` (`sum`, `count`) | no | provably yes (commutative) |
| streak, moving average, "reset on miss" | no | no |
| `x + amount` where `x` is an [extension type](Reference-05-Scalars#extension-types) | as the type's author declared for `+` | as the type's author declared for `+` |

For an extension type the author answers both columns per operator, and the compiler checks each answer on the type's own sample values. `Money` declares that two `+` steps may be applied in either order and that one step applied twice is not the same as once, so a `Money` fold reads the second row of the table.

A fold is rejected when the rule is exposed to a hazard its form is not known to be safe under. The error asks for a policy: "this value's correctness depends on each qualifying commit being folded exactly once; state how that is ensured." A streak really does break under replay, so the error is correct even when the code is what you meant. The four answers:

1. **Guard the rule** so it fires once per record ([§18](Reference-18-Run-once-guards)).
2. **Derive the value** from the source records instead. A derived `balance = sum(deposits, amount)` cannot double count.
3. **Add a periodic recompute.** Keep the unguarded fold, and add a nightly rule such as `viewCount = count(PageView for this)` that restores the true value ([§20](Reference-20-State-change-patterns)).
4. **Declare that the business accepts the hazard** with `tolerates`:

```
shape Article {
    viewCount: long tolerates duplication
}
```

`tolerates duplication` and `tolerates reordering` go on the field. `tolerates loss` goes on a rule whose external effect may be dropped ([§11](Reference-11-rule-when-on#transactions-and-after-commit)). In `examples/membership/membership.velle`, `visitCount: long initially 0 tolerates duplication` is fed by a rule firing `on commit, Hourly`. Read the declaration as a business claim: `balance: Money tolerates duplication` is visibly wrong, and that is the point.

## Three rules, three verdicts

Same trigger, different outcomes:

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

- `ApplyDeposit` is rejected. The hourly run folds every existing deposit again. The guarded form is the example in [§18](Reference-18-Run-once-guards).
- `TrackLargest` compiles silently. `max` gives the same answer however often, and in whatever order, it runs.
- `TrackStreak` is rejected, correctly. It fires `on commit`, so order is safe, but a replay would count a payment twice. A guard fixes it.

## Order-dependent folds at a tick

A streak updated by a nightly sweep is exposed to reordering, and nothing on the rule can fix that: the tick's firings have no order, and `tolerates reordering` would be a false claim for a streak. Rules have no ordering clause. The order a streak needs is business data, such as the payment date, so it goes in the data model.

Write the value as derived history. Each record finds its predecessor by the ordering field and derives its running value from the predecessor's. The current value is a read of the latest record:

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

- `previous` is the latest earlier payment for the same account, ordered by `receivedOn`.
- `streakAfter` is the streak as of this payment: zero on a miss, otherwise one more than the predecessor's. A derived property may read the same property on another record, one hop away ([§7](Reference-07-Derived-properties)).
- `streak` on the account reads `streakAfter` off the latest payment.

There is no stored field, no guard, and nothing to tolerate. Every payment carries the streak as of itself: readable history. A backdated payment slots into place by `receivedOn`, and every later payment's streak re-derives. A stored fold would have folded it last and kept the wrong value. This pattern is the [predecessor recurrence](GLOSSARY#patterns); a fuller version is `DuesPayment` in `examples/loyalty/loyalty.velle`.

If two records can tie on the ordering field and the result depends on which comes first, declare a further ordering field. `id` cannot serve, because ids are unordered ([§5](Reference-05-Scalars)).

A stored fold on an `on commit` rule folds in arrival order. Use it when arrival order is the business order.

## What the compiler checks

- **Rejects** a self-referential assignment exposed to duplication (the rule can fire twice for one commit) or reordering (a scheduled rule) when the formula is not on the safe list for that hazard, unless a guard, a periodic recompute, or the matching `tolerates` covers it ([checks.md, V8](checks#v8--fold-obligations-and-tolerance-coverage)).
- **Rejects** a `tolerates` that covers no hazard the rule can meet, such as `tolerates reordering` on an `on commit` rule. The message names the hazard you may have meant ([checks.md, V8](checks#v8--fold-obligations-and-tolerance-coverage)).
- **Rejects** a derived property that refers to itself through another record unless the chain provably ends: a reference that strictly descends a field fixed at creation, as `previous` does by `receivedOn`, with a base case, or a `never` that rules out the cycle ([checks.md, V14](checks#v14--well-foundedness-coarse)).
- **Rejects** a derived property whose result depends on the order of records that can tie on the ordering field ([checks.md, V14](checks#v14--well-foundedness-coarse)).

## Related pages

- [§18 Run-once guards](Reference-18-Run-once-guards), for making a rule fire once per record.
- [§20 State-change patterns](Reference-20-State-change-patterns), for choosing between stored, derived, and periodically recomputed values.
