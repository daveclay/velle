# 11. `rule ... when ... on ...`

A rule is how a Velle spec says "when this is true, do that." Everything a system does in response to its data is written as a rule: send a receipt when an invoice is paid, suspend a member when a balance goes negative, sync a signup to the CRM. There is no `if`, no event handler, and no function to call. A rule names a condition and the effects that follow, and the compiler works out which commits can make the condition true.

After reading this page you can write a rule header, say whether a rule reacts to a change or re-checks on a schedule, decide whether its effects belong inside the triggering transaction or after it, and recognize the compile errors a rule can draw.

## The header

```
rule <name> when [leaving] <condition> [on|after <trigger>, ...] { <effects> }
```

```
rule SendReceipt when SettledInvoice {
    Receipt for invoice sentOn: now
}
```

- `when <condition>` names a [refinement](Reference-08-Refinements). The rule fires for a record that *enters* the refinement: it was not a member before the commit and is one after it. `when leaving` is the mirror, a record that stops being a member ([§13](Reference-13-Exit-triggers)).
- `on <trigger>` names what prompts the rule to look. `on commit` means any commit that can affect the condition, and is the default when the clause is omitted. A schedule name such as `on Daily` means the rule looks at every tick of that schedule ([§17](Reference-17-Schedule-triggers)). Both can be listed: `on commit, Daily`.
- `after commit` instead of `on commit` runs the effects in a new transaction after the triggering one has been saved. See "Transactions and `after commit`" below.
- The body is one or more effects: create a record, assign a field ([§12](Reference-12-Assignment)), or order effects with `then` ([§15](Reference-15-then)).

A rule fires exactly once for each record that newly satisfies its condition. Which records those are depends on the trigger:

- From `on commit`, the rule fires for each record whose membership flipped at that commit.
- From a schedule, the rule fires at the tick for every record that is *currently* a member. The tick re-checks the condition, it does not watch for a change. That is why a scheduled rule usually needs a condition that becomes false once the rule has run, such as `not exists Reminder for this`. A scheduled rule remembers nothing between ticks, so "already handled" has to be stored as data ([§17](Reference-17-Schedule-triggers), [§18](Reference-18-Run-once-guards)).

The header says nothing about how the change is detected. Whether the generated system checks at write time, listens to an event stream, or keeps an index is not part of the rule's meaning.

## The compiler finds the triggering commits for you

A Velle system never does anything on its own. It reacts only to external events: an act being committed, or a schedule ticking. A tick is just a commit whose changed value is `today`.

A rule's condition can be a refinement over data that no act mentions directly, such as `Delinquent = Member where balance < 0`. You do not say which commits can make a member delinquent. The compiler knows every rule that assigns `balance` and every act that feeds those rules ([§12](Reference-12-Assignment)), so it derives the full set of commits that can fire the rule. If next year someone adds a new rule that writes `balance`, the trigger set grows without any edit to the delinquency rule, because the business condition did not change.

Two things follow:

- **A rule nothing can fire is a compile error.** If no commit in the spec, and no schedule in the rule's `on` clause, can make a record enter the condition, the compiler rejects the rule ([checks.md, V3](checks#v3--unfireable-rule)). The common case is time. `OverdueInvoice = Invoice where due < today` depends on `today`, and no act changes `today`. A rule `when OverdueInvoice` with no schedule fires for an invoice created already overdue, but never for one that ages into overdue. The error says so and asks for a schedule in `on`.
- **"When does this run?" has an answer the compiler can print.** Forward: if this commit lands, these rules may fire. Backward: this rule fires as a consequence of withdrawals, deposits, and the daily tick ([checks.md, A3](checks#a3--impact-analysis-surfaces)).

## Entering and leaving happen at a commit

"Newly satisfies" is defined per commit. A commit is a discrete moment with a before-state and an after-state, and `when Delinquent` means "the commit at which this member went from not delinquent to delinquent." Consequences:

- **Re-entry fires again.** A member who recovers and goes delinquent again in September enters `Delinquent` a second time, so the rule fires a second time. You need no extra machinery to react once per occurrence. A [run-once guard](Reference-18-Run-once-guards) is for surviving a transaction boundary or a schedule, not for entry itself.
- **A rule on an act fires once per act.** `when CorrectEmail` fires once for each `CorrectEmail` committed. It is never re-checked by a tick, so no scheduled sweep can apply an old correction twice.
- **Aging is an entry too.** With `on commit, Daily`, an invoice whose due date passes at the daily tick enters `OverdueInvoice` at that tick, exactly as it would enter at a payment-reversal commit.

## Transactions and `after commit`

A rule's body is **one commit**. Every effect in the body lands, or none does. A body is never half applied. The body's effects are themselves a new commit, and that commit may fire further rules the same way an act's commit does.

By default, an act's commit and every commit its rules produce share one all-or-nothing **transaction**. The word is descriptive, not a keyword. If anything fails anywhere in that set, the whole set rolls back, the act included, and resubmitting the act produces the same commits again. This default is what makes a plain rule reliable. A record's entry into a refinement exists only at the commit that caused it. Afterwards, "was restored" and "was never delinquent" look the same in the data. A rule that fires outside the causing transaction and fails would have nothing to retry from, because the thing that triggered it is not stored anywhere.

What the transaction guarantees, such as snapshot reads, atomic landing, and serialization of conflicting commits, is the [universal-transaction contract](evaluation#the-universal-transaction) in evaluation.md. Delivering it over real storage is the engineer's job, and one serializable database transaction per Velle transaction delivers all of it.

A rule opts out of the shared transaction with `after commit`. Its effects run as a new transaction, entered only after the triggering transaction is durably saved. The act stands even if this rule's firing then fails. A schedule in the same clause is the backup that re-runs anything the gap lost:

```
rule SyncToCrm when UnsyncedSignup after commit, Hourly {
    CrmSync from { signup: this, syncedOn: now }
}
```

Read a header this way: `on commit` means part of the act; `after commit` means follows the act, healed on the stated cadence; `on Nightly` means follows on its own clock.

A transaction boundary arises in three ways:

- **Declared.** The rule says `after commit`.
- **Inherent.** The rule runs on a schedule. A tick is a fresh commit and the originating act's transaction is long gone, so there is no `on` versus `after` distinction for schedules.
- **Forced.** The rule makes an external call, such as charging a card. The call happens in the world, outside any transaction, so the rule cannot share the triggering transaction. Writing such a rule with plain `on commit` is a compile error. The fix is `after commit` with a guard and a backup schedule, or `tolerates loss` on the rule for an effect the business does not mind losing, such as an analytics ping ([§19](Reference-19-Self-referential-folds-and-tolerates)).

Boundaries nest. An `after commit` rule's own consequences share *its* transaction by default. A chain of rules is therefore a tree of transactions, each subtree all-or-nothing, with each boundary's guard and backup healing the gap above it.

**The compiler checks that a boundary and its guard go together, both ways** ([checks.md, V4](checks#v4--boundaryapparatus-both-ways)). An `after commit` rule without a [run-once guard](Reference-18-Run-once-guards) and a backup schedule is rejected: its firing can be lost at the boundary and nothing would find it again. A guard and backup schedule on a plain `on commit` rule is also rejected: inside a transaction there is no gap to lose a firing in, so the guard protects nothing.

## Reading who committed the act

A rule that runs inside an act's transaction may read who committed the act, the [actor](GLOSSARY#acts-and-the-trust-boundary) ([§22](Reference-22-Open-unresolved), "The actor"). When the rule's subject is the act itself, `actor` is already in scope. When the subject is a record the transaction produced, the rule names the actor with a `by` clause in its header:

```
rule LogQuarantine when Quarantine by Staff? { ... }
```

The compiler types the clause by which commits can reach the rule ([checks.md, V31](checks#v31--a-rules-actor-typed-by-reachability)). If some path to the trigger starts at a tick, an `after commit` rule, or an act without a `by` clause, no actor is present on that path, and the clause must be optional (`by Staff?`).

An `after commit` or scheduled rule may not declare `by`, because it fires when nobody is asking. It reads the actor a durable act stored instead, as `this.actor` when the act is its subject or `this.request.actor` through a reference. That value is the same whether the prompt firing or the backup firing ran.

## Calling out to the world: commit the intent first

No system can call a card processor and be guaranteed that the record of the attempt also lands in the database. The call and the record live in different worlds. Velle does not try to make that atomic. Instead, the idiom is ordering: **commit the intent before the effect.**

```
shape ChargeIntent {
    order: one Order
    requestedOn: DateTime initially now
}

shape ChargeResolution {
    intent: one ChargeIntent
    resolvedOn: DateTime
}

shape UnchargedOrder   = Order where not exists ChargeIntent for this
shape UnresolvedCharge = ChargeIntent where not exists ChargeResolution for this

rule RecordChargeIntent when UnchargedOrder {         -- pure data — durable before any call
    ChargeIntent from { order: this }
}

rule ResolveCharge when UnresolvedCharge after commit, Hourly {
    -- the processor interaction happens here; what it learns lands as the resolving fact
    ChargeResolution from { intent: this, resolvedOn: now }
}
```

Line by line:

- `ChargeIntent` records that a charge is wanted. `RecordChargeIntent` creates it inside the order's transaction, so it is saved before any call goes out.
- `UnresolvedCharge` is an intent with no outcome yet. That is honest state: a charge may have gone out, and its result is unknown.
- `ResolveCharge` runs `after commit`, makes the call, and records what it learned. If the process crashes between the call and the record, the intent still stands, and the hourly backup revisits it.

What the backup does with an unresolved intent is a business decision: ask the processor what became of this attempt, record the outcome, or reverse the charge. Recognizing your attempt among the processor's records needs an identity the data already carries, the intent's `id` or a generated key such as `processorKey: text initially randomUUID` ([§5](Reference-05-Scalars)).

A retry budget is memory across transactions, so it is data too. Create a record for each try, such as `ResolutionAttempt`, write the failure outcomes as refinements, guard the retry with `count(ResolutionAttempt for this) < 3`, and let a separate rule react to the exhausted state.

## Firing order is never specified

When one commit matches several rules, the generated system may fire them in any order, or at the same time. Where one rule's effect feeds another's condition, the data flow orders them ([§15](Reference-15-then)). Where it does not, the result must be the same in every order. A spec whose outcome depends on an unstated order is rejected, naming the rules involved ([checks.md, V15](checks#v15--confluence-coarse)). Two rules that write the same field in one transaction are the one-writer error ([§12](Reference-12-Assignment), [checks.md, V1](checks#v1--one-writer-disjointness)).

## Related pages

- [§13 Exit triggers](Reference-13-Exit-triggers), for `when leaving` and `when deleted`.
- [§15 then](Reference-15-then), for ordering effects within a body.
- [§16 Sweeps](Reference-16-Sweeps) and [§17 Schedule triggers](Reference-17-Schedule-triggers), for how a scheduled rule runs once per matching record.
- [§18 Run-once guards](Reference-18-Run-once-guards), for the guard and backup an `after commit` rule needs.
- [§19 Self-referential folds and tolerates](Reference-19-Self-referential-folds-and-tolerates), for `tolerates loss` and `tolerates contention`, the two clauses a rule header may carry.
