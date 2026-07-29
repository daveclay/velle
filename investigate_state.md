# State Changes

Velle needs a way for product owners to describe how state changes occur in the system.

## Philosophy

Any system can be considered a black box with its persistent store as a single state. From Velle's perspective, mutations are committed to change this state, and the truth of the system must be consistent.

React is a similar analogy/approach with mutations committing to a single state tree. What I'm taking from that is the idea of a mutation being an arbitrary shape, and thinking of "commit" as an agnostic term for all underlying CRUD operations. The "state" is agnostic to the storage mechanism.

**The substrate is last-in-wins.** Nothing in any computer system provides anything other than last-in-wins; an engineer may design a system where concurrent writes are merged in more sophisticated ways, but always built from fundamental tools where last-in-wins (the database). Velle therefore needs no ordering vocabulary for in-place mutation — a mutated field holds the last value committed.

**Mutating a field in place is allowed.** Velle does not force the ledger pattern (below) onto a model that doesn't need history. The goal instead is that when a field is mutated, Velle can tell the product owner the impact — which refinements may change membership, which rules may consequently fire, which mutation policies could reject the change — and verify that relationships and behaviors stay consistent.

**Flexible, not restrictive.** Velle should not force one design pattern over another — evidence shape vs. guard flag, ledger vs. in-place, internal vs. client-supplied fields are the author's calls, made per use case (how much trust the client warrants, how much history the business needs). Velle's job is to provide the validation tools that prove the system does what the author intended, whichever pattern they chose.

**A system never does anything on its own.** It can only react to an external event — a commit. Scheduled ticks are just another external event (README §16 already treats a tick as a shape instance arriving). Every rule firing must therefore trace to a commit; see "Rules ground in commits," below.

## Mutation in place

A mutation shape is an ordinary shape — nothing marks it as special. (Some way for an author to mark a shape as an "external input" may eventually be wanted, but that can be put off; for now a mutation is just another shape.)

```
shape Customer {
    email: text
}

shape CorrectEmail {
    customer: one Customer
    corrected: text
}
```

A rule is where a mutation's effect is described. The effect itself is an assignment statement:

```
rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}
```

The pieces:

