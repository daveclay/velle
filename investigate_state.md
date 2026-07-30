# State Changes

Velle needs a way for product owners to describe how state changes occur in the system.

## Philosophy

Any system can be considered a black box with its persistent store as a single state. From Velle's perspective, mutations are committed to change this state, and the truth of the system must be consistent.

React is a similar analogy/approach with mutations committing to a single state tree. What I'm taking from that is the idea of a mutation being an arbitrary shape, and thinking of "commit" as an agnostic term for all underlying CRUD operations. The "state" is agnostic to the storage mechanism.

**The substrate is last-in-wins.** Nothing in any computer system provides anything other than last-in-wins; an engineer may design a system where concurrent writes are merged in more sophisticated ways, but always built from fundamental tools where last-in-wins (the database). Velle therefore needs no ordering vocabulary for in-place mutation — a mutated field holds the last value committed.

**Mutating a field in place is allowed.** Velle does not force the ledger pattern (below) onto a model that doesn't need history. The goal instead is that when a field is mutated, Velle can tell the product owner the impact — which refinements may change membership, which rules may consequently fire, which mutation policies could reject the change — and verify that relationships and behaviors stay consistent.

**Flexible, not restrictive.** Velle should not force one design pattern over another — evidence shape vs. guard flag, ledger vs. in-place, internal vs. client-supplied fields are the author's calls, made per use case (how much trust the client warrants, how much history the business needs). Velle's job is to provide the validation tools that prove the system does what the author intended, whichever pattern they chose.

**A system never does anything on its own.** It can only react to an external event — a commit. Scheduled ticks are just another external event (README §17 already treats a tick as a shape instance arriving). Every rule firing must therefore trace to a commit; see "Rules ground in commits," below.

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
rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}
```

The pieces:

- **`when CorrectEmail`** — a plain-shape condition, already sanctioned (README §15's `rule InitiateCharge when Order`): the rule fires when an instance is committed.
- **`customer.email = corrected`** — assignment, a new effect-statement form alongside `X from { ... }` and `X for y ...`. `=` in rule-body position means *becomes, now*; in shape-body position it remains *is defined as, always* (derivation). One symbol, two positions — the same deliberate positional reuse the language already makes with `for` (association vs. query) and `?` (optional marker vs. `?.`). The two contexts never collide: rule bodies contain no definitions, shape bodies contain no effects.
- **The target is a literal static path — a hard requirement.** Because `customer.email` is statically known, the whole-spec compiler knows every refinement predicate and derived property that reads `email`, and impact analysis falls out for free: which memberships may change, which entry/exit rules may consequently fire, which `forbidden` liens (README §13) could reject the commit. A computed or reflective target would destroy this, so no solution for mutation syntax may ever introduce one.

### One writer per field, per commit

More than one assignment to the same field of the same referenced shape is a compile error whenever the assignments' triggers can coincide — whenever a single commit could fire both. There is no `then`-ordered escape: sequencing two writes to one field inside a single firing is not a real business statement, and one assignment whose RHS states the whole intent replaces it. Within a single commit's rule firings "last" is undefined; last-in-wins is only real *across* commits, where "last" is a fact of the world.

"Can the triggers coincide?" is answered statically from the declarations, never at runtime: same trigger shape → yes; a refinement and its base (or two overlapping refinements) → yes; unrelated shapes → provably never. If the compiler can't prove two triggers disjoint, it errors — uncertainty fails closed. The machinery is the refinement-overlap check README §8 already names as a compiler goal, applied to trigger shapes. It's a whole-spec check: a second rule assigning `customer.email`, added months later in another file, trips it, reported as one connected diagnostic naming both rules. A spec that compiles has no ambiguous-write configuration reachable at runtime.

Legal — separate acts whose triggers can never coincide fire from different commits, where last-in-wins orders them:

```
shape CorrectEmail  { customer: one Customer, corrected: text }
shape OverrideEmail { customer: one Customer, admin: one Admin, overridden: text }

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}

rule ApplyEmailOverride when OverrideEmail {
    customer.email = overridden          -- legal: an OverrideEmail is never a CorrectEmail,
                                         -- so these always fire from different commits
}
```

Whichever act is committed last wins — precisely the business reality ("the admin's override stands until the customer corrects it again, and vice versa").

Errors — triggers that can coincide:

```
-- same trigger shape: one CorrectEmail commit fires both
rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}
rule NormalizeEmail when CorrectEmail {
    customer.email = lowercase(corrected)     -- compile error
}
```

```
-- a refinement and its base: a TrustedCorrection IS a CorrectEmail,
-- so one commit fires both rules
shape TrustedCorrection = CorrectEmail where customer is VerifiedCustomer

rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}
rule ApplyTrustedCorrection when TrustedCorrection {
    customer.email = corrected                -- compile error
}
```

### Assignment targets are stored fields

A derived property is a computation; a computation can't be assigned:

```
shape Invoice {
    amount: Money
    payments: many Payment
    balance: Money = amount - sum(payments, amount)
}

