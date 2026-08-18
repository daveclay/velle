# OQ40 — Serialization domains: what must serialize, what may run in parallel, and who says

**Status:** open
**In plain terms:** OQ36's U3 demands transactions behave as if they ran one at a time — but a backend that serializes *everything* will never scale. Part of engineering is knowing when to acquire a lock and, specifically, what value to lock on: two deposits to two accounts should run in parallel; two deposits to the *same* account must be serial. Can Velle derive that key — the serialization domain, the value conflicting work queues on — at its own layer of abstraction, and does the author ever need syntax for it?
**Opened by:** OQ36 draft review, 2026-08-16 (the U3 refinement thread)

---

## The refinement of U3

Everything U3 guarantees rests on serial-*equivalence* — the final state being indistinguishable from some one-at-a-time order — not on transactions actually running one at a time. Two envelopes with disjoint footprints **commute**: run them in either order, or interleaved, and the final state is identical, so running them in parallel is invisible to any observer the language defines. U3's honest form is therefore two obligations, not one: **conflicting envelopes must take turns** (with real time respected between them — last-in-wins was only ever meaningful for conflicting pairs), and **commuting envelopes are unconstrained**. What "conflicting" means precisely is this question's first deliverable, and it must count *predicate* reads — conditions the envelope checked — not just rows it fetched. The guard read `not exists DepositApplication for this` conflicts with a concurrent envelope *creating* that very witness, even though there is no shared row for the two to collide on — the read was "no such row exists," and the conflicting write is that row appearing (in database terms, a phantom conflict). That intersection is exactly what forces two deposits to the same account to take turns; miss it, and two concurrent envelopes each see "not applied yet," each fire, and the deposit lands twice — the failure U3 names.

## What is being derived: the serialization domain

The derived object, defined once. For each exposed act — and each tick-fired rule, per firing — the compiler computes a **serialization domain**: an expression over the act's own fields naming the set of **keys** the envelope revolves around. A key is usually an instance the envelope can read or write beyond what it creates itself, reduced to the path that reaches it — `{this.account}` for a deposit, `{this.source, this.target}` for a transfer — and occasionally a committed *value*, where the conflict correlates on data rather than on an existing row (the uniqueness example below). The domain **expression** is static, one per act shape; the **keys** are per call — the engineer's code evaluates `deposit.account` at commit time to know which queue this commit's work joins (which value to take a lock on, if a lock is the chosen mechanism).

In engineer- and PO-facing artifacts a key is called a **queue key** (vocabulary ruling, 2026-08-17): work sharing a queue key is handled one item at a time in arrival order, like any queue, while separate queues run independently. "Queue" is chosen deliberately over "lock," "synchronized," and "serialize": lock and synchronized each name one implementation among several the engineer may choose, and to a Kotlin or Java engineer "serialization" reads as converting objects to bytes before it reads as concurrency. "Serialization domain" stays as this question's formal name for the derived object, aligned with U3's clause name; **contention** stays as the name of the relation (two acts *contend* on a key; contending work *queues*).