- **`on CorrectEmail`** — a plain-shape trigger, already sanctioned (README §14's `rule InitiateCharge on Order`): the rule fires when an instance is committed.
- **`customer.email = corrected`** — assignment, a new effect-statement form alongside `X from { ... }` and `X for y ...`. `=` in rule-body position means *becomes, now*; in shape-body position it remains *is defined as, always* (derivation). One symbol, two positions — the same deliberate positional reuse the language already makes with `on` (prefix entry-trigger vs. postfix schedule), `for` (association vs. query), and `?` (optional marker vs. `?.`). The two contexts never collide: rule bodies contain no definitions, shape bodies contain no effects.
- **The target is a literal static path — a hard requirement.** Because `customer.email` is statically known, the whole-spec compiler knows every refinement predicate and derived property that reads `email`, and impact analysis falls out for free: which memberships may change, which entry/exit rules may consequently fire, which `forbidden` liens (README §12) could reject the commit. A computed or reflective target would destroy this, so no solution for mutation syntax may ever introduce one.

### One writer per field, per commit

More than one assignment to the same field of the same referenced shape is a compile error whenever the assignments' triggers can coincide — whenever a single commit could fire both. There is no `then`-ordered escape: sequencing two writes to one field inside a single firing is not a real business statement, and one assignment whose RHS states the whole intent replaces it. Within a single commit's rule firings "last" is undefined; last-in-wins is only real *across* commits, where "last" is a fact of the world.

"Can the triggers coincide?" is answered statically from the declarations, never at runtime: same trigger shape → yes; a refinement and its base (or two overlapping refinements) → yes; unrelated shapes → provably never. If the compiler can't prove two triggers disjoint, it errors — uncertainty fails closed. The machinery is the refinement-overlap check README §7 already names as a compiler goal, applied to trigger shapes. It's a whole-spec check: a second rule assigning `customer.email`, added months later in another file, trips it, reported as one connected diagnostic naming both rules. A spec that compiles has no ambiguous-write configuration reachable at runtime.

Legal — separate acts whose triggers can never coincide fire from different commits, where last-in-wins orders them:

```
shape CorrectEmail  { customer: one Customer, corrected: text }
shape OverrideEmail { customer: one Customer, admin: one Admin, overridden: text }

rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}

rule ApplyEmailOverride on OverrideEmail {
    customer.email = overridden          -- legal: an OverrideEmail is never a CorrectEmail,
                                         -- so these always fire from different commits
}
```

Whichever act is committed last wins — precisely the business reality ("the admin's override stands until the customer corrects it again, and vice versa").

Errors — triggers that can coincide:

```
-- same trigger shape: one CorrectEmail commit fires both
rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}
rule NormalizeEmail on CorrectEmail {
    customer.email = lowercase(corrected)     -- compile error
}
```

```
-- a refinement and its base: a TrustedCorrection IS a CorrectEmail,
-- so one commit fires both rules
shape TrustedCorrection = CorrectEmail where customer is VerifiedCustomer

rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected
}
rule ApplyTrustedCorrection on TrustedCorrection {
    customer.email = corrected                -- compile error
}
```

## Mutation with ledger design

The ledger is an alternative *pattern* the author chooses when history is part of the model — not something Velle forces. Nothing is ever edited; each change is a new record — an ordinary shape instance — and the "current value" is a derived property that selects the latest one:

```
shape Customer {
    signupEmail: text
    email: text = if exists EmailCorrection for this
                  then latest(EmailCorrection for this).corrected
                  else signupEmail
}

shape EmailCorrection {
    customer: one Customer
    corrected: text
    correctedOn: Date
}
```

Nothing here is new mechanism: `EmailCorrection` is a reified act like any other shape, `latest(...)` orders by `correctedOn` (the shape's only `Date` property), and the `exists` check narrows the then-branch so `.corrected` is provably evaluable — the collection can't be empty there.

The ledger case needs no connect-statement at all: the connection *is* the derived property, declared on `Customer` itself. Commit means insert, the ledger is append-only, and the stored truth (`signupEmail`, plus the correction history) is never touched. The two patterns answer different business realities — "email changed" (in place) versus "email changed and the history matters" (ledger) — and the author picks per field.

## Rules ground in commits

A rule on a drift refinement looks, at first glance, like it's declared outside any commit — suspect under the nothing-happens-on-its-own principle:

```
shape Delinquent = Account where balance < 0

rule SuspendService on Delinquent once {
    ServiceSuspension from { account: this, suspendedOn: now }
}
```

What makes it fire? The resolution: **drift is always commit-mediated.** An account doesn't drift into `Delinquent` by itself — `balance < 0` becomes true only because some commit changed the data the predicate reads. So a rule on a drift refinement is not waiting outside any commit; its real trigger set is *the set of commits that can affect its predicate*, and that set is statically computable from exactly the machinery in-place mutation mandates: `Delinquent` reads `balance`, and the static-path + one-writer requirements mean the compiler knows every assignment targeting `balance` and every act that feeds it. `SuspendService` compiles to a derived fact — it can fire only as a consequence of (say) a `Withdrawal` commit or a `Deposit` commit. The system never acts on its own; `on Delinquent` is a declarative surface over a commit-reactive reality, and the compiler can name the commits.

This preserves both halves of a real tension. The benefit of defining a rule *outside* its trigger survives: `on Delinquent` states the business condition — the durable judgment — and a new writer of `balance` added next year automatically extends the rule's trigger set with no edit to the rule (correct: the business condition didn't change). But Velle still knows *when* the rule executes — not because the author declared it, but because the "when" is derivable. Declared **what**, computed **when**, proven **reachable** — the same division of labor as the rest of the language.

Two compiler obligations fall out:

1. **The unfireable-rule error.** If the derived commit set is empty — no commit anywhere in the spec can cause entry into the refinement — the rule can never fire, and that's a whole-spec compile error, not a dead-code shrug. The sharp instance is time: `OverdueInvoice = Invoice where due < today` — no commit changes `today`. Entry at `Invoice` creation (committed already-overdue) is observable, but entry *by time passing* is unobservable without a tick. The diagnostic is precise: "entry into `OverdueInvoice` via the passage of time is unobserved by any schedule — add a postfix `on Daily` or this rule under-fires." README §16's stance stops being a convention and becomes a provable coverage check, since schedule ticks are commits and appear in the same derived trigger set.
2. **The PO-facing answer to "when does this run?"** The derived commit set is impact analysis read backward. Forward: "if this commit lands, these rules may fire." Backward: "this rule fires as a consequence of: withdrawals, deposits, the daily tick." Both are the same graph; the author never writes the *when*, the compiler proves it and can show it.

## Open questions

(OQ1, concurrent assignments to one field, is settled — see "One writer per field, per commit" above. Retired tags are not reused.)

### OQ2. Exactly-once, and what an assignment's RHS may read

**Still open — under active discussion.** What follows is the current state of the thinking, not a resolution.

There's a clean static split between two kinds of assignment RHS:

```
rule ApplyEmailCorrection on CorrectEmail {
    customer.email = corrected               -- reads only the act's own data: idempotent
}

rule ApplyDeposit on Deposit {
    account.balance = account.balance + amount   -- reads the state being mutated: NOT idempotent
}
```

The first is harmless to re-fire — exactly-once degrades gracefully to at-least-once. The second applied twice is a double deposit; it is non-idempotent and order-sensitive, and the compiler can tell the two apart statically (does the RHS traverse into mutable stored state, or only into the triggering act?). Whether the non-idempotent kind is (a) forbidden outright, (b) legal only with a run-once guard, or (c) legal and the product owner's problem, is undecided; the discussion so far has been about what (b)'s guard actually is.

**The guard is a data invariant, and it can't be anything else.** README §11's `produces` guard, desugared, is a refinement of the trigger — "a Deposit for which no DepositApplication exists" — and firing creates the evidence that falsifies its own trigger. Run-once protection requires durable memory that a firing happened; Velle's principles leave exactly one place for durable memory: data. A runtime keeping hidden fired-flags somewhere no shape describes would be untraceable state, which the language forbids everywhere else — so the witness *must* be a shape. (Strongly agreed.) The fully explicit spelling needs no new syntax at all — the guard is just a refinement in the trigger:

```
shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit on UnappliedDeposit {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

**Settled direction: this is the canonical form — any sugar must desugar to exactly this.** The guard is a named refinement: a business state (*an unapplied deposit*) up front in trigger position, in the language's core vocabulary. And the connection between gate and witness is not a keyword but a **compiler obligation**: the body must provably falsify the trigger's own predicate — the rule disarms itself. A rule on `UnappliedDeposit` that forgets the production line fails to compile ("this rule never leaves its trigger state") — the double-deposit bug caught as structural incoherence, not a runtime surprise. As a law: *a non-idempotent rule's trigger must be a state its own effects provably exit.* The disarm analysis unifies every witness kind with zero keywords: producing evidence falsifies an `exists` predicate; `this.applied = true` falsifies a flag predicate; the nightly sweep's assignment falsifies its filter — with a restore rule legally *re-arming* it, making non-monotone re-entry ordinary behavior of a resettable state rather than an edge case.

**Sugar is the open part, and every header candidate shares `produces`' flaw.** `produces` reads like a *data* concept ("this rule emits a DepositApplication") but behaves like a *guard* — one keyword doing two jobs, its name only admitting to the first. A header keyword naming the behavior instead (`once`, `once by DepositApplication`, or `until DepositApplication` — "fires until the witness exists") reads better but keeps the structural weakness: the gate in the header and the witness in the body are two mentions joined only by name-coincidence; nothing in the syntax says they are the same fact. A statement-level marker fuses them instead:

```
rule ApplyDeposit on Deposit {
    once DepositApplication from { deposit: this, appliedOn: now }
    account.balance = account.balance + amount
}
```

— gate and effect are one piece of syntax, at the cost of the header no longer showing the rule is guarded, and of one statement silently gating the whole body. Which sugar, if any, earns adoption is open; deferring costs nothing, since the canonical form is fully expressive today. Points that survive whichever sugar wins:

- **Guard granularity is predicate content, not annotation.** Once-per-deposit is `not exists DepositApplication for this`; at-most-once-per-customer-ever is `not exists (FlagNotification where customer == this.customer)`. §11's `produces X for field` annotation was rendering this choice; the canonical form states it directly.
- **No guard means "once ever" unless its predicate says so.** The plural case is the default: every commit of an act is a new instance with its own guard state — a hundred `Deposit` commits, a hundred firings, each exactly once.
- **The enforcement chain**, each step a one-line diagnostic pointing at the next missing piece: RHS reads mutable state → not idempotent → the trigger must be a dischargeable state → the body must provably discharge it.

**The edge: a guard's unit must be a shape the data contains — and a drift episode isn't one.** A rule on a drift-entered refinement, guarded per instance, silently under-fires on re-entry:

```
shape Delinquent = Account where balance < 0
shape UnsuspendedDelinquent = Delinquent where not exists ServiceSuspension for this

rule SuspendService on UnsuspendedDelinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
}

