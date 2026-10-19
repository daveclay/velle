# 16. Sweeps: rules fire per record

Velle has no loop. To act on every record that meets a condition, you write one rule with that condition, and Velle runs it once per matching record. A scheduled rule that works through every record still needing attention is a *sweep*. This page shows how to write one and what happens when one firing fails.

"Every night, suspend every delinquent member who is not suspended yet." From `examples/membership/membership.velle`:

```
rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}
```

- `when (Delinquent where not suspended)` selects the records that need work. Every rule has a `when` condition.
- `on Nightly` names the [schedule](Reference-17-Schedule-triggers).
- `this` is the record the current firing is handling.
- `where not suspended` stops the rule handling a member twice. It can be inline or a named [refinement](Reference-08-Refinements) ([§18](Reference-18-Run-once-guards)).

## How many times a rule fires

A rule `on commit` fires once per record entering its condition at that commit ([§11](Reference-11-rule-when-on)). A scheduled rule fires once per record meeting its condition when the schedule runs.

## When one firing fails

Each scheduled firing is its own transaction. If one member's handling fails, the others are still handled. The failed member still meets the condition, so the next run picks it up. A sweep repairs itself.

Firings triggered by a commit run inside that commit's transaction ([§11, "Transactions and `after commit`"](Reference-11-rule-when-on#transactions-and-after-commit)). If ten records enter a condition at one commit, all ten firings land or fail with the commit. That is how to get "all or none" without a batch construct ([§20, "All-or-nothing batches"](Reference-20-State-change-patterns#all-or-nothing-batches)).
