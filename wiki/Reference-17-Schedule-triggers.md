# 17. Schedule triggers (`on <schedule>`)

A rule can be triggered by a named schedule instead of (or in addition to) `on commit`, by naming the schedule in the header's `on` clause:

```
rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }
}
```

`on` accepts a comma-separated list (`on Daily, Hourly`) for a rule that needs to run on more than one cadence — including mixed with commit (`on commit, Daily`: fire the moment the condition is entered, *and* re-check on the cadence). The list is distributive — each entry independently triggers the rule: `after commit, Hourly` declares two triggers, *fire after the commit* and *fire at the Hourly tick*. The `on`/`after` preposition is meaningful only for the `commit` entry, the one trigger source where sharing the triggering transaction is possible at all; a schedule entry's firings inherently begin their own transactions either way (`## rule`, "Transactions and `after commit`"). `Daily` is a placeholder name, not a built-in or sugar for a specific interval — what actually defines a schedule (cadence, timezone, one-off vs. recurring) is a separate, not-yet-designed construct, assumed to be provided by some cron-like scheduling framework. Only the rule-side trigger syntax is settled.

A schedule trigger is the *only* way a purely time-dependent refinement (like `OverdueInvoice`, which depends on `today`) gets re-checked — nothing in Velle executes purely on the passage of time by default. A scheduled tick is conceptually a commit like any other (the same category as a `Payment` arriving or a `ChargeResponse` coming back — a tick is a commit whose changed datum is `today`), but it's referenced by name in `on`, not declared inline as a custom shape. A tick's firings inherently begin new transactions — by the time a schedule fires, any originating act's transaction is long gone (`## rule`, "Transactions and `after commit`") — and each firing at a tick is its **own** transaction: a tick is a moment, not an envelope, so one firing's failure at the nightly tick never unwinds another's work, whether the firings belong to different rules or to different records of one rule (`## Sweeps`), and each firing's own consequences share that firing's transaction in the ordinary way.

**The tick law: cross-tick memory must be data.** A tick-triggered rule sees only current state — "what changed since last tick" would require persistent memory between ticks, and memory must be data. Sweeps carry their memory as witnesses, flags, or evidence: the example's "at most every 3 days" lives in the `Reminder` evidence itself, with no hidden last-run timestamp anywhere.

**Transient membership is a policy, stated in the header.** An account goes negative Monday 09:00 and recovers at 17:00. Under `when Delinquent on commit`, suspension fires at 09:00 and restoration at 17:00 — the blip was real, service was off for eight hours. Under an `on Nightly` sweep, the check sees a positive balance — the blip never mattered. Neither is wrong: commit-triggered observes every membership the commit stream produces; tick-triggered observes what persists to the tick. The choice is business-visible in one clause, answered per rule by the author rather than globally by the language.