-- Jan  5: enters Delinquent → no evidence → fires, suspension recorded
-- Feb  1: paid in full → leaves Delinquent → service restored (on leaving, §12)
-- Sep 12: enters Delinquent again → ServiceSuspension for this account already
--         exists → silently does not fire. September is never suspended.
```

The business intent was once per *episode*, but the September entry happened by drift — no act instance marks it, so `once per <episode>` has nothing to name. The fix, using only existing machinery, is to reify the episode as a flag/resolution pair:

```
shape DelinquencyFlag {
    account: one Account
    flaggedOn: Date
}

shape DelinquencyResolution {
    flag: one DelinquencyFlag
    resolvedOn: Date
}

shape OpenDelinquencyFlag = DelinquencyFlag where not exists DelinquencyResolution for this

-- entry: a delinquent account with no open flag starts a new episode
rule OpenDelinquencyEpisode
    on (Delinquent where not exists (OpenDelinquencyFlag where account == this)) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- exit: leaving Delinquent closes the open episode
rule CloseDelinquencyEpisode on leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag where account == this)
        resolvedOn: today
    }
}

-- the business rule, now guarded at the right unit
shape UnsuspendedFlag = DelinquencyFlag where not exists ServiceSuspension for this

rule SuspendService on UnsuspendedFlag {
    ServiceSuspension from { flag: this, suspendedOn: now }
}
```

September now works: the old flag is resolved, so a new flag opens — a new instance with its own guard state — and the rule fires again. Noteworthy in passing: the exit rule's singular reference `(OpenDelinquencyFlag where account == this)` is provably at-most-one *because of the entry rule's own guard* — a whole-spec singularity proof (§9's rule discharged by a guard elsewhere in the spec). And reified episodes immediately pay for themselves the moment the business quantifies over them (`count(DelinquencyFlag where account == this) >= 3`, episode duration, "second delinquency this year"). The honest cost: three shapes and two rules of completely mechanical pattern — possible future sugar territory (`episode of Delinquent`?), same relationship any guard sugar has to the canonical form.

**The flag-guard variant.** A PO may want the guard as a field on the act itself rather than a separate evidence shape — and with in-place mutation in the language, that's now expressible with existing machinery (it wasn't before: until assignment existed, evidence shapes were the only possible durable run-once memory):

```
shape Deposit {
    account: one Account
    amount: Money
    applied: boolean
}

