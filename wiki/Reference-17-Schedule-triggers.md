# 17. Schedule triggers (`on <schedule>`)

Most rules fire when a commit changes the data. Some business rules are about time: "chase overdue invoices daily." Nothing in Velle runs just because time has passed. To run a rule on a cadence, you name a schedule in its `on` clause. This page shows how to write that clause, what a scheduled rule can remember, and when to prefer it.

"Remind the customer about each overdue invoice daily, but not more than once every three days." Shortened from `RemindOverdue` in `examples/billing/billing.velle`:

```
rule RemindOverdue
    when (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days))
    on Daily {
    Reminder from { invoice: this, sentOn: today }
}
```

- `on Daily` runs the rule at each run of `Daily`.
- `OverdueInvoice` is a refinement that reads `today`. Only a scheduled rule can notice an invoice becoming overdue by the calendar, because no commit happens when a day passes.
- `not exists (Reminder ...)` is how the rule remembers it already sent a reminder: the record it creates is its memory.
- The rule fires once per matching invoice, each in its own transaction ([§16](Reference-16-Sweeps)).

## Writing the `on` clause

`on` takes comma-separated triggers, each firing the rule on its own:

- `on Daily`: at each run of the schedule.
- `on commit, Daily`: the moment a record enters the condition, and again at each run.
- `after commit, Hourly`: right after the triggering commit, in its own transaction, and again each hour ([§18](Reference-18-Run-once-guards)).

`on` versus `after` matters only for `commit`, the only trigger that can share the triggering transaction ([§11, "Transactions and `after commit`"](Reference-11-rule-when-on#transactions-and-after-commit)). A schedule's firings always start their own transactions.

`Daily`, `Hourly`, and `Nightly` are names, not built-in intervals. Velle does not define schedules; the engineer sets up cadence and timezone in whatever scheduler runs the system.

## What a scheduled rule can see

A scheduled rule sees the data as it is when it runs. It does not know what changed since the last run. Anything it must remember between runs must be a record or field the spec declares, like `Reminder` above.

Each run of the schedule is a commit whose changed value is `today`. Every firing at that run is its own transaction: one invoice's failure undoes neither another invoice's reminder nor another rule's firing. What a firing creates triggers further rules inside that firing's transaction.

## Commit or schedule: a business choice

A member's balance goes negative at 09:00 and recovers at 17:00. With `when Delinquent` on commit, suspension fires at 09:00 and restoration at 17:00: service was off for eight hours. With `on Nightly`, the night's run sees a positive balance and the dip never mattered. Neither is wrong. A commit-triggered rule reacts to every change; a scheduled rule reacts to what is still true when it runs.

## What Velle records about a tick

Every tick leaves one record behind, in a shape Velle declares for every system:

```
shape Tick {
    schedule: text
    at: timestamp on create
}
```

- `schedule` is the schedule's name, `Weekly` for a tick of `Weekly`.
- `at` is the moment the tick ran.

You read `Tick` as you read any shape: over the API, in a query, or in a rule's condition. No act creates it, and you cannot write one: the tick is the event, and the record is what the system remembers of it. Velle reads the latest `Tick` for a schedule to know when that schedule last ran, which a `when leaving` rule on a schedule needs ([§13](Reference-13-Exit-triggers)): a record that left between two runs is noticed at the second run, even when the system was restarted between them, and every copy of a system over one database agrees on the previous run. In a system with [authorization](Authorization), `Tick` takes the spec-wide `visible to` default when the spec has one; with no default it is visible to anyone.

## What the compiler checks

- **It rejects** a rule whose condition reads `today` but has no schedule in `on`, because the rule could never fire ([checks.md, V3](checks#v3--unfireable-rule)).
- **It rejects** a schedule trigger, or `after commit`, on a rule whose subject is a `transient act`, which is gone when the schedule runs ([§4, "Transient acts"](Reference-04-State-and-commits#transient-acts-transient-act); [checks.md, V17](checks#v17--transient-isolation)).
- **It rejects** a shape or refinement of your own named `Tick`, which is Velle's ([checks.md, F1](checks#f1--name-resolution-and-casing)).
