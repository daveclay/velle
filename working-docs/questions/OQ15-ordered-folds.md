# OQ15 — Ordered folds and firing order at a tick

**Status:** open — calibration; the recurrence spelling exists, the certificate whitelist grows with real specs
**In plain terms:** a running total that depends on processing order (a payment streak) has no defined order when a nightly sweep fires many updates at once — what makes that safe: an ordering clause on the rule, or re-spelling the total as derived history?
**v0 stance:** ship stratification plus the strict-descent certificate whitelist (checks.md V14), fail closed; commit-cadence remains the served spelling for the mutation form.
**See:** README §19 (folds), §7 (self-reference) · checks.md V14 · OQ16 (the within-transaction sibling of this question) · the static selector-discrimination check rides this calibration (`investigate_runtime.md` §9)

---

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

Ordering ties, by contrast, are **the author's problem, not the language's**. Two payments with the same `receivedOn` make `previous` (and any `latest`) ambiguous — but if the business's records can tie on the ordering datum, the *model* owes additional ordering criteria; that's a product decision, the same category as guard granularity (README §18, "No guard sugar"). The compiler's job is the usual one — fail closed where it can't prove the order total and the result depends on it — and the fix is model-side, never new machinery. `latest`/`first` are convenience helpers over the predicate grammar, not top-level language structures; whether the selector family grows richer ordering spellings is ordinary vocabulary expansion (README §22's selector-syntax item), not a problem with Velle. (Since this was written, `by` became mandatory on `latest`/`first` — the author names the ordering datum; `investigate_runtime.md` §9, README §10.)

Until the well-foundedness proof lands, commit-cadence remains the only fully-served spelling for the *mutation* form — the twin `when Payment` rule (README §19's showcase) buys order-safety structurally: commits are serialized, so fold order *is* commit order.