rule Forgive when ForgivenessGrant {
    invoice.balance = 0        -- compile error: balance is derived
}
```

Assignment writes stored truth; derived properties recompute over it. The correct model commits data the derivation already reads (a `Payment`, or a stored `forgiven` field the formula consults).

### Initial values: `initially`

A stored field may declare a starting value: `applied: boolean initially false` — stored, assignable, set at instance creation. This is a third property kind, needed because `applied: boolean = false` in shape-body position means *derived, always false* — unassignable ("Assignment targets are stored fields"), incoherent as a guard target. `initially` supplies the starting value without touching the `=`-means-derivation invariant: the flag-guard quartet gets its missing piece, and the initializer is evaluated once, at the creation commit — so `submittedOn: Date initially now` records commit time as model data (the OQ5 anchor case's one legitimate author-declared timestamp). Evidence-shape guards never need any of this — absence *is* their initial state. Whether declaring an initial value can *optionally* also mark the field internal (not suppliable by committers) remains open with the boundary question (OQ5).

### Bare boolean atoms

A bare boolean field is a predicate atom: `where not applied` and `where applied` are grammatical, equivalent to `applied == false` / `applied == true`. The flag quartet's refinement reads as the business phrase (`Deposit where not applied`) instead of a comparison. A small extension to README's predicate grammar when promoted there.

### No act-level sugar

A one-assignment rule stays a rule — there is no collapsed form letting a mutation shape carry its assignment in its own body (the territory README §12's retired `output` construct was exploring):

```
shape CorrectEmail {
    customer: one Customer
    corrected: text
    customer.email = corrected      -- rejected
}
```

Two reasons. The positional meaning of `=` is load-bearing: "rule bodies contain no definitions, shape bodies contain no effects" is exactly what lets one symbol serve derivation and assignment without ambiguity — an effect in shape-body position would spend that invariant to save four lines. And the sugar would mostly abbreviate a rarity: the common external-submission case ("save what the user sent") needs no rule at all, because committing a shape instance *is* persisting the record — a rule exists only when an act has a *consequence* beyond its own persistence, and naming that consequence (`rule ApplyEmailCorrection`) is the point, not overhead.

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

Nothing happens on its own, so a rule only ever fires as a consequence of a commit. The `when`/`on` header (next section) declares the rule's condition and its trigger source — but `on commit`, the default source, names no *particular* commits, and the condition it evaluates can be a drift refinement whose data no act shape mentions:

```
shape Delinquent = Account where balance < 0

rule SuspendService when Delinquent {          -- on commit, by default
    ServiceSuspension from { account: this, suspendedOn: now }
}
```

Which commits can fire this? The author never says — and doesn't have to, because **drift is always commit-mediated**: `balance < 0` becomes true only when some commit changes data the predicate reads. That makes the trigger set derivable from exactly the machinery in-place mutation mandates: `Delinquent` reads `balance`; the static-path and one-writer requirements mean the compiler knows every assignment targeting `balance` and every act that feeds it; so `SuspendService` can fire only as a consequence of (say) a `Withdrawal` commit or a `Deposit` commit. `on <schedule>` adds tick commits to the same set — a tick is a commit whose changed datum is `today`, one more entry in the list rather than a second mechanism.

The division of labor: the author declares the **what** (`when` — the business condition) and the **source** (`on` — commit, ticks, or both); the compiler computes the **which** (the exact commits) and proves the rule **reachable**. The benefit of the condition staying declarative survives intact: a new writer of `balance` added next year automatically extends `SuspendService`'s trigger set with no edit to the rule — correct, because the business condition didn't change.

Two compiler obligations fall out:

1. **The unfireable-rule error.** If a rule's trigger set is empty — no commit in the spec, and no tick in its `on` clause, can cause entry into its condition — the rule can never fire, and that's a whole-spec compile error, not a dead-code shrug. The sharp instance is time: `OverdueInvoice = Invoice where due < today` depends on `today`, which no act commit changes. A rule `when OverdueInvoice` with no schedule in its `on` clause observes entry at `Invoice` creation (committed already-overdue) but never entry *by aging* — and the diagnostic is precise: "entry into `OverdueInvoice` via the passage of time is unobserved — add a schedule to `on`, or this rule under-fires." README §17's stance stops being a convention and becomes a coverage check read directly off the header.
2. **The PO-facing answer to "when does this run?"** The derived trigger set is impact analysis read backward. Forward: "if this commit lands, these rules may fire." Backward: "this rule fires as a consequence of: withdrawals, deposits, the daily tick." Both are the same graph; the author declares the source, and the compiler proves — and can show — the specifics.

## Rule triggers: `when` and `on`

**Settled direction.** A rule's header separates its condition from its trigger source, one keyword each:

```
rule <name> [when [leaving] <condition>] [on <trigger>, ...] { <effects> }
```

- **`when`** — the condition: a refinement, entered or left. Single meaning, always.
- **`on`** — the trigger source: `commit`, or a named schedule (`Nightly`). Single meaning, always. This retires the prefix/postfix positional reuse of `on`; README §17's schedule references keep their existing `on Daily` spelling, relocated into the header.
- Omitted `on` defaults to `on commit` — a single well-defined default, the same category as properties being required unless marked `?`.
- The given/when/then echo is deliberate and on-brand: the README names BDD as "closer but not structured enough," and a rule now reads as that artifact made rigorous — *given* the declared shapes, *when* the condition holds, *on* commit or tick, then the effects (the body's sequencing keyword is already literally `then`).

The two trigger sources are the two things a Product Owner actually distinguishes, and both are valid designs, chosen per use case:

- **`on commit`** — "*the moment* it happens." The rule evaluates as a consequence of every commit that can affect its condition (the derived trigger set — computed, never enumerated by the author).
- **`on <schedule>`** — "*every night*, check." The tick is itself a commit arriving; a tick carries no subject of its own, so a schedule-only rule quantifies over current state with `each`.

```
rule ApplyEmailCorrection when CorrectEmail on commit {
    customer.email = corrected
}

