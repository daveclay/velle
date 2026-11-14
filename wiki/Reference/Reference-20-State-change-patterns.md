# 20. State-change patterns

When a business describes a change in its system, such as "the account becomes suspended" or "the order ships," Velle gives you three ways to represent that change, and they are not interchangeable. A change can be a *classification* that is computed from other data whenever it is read. It can be a *stored value* that rules keep in step with the data. Or it can be an *event* that is recorded each time it happens, so that the history exists as data. Which one you write depends on what the business needs from the change: a current answer, a stored answer, or a record of occurrences.

This page teaches you to tell those three needs apart and to write each one. It works through a single business sentence, "suspend delinquent members," in all three forms. It then shows how to count or time the occurrences once they are recorded, and what to write when the business says "handle these records together, all or none."

The code on this page comes from `examples/membership/membership.velle` and `examples/orders/orders.velle`.

## One sentence, three designs

A product owner says "suspend delinquent members." That sentence can mean three different things, and each one is written differently:

| What the business means | What to write |
|---|---|
| "Suspended" is a status we report. Nothing happens because of it. | A refinement, no rule. |
| "Suspended" is a value we store. Only the current value matters. | A stored flag and two scheduled rules, one to set it and one to clear it. |
| Each suspension is an event. We need to know when it happened, or how many there were. | A rule that creates a record each time a member becomes delinquent. |

The question to ask is: *is suspension a fact you compute, or an action you take?* If it is computed, write the first design. If it is an action whose record matters, write the third. The second sits between them, for a value that must be stored but whose history does not matter.

## Design 1: a computed classification

If being suspended causes no effect in the system, it is a question the data can answer. Write a [refinement](Reference-08-Refinements) and nothing else:

```
shape Delinquent = Member where balance < 0
```

A refinement is always current, needs no rule, and can never be out of date. Choose this design whenever nothing in the system acts on the status.

## Design 2: a stored flag kept current by scheduled rules

Sometimes the status must be stored. A support agent may override it, an external system may read it, or a grace period may keep it set after the balance recovers. If only the current value matters, store a flag and keep it in step with the condition using two [scheduled rules](Reference-17-Schedule-triggers):

```
shape Member {
    balance: Money
    suspended: boolean initially false
}

shape Delinquent = Member where balance < 0

rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}

rule RestoreService when (Member where suspended and balance >= 0) on Nightly {
    this.suspended = false
}
```

Line by line:

- `suspended: boolean initially false` stores the flag on the member.
- `SuspendDelinquents` runs every night. It finds each delinquent member whose flag is not yet set, and sets it.
- `RestoreService` also runs every night. It finds each suspended member whose balance has recovered, and clears the flag.

Each rule writes a constant. Running it twice in one night, or rerunning it after a crash, changes nothing. That is what makes this design safe without any further protection: the rule does not need to fire exactly once, because an extra firing is harmless.

Two consequences follow from the nightly schedule. The flag can lag the balance by up to a day. And a balance that dips below zero and recovers within the same day is never noticed. If either of those is unacceptable, use design 3.

A scheduled rule runs once per matching record. See [§16](Reference-16-Sweeps) for how that works and [§17](Reference-17-Schedule-triggers) for the schedule clause.

## Design 3: a record of each event

When the moment of suspension matters, or the business wants a history, create a record each time a member becomes delinquent:

```
rule SuspendService when Delinquent {
    ServiceSuspension from { member: this, suspendedOn: now }
}
```

A rule with `when Delinquent` and no schedule fires at the commit where a member *enters* the Delinquent refinement ([§11](Reference-11-rule-when-on)). If the member recovers and goes delinquent again in September, that is a new entry, so the rule fires again and a second `ServiceSuspension` is created. The records accumulate as the member's suspension history.

This rule fires exactly once per entry without any extra protection, because it runs inside the same transaction as the commit that triggered it. You need a [run-once guard](Reference-18-Run-once-guards) only when the rule has `after commit` or runs on a schedule, because then there is a gap between transactions where a firing could be lost or repeated.

## Counting and timing the episodes

Call each stretch of time a member spends delinquent an *episode*: it begins when the member enters `Delinquent` and ends when they leave it. Design 3 records the beginning of each episode. Two kinds of question need more than that:

- The business asks about episodes as things: "third delinquency this year," "how long was the last one?"
- A scheduled rule needs to know whether the current episode has already been handled. A scheduled rule has no memory between runs, so anything it must remember has to be stored as data ([§17](Reference-17-Schedule-triggers)).

For either one, store the episode itself as a pair of records: one created when it opens and one created when it closes.

```
shape DelinquencyFlag {
    member: one Member
    flaggedOn: Date
}

shape DelinquencyResolution {
    flag: one DelinquencyFlag
    resolvedOn: Date
}

shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

-- a delinquent member with no open flag starts a new episode
rule OpenDelinquencyEpisode
    when (Delinquent where not exists OpenDelinquencyFlag for this) {
    DelinquencyFlag from { member: this, flaggedOn: today }
}

-- leaving Delinquent closes the open episode
rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag for this)
        resolvedOn: today
    }
}
```

