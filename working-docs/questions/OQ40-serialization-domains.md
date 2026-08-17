# OQ40 — Serialization domains: what must serialize, what may run in parallel, and who says

**Status:** open
**In plain terms:** OQ36's U3 demands transactions behave as if they ran one at a time — but a backend that serializes *everything* will never scale. Part of engineering is knowing when to acquire a lock and, specifically, what value to lock on: two deposits to two accounts should run in parallel; two deposits to the *same* account must be serial. Can Velle derive that lock key — the serialization domain — at its own layer of abstraction, and does the author ever need syntax for it?
**Opened by:** OQ36 draft review, 2026-08-16 (the U3 refinement thread)

---

## The refinement of U3

Serial-*equivalence* is what the proofs spend; the total order is the observational fiction, never an implementation demand. Two envelopes with disjoint footprints commute — every interleaving is equivalent to either serial order, so running them in parallel is invisible to any observer the language defines. U3's honest form is therefore two clauses, not one: **conflicting envelopes serialize** (with real time respected between them — last-in-wins was only ever meaningful for conflicting pairs), and **commuting envelopes are unconstrained**. What "conflicting" means precisely is this question's first deliverable, and it must count *predicate* reads, not just row reads: the guard read `not exists DepositApplication for this` conflicts with the witness create it watches for — the classic phantom — and that intersection is exactly what forces same-account deposits serial. Miss it and snapshot-isolated concurrent envelopes both see the guard armed: the double deposit U3 names.

## What is being derived: the serialization domain

The derived object, defined once. For each exposed act — and each tick-fired rule, per firing — the compiler computes a **serialization domain**: an expression over the act's own fields naming the set of **keys** the envelope revolves around. A key is usually an instance the envelope can read or write beyond what it creates itself, reduced to the path that reaches it — `{this.account}` for a deposit, `{this.source, this.target}` for a transfer — and occasionally a committed *value*, where the conflict correlates on data rather than on an existing row (the uniqueness example below). The domain **expression** is static, one per act shape; the **keys** are per call — the engineer's code evaluates `deposit.account` at commit time to know which lock to take.

What the object means is one sentence: **two envelopes conflict iff their domains intersect.** U3 obligates serialization exactly between conflicting envelopes, so the domain is the complete statement of what an implementation must serialize on — and everything outside it is licensed parallelism. A domain that names the whole shape, or everything, is the coarse fail-closed end, not a different kind of thing.