rule SuspendService when Delinquent on commit {
    ServiceSuspension from { account: this, suspendedOn: now }
}

rule RestoreService when leaving Delinquent on commit {
    ServiceRestoration from { account: this, restoredOn: now }
}

rule SuspendDelinquents on Nightly {
    each (Delinquent where not suspended) {
        this.suspended = true
    }
}

rule NagCustomer when OverdueInvoice on commit, Daily {
    Nag from { invoice: this, naggedOn: now }
}
```

### Entry and exit are commit-local diffs

"Newly-satisfying" (README §11) is well-defined with no hidden bookkeeping, but only *per commit*: a commit is a discrete moment, and evaluating its consequences means pre-state and post-state are both transiently available. `when Delinquent on commit` means *the commit that made it true* — false before, true after. `when leaving` is the same diff reversed. Consequences:

- **Episodes are free at commit granularity.** September's re-entry into `Delinquent` is a new entering commit, so `SuspendService` fires again — once per episode, with no guard apparatus. (The outcomes even accumulate as history: one `ServiceSuspension` per episode.) The episode apparatus ("Episodes as data," below) was compensating for monotone evidence-guards; commit-entry semantics never had the problem. The guard patterns remain what you buy for *durability* (crash recovery) and *cross-tick memory*, not for entry itself.
- **Bare act triggers are sound.** `when CorrectEmail on commit` fires once per correction commit — the commit is the unit of firing. The rule is never tick-evaluated, so no sweep can re-apply old corrections.
- **A tick is a commit whose changed datum is `today`.** So `on commit, Daily` is one mechanism, not two: an invoice *aging* into `OverdueInvoice` is a commit-local diff over the tick — the same entry semantics as a payment-reversal commit. (Compare the sweep: a subjectless `each` rule on a tick is the *sampling* usage; `when` + schedule is the *aging* usage. Both valid, visibly different spellings.)

### The tick law: cross-tick memory must be data

A tick-triggered rule sees only current state — "what changed since last tick" would require persistent memory between ticks, and memory must be data. So sweeps carry their memory as witnesses, flags, or evidence:

```
rule SendReminder on Daily {
    each (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days)) {
        Reminder from { invoice: this, sentOn: today }
    }
}
```

"At most every 3 days" is memory across ticks, so it's data — the `Reminder` evidence itself, with no hidden last-run timestamp anywhere.

### Transient membership is a policy, stated in the header

An account goes negative Monday 09:00 and recovers at 17:00. Under `when Delinquent on commit`: suspension fires at 09:00, restoration at 17:00 — the blip was real, service was off for eight hours. Under the `on Nightly` sweep: the check sees a positive balance — the blip never mattered. Neither is wrong: commit-triggered observes every membership the commit stream produces; tick-triggered observes what persists to the tick. The choice is business-visible in one clause — README §17's transient-membership question, answered per rule by the author rather than globally by the language.

### Composition as a durability policy

```
rule ApplyDeposit when UnappliedDeposit on commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

`on commit` gives latency; `on Hourly` is a reconciliation backstop that catches anything a crash dropped — safe to add precisely because the canonical guard ("Run-once guards," below) makes re-evaluation harmless. One line reads: *immediately, and self-healing hourly*.

## Run-once guards

Commit-triggered rules already fire exactly once per commit ("Entry and exit are commit-local diffs"), so an ordinary rule needs no guard. A guard earns its place in exactly two situations: **durability** — the firing must survive a crash and be provable afterward — and **cross-tick memory** — a tick rule needs "already handled" as data (the tick law). The canonical form — and the only form; there is no guard sugar ("No guard sugar", below) — is a named refinement whose predicate the rule's own body falsifies:

```
shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit on commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

**The guard is a data invariant, and it can be nothing else.** Run-once protection requires durable memory that a firing happened, and the only durable memory in Velle is data. A runtime keeping hidden fired-flags somewhere no shape describes would be untraceable state, forbidden everywhere else — so the witness must be data: a produced shape, or a stored field.

**The disarm law.** The connection between gate and witness is a compiler obligation, not a keyword: the body must provably falsify the trigger's own predicate — *a guarded rule's trigger must be a state its own effects provably exit*. A rule on `UnappliedDeposit` that forgets the production line fails to compile ("this rule never leaves its trigger state") — the double-deposit bug caught as structural incoherence, not a runtime surprise.

**Two witness kinds, one analysis.** Producing evidence falsifies an `exists` predicate; a flag assignment falsifies a flag predicate — a form in-place mutation made expressible at all (before assignment existed, evidence shapes were the only possible durable run-once memory):

```
shape Deposit {
    account: one Account
    amount: Money
    applied: boolean initially false
}

shape UnappliedDeposit = Deposit where not applied

rule ApplyDeposit when UnappliedDeposit {
    account.balance = account.balance + amount
    this.applied = true
}
```

Both are dischargeable trigger states; the same disarm proof covers both, and the one-writer check covers the flag. Witness grain is the author's choice, history opt-in at every step: a boolean (*that* it happened), `appliedOn: Date?` (*when* — guard on `appliedOn is none`, disarm with `this.appliedOn = now`), or a full evidence shape (payload, provenance). Where the flag lives is likewise per use case — directly on a trusted client's act, or on an internal record the external act commits (the high-stakes flow, which still needs a worked example; OQ5 carries the boundary vocabulary).

**Granularity is predicate content, not annotation.** Once-per-deposit is `not exists DepositApplication for this`; at-most-once-per-customer-ever is `not exists (FlagNotification where customer == this.customer)`. The guard's unit is whatever its predicate says — and it must be something the data contains.

### `produces` is retired

The keyword read like a *data* concept but behaved like a *guard* — one keyword, two jobs, and both are now done by settled machinery:

- **Its guard job** — the compiler-derived implicit "hasn't happened yet" gate — is exactly what "No guard sugar" rejects: a header form whose correlation is *assumed* (field-type matching, `for <field>` as the patch when the guess is wrong). The granularity trap README §18 documents — a witness silently keyed to the flag vs. the customer — is the hidden-join problem by name. Gates are named refinements with a disarm proof; nothing else.
- **Its data job** — the rule creates an instance — is an ordinary body statement: `Charge from { ... }`. A header `produces Charge` states the same fact twice.
- **Its remaining candidates fail on principle.** A checked header summary of a rule's outputs is a *derived* fact, and derived facts aren't declared (the same precedent as refinement entry-kinds) — tooling can display what a rule produces; syntax shouldn't demand it. Singularity licensing for `for`-queries (`(NurseVerification for this).nurse` provably at-most-one) comes from *any* guard establishing at-most-one — the whole-spec singularity proof in "Episodes as data" — not from the keyword. Exactly-once external effects (API calls as commits without a db change) are the canonical witness pattern, unchanged.

**Re-grounding notes for the README rewrite** (§§11, 12, 14, 15 and every `produces` example): effect creation moves into rule bodies (`X from { ... }`); §12's `for`-query legality re-grounds on guard-derived at-most-one proofs; §12's `compensate X` sugar was defined by desugaring to a `produces`-guarded rule and must either re-desugar to a canonical-guarded rule or fall to the same no-sugar reasoning; §14's one-effect header collapse is gone (a one-effect rule is a one-statement body — "No act-level sugar" reasoning extends).

**The enforcement chain**, each step a one-line diagnostic pointing at the next missing piece: RHS reads the state it assigns (a self-referential fold with an unproven hazard — "Self-referential folds and `tolerates`") → not idempotent → the trigger must be a dischargeable state → the body must provably discharge it. The chain is mandated — an undischarged fold obligation is a compile error, with the guard as one of four discharges; guard soundness also assumes the mutation and its witness enter the state together, which is OQ6's territory.

### No guard sugar

The canonical form is the spelling — no sugar layer sits over it. The candidates surveyed (a header clause like `until DepositApplication`, policy phrases like `once per customer by X` and `at most every 3 days by X`, an `episode of` apparatus generator) sounded like one family, but the connection between condition and witness has a different shape in every realistic use case:

```
-- identity: the witness correlates 1:1 with the trigger instance
Deposit where not exists DepositApplication for this

-- self: guard state lives on the trigger instance; no join at all
Deposit where not applied

-- chosen key: "once per customer" — the granularity is a product decision
AccountFlag where not exists (FlagNotification where customer == this.customer)