What the object means is one sentence: **two envelopes conflict iff their domains intersect.** U3 obligates serialization exactly between conflicting envelopes, so the domain is the complete statement of what an implementation must queue conflicting work on — and everything outside it is explicitly safe to run in parallel. A domain that names a whole shape, or everything, is not a different kind of thing — it is the same object at its widest, produced whenever the compiler cannot prove anything narrower. Erring wide is always safe (envelopes wait that didn't need to); erring narrow is the double deposit.

"Derived" in this document means what it means for trigger sets (README §11: the author declares *what*, the compiler computes *which*): computed from the spec, never declared. Input — the envelope's static footprint: its read paths, predicate reads included, and its write paths. Output — the domain expression. Destination — the generated commit function's contract. (Not §7's "derived *property*": no runtime value is computed over state; the domain is a static artifact of compilation.)

## Velle already knows the footprint

Nothing new needs collecting — the inputs are the analyses already built. Per act: the reachable read set (`investigate_runtime.md` §2 — the transitive closure over the rule graph from the trigger shape), the static write set (README §12 — every assignment is a literal path), and the predicate read summaries relevance gating runs on (`investigate_runtime.md` §6). Shape-level conflict is the trigger-coincidence machinery re-aimed at *pairs of envelopes* instead of pairs of rules. The step past shape level is the one that matters here: footprints are usually **keyed by paths from the act** — everything a `Deposit` envelope reads or writes is reached through `this.account` — and when every path in the footprint passes through one instance, that instance *is* the domain. The queue key, derived per exposed act, at the spec's abstraction: "deposits queue per account" is a business sentence, and it falls out of the data graph the spec already draws.

**A correlated scan collapses to its key** — the compiler never concludes "lock every record of the shape" just because a predicate reads a whole collection. `sum(loans where Approved, amount)` evaluated for a `Branch` reads every `Loan` whose `branch` points at that branch — a scan, but a *correlated* one: the read set is keyed by the branch. Walk that read backward and every commit that could change the sum (writing `Loan.amount`, flipping a loan's `Approved` membership, creating a loan, repointing `Loan.branch`) touches one specific loan, and that loan names its branch — so every potential writer's domain contains a branch key, and the conflict collapses to "same branch." The engineer queues approvals on the branch's id and never locks the `Loan` table. This is also where Velle can do strictly better than a database defending the same sum: the rows that would change a sum may not exist yet (the phantom conflict again — the read was "these are all the rows," the conflicting write is a new row appearing), so a database's honest tools are predicate locks or range locks over the *data*. Velle escapes that because of U5, no side doors: the set of possible writers is closed and statically known, so instead of locking data it lines the *writers* up behind a shared queue key — sound precisely because nothing can write except through commits whose domains were derived from the same read paths. The genuinely wide case is the **uncorrelated** read: an aggregate rooted at the shape itself with no path back to any key (`sum(Loan where Approved, amount)` — all loans, full stop), or anything the read-summary walker marks `opaque`. Only there does the domain honestly widen to the whole shape.

## What the compiler could emit

The natural surface is the one §10 of `investigate_runtime.md` already built: per generated commit function, the typed store layer states "queue key: `account`" — kdoc plus, plausibly, a typed handle the engineer's lock/`SELECT FOR UPDATE`/partition-key code takes as a parameter. The engineer reads the key off the contract instead of re-deriving it by hand from the rule graph — and a spec edit that widens a footprint *changes the emitted domain*, surfacing as a visible contract change rather than a silently stale lock choice. The emitted contract states a key whenever the derivation found one — a correlated scan collapses to its correlation key (above), so "reads a whole collection" never by itself produces a table-wide contract. Where the compiler cannot find a key, it must widen rather than guess narrow: a footprint containing an *uncorrelated* read (an aggregate rooted at a shape with no path back to any key, or anything relevance gating marks `opaque`) widens the domain to the whole shape, the emitted contract says so plainly — "every commit that touches `Loan` joins a single queue" — and the diagnostic names which read caused the widening, the same coarse-first-calibrate-later arc as V1's collection-path rule and §6's reverse-path narrowing. Declared `never` invariants narrow the derivation the way they already narrow one-writer: an invariant is a fact the compiler may treat as always true, and "these two paths can never reach the same instance" is exactly the kind of fact that shrinks a conflict estimate.

## The syntax question

Three candidate solutions, undecided:

- **Derived only, never declared.** The domain is a consequence of the footprint, like the trigger set (README §11: the author declares *what*, the compiler computes *which*). An author declaration could only be unsound (narrower than the footprint — under-locking) or redundant (wider — which is the engineer's freedom below the contract anyway, no spec vocabulary needed). Under this solution OQ40 adds zero syntax; the whole deliverable is derivation plus the emitted contract.
- **Author-stated facts that *feed* the derivation.** When the derived domain is coarser than the business knows it needs to be, the author's fix is not a lock annotation but a business fact stated in the model — a `never` establishing that two paths can never reach the same instance, or the state-partition declaration (README §22) — which the compiler then relies on to compute a narrower domain. The pressure is identical to relevance gating's; any new syntax lands on constructs already queued, not on new lock vocabulary.
- **A width obligation in the `tolerates` family.** An uncorrelated read is an exposed hazard in §19's sense — the ruling holds "unintentionally single-threaded" to be as legitimate a failure as a wrong value — so the compiler demands a stated policy: correlate the read (the second solution's move), put the rule on a cadence, or declare **`tolerates contention`** on the declaration whose read causes the width. The spelling joins §19's closed tolerance vocabulary rather than inventing a new species; whether the demand is a compile error or an advisory is the open dial.

Current lean (updated 2026-08-17): the first solution with the second as its pressure valve — matching "compiling means validating" (the domain is a fact about the spec, so it is computed, not asserted) and the design-philosophy stance that structural derivation beats checked discipline — with the human-facing visibility landing in a compilation artifact, the contention map (below): no author ever declares a queue key. The third solution is a §19-family obligation answered by `tolerates contention`, with one dial still open — whether exposure is a compile error or an advisory. That calibration is the same empirical campaign as OQ16's — this question is OQ16's cross-transaction sibling: V16 asks whether sibling firings *within* one envelope commute; OQ40 asks which *envelopes* commute with each other, over the same commutation machinery.

## The three solutions, exercised

One running world: deposits fold into `Account.balance` under the canonical guard, transfers post to two accounts, and loan approvals check a lending cap. Every spelling below marked *placeholder* is exactly that — invented to make a solution concrete, not proposed.

### The strawman first — the solution not on the list

Every exercise below has to answer why the direct spelling isn't a candidate: the author declaring the queue key.

```
expose shape Deposit {
    account: one Account
    amount: Money
} queues on account              -- strawman, not a candidate solution
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
/** Queue key: [deposit.account].                                  // placeholder emission
 *  Commits sharing a queue key are handled one at a time, in arrival
 *  order (U3); commits whose keys are disjoint run in parallel. */
fun commitDeposit(deposit: DepositInput): CommitResult
```

The transfer shows a domain is a key *set*, not one key:

```
rule PostTransfer when Transfer {
    LedgerEntry from { account: source, amount: -amount }
    LedgerEntry from { account: target, amount: amount }
}
```

Domain: `{source, target}`. Two transfers conflict iff their sets intersect; A→B and C→D run in parallel, A→B and B→C take turns. The engineer's classic move — acquire both locks in canonical key order to dodge deadlock — stays below the contract, but the contract hands them the set. Note that a declared `never (Transfer where source == target)` — a business fact the author would state for its own sake — also does work here: it proves the key set always has two *distinct* members, which is exactly the kind of fact a lock-ordering implementation wants guaranteed rather than assumed. That is solution 2's mechanism (business facts in the model sharpening the serialization contract) showing up before anyone asked for it.

One more case, which shows the domain is not always an instance. Uniqueness —

```
never (Customer where exists (Customer as other where other.email == this.email and not (other == this)))
```

— makes two `RegisterCustomer` envelopes conflict iff they commit *equal email values*: there is no shared row to lock, because neither row exists yet. The derived domain keys on the committed **value** (`email`), read off the invariant's correlation (`other.email == this.email`), and the emitted contract says so — which is precisely the hint an engineer needs to reach for a unique index or a value-lock rather than a row lock. Derivation handles it; a hand-declared `queues on` almost certainly would not have.

### Solution 2 — author-stated model facts: the lending cap

```
shape Institution { lendingCap: Money }
shape Loan { amount: Money }                       -- no relationship to Institution
never (Institution where sum(Loan where Approved, amount) > lendingCap)
```

The aggregate is rooted at the `Loan` shape with no correlation back to anything — the model literally says "one cap over every loan there is." The derivation has no key to collapse to: any commit that touches any loan can change the sum, so every approval conflicts with every other approval, the emitted contract says "every loan-touching commit joins a single queue," and the warning names the uncorrelated read. Under this solution the author's response is never a lock annotation, because the width is a fact about the *model*: the loans aren't related to anything that would partition them. If the business truth is per-branch caps, the fix is stating the relationship the model was missing:

```
shape Branch { lendingCap: Money }
shape Loan { branch: one Branch, amount: Money }
never (Branch where sum(loans where Approved, amount) > lendingCap)
```

Now the scan is correlated — `loans` is the inferred inverse of `Loan.branch`, so the cap's read set is keyed by branch — and the derivation collapses to the branch key: approvals at different branches provably commute (either order, same final state), approvals at the same branch take turns. No lock vocabulary appeared; the narrowing construct was a *relationship*, and the throughput fix is visible as business structure — any future reader learns branches carry their own caps, where a lock clause would have buried the same decision in runtime configuration. The constructs the author touches are the ones they already have (shapes, relationships, `never`, eventually the state partition declaration), because the serialization domain *is* the model's correlation structure: narrowing one means narrowing the other. The solution's claim in one line: **a coarse domain is a modeling smell, and the fix belongs in the model where everyone reads it, not in a lock clause only the runtime sees.**

### Solution 3 — `tolerates contention`: the cap that is truly global

Suppose the cap is real — a regulator caps the *institution's* total exposure, all branches combined. The per-branch restructure would misstate the business just to buy throughput: the uncorrelated model above is the true one, the whole-shape domain the compiler derived from it is right, and it will stay that way.

An *optional* acknowledgment construct — something the author may write to record that the width is deliberate — would be the wrong tool: documentation nobody demands teaches nobody. An author who already understands the concurrency contract doesn't need it, and the author who doesn't understand it never writes it. The actual problem is comprehension — *does the author understand the concurrency their spec creates?* — and Velle's committed pattern for comprehension problems is the compiler: compiling is the event that forces a human to notice and be explicit (README §1); the fold obligations demand a stated policy even from provably legitimate code (§19 — legit-but-unprovable and dangerous-but-unprovable get the same diagnostic, and the difference is only that the legit author has an answer to give); V17–V18 refuse to proceed until the spec answers a product question. Applied here: an uncorrelated read is an **exposed hazard** — "unintentionally single-threaded" is as legitimate a failure category as "the value could end up wrong" — and exposure demands a stated policy. The acceptance is then not a new species but the fourth member of §19's closed tolerance vocabulary: **`tolerates contention`**, declared on the declaration whose read causes the width.

On the `never`, whose uncorrelated aggregate is the widener ("invariant" always means the `never` here — README §21's term for it):

```
never (Institution where sum(Loan where Approved, amount) > lendingCap)
    tolerates contention
```

Without the tolerance, the §19-style diagnostic demands the policy:

```
error: every commit touching Loan joins a single queue.
  The invariant's aggregate `sum(Loan where Approved, amount)` reads every
  Loan and correlates to no key, so any two loan-touching commits conflict.
  State the policy:
    - correlate the read (e.g. cap per Branch: `Loan.branch`, sum over `loans`), or
    - declare `tolerates contention` on the invariant.
```

Attachment follows the read, not the act: one tolerance on the `never` covers every act the invariant widens, where `expose shape ApproveLoan { ... } tolerates contention` would need repeating on each such act and would go stale the moment a new act starts reading the cap. A `never` is not the only declaration that can carry the widening read — a rule's condition can, worked next; so can a derived property with an uncorrelated formula, read by any rule. Wherever the widening read lives is where the tolerance attaches.

#### The same obligation on a rule

The width can arrive as a *reaction* instead of a refusal. Suppose compliance wants an alert the moment total approved exposure crosses a threshold:

```
shape Institution {
    lendingCap: Money
    alertThreshold: Money
}

rule NotifyCompliance
    when (Institution where sum(Loan where Approved, amount) > alertThreshold)
    tolerates contention {
    ComplianceAlert from { institution: this, raisedOn: now }
}
```

The condition's aggregate is the same uncorrelated read as the cap invariant's — rooted at the `Loan` shape, no path back to any key — and a commit-triggered rule's condition is evaluated *inside* every envelope that could affect it (the derived trigger set, README §11). So every loan-touching commit carries the global sum in its footprint and joins one queue, exactly as under the `never`; the tolerance attaches to the rule, because the rule's condition is the declaration whose read causes the width. A rule's diagnostic lists one discharge no `never` has — **cadence**:

```
error: every commit touching Loan joins a single queue.
  NotifyCompliance's condition reads every Loan and is evaluated inside
  each envelope that can affect it. State the policy:
    - correlate the read, or
    - move the rule to a schedule (`on Nightly`) — the read then runs once
      per tick instead of inside every commit, or
    - declare `tolerates contention` on the rule.
```

Rewriting the header as `on Nightly` moves the condition's evaluation out of every act envelope and into the tick's own firing — a tick-only rule takes no part in commit-time watching at all (`investigate_runtime.md` §6), so the global read leaves every deposit's and approval's footprint and runs once per night in the tick's own transaction. That is not a trick; it is the author answering a business question — "must this alert fire the instant the threshold is crossed, or is tomorrow morning fine?" — the same per-rule latency policy README §17 already frames ("transient membership is a policy, stated in the header"). A `never` has no such dial: enforcement at every transaction's end is what a `never` *means* (README §21). So a rule carrying `tolerates contention` marks exactly the case where the business has answered "the instant it crosses" — and accepts the system-wide queue that immediacy buys.

#### Self-vetting and dead tolerance

The spelling inherits §19's virtues with no new machinery. It is self-vetting because it *is* the business claim, read back: `tolerates contention` on the cap invariant reads "the institution-wide cap is worth every approval waiting its turn" — a sentence an author can stand behind or visibly can't, the same property that makes `balance: Money tolerates duplication` visibly absurd. And it cannot be sprinkled defensively: on a declaration whose reads all correlate — `rule ApplyDeposit when UnappliedDeposit tolerates contention` — there is no width to tolerate, and the dead-tolerance diagnostic fires exactly as it does for an impossible `reordering`: "this rule causes no contention beyond its queue key (`account`) — remove the tolerance." Checking stays §19's mechanical set-coverage: width exposures, minus those discharged by a correlated read (a model fact — solution 2), a cadence (rules only), or a declared tolerance, must be empty.

Grammar impact is small and pre-figured: on a rule, the tolerance sits in the header, where `tolerates loss` already attaches to rules (its header placement is not yet pinned in `grammar.md` either — pin both together); on a `never`, the placement is new, since nothing attaches to a `never` today.

Two further modeling moves exist even here; neither is a problem with Velle at all — each accurately describes the system the author is trying to define, which is Velle doing exactly its job.

#### The reification move

The author can relate every loan to a reified institution — `Loan { institution: one Institution, amount: Money }`, with the cap invariant correlated through the inferred inverse — and the derivation collapses to a per-institution key: no warning, compiler satisfied. With one `Institution` row in the database the system still runs serially in practice, but that is now a fact about the *data*, not the spec. Ruling: this is honest modeling, not ceremony — "loans belong to the institution" is simply true of this business, and stating the relationship makes the model *more* accurate. Taking the move turns solution 3's case into an instance of solution 2, which is where it belonged.

#### The singleton assertion

The compiler cannot currently know that only one `Institution` exists — no construct claims it. If a future declaration lets the author assert "exactly one instance of this shape exists" (the state-partition/singleton family, README §22), the compiler could then *prove* that a per-institution key means globally serial. Ruling: this too is accurate description — "there is exactly one institution" is a true business fact worth declaring, and the singleton declaration earns its place independently of serialization. What it adds here is making global width *provable*, so the contention map (below) can state "every approval joins one queue" as fact rather than as possibility.

#### Why a tolerance fits here, and the remaining dial

One could object that the tolerance family is for correctness: `tolerates duplication`/`reordering`/`loss` each answer a compile error that exists because a value could actually end up wrong, while a wide domain corrupts nothing — so no honest obligation exists for a fourth tolerance to answer. The objection undercounts what Velle already demands at compile time: the unfireable-rule error, the dead-machinery diagnostics, and the fold demand on provably legitimate code are all "the spec says something you probably don't mean; be explicit," with no wrong value anywhere. An unintentionally system-wide queue is squarely in that family — grinding to a halt is as legitimate a failure as a wrong value — so the obligation is consistent with the language's existing demands, and `tolerates contention` answers it exactly as the other tolerances answer theirs.

What the §19 analogy does not settle is the severity default — **the one remaining dial: required or advisory.** Required (fold-style, fail-closed): every uncorrelated read is a compile error until answered — comprehension is forced at the moment the width is written, at the cost of ceremony in small systems where no queue will ever have a line. Advisory: the warning and the contention map carry the visibility with no ceremony, at the cost of never forcing the author who most needs forcing. Calibration against realistic specs decides, the same empirical campaign as OQ16's; the comprehension framing that produced this solution leans **required**.

## The contention map: a compilation artifact for humans

The reframe (2026-08-17) that moves the deliverable out of the spec. Compiling Velle was never going to produce only executable code: the README's opening already promises Product-Owner-facing artifacts (design specs, possibly diagrams), `testgen.md` derives executable tests that validate the runtime against the business logic, and the same family plausibly grows UML-style class diagrams read off the shapes and relationships, and state-flow diagrams read off the refinements and rules. Hold the author in view against that backdrop: a person writing Velle wants a *working system described in intuitive syntax* — they are not thinking about isolation levels or compiler-proof semantics while modeling a business, and no lock vocabulary belongs in front of them. So the serialization derivation's first deliverable is another member of the artifact family: the **contention map** — a compilation output, text or diagram or both, that shows an engineer, and a product owner, where the system can work in parallel and where work must wait its turn.

Per exposed act and per tick rule, the map states the derived domain in business words, what contends with what, and every wide domain with the read that caused it:

```
ApplyDeposit        queues per account            two deposits to one account take turns;
                                                  different accounts run at the same time
PostTransfer        queues per {source, target}   contends with deposits and transfers
                                                  touching either account
RegisterCustomer    queues per email value        two signups with the same address take turns
ApproveLoan      ⚠  one queue, system-wide        every approval waits behind every other:
                                                  the institution lending cap reads every Loan
```

A diagram form would draw acts as nodes joined by the queue keys they share — the system's queues and what defines them — but the load-bearing content is the domain statements; the form is presentation. Same derivation, two audiences: the engineer also gets the per-commit-function contract ("What the compiler could emit," above) — the map is the system-wide view, the generated function's contract the per-call-site one.

The map is also where the "is this width deliberate?" conversation happens. An unintentionally serial system gets caught the way an unintentionally missing rule gets caught: a human reads the artifact and asks "why does every approval wait behind every other?" — the concurrency-facing sibling of the derived trigger set's Product-Owner-facing answer to "when does this rule run?" (README §11). No author ever declares a queue key; the model states only business facts; and the one piece of concurrency vocabulary the spec can carry is the answer to a width obligation — `tolerates contention` (solution 3). The map renders the difference: a tolerated width shows as accepted, naming the declaration that carries the tolerance ("one queue, system-wide — tolerated: the institution lending cap"), while an unexamined width is the ⚠ row above — a row that can exist at all only if the obligation is dialed to advisory rather than required (solution 3's remaining dial).

## Threads

- **The artifact family (OQ41, settled → `diagrams.md`).** The contention map is one member of a larger set of compilation artifacts for humans — class diagrams, state-flow diagrams, the rule graph, sequence diagrams — now normative in `diagrams.md`, with the built generators under `compiler/`. The pairing worth keeping straight: a sequence diagram is the *within*-envelope view (causality — OQ16's territory, since sibling commutation is what licenses drawing parallel lanes), while the contention map is the *between*-envelope view (queueing); neither substitutes for the other.
- **Ticks and sweeps.** Each firing at a tick is already its own transaction (README §17) — a sweep's firings have per-member domains and parallelize across records for free, which is most of what "batch throughput" needs. The tick's member *scan* is a snapshot read over the swept shape: whether it conflicts with concurrent act envelopes on that shape (delaying the scan or the acts) or reads a settled snapshot and lets stragglers heal at the next tick is exactly the guard-self-healing design already present — likely the latter, worth stating.
- **Impact on OQ36 — applied.** OQ36 settled (2026-08-16) with U3 already in this shape: serial-equivalence with real time respected between conflicting envelopes, disjoint-footprint envelopes explicitly licensed to run in parallel, and the domain derivation pointed here. The settled contract lives in `evaluation.md`, "The universal transaction"; whatever this question decides lands as a refinement of that U3, not a rewrite.
- **Cross-store domains.** A domain that spans resolvers (an envelope touching two engineer stores) is where "what value to lock on" stops being one `synchronized` — the contract can still *name* the domain; delivering serialization across stores stays the engineer's distributed-territory problem (the contract's exclusion list in `evaluation.md`, unchanged).