shape UnappliedDeposit = Deposit where applied == false

rule ApplyDeposit on UnappliedDeposit {
    account.balance = account.balance + amount
    this.applied = true
}
```

Same self-falsifying structure as the evidence guard: the body performs the write that removes the instance from the trigger set, so firing disarms it. The no-hidden-state principle is satisfied (`applied` is a declared, traceable field), the one-writer check covers the flag, and the compiler can even *prove* the rule disarms itself — the body assigns the exact field the trigger's predicate reads. Under the canonical form the two guard kinds are already unified: both are dischargeable trigger states, differing only in where the memory lives (a produced shape vs. a stored field), and the same disarm proof covers both.

The flag-guard surfaces one real language gap and two authoring choices Velle should support, not police:

1. **Stored fields have no initial values — and the obvious syntax is taken.** `applied` must start `false`, but `applied: boolean = false` in shape-body position means *derived, always false* — unassignable per OQ3, incoherent as a guard. "Stored but initialized" is a missing third property kind needing its own spelling (`applied: boolean initially false`?). The evidence guard got its initial state for free: absence *is* unstarted.
2. **Where the flag lives is a modeling choice, per use case.** In a money case the flag likely wouldn't sit on the externally-committed act at all — realistically the external shape commits an *internal* record that carries the flag, and the internal record is applied against the account (a flow that presumably resembles the ledger pattern; worth working through as its own example). But where the client is trusted, or the stakes don't warrant the split, the flag directly on the mutation shape is legitimate. Velle's job is not to forbid the direct form — it's to give the author validation tools when intent needs enforcing (OQ5's boundary vocabulary, e.g. `requires`-style constraints on what a committer may supply).
3. **Flag vs. evidence richness is the author's call, not Velle's.** A flag records only *that* it happened; if the business also wants the timestamp without a separate shape, `appliedOn: Date?` does both jobs in one field — guard on `appliedOn is none`, disarm with `this.appliedOn = now`. History stays opt-in at every grain: none (boolean), when (optional date), full payload (evidence shape).

(Grammar note: `applied == false` is the sanctioned spelling — a bare boolean field isn't an atom in the predicate grammar, so `where not applied` would need a small extension. Probably worth having; reads far better.)

**The solution space is wider than guards.** The flag/resolution apparatus was one PO's answer — the one who needs episodes as history. A PO who says "just suspend delinquent accounts nightly" is describing a different solution family, expressible with existing machinery, in which the episode problem *vanishes* rather than being solved:

```
shape Account {
    balance: Money
    suspended: boolean
}