-- chosen key + time window: a rate limit; the guard expires
OverdueInvoice where
    not exists (Reminder where invoice == this and sentOn > today - 3 days)

-- paired apparatus: an episode's correlation is maintained by entry/exit rules
-- ("Episodes as data")
```

Every guard is a triple *(witness shape, correlation predicate, optional temporal scope)*, and only the witness is keyword-nameable. The correlation predicate is not mechanism — it *is* the business rule: "once per customer" vs. "once per flag" is a product decision, and the refinement is already its clearest spelling. Sugar would either grow a clause for the join (at which point it is barely shorter than the refinement) or assume one (hiding the decision — `until DepositApplication` reads well only because it silently assumes 1:1). With no common desugaring, the phrases are not one construct but a pile of special cases, each hiding a decision that should stay visible. Even the purely mechanical residue — the 1:1 `not exists W for this` idiom, the unmarked witness statement in a rule body — stays unsweetened: the hand-written refinement names a trigger state worth naming, and connecting witness to trigger is the disarm proof's job, checked rather than spelled.

## State-change patterns

The same business sentence — "suspend delinquent accounts" — has several valid designs, chosen by what the business actually needs (Philosophy: flexible, not restrictive). The spectrum runs **derivation → reconciliation → exactly-once events**, and the guard question only exists at the far end.

**1. Pure classification — no rule at all.** If "suspended" causes no external effect — it's a status the system reports — it isn't state, it's a predicate: `shape Suspended = Account where balance < 0`. Zero machinery, always exactly current, nothing to guard. The question that selects this rung: *is suspension a fact you compute, or an action you take?*

**2. Reconciliation — idempotent sweep + resettable current-state flag.** When suspension must be *stored* (independently meaningful: manual overrides, grace periods, an external system reads it) but only its current value matters:

```
shape Account {
    balance: Money
    suspended: boolean
}

shape Delinquent = Account where balance < 0

rule SuspendDelinquents on Nightly {
    each (Delinquent where not suspended) {
        this.suspended = true
    }
}

rule RestoreService on Nightly {
    each (Account where suspended and balance >= 0) {
        this.suspended = false
    }
}
```

`this.suspended = true` writes a constant — idempotent, so exactly-once is moot: the sweep can run twice a night or crash mid-run and rerun; it converges. The answer here is not *guard the firing* but *make firing harmless* — a reconciliation loop, desired state enforced by an idempotent sweep, the React/Kubernetes shape matching this doc's React philosophy. The flag is a *current-state* guard and it resets (`RestoreService` re-arms it), so re-entry is ordinary behavior. Latency = cadence, and transient blips are deliberately unobserved ("Transient membership is a policy").

**3. Exactly-once events.** When the moment matters or the record does:

```
rule SuspendService when Delinquent {
    ServiceSuspension from { account: this, suspendedOn: now }
}
```

Commit-local entry makes re-entry free — September's second delinquency is a new entering commit, so the rule fires again, and the `ServiceSuspension`s accumulate as per-episode history. Add a guard ("Run-once guards") when the firing must be durable.

Off the end of the scale, the deposit case done reconciliation-style is `balance: Money = openingBalance + sum(...)` — the derived property, i.e., the ledger. The guard isn't the answer to mutation safety in general; it's the answer for the subset of designs where the author has chosen occurrence semantics, and the language's job is to make every rung expressible and validate whichever one the author picked — which the compiler does by classifying rungs ("The compiler recognizes the rungs").

### Episodes as data

Commit-triggered rules get per-episode *firing* free, but a tick rule that needs "this episode was handled" across ticks, or a business that quantifies over episodes ("third delinquency this year," episode duration), needs the episode reified — the tick law again: cross-tick memory must be data. The pattern is a flag/resolution pair:

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

-- a delinquent account with no open flag starts a new episode
rule OpenDelinquencyEpisode
    when (Delinquent where not exists (OpenDelinquencyFlag where account == this)) {
    DelinquencyFlag from { account: this, flaggedOn: today }
}

-- leaving Delinquent closes the open episode
rule CloseDelinquencyEpisode when leaving Delinquent {
    DelinquencyResolution from {
        flag: (OpenDelinquencyFlag where account == this)
        resolvedOn: today
    }
}
```