"Derived" in this document means what it means for trigger sets (README §11: the author declares *what*, the compiler computes *which*): computed from the spec, never declared. Input — the envelope's static footprint: its read paths, predicate reads included, and its write paths. Output — the domain expression. Destination — the generated commit function's contract. (Not §7's "derived *property*": no runtime value is computed over state; the domain is a static artifact of compilation.)

## Velle already knows the footprint

Nothing new needs collecting — the inputs are the analyses already built. Per act: the reachable read set (`investigate_runtime.md` §2 — the transitive closure over the rule graph from the trigger shape), the static write set (README §12 — every assignment is a literal path), and the predicate read summaries relevance gating runs on (`investigate_runtime.md` §6). Shape-level conflict is the trigger-coincidence machinery re-aimed at *pairs of envelopes* instead of pairs of rules. The step past shape level is the one that matters here: footprints are usually **keyed by paths from the act** — everything a `Deposit` envelope reads or writes roots through `this.account` — and when every path in the footprint factors through one instance, that instance *is* the domain. The lock key, derived per exposed act, at the spec's abstraction: "deposits serialize per account" is a business sentence, and it falls out of the data graph the spec already draws.

## What the compiler could emit

The natural surface is the one §10 of `investigate_runtime.md` already built: per generated commit function, the typed store layer states "serializes on: `account`" — kdoc plus, plausibly, a typed handle the engineer's lock/`SELECT FOR UPDATE`/partition-key code takes as a parameter. The engineer reads the key off the contract instead of re-deriving it by hand from the rule graph — and a spec edit that widens a footprint *changes the emitted domain*, surfacing as a visible contract change rather than a silently stale lock choice. Fail-closed at the coarse end: a footprint containing a scan-shaped read (a `never` over all of a shape, an aggregate over an unkeyed collection, anything relevance gating marks `opaque`) widens the domain — to the shape, or to global — with a diagnostic naming the widening read, the same coarse-then-calibrate arc as V1's collection-path rule and §6's reverse-path narrowing. Established `never`s narrow it the way they already narrow one-writer: an invariant is what proves two paths can't reach the same instance.

## The syntax question

Three candidate solutions, undecided:

- **Derived only, never declared.** The domain is a consequence of the footprint, like the trigger set (README §11: the author declares *what*, the compiler computes *which*). An author declaration could only be unsound (narrower than the footprint — under-locking) or redundant (wider — which is the engineer's freedom below the contract anyway, no spec vocabulary needed). Under this solution OQ40 adds zero syntax; the whole deliverable is derivation plus the emitted contract.
- **Author-stated facts that *feed* the derivation.** When the derived domain is coarser than the business knows it needs to be, the fix is not a lock annotation but a model statement the prover can spend — a `never` establishing disjointness, or the state-partition declaration (README §22) — pressure identical to relevance gating's. Syntax pressure lands on constructs already queued, not on new lock vocabulary.
- **A tolerance-family escape.** If real specs surface unavoidable global domains the author knowingly accepts (a genuinely global invariant), the honest spelling might be a signed acceptance in the `tolerates` family — "this act serializes globally, and the author knows" — rather than a silent wide default.

Current lean: the first solution, with the second as its pressure valve — matching "compiling means validating" (the domain is a fact about the spec, so it is computed, not asserted) and the design-philosophy stance that structural derivation beats checked discipline. The third only earns a place if calibration against realistic specs finds domains that are wide, correct, and worth signing. That calibration is the same empirical campaign as OQ16's — this question is OQ16's cross-transaction sibling: V16 asks whether sibling firings *within* one envelope commute; OQ40 asks which *envelopes* commute with each other, over the same commutation machinery.

## The three solutions, exercised

One running world: deposits fold into `Account.balance` under the canonical guard, transfers post to two accounts, and loan approvals check a lending cap. Every spelling below marked *placeholder* is exactly that — invented to make a solution concrete, not proposed.

### The strawman first — the solution not on the list

Every exercise below has to answer why the direct spelling isn't a candidate: the author declaring the lock key.

```
expose shape Deposit {
    account: one Account
    amount: Money
} serializes on account          -- strawman, not a candidate solution
```

The declaration can only restate the derivation or contradict it. If every path in the envelope's footprint already factors through `account`, the clause is noise — and worse than noise over time: a rule added next year whose condition reads the customer's *other* accounts silently widens the true domain while the declaration keeps saying `account`. That is the stale hand-derived lock choice, now with spec blessing. And if the clause names something narrower than the footprint from day one, it is an instruction to under-lock — the double deposit as a keyword. Sound-but-wider is the only safe direction a declaration could take, and wider is the engineer's freedom below the contract, needing no vocabulary. This is the same argument that killed manual `updatedAt` (§5: boilerplate that lies the first time a rule forgets) — a fact the compiler can compute, restated by hand, is a lie waiting for a spec edit.

### Solution 1 — derived only: the deposit and the transfer

Nothing in the spec mentions serialization:

```
shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

The compiler walks the commit's envelope footprint: reads `this.account.balance` (the fold), reads `DepositApplication for this` (the guard — a predicate read, counted), writes `account.balance`, creates a `DepositApplication`. Every path factors through two instances — the act itself, born in this envelope and conflict-free by construction, and `this.account`. The domain derives to `account`; the author's hand never appears. The construct lives on the *output* side, on the generated surface:

```kotlin
/** Serialization domain: [deposit.account].                       // placeholder emission
 *  Envelopes whose domains intersect must not run concurrently (U3);
 *  disjoint domains may run in parallel. */
fun commitDeposit(deposit: DepositInput): CommitResult
```

The transfer shows a domain is a key *set*, not one key:

```
rule PostTransfer when Transfer {
    LedgerEntry from { account: source, amount: -amount }
    LedgerEntry from { account: target, amount: amount }
}
```

Domain: `{source, target}`. Two transfers conflict iff their sets intersect; A→B and C→D run in parallel, A→B and B→C take turns. The engineer's classic move — acquire both locks in canonical key order to dodge deadlock — stays below the contract, but the contract hands them the set. And a declared `never (Transfer where source == target)` is solution 2's mechanism appearing uninvited: it proves the set always has two distinct members, which is exactly the kind of fact a lock-ordering implementation wants stated rather than assumed.

One sharpener: the domain is not always an instance. Uniqueness —

```
never (Customer where exists (Customer as other where other.email == this.email and not (other == this)))
```

— makes two `RegisterCustomer` envelopes conflict iff they commit *equal email values*: there is no shared row to lock, because neither row exists yet. The derived domain keys on the committed **value** (`email`), read off the invariant's correlation (`other.email == this.email`), and the emitted contract says so — which is precisely the hint an engineer needs to reach for a unique index or a value-lock rather than a row lock. Derivation handles it; a hand-declared `serializes on` almost certainly would not have.

### Solution 2 — author-stated model facts: the lending cap

```
shape Bank { lendingCap: Money }        -- one instance in practice
shape Loan { bank: one Bank, amount: Money }
never (Bank where sum(loans where Approved, amount) > lendingCap)
```

Every approval envelope reads the sum over all the bank's loans, so every approval's domain contains the `Bank` instance: all approvals serialize, institution-wide. The derivation is *correct* — the invariant really does make any two approvals conflict — and the diagnostic names the widening read: "every loan approval serializes on `Bank`: the cap invariant reads every `Loan.amount`." Under this solution the author's response is never a lock annotation, because the width is a fact about the *model*. If the business truth is per-branch caps, say so:

```
shape Branch { bank: one Bank, lendingCap: Money }
shape Loan { branch: one Branch, amount: Money }
never (Branch where sum(loans where Approved, amount) > lendingCap)
```

The domain narrows to `branch` with no new vocabulary — approvals at different branches now provably commute — and the throughput fix is *visible as business structure*: any future reader learns branches carry their own caps, where a lock clause would have buried the same decision in runtime configuration. The constructs the author touches are the ones they already have (shapes, `never`, eventually the state partition declaration), because the serialization domain *is* the model's correlation structure: narrowing one means narrowing the other. The solution's claim in one line: **a coarse domain is a modeling smell, and the fix belongs in the model where everyone reads it, not in a lock clause only the runtime sees.**

### Solution 3 — the signed wide domain: the cap that is truly global

Suppose the cap is real — a regulator caps the *institution's* total exposure. The per-branch restructure would misstate the business to buy throughput; the domain is wide, correct, and permanent, and under solutions 1+2 the advisory never goes quiet. The tolerance-family spelling signs it where the width comes from:

```
never (Bank where sum(loans where Approved, amount) > lendingCap)
    accepts serial               -- placeholder: "this invariant serializes every
                                 --  envelope that can touch it; the author knows"
```

(On the invariant rather than the act, tentatively: the invariant is *why* the domain is wide, and one signature there covers every act it widens — `expose shape ApproveLoan { ... } accepts serial` would need repeating per act and would go stale when a new act starts reading the cap.)

Writing it down exposes the solution's wrinkle, worth recording: `tolerates duplication`/`reordering`/`loss` sign **correctness** hazards — undischarged, they are compile errors, and the signature is the discharge. A wide domain is never incorrect, only slow, so there is no fail-closed obligation for this signature to discharge; the construct would exist to silence an *advisory* and document intent — a different species from `tolerates`, closer to acknowledging a dead-machinery notice. That asymmetry is itself an argument for the current lean: derived domains with an advisory on wide ones (solution 1), model facts as the fix (solution 2), and the signature only if calibration shows authors drowning in advisories they have consciously, permanently accepted.

## Threads

- **Ticks and sweeps.** Each firing at a tick is already its own transaction (README §17) — a sweep's firings have per-member domains and parallelize across records for free, which is most of what "batch throughput" needs. The tick's member *scan* is a snapshot read over the swept shape: whether it conflicts with concurrent act envelopes on that shape (delaying the scan or the acts) or reads a settled snapshot and lets stragglers heal at the next tick is exactly the guard-self-healing design already present — likely the latter, worth stating.
- **Impact on OQ36 — applied.** OQ36 settled (2026-08-16) with U3 already in this shape: serial-equivalence with real time respected between conflicting envelopes, disjoint-footprint envelopes explicitly licensed to run in parallel, and the domain derivation pointed here. The settled contract lives in `evaluation.md`, "The universal transaction"; whatever this question decides lands as a refinement of that U3, not a rewrite.
- **Cross-store domains.** A domain that spans resolvers (an envelope touching two engineer stores) is where "what value to lock on" stops being one `synchronized` — the contract can still *name* the domain; delivering serialization across stores stays the engineer's distributed-territory problem (the contract's exclusion list in `evaluation.md`, unchanged).