Line by line:

- `DelinquencyFlag` is one episode. `DelinquencyResolution` marks its end and points back at it.
- `OpenDelinquencyFlag` is a flag with no resolution yet: an episode still in progress.
- `OpenDelinquencyEpisode` creates a flag for a delinquent member who has no open one. The `not exists OpenDelinquencyFlag for this` condition is what stops it creating a second flag for the same episode.
- `CloseDelinquencyEpisode` fires when the member leaves `Delinquent` ([§13](Reference-13-Exit-triggers)) and resolves the open flag.

With the episode stored, the questions become ordinary expressions. `count(DelinquencyFlag where member == this) >= 3` is "third delinquency." Any other rule can check `not exists ServiceSuspension for this` against a flag to act once per episode.

The closing rule writes `(OpenDelinquencyFlag for this)` as a single value, not a collection. The compiler allows that only when it can prove there is at most one open flag per member. Here the proof comes from the opening rule's own condition, which never creates a second open flag. See [§10](Reference-10-Predicate-expressions) for the `for` form and `singular_references.md` for when the compiler accepts it.

This pattern is three shapes and two rules every time. There is no shorter spelling.

## All-or-nothing batches

Velle has no construct for "do these together as one transaction." When a business says "handle these records together, all or none," one of the following is what to write.

**Several effects in one rule body are already one transaction.** Double-entry bookkeeping is two statements in one body. Both land or neither does ([§11](Reference-11-rule-when-on)):

```
rule PostTransfer when Transfer {
    LedgerEntry from { account: from, amount: -amount }
    LedgerEntry from { account: to,   amount: amount }
}
```

**A rule on the parts of a commit fires once per part, inside that commit.** "When an order is placed, reserve stock for every line, all or none." An order and its lines arrive in one commit ([§6, "Inline part creation"](Reference-06-Relationships#inline-part-creation-with)). A rule on the line fires once per line. Every firing shares the order's transaction, so if any one fails the whole order is refused ([§16](Reference-16-Sweeps)). From `examples/orders/orders.velle`:

```
rule ReserveStock when OrderLine {
    Reservation from { line: this, quantity: this.quantity }
}
```

To make the reservation wait for a condition on the whole order, put the condition on the order and refer to it from the line's rule: `when (OrderLine where order is FulfillableOrder and not exists Reservation for this)`.

**One change that every record must show at once is one record that every record reads.** "Apply the new rate to every subscription at midnight" is not a batch update. Commit one `PriceSchedule` record and let each subscription's price be a [derived property](Reference-07-Derived-properties) that reads it. Every price changes at that one commit.

**A set that succeeds or fails together is a business object.** A settlement batch or a payroll run has a name in the business. Give it a shape, make the members point at it, and write the rules on the batch record. One record, one transaction, one `id` to reconcile against the outside world ([§4](Reference-04-State-and-commits)).

**Records that arrive one at a time get a completeness condition.** Results from a clearing house land one commit at a time. Downstream rules should wait for the complete set, so write the complete set as a refinement and trigger on it:

```
shape CompleteResponse = BatchResponse where count(results) == count(batch.entries)
```

Nothing observes the partial set, because nothing is triggered by it.

## What the compiler checks

The compiler works out which of the three designs each rule is, from what is written. It uses that in two ways:

- **It warns, and does not reject,** when a design looks heavier than it needs to be. For example, a stored flag that nothing ever reads except the rules that set it could have been a refinement. The author may ignore these ([checks.md, A1](checks#a1--rung-recognition)).
- **It rejects** a rule whose condition contains a guard, such as `not exists ServiceSuspension for this`, when the rule's body never creates the record the guard looks for. Such a rule would fire again at every run ([checks.md, V2](checks#v2--disarm-proof)). It also rejects a guard and backup schedule on a rule that fires only `on commit`, because that rule already fires exactly once and the guard protects nothing ([checks.md, V4](checks#v4--boundaryapparatus-both-ways)).

Tooling such as impact analysis can report the design to a reader: "this value is a nightly snapshot," or "this flag is kept current by a nightly rule and may lag by up to a day" ([checks.md, A3](checks#a3--impact-analysis-surfaces)).

## Related pages

- [§12 Assignment](Reference-12-Assignment), including the ledger alternative to updating a field in place.
- [§16 Sweeps](Reference-16-Sweeps) and [§17 Schedule triggers](Reference-17-Schedule-triggers), for how scheduled rules run.
- [§18 Run-once guards](Reference-18-Run-once-guards), for when a rule needs protection against firing twice.
- [§19 Self-referential folds](Reference-19-Self-referential-folds-and-tolerates), for running totals and other values a rule updates from their own previous value.
