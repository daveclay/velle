# Time, repetition, and running totals

Open this file for rules on a schedule or behind `after commit`, for making a rule run once per record, for values a rule keeps by adding to them, for reads that make commits take turns, and for choosing how to represent a status. The basics of `on` and `after commit` are in [language.md](language.md), §11. Every example marked `velle` compiles on its own; one marked `velle refused` is refused with the messages quoted after it, and one marked `velle advisory` compiles with the advisory quoted after it ([language.md](language.md) says how the quotes are written).

## Contents

1. [Sweeps: a scheduled rule runs once per record](#1-sweeps-a-scheduled-rule-runs-once-per-record)
2. [Run-once guards](#2-run-once-guards)
3. [`after commit`: work outside the request's transaction](#3-after-commit-work-outside-the-requests-transaction)
4. [Running totals and `tolerates`](#4-running-totals-and-tolerates)
5. [A running value in the order the business means](#5-a-running-value-in-the-order-the-business-means)
6. [Commits that take turns: `tolerates contention`](#6-commits-that-take-turns-tolerates-contention)
7. [A status: computed, stored, or a record per event](#7-a-status-computed-stored-or-a-record-per-event)
8. ["All or none"](#8-all-or-none)

## 1. Sweeps: a scheduled rule runs once per record

Velle has no loop. To act on every record that meets a condition, write one rule with that condition. On a schedule it runs once per matching record, each firing in its own transaction.

```velle
act Member { name: text, balance: decimal, suspended: boolean }

shape Delinquent = Member where balance < 0

rule SuspendDelinquents when (Delinquent where not suspended) on Nightly {
    this.suspended = true
}

rule RestoreService when (Member where suspended and balance >= 0) on Nightly {
    this.suspended = false
}
```

- `on Nightly` names a schedule. Schedule names are not declared; the engineer decides when each ticks. `Daily`, `Hourly`, `Weekly`, `Monthly` are names like any other.
- At each tick the rule fires for every record in its condition at that moment. It re-checks; it does not watch for a change.
- `where not suspended` stops the rule handling a member twice.
- If one member's firing fails, the others still land. The failed member still matches, so the next tick picks it up.
- A rule `on commit` fires for each record whose membership changed at that commit. With `on commit, Nightly` it does both.
- A commit-triggered rule reacts to every change; a scheduled rule reacts to what is still true when it runs. A balance that dips below zero at 09:00 and recovers at 17:00 is never seen by the nightly rule. Which is right is a business choice.
- A rule whose subject is a transient act may not run on a schedule or `after commit`.

**Refused.** A transient act's rule on a schedule:

```velle refused
transient act Ping { note: text }
shape Pong { note: text }

rule P when Ping on Daily {
    Pong from { note: note }
}
```

> [V17] rule 'P' — transient act 'Ping' is gone before any 'after commit' firing or tick runs; handle it at its commit, and hang asynchronous work off a durable intent the handling rule creates

## 2. Run-once guards

A rule `on commit` runs inside the triggering transaction. It fires exactly once and needs no protection. A rule behind `after commit` must have a **guard**: a condition in `when` that its own body makes false. A rule on a schedule sees the same record at every tick, so it needs one too unless running twice is harmless, as setting a flag to a constant is. The record or field that makes the guard false is the evidence the work was done.

```velle
act Invoice { total: decimal }

act Receipt { invoice: one Invoice, sentOn: DateTime }

shape ReceiptEmail { receipt: one Receipt, queuedOn: DateTime }

shape UnemailedReceipt = Receipt where not exists ReceiptEmail for this

rule EmailReceipt when UnemailedReceipt after commit, Hourly {
    ReceiptEmail from { receipt: this, queuedOn: now }
}

act Account { balance: decimal }

act Deposit {
    account: one Account
    amount: decimal
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    this.applied = true
}
```

- `UnemailedReceipt` is the guard. Creating the `ReceiptEmail` takes the receipt out of it. The effect and its evidence are one body, so they land together or not at all.
- `not applied` is a guard on a flag of the record itself, and `this.applied = true` makes it false. The compiler recognizes two forms of guard: `not exists ...` and `not` a boolean flag. A condition such as `appliedOn is none` with the body setting `appliedOn` is not recognized today, and the rule is refused as unguarded.
- The guard decides what "once" means, and that is a business decision:

| Once per | Guard |
|---|---|
| record | `Deposit where not exists DepositApplication for this` |
| record, by a flag | `Deposit where not applied` |
| customer, ever | `AccountFlag where not exists (FlagNotification where customer == this.customer)` |
| invoice, at most every three days | `OverdueInvoice where not exists (Reminder where invoice == this and sentOn > today - 3 days)` |

**Refused.** A guarded rule whose body does not make its guard false:

```velle refused
act Account { balance: decimal }

act Deposit {
    account: one Account
    amount: decimal
    applied: boolean initially false
}

shape DepositNote { deposit: one Deposit }
shape UnappliedDeposit = Deposit where not applied

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    DepositNote from { deposit: this }
}
```

> [V2] rule 'ApplyDeposit' never leaves its trigger state, so it would fire again for the same record; its body must assign 'applied' to make its own condition false

## 3. `after commit`: work outside the request's transaction

`after commit` runs a rule's effects in a transaction of its own, after the triggering one is saved. The request stands even if the rule then fails. Every `after commit` rule needs a guard and a backup schedule beside it, so a lost firing is found again: `after commit, Hourly`.

Calling the outside world, such as charging a card, cannot be made atomic with the records. Record the intent first, then act on it:

```velle
act Order { total: decimal }

shape ChargeIntent { order: one Order, requestedOn: DateTime }
shape ChargeResolution { intent: one ChargeIntent, resolvedOn: DateTime }

shape UnchargedOrder = Order where not exists ChargeIntent for this
shape UnresolvedCharge = ChargeIntent where not exists ChargeResolution for this

rule RecordChargeIntent when UnchargedOrder {
    ChargeIntent from { order: this, requestedOn: now }
}

rule ResolveCharge when UnresolvedCharge after commit, Hourly {
    ChargeResolution from { intent: this, resolvedOn: now }
}
```

- `RecordChargeIntent` runs inside the order's transaction, so the intent is saved before any call goes out.
- The engineer's code makes the call and records what it learned. `UnresolvedCharge` is honest state: the charge may have gone out and its result is unknown. The hourly run revisits it.
- A retry budget is data too: a record per attempt, and a guard such as `count(ResolutionAttempt for this) < 3`.
- A rule whose effect the business does not mind losing, such as an analytics ping, declares `tolerates loss` in its header: `rule PingAnalytics when Visit tolerates loss { ... }`.
- A rule behind `after commit` or on a schedule may not declare `by` for the actor; it reads the actor a kept act stored, `this.actor` ([prohibitions.md](prohibitions.md), §8).

**Refused.** `after commit` with no guard:

```velle refused
act Order { total: decimal }
shape Confirmation { order: one Order, sentOn: DateTime }

rule Confirm when Order after commit {
    Confirmation from { order: this, sentOn: now }
}
```

> [V4] rule 'Confirm' fires 'after commit', in a transaction of its own, so a firing can be lost between the commit and the rule, and nothing would find it; trigger the rule on a guard, a condition its body makes false by creating a record or setting a field, and add a backup schedule beside 'after commit'

A guard but no backup:

```velle refused
act Account { balance: decimal }

act Deposit {
    account: one Account
    amount: decimal
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied

rule Apply when UnappliedDeposit after commit {
    account.balance = account.balance + amount
    this.applied = true
}
```

> [V4] rule 'Apply' fires 'after commit' with a guard but no backup schedule, so a lost firing would never be retried; add a schedule beside it, 'after commit, Hourly'

## 4. Running totals and `tolerates`

An assignment whose right side reads the field it writes, `balance = balance + amount`, is a running total. It is right only if each record is added once and, for some totals, in the right order. A rule on a schedule or with a backup schedule can fire twice for one record. A rule on a schedule handles its records in no order.

| Form | Safe if added twice | Safe in any order |
|---|---|---|
| `max(x, amount)`, `min`, adding to a `many`, `x or flag` | yes | yes |
| `x + amount`, a count | no | yes |
| appending to a `many` of values that tolerates duplication | as declared | no |
| a streak, a moving average, "reset on a miss" | no | no |

When a rule is exposed to a hazard its form is not safe under, the compiler refuses it and names four ways out:

1. Guard the rule so it fires once per record (§2).
2. Derive the value instead: `balance: decimal = sum(deposits, amount)` cannot double count.
3. Add a scheduled rule that recomputes the true value, such as `viewCount = count(PageView for this)`.
4. Declare that the business accepts it, on the field: `visitCount: long initially 0 tolerates duplication`, or `tolerates reordering`.

```velle
act Member { name: text }

shape Counter {
    member: one Member
    visits: long initially 0 tolerates duplication
    largest: integer initially 0
}

rule OpenCounter when Member {
    Counter from { member: this }
}

act Visit { counter: one Counter, minutes: integer }

rule CountVisit when Visit on commit, Hourly {
    counter.visits = counter.visits + 1
    counter.largest = max(counter.largest, minutes)
}
```

- `visits` is folded by a rule with an hourly backup, so a visit may be counted twice. `tolerates duplication` says the business accepts that. Read it as a claim: `balance: decimal tolerates duplication` would be visibly wrong.
- `largest` uses `max`, which gives the same answer however often it runs. It needs nothing.
- Write `tolerates` only for a hazard the rule meets. The compiler does not always catch one that covers nothing.

**Refused.** A running total folded by a rule with a backup schedule, unguarded:

```velle refused
act Account { balance: decimal }
act Deposit { account: one Account, amount: decimal }

rule ApplyDeposit when Deposit on commit, Hourly {
    account.balance = account.balance + amount
}
```

> [V8] 'Account.balance' is computed from its own value by rule 'ApplyDeposit', which can fire more than once for one record, so the same record could be folded in twice; guard the rule so it fires once per record, derive 'balance' from the records instead, add a scheduled rule that recomputes it, or declare 'balance' with 'tolerates duplication'

## 5. A running value in the order the business means

A streak kept by a nightly sweep depends on order, and a tick's firings have none. Put the order in the data: each record finds its predecessor by a field and derives its running value from the predecessor's.

```velle
act Account { name: text }

act Payment {
    account: one Account
    onTime: boolean
    paidOn: Date
    previous: one Payment? = latest(Payment where account == this.account and paidOn < this.paidOn by paidOn)
    streakAfter: integer = if not onTime then 0 else if previous is some then previous.streakAfter + 1 else 1
}

shape Standing = Account where exists Payment for this {
    streak: integer = latest(Payment for this by paidOn).streakAfter
}
```

- `previous` is the latest earlier payment for the same account. The comparison must be strict, `<`, and the field fixed at creation, so the chain ends.
- `streakAfter` reads itself one step back. A late payment slots into place by `paidOn`, and every later streak re-derives.
- Two payments on one date tie. If the result depends on which comes first, order by a further field the business supplies.

**Refused.** `<=` in `previous`:

```velle refused
act Account { name: text }

act Payment {
    account: one Account
    onTime: boolean
    paidOn: Date
    previous: one Payment? = latest(Payment where account == this.account and paidOn <= this.paidOn by paidOn)
    streakAfter: integer = if not onTime then 0 else if previous is some then previous.streakAfter + 1 else 1
}
```

> [V14] 'Payment.streakAfter' reads itself on its 'previous', so the chain through 'previous' must end. 'previous' picks by 'paidOn <= this.paidOn', which can pick a record with the same 'paidOn', so the chain can come back to this record; write 'paidOn < this.paidOn'

## 6. Commits that take turns: `tolerates contention`

Commits normally run at the same time, and wait for each other only when they touch the same record or value. A read over a whole shape, such as `count(Waitlist)`, is about every record, so every commit on that shape must wait its turn behind it. The compiler warns, because it cannot tell whether the wait is wanted.

```velle
act Waitlist { slot: DateTime, name: text }

never (Waitlist where count(Waitlist) > 20) tolerates contention

act Booking { slot: DateTime, name: text }

never (Booking where count(Booking where slot == this.slot) > 4)
```

- The waitlist holds twenty names at most, whoever they are. Two people joining at once must be decided one after the other. `tolerates contention` says the wait is the requirement, and the warning stops.
- The cap on bookings is per slot, so the read is tied to the commit's own slot and no warning arises.
- Without the clause the compiler advises, and goes on to name the ways out: tie the read to the commit, move the rule to a schedule, or declare `tolerates contention`.

```velle advisory
act Waitlist { slot: DateTime, name: text }

never (Waitlist where count(Waitlist) > 20)
```

> [A5] every commit touching Waitlist joins a single queue. `Waitlist` reads every Waitlist and correlates to no key (never #1), so any two such commits conflict. State the policy: ...
- The clause goes at the end of a `never` or `requires`, or in a rule's header after the trigger: `rule ... when ... on commit tolerates contention { ... }`.
- The advisory appears often, for example on role gates and on reads through a reference that has no key. It never blocks a spec. Adding `tolerates contention` is a business decision; ask before writing it.

## 7. A status: computed, stored, or a record per event

"Suspend delinquent members" can mean three things. Ask: is suspension a fact you compute, or an action you take?

| What the business means | Write |
|---|---|
| A status we report; nothing happens because of it. | A refinement, no rule: `shape Delinquent = Member where balance < 0`. |
| A value we store; only the current value matters. | A stored flag and two scheduled rules that set and clear it (§1). It can lag by up to a tick. |
| Each suspension is an event; we need when, or how many. | A rule that creates a record each time a member enters the state. |

A record per event:

```velle
act Member { name: text, balance: decimal }

transient act Charge { member: one Member, amount: decimal }

rule ApplyCharge when Charge {
    member.balance = member.balance - amount
}

shape Delinquent = Member where balance < 0

shape DelinquencyFlag { member: one Member, flaggedOn: Date }
shape DelinquencyResolution { flag: one DelinquencyFlag, resolvedOn: Date }

shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

rule OpenDelinquencyEpisode when (Delinquent where not exists OpenDelinquencyFlag for this) {
    DelinquencyFlag from { member: this, flaggedOn: today }
}

rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from { flag: (OpenDelinquencyFlag for this), resolvedOn: today }
}
```

- Each stretch of delinquency is one flag, opened on entry and resolved on exit.
- `count(DelinquencyFlag where member == this) >= 3` is then "third delinquency".
- `(OpenDelinquencyFlag for this)` is one record because the opening rule never creates a second open flag for a member. If something else could, the compiler refuses the read and asks for `latest(... by ...)`.
- A rule fires again each time a record re-enters its condition; no extra machinery is needed for "once per occurrence".

## 8. "All or none"

Velle has no batch construct. When the business says "handle these together, all or none":

- Several effects in one rule body are one commit: both ledger entries of a transfer land or neither does.
- A rule on the parts of an act fires once per part inside the act's transaction, so one failing part refuses the whole act ([relationships.md](relationships.md), §4).
- One change every record must show at once is one record they all read, such as a price schedule each subscription's derived price reads.
- A set that succeeds or fails together, such as a payroll run, is a business object: a shape the members point at.
- Records that arrive one at a time wait on a completeness condition: `shape CompleteResponse = BatchResponse where count(results) == count(batch.entries)`, and downstream rules trigger on it.