shape Delinquent = Account where balance < 0

rule SuspendDelinquents {
    each (Delinquent where suspended == false) {
        this.suspended = true
    }
} on Nightly

rule RestoreService {
    each (Account where suspended == true and balance >= 0) {
        this.suspended = false
    }
} on Nightly
```

The January/September bug came from using *immutable evidence* as the guard — evidence persists, the guard is monotone, a second episode can never re-arm it. The `suspended` flag is a *current-state* guard, and it resets: February's restoration writes `false`, which re-arms September for free. Reification was only ever the cost of history, and this PO isn't buying history.

The exactly-once obligation disappears too: `this.suspended = true` writes a constant — idempotent by this OQ's own static split. The sweep can run twice a night, crash mid-run and rerun; it converges to the same state. This is a genuinely different answer to exactly-once — not *guard the firing* but *make firing harmless*: a reconciliation loop, desired state (`suspended` ⇔ delinquent) enforced by an idempotent sweep — the React/Kubernetes shape, matching this doc's React philosophy almost verbatim. The tick grounds it per "Rules ground in commits" (no drift-detection machinery needed at all), and the trade-off is stated in the schedule itself: suspension latency = sweep cadence. "We suspend nightly" vs. "we suspend the moment you go negative" is a visible one-word business decision — `on Nightly` vs. `on Delinquent`.

So the PO's solution space for "suspend delinquents" has three rungs, each existing machinery, chosen by what the business actually needs:

1. **Pure classification — no rule at all.** If "suspended" causes no external effect — it's just a status the system reports — it isn't state, it's a predicate: `shape Suspended = Account where balance < 0`. Zero machinery, always exactly current, nothing to guard. The PO question that selects this rung: *is suspension a fact you compute, or an action you take?*
2. **Reconciliation — idempotent sweep + resettable flag.** When suspension must be *stored* (independently meaningful: manual overrides, grace periods, an external system reads it) but only its current value matters. No exactly-once machinery, no episodes; re-entry is free.
3. **Exactly-once events — witnessed guard + reified episodes.** When the moment matters (suspend *now*, not tonight) or the history matters (count episodes, durations, "second delinquency this year"). This is where dischargeable guards, evidence, and the flag/resolution pattern live — the full apparatus, purchased only when the business is actually asking for occurrences.

There's a fourth point off the end of the scale: the deposit case done reconciliation-style is `balance: Money = openingBalance + sum(...)` — the derived property, i.e., the ledger. The spectrum runs *derivation → reconciliation → exactly-once events*, and the exactly-once question only exists at the far end. That reframes OQ2: the guard isn't the answer to mutation safety in general — it's the answer for the subset of designs where the PO has chosen occurrence semantics, and the language's job (per flexible-not-restrictive) is to make every rung expressible and validate whichever one the author picked.

Open sub-questions:

- The main (a)/(b)/(c) decision itself.
- **Fate of `produces`** — under the canonical direction it's sugar that must desugar to a dischargeable trigger state: retire it, or keep it as a pure data declaration with no guard semantics — either touches every rule in the README.
- **Which sugar, if any** — header keywords (`once`, `once by X`, `until X`) fail the structural-connection test (gate and witness joined only by name-coincidence); the statement-level marker passes it but hides the gate from the header. Deferring costs nothing; usage of the canonical form can reveal which sugar is actually missed.
- **Interaction with `each` and schedule triggers** — the sweep examples already use `each` over a guard refinement; whether the disarm proof extends cleanly through `each` bodies and multi-cadence schedules needs its own worked pass.
- **Atomicity prerequisite (→ OQ6).** The guard is only sound if the mutation and its evidence enter the state as one commit; a failure between them re-arms the guard → double deposit. Rule-firing atomicity is undefined, so OQ6 is a prerequisite of any resolution here.
- **The guard does not dedupe acts (→ OQ5).** It prevents one act from applying twice; two committed identical `Deposit` instances (double-click, retried API call) are two acts, each applying once. Whether they're two deposits or one is a business-identity question about external inputs.
- **Episode sugar** — whether the flag/resolution reification pattern deserves a construct.
- **Initial values for stored fields** — the flag-guard needs `applied` to start `false`, and `= false` is taken by derivation. A missing third property kind (stored-but-initialized); whether an initial-value declaration can *optionally* also mark a field internal (not suppliable by committers) ties into OQ5.
- **The external→internal flow** — the realistic modeling for high-stakes cases: an external mutation shape whose rule commits an internal record carrying the guard, which is then applied against the target. Presumably resembles the ledger pattern; needs a worked example.
- **Bare boolean atoms** — extend the predicate grammar so `where not applied` is legal, or keep `applied == false` as the only spelling.
- **Does the compiler recognize the rungs?** The derivation/reconciliation/exactly-once spectrum is currently implicit in how a spec is written. Should the compiler classify which rung each rule sits on and validate accordingly (e.g. prove a sweep converges and is idempotent; warn when a stored flag could have been a predicate; require `once` only on the exactly-once rung)? Ties the whole solution space back to validation-of-intent.

### OQ3. Assignment targets must be stored fields

A derived property is a computation; a computation can't be assigned:

```
shape Invoice {
    amount: Money
    payments: many Payment
    balance: Money = amount - sum(payments, amount)
}