Now `count(DelinquencyFlag where account == this) >= 3` is expressible, and any rule can guard per episode (`DelinquencyFlag where not exists ServiceSuspension for this`). Noteworthy in passing: the exit rule's singular reference `(OpenDelinquencyFlag where account == this)` is provably at-most-one *because of the entry rule's own guard* — a whole-spec singularity proof (README §10's rule discharged by a guard elsewhere in the spec). The honest cost: three shapes and two rules of completely mechanical pattern — accepted, not sugared ("No guard sugar").

### The compiler recognizes the rungs

The spectrum is not just documentation — the compiler classifies which rung each rule sits on and validates it accordingly. The classification is *derived* from what's written, never declared (the same precedent as refinement entry-kinds and fold hazards). Much of the machinery is already settled elsewhere: the RHS three-class split is the rung classification for assignments ("Self-referential folds and `tolerates`"), obligations arise only where non-idempotence demands one (obligation = exposed ∧ unproven), and a reconciliation sweep is recognized as a discharge. Recognition adds on top:

- **Rung-specific proofs.** A sweep serving as reconciliation must be provably convergent and idempotent — re-running it against unchanged data changes nothing. A latch pair (set-rule / reset-sweep) must actually cover both directions, or the flag drifts from the condition it mirrors.
- **Advisory, not policing** (Philosophy: flexible, not restrictive). "This stored flag could have been a pure classification — no rule needed" is guidance the author may ignore; the *required* diagnostics remain only the settled fail-closed ones (one-writer, disarm, fold obligations).
- **Classification feeds the analyses.** Impact analysis and `why` can name the rung — "this value is a nightly snapshot," "this flag is reconciled, drift bounded by the sweep cadence" — so a reader learns the design intent from the tooling, not from convention.

How this behaves against realistic specs is untested — the classification boundaries and the advisory/required line are expected to be calibrated when realistic Velle examples get written; the principle is settled, the calibration isn't.

## Self-referential folds and `tolerates`

Static analysis splits assignment RHSes into three classes; only the last needs machinery.

**1. Act-only** — reads nothing but the triggering act. Idempotent; re-firing is harmless.

```
rule ApplyEmailCorrection when CorrectEmail {
    customer.email = corrected
}
```

**2. Recomputing** — reads state, but *not the target itself*: a deterministic formula over current data. Convergent — same inputs, same value — so exactly-once is moot on any trigger. Per-commit recompute is an incrementally maintained materialization (semantically a derived property that happens to be stored); tick recompute is a *snapshot* — "the score as of last night," deliberately stable between ticks, a business concept live derivation can't express. Both valid, no guard:

```
-- "every commit, update this value": materialized derivation
rule UpdateRating when Review {
    product.rating = sum(product.reviews, stars) / count(product.reviews)
}

-- "every night, recalculate this value": snapshot
rule RecalculateRiskScore on Nightly {
    each Account {
        this.riskScore = <formula over payments, balance, history>
    }
}
```

Where a fold is expressible as a recompute, the incremental spelling (`rating = (rating * n + stars) / (n + 1)`) and the recompute spelling are the same description with different *execution strategies* — and incremental maintenance is what §1 assigns to compilation, not description. Both spellings stay legal (flexible, not restrictive); surfacing the twin as guidance is rung recognition's advisory job ("The compiler recognizes the rungs").

**3. Self-referential — a denormalized fold.** The RHS reads the target's own current value, so the next value is a function of the previous one (`value′ = f(value, commit data)`): the stored field denormalizes a *fold over the commit sequence*. `balance = balance + amount` is only the simplest instance — streaks, peaks, and moving averages are all folds.

**The compiler never determines the algebra of `f` — it doesn't need to.** Two decidable facts replace it:

- **Self-reference is syntactic.** The RHS reads the field it assigns. Reliably decidable; this is what triggers the analysis.
- **Exposure is structural, per hazard.** *Duplication exposure*: an unguarded fold can be applied twice for one commit (the crash/replay window — OQ6 — and trivially under a `, Hourly` backstop). A fold gated on a dischargeable state cannot. *Reordering exposure*: a commit-cadence rule folding one act at a time cannot reorder — commits are serialized, so fold order *is* commit order; a tick-cadence rule batch-folding several pending items in one firing has no defined iteration order.
- **Insensitivity is a fail-closed whitelist, per axis.** The compiler proves safety only for known forms, along the two axes independently:

| fold | duplication-safe? | order-safe? |
|---|---|---|
| `max(x, amount)`, `min`, set-union, boolean-or | provably yes | provably yes |
| `x + amount` (`sum`, `count`) | no | provably yes (commutative) |
| streak, moving average, "reset on miss" | no | no |

**An obligation exists exactly where a rule is exposed to a hazard its fold is not provably insensitive to.** An undischarged obligation is a compile error — fail-closed, mandated. The diagnostic is a demand for a stated policy, not an accusation of a bug: "this value's correctness depends on each qualifying commit being folded exactly once — state how that's ensured." Legitimate use cases carry the obligation *correctly* — a streak really does break under replay — so legit-but-unprovable and dangerous-but-unprovable get the same diagnostic; the difference is only that the legit author has an answer to give. Four ways to give it:

1. **Guard** — make exactly-once true ("Run-once guards").
2. **Derivation twin / recompute** — make it irrelevant: the value re-derives from source records.
3. **Reconciliation sweep** — bound the drift: the fold stays cheap and unguarded on the hot path; a periodic recompute (`viewCount = count(PageView for this)` nightly) restores truth on a cadence ("State-change patterns"' reconciliation rung).
4. **`tolerates <hazard>`** — accept the named risk, declared on the field:

```
shape Article {
    viewCount: Number tolerates duplication
}
```

The vocabulary is closed and mirrors the hazards one-for-one — `tolerates duplication`, `tolerates reordering` — so checking is mechanical set-coverage: exposed hazards, minus those discharged by a guard, an ordering, a reconciliation, or a tolerance, must be empty. A dead tolerance is itself a diagnostic ("this rule cannot experience reordering — did you mean duplication?"): the author never needs the exposure model in their head; the compiler names the specific risk, and the author fixes it or signs it by name. The declaration is self-vetting because it *is* the business claim — `balance: Money tolerates duplication` is visibly absurd in exactly the way that forces the author to confront what they're signing.

**The showcase trio** — same trigger, same cadence, three different verdicts:

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

**Residual gap.** A *batched* order-dependent fold (a nightly streak sweep) is exposed to reordering, and the honest discharges don't exist yet: no clause gives a batch an iteration order, and the derivation grammar has no ordered folds — so declared tolerance is currently the only spelling, which is wrong for a streak. This expressiveness hole is OQ15.

Dependencies that remain open elsewhere: the duplication exposure's crash window is OQ6's definition of a commit; a guard cannot dedupe *repeated acts* (two identical `Deposit` commits are two firings, each exactly once) — OQ5's act-identity question.

## Open questions

(Retired tags are not reused. OQ1, concurrent assignments to one field, is settled — see "One writer per field, per commit." OQ2, self-referential assignments, is settled — see "Self-referential folds and `tolerates`." OQ3, assignment targets must be stored fields, is settled — see "Assignment targets are stored fields." OQ4, act-level sugar for the one-assignment case, is settled — see "No act-level sugar." OQ8, guard and pattern sugar, is settled — see "No guard sugar." OQ9, initial values for stored fields, is settled — see "Initial values: `initially`." OQ10, bare boolean atoms, is settled — see "Bare boolean atoms." OQ11, compiler recognition of the rungs, is settled in principle — see "The compiler recognizes the rungs." OQ12, fate of `produces`, is settled — see "`produces` is retired.")

### OQ5. Marking a shape as "external input"

Mutation shapes arrive from outside the system — a user action, an API call — and nothing in the spec currently says so:

```
shape CorrectEmail {        -- committed by whom? a user? another rule? an API?
    customer: one Customer
    corrected: text
}
```

An author may eventually want to declare the distinction (e.g. only externally-committed shapes cross a trust/validation boundary; a `visible to`-style clause may want to constrain who can commit one). Deferred — for now a mutation is just another shape.

**The anchor use case** — an externally-submitted shape saved whole as a record, with `createdAt`/`updatedAt`:

```
shape Review {
    product: one Product
    stars: Number
    body: text
}
```

Committing a `Review` *is* saving the record — persistence needs no rule ("No act-level sugar"). What remains of the use case is the timestamps, and neither should be an author-maintained field:

- `createdAt` can't be client-supplied (trust) and can't be `= now` (shape-body `=` is a live derivation — it would change constantly). It's a fact *about the commit*, not about the data.
- `updatedAt` maintained by hand would mean every rule that assigns any field of the shape also assigns `updatedAt = now` — boilerplate that lies the first time a rule forgets it (though one-writer at least forces the writers' triggers disjoint).

Both are **commit metadata**. "A system never does anything on its own" means every change to a record traces to a commit, so *when it was created* and *when it last changed* are already derivable from the commit trace that `why` and impact analysis walk. Author-maintained timestamp fields are a workaround from systems where the commit log isn't first-class; in Velle they should be readable, never writable — some spelling over the trace (syntax unexplored; whether the trace is queryable data at all is OQ6-adjacent). Where the author wants a timestamp *as model data* — business rules over `submittedOn` — the spelling is `submittedOn: Date initially now` ("Initial values: `initially`"); what remains for this OQ is whether such a field can be marked internal (not committer-suppliable).

So the boundary declaration this OQ contemplates has three parts: **(1)** who may commit the shape, **(2)** which fields the committer supplies vs. which are internal (an `initially` field is the natural candidate — "Initial values: `initially`"), and **(3)** what commit metadata is readable in predicates and derivations (→ OQ6).

### OQ6. What, exactly, is one commit?

Grown from a footnote into the load-bearing question — commits, state, and guards all meet here. The open threads, from narrowest to widest:

- **Is a commit a single act instance?** The one-writer check's "unrelated shapes can never coincide" holds only if two different acts can't enter the state as one commit. Nothing states this anywhere yet. If multi-instance commits exist, "can these triggers coincide?" needs a broader definition than trigger-shape overlap.
- **Is a rule firing inside its triggering commit, or a subsequent commit of its own?** "Rules ground in commits" establishes that every firing happens *as a consequence of* a commit — but not whether the withdrawal and the suspension it triggers are one atomic state transition or two. Guard soundness ("Run-once guards") already demands mutation-plus-witness be atomic (a failure between them re-arms the guard → double deposit); that was this question in miniature.
- **What is a cascade?** A rule's effects can trip further rules — the suspension enters some refinement, another rule fires on that. Is the whole cascade one commit, a chain of commits with observable intermediate states, or bounded somehow? The atomicity self-referential folds need generalizes to the entire chain. *Spun out into its own investigation: `investigate_transactions.md` (OQ16–19); this OQ retains act identity, multi-instance commits, and firing-vs-triggering-commit atomicity.*
- **Where do `then`'s intermediate moments sit?** README §15's `then` (ordering effects within one firing) implies observable intermediate states whose relationship to commit boundaries is undefined.

Needs pinning down before the one-writer check, the guard soundness argument, or the derived-trigger-set machinery can be specified precisely.

### OQ7. Rule anatomy and timing — remaining threads

The core is settled — see "Rule triggers: `when` and `on`" above: the commit/tick dichotomy, entry and exit as commit-local diffs, the tick law (cross-tick memory must be data), transient membership as per-rule policy, and the `when`/`on` header with `on commit` as default. A rule's anatomy is **name + condition (`when`) + trigger source (`on`) + outcome (the body)** — an external API call is an outcome commit whose only db-visible trace is its witness, which is *why* the witness must exist. Still open:

- **Reliability of commit-triggered firing (→ OQ6).** "Once per commit" is logically clean, but a crash between the commit and the rule's effects is an atomicity question. The `on commit, Hourly` backstop is a business-legible mitigation, not a semantics; whether the language requires a backstop for non-idempotent rules, or OQ6's commit definition absorbs the problem, is open.
- **Re-derive README §13 under commit-local diffs** — mutation policies (`stands`/`forbidden`/`compensate`), what an exit rule may read (captured properties retract at the very moment a `when leaving` rule fires), and `compensate`'s desugared form, now that `leaving` is a diff rather than a primitive observation.
- **Latency vocabulary** — `on` expresses the evaluation *source*, not latency *requirements*. Is "immediate by default, named schedule otherwise" enough, or do deadlines ("within 24h") deserve first-class expression the compiler validates against declared cadences?
- **`on commit of <Shape>` narrowing** — "only withdrawals suspend, not fee assessments." Expressible and occasionally meaningful, but it can silently miss entry paths; per flexible-not-restrictive it would be allowed *with* the compiler reporting exactly which entry paths go unobserved. Not yet designed.
- **The pattern catalog and sugar.** The durable-state patterns — handled-once (*C ∧ ¬witness*), episode (flag/resolution pairing; entry = *C ∧ ¬open*, exit = *¬C ∧ open*), resettable latch (flag reconciled by sweeps), classification (*C* alone, no rule) — remain the vocabulary for crash-safe and cross-tick designs, no longer a replacement for entry/exit themselves. The patterns themselves are documented in "State-change patterns"; sugar over them is settled out — the apparatus stays hand-written ("No guard sugar").


### OQ13. The `each`/multi-schedule pass

Whether the disarm proof extends cleanly beyond the simple rule shape it was settled on. Two directions to check: multi-cadence `on` lists (`ApplyDeposit ... on commit, Hourly` — the disarm proof must hold under every trigger source for the durability backstop to be safe), and `each` bodies, where the guard predicate lives inside the selector, so the proof obligation is per iterated instance, not per rule firing:

```
rule SendReminder on Daily {
    each (OverdueInvoice where
          not exists (Reminder where invoice == this and sentOn > today - 3 days)) {
        Reminder from { invoice: this, sentOn: today }
    }
}
```

No conclusions yet; needs worked examples.

### OQ14. Diagnostic-led guard adoption

Fold enforcement ("Self-referential folds and `tolerates`") means the compiler will be *proposing* guards to authors mid-error. With guard sugar dropped ("No guard sugar"), the fix-it suggestion writes the canonical form itself, so the question is now exactly this: is the canonical form pleasant enough to be what a diagnostic asks an author to write? Ergonomics and enforcement aren't separable — a required guard that's miserable to write makes enforcement hostile; one that reads as the business rule makes it a teaching moment.

### OQ15. Ordered folds and batch ordering

Exposed by "Self-referential folds and `tolerates`": a batched order-dependent fold (a nightly streak sweep) owes a reordering obligation with no honest discharge — declared tolerance is wrong for a streak, and the two missing spellings are both grammar, not analysis: an ordering clause giving a tick-cadence batch a defined iteration order (`ordered by`?), and ordered folds in the derivation grammar (which would let a streak be a derivation over ordered history, dissolving the mutation entirely — README §21's derived-value grammar and `latest`-ordering items are adjacent). Until one exists, commit-cadence is the only fully-served spelling for order-dependent folds.
