# 16. Sweeps: rules fire per record

There is no loop construct. A rule fires once per matching record — from `on commit`, once per entrant at the triggering commit; from a schedule source, once per *current member* of its condition at the tick (`## rule`). Transaction scope follows the source. A schedule firing is its own transaction (`## Schedule triggers`): one record's failure never unwinds another's handling — a poison record blocks only itself, everything else stands and disarms its guard, and the next tick retries exactly the stragglers. Commit-source firings are consequences of the triggering commit and share its transaction (`## rule`, "Transactions and `after commit`") — which is what makes a fan-out atomic when the business wants all-or-nothing: N records entering a condition at one commit yield N firings that stand or fall together with the act. A sweep is therefore just a schedule-triggered rule whose condition selects the records needing work:

```
rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}
```

`this` is the matched record. A guard against re-handling is part of the condition's own predicate (`where not suspended`, above — inline or as a named refinement; `## Run-once guards`), so the run-once obligation is per record — and because a schedule source quantifies current members, the sweep is self-healing: a record whose handling was lost is simply still a member at the next tick. Two consequences: every rule has a `when` condition — subjects come from the condition, so there are no subjectless rules — and a batch of records never needs an envelope of its own (`## State-change patterns`, "All-or-nothing batches").