rule Forgive on ForgivenessGrant {
    invoice.balance = 0        -- compile error: balance is derived
}
```

Assignment writes stored truth; derived properties recompute over it. The correct model commits data the derivation already reads (a `Payment`, a stored `forgiven` field the formula consults). Trivial check, needs stating in the reference.

### OQ4. Act-level sugar for the one-assignment case

`produces X for field from { mapping }` collapses a one-effect rule onto its header (README §14). The same collapse could let a mutation shape carry its single assignment directly — the territory README §17's provisional `output` was exploring — rather than requiring a separately-named rule:

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    customer.email = corrected      -- hypothetical collapsed form; syntax unsettled
}
```

Whether that sugar should exist, what it looks like, and how it stays visually distinct from a derived-property declaration in the same body position (README §7's `captured` faced the same must-read-differently problem) is unexplored.

### OQ5. Marking a shape as "external input"

Mutation shapes arrive from outside the system — a user action, an API call — and nothing in the spec currently says so:

```
shape CorrectEmail {        -- committed by whom? a user? another rule? an API?
    customer: one Customer
    corrected: text
}
```

An author may eventually want to declare the distinction (e.g. only externally-committed shapes cross a trust/validation boundary; a `visible to`-style clause may want to constrain who can commit one). Deferred — for now a mutation is just another shape.

### OQ6. What, exactly, is one commit?

Grown from a footnote into the load-bearing question — commits, state, and guards all meet here. The open threads, from narrowest to widest:

- **Is a commit a single act instance?** The one-writer check's "unrelated shapes can never coincide" holds only if two different acts can't enter the state as one commit. Nothing states this anywhere yet. If multi-instance commits exist, "can these triggers coincide?" needs a broader definition than trigger-shape overlap.
- **Is a rule firing inside its triggering commit, or a subsequent commit of its own?** "Rules ground in commits" establishes that every firing happens *as a consequence of* a commit — but not whether the withdrawal and the suspension it triggers are one atomic state transition or two. OQ2's guard soundness already demands mutation-plus-evidence be atomic (a failure between them re-arms the guard → double deposit); that was this question in miniature.
- **What is a cascade?** A rule's effects can trip further rules — the suspension enters some refinement, another rule fires on that. Is the whole cascade one commit, a chain of commits with observable intermediate states, or bounded somehow? The atomicity OQ2 needs generalizes to the entire chain.
- **Where do `then`'s intermediate moments sit?** README §14's `then` (ordering effects within one firing) implies observable intermediate states whose relationship to commit boundaries is undefined.

Needs pinning down before the one-writer check, the guard soundness argument, or the derived-trigger-set machinery can be specified precisely.

### OQ7. Rule anatomy: is any timing semantic?

A rule is named, has a condition, and has an outcome. Dissecting the prefix `on R` clause shows it bundling three distinguishable things:

1. **The condition** — a boolean over state. Genuinely timeless: refinements are pure predicates (README §7); nothing about `balance < 0` says when to ask.
2. **The evaluation moments** — when the condition gets calculated. Never a free choice (nothing-happens-on-its-own): evaluation only happens as a consequence of a commit, from exactly two sources — the *derived* set (commits whose impact touches what the predicate reads) and *tick* commits.
3. **The firing semantics** — edge vs. level. Prefix `on R` means *entering* (edge: fire on the transition). A sweep means *while a member, sampled at the tick* (level).

The conflation lives in the trigger clause, not the condition — and **a guarded rule in OQ2's canonical form is timing-independent, which dissolves it**. `UnsuspendedDelinquent` doesn't mean "just became delinquent" (edge — requires knowing when you last looked); it means "delinquent and not yet handled" (level — a fact about current state). The guard reifies *not yet handled* into the condition, so the rule observes no transition: evaluate it after every commit, once a night, or seventeen times in a row — extra evaluations are harmless, because firing discharges the guard and non-members are no-ops. (The independence is one-sided, though: *sparser* evaluation can miss transient truths entirely — see the transient-membership sub-question below.) *When to calculate* stops being semantic and becomes quality-of-service: the main thing timing changes is **latency**, the one timing fact the business owns ("suspend immediately" vs. "suspend nightly"), declared by schedule or defaulted to immediate. §10's "detection mechanism is a compiling concern" becomes provable rather than aspirational.

Proposed anatomy, with no other timing anywhere in the semantics:

- **name** — identity
- **condition** — a *state* (refinement including its guard); level-semantics, safely re-evaluable
- **outcome** — commits (an external API call is an outcome commit whose only db-visible trace is its witness — which is *why* the witness must exist)
- **latency** — the single author-facing timing declaration

**Hypothesis (suspected, not proven): entry and exit triggers are not primitives — they're state patterns.** The episode example already contains the demonstration. Entry is *C ∧ ¬witness*; exit is *¬C ∧ witness* — a symmetric pair, both level-triggered, both self-disarming, both safe under any evaluation schedule:

```
-- "entry": condition holds, no open witness
rule OpenDelinquencyEpisode
    on (Delinquent where not exists (OpenDelinquencyFlag where account == this)) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- "exit": condition no longer holds, an open witness exists
rule CloseDelinquencyEpisode
    on (Account where balance >= 0 and exists (OpenDelinquencyFlag where account == this)) {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag where account == this)
        resolvedOn: today
    }
}
```

The second rule is `on leaving Delinquent` with the edge removed: the open witness is the memory that *we were in*, so "leaving" is reconstructed from state with no transition observation. "Only a member can leave" (README §12's argument for `leaving` as primitive) falls out: an open witness exists only for accounts that were flagged while in.

**The pattern catalog so far** — nailing these down across use cases is the path to sugar:

1. **Handled-once** — *C ∧ ¬witness* → produce witness (`UnappliedDeposit`)
2. **Episode** — paired open/close acts + an "open" refinement; entry = *C ∧ ¬open*, exit = *¬C ∧ open* (flag/resolution)
3. **Resettable latch** — stored flag reconciled to *C* by idempotent sweeps (`suspended`)
4. **Classification** — *C* alone, no rule at all (`Suspended = Account where balance < 0`)

If the catalog stabilizes, the sugar direction mirrors OQ2's settled one: `on entering R` / `on leaving R` could *survive as sugar* that desugars to the level+guard pair with a declared or generated witness — up to something like `episode of Delinquent` generating the entire flag/resolution apparatus. Canonical form is state; readable sugar must desugar to it.

Open sub-questions:

- **Do bare act triggers survive as a primitive?** `rule ApplyEmailCorrection on CorrectEmail { customer.email = corrected }` has no guard, and an act instance is a member of its shape forever — the level reading would re-fire for *every* correction at every evaluation, and the set of firings is order-sensitive (a nightly sweep could re-apply an old correction over a newer one; "idempotent per instance" does not mean "timing-independent as a set"). So either the act's *commit* is a real edge primitive (the one place entry is irreducible), or bare act triggers get an implicit per-instance guard, or the state-pure spelling is the derivation (`email = latest(correction).corrected` — the ledger again). The timing-independence claim currently holds only for guarded rules; this is the gap.
- **Transient memberships: latency is also observational granularity.** Timing-independence is one-sided — extra evaluations are no-ops, but sparser evaluation can miss brief truths *entirely*. An account that enters and exits `Delinquent` between two nightly sweeps is never observed: Thursday sees a non-delinquent account with no open flag, and neither the entry nor the exit rule ever fires — the episode isn't delayed, it never existed as far as the spec's effects are concerned. Eager (per-commit) evaluation observes every membership the commit stream can produce; sampled evaluation observes only what persists across gaps. So a latency declaration is really also a declaration of *how short a truth may be and still matter* — and whether an unobserved brief membership obligates the effect at all is the business question README §18 already flags, made concrete.
- **Re-derive §12 under the state reading** — mutation policies (`stands`/`forbidden`/`compensate`), what an exit rule may read (captured properties retract at exit — does *¬C ∧ witness* reproduce the same restriction?), and `compensate`'s desugared form.
- **Latency vocabulary** — is "immediate by default, named schedule otherwise" enough, or does latency deserve first-class expression (deadlines, "within 24h") the compiler validates against cadences?
- **What remains of "newly-satisfying instance" (§10)** if edges are derived — the contract language itself presumes entry semantics.
