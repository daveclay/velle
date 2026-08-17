# OQ41 — The compilation-artifact family: what compiling produces for humans

**Status:** open
**In plain terms:** compiling Velle should produce more than runnable code and tests — diagrams and reports that let an engineer or a product owner *see* the system: its shapes (class diagrams), its states and rules (state-flow diagrams), where work queues (the contention map, OQ40), and what happens when an act arrives (sequence diagrams). Which artifacts, derived from what the compiler already knows, and how concrete can each one get?
**Opened by:** OQ40's contention-map reframe and the sequence-diagram discussion, 2026-08-17

---

## The family

The README's opening already promises Product-Owner-facing artifacts (design specs, possibly diagrams), and `testgen.md` derives executable tests from the spec. The members identified so far, each derived from analyses the compiler already runs:

- **Class diagrams** — shapes and relationships; near-mechanical (UML's bread and butter), with inferred inverses and derived properties visually distinguished from stored fields.
- **State-flow diagrams** — refinements as states, entry/exit rules as transitions; the state-partition declaration (README §22) would sharpen these into proper statecharts ("an invoice is always in exactly one of Draft, Issued, Paid, Voided").
- **The contention map** (OQ40) — the *between*-envelope view: which acts queue on which keys, and where a single system-wide queue forms.
- **Sequence diagrams** (this file's main content) — the *within*-envelope view: what one act's arrival causes, in what causal order, across which transactions.

Two principles carry over from OQ40. First, the author writes business facts in intuitive syntax; comprehension is served by artifacts *derived from* the spec, never by annotations added to it. Second, **generated tests and diagrams are the same derivation rendered twice** — a test scenario can emit its own sequence diagram, so a product owner reviews as pictures exactly what the test suite executes as assertions.

## Sequence diagrams: the mapping

Velle has no call graph — rules react to commits, nothing calls anything — but everything a sequence diagram needs is statically derived, and Velle's semantics map onto notation UML already has for exactly the right distinctions:

- **Lifelines** (the vertical participant lines) — the committer, the shape instances the cascade touches, each schedule, and any external system (the payment processor in intent-before-effect).
- **The transaction envelope is a frame** — UML's `critical` region means "everything in this box is one atomic unit," which is exactly what an envelope is: the act's commit, its in-transaction firings, and the transaction-end `never` check, standing or falling together.
- **`after commit` is an async arrow** — UML's open arrowhead means "the sender does not wait for this," which is exactly the declared boundary: the arrow leaves the triggering frame and starts a new one, entered only after the first is durable.
- **Independent sibling firings are a `par` fragment** (side-by-side lanes meaning "these happen in no particular order") — and Velle is the rare system where drawing that is *provably honest*: V16 requires sibling order not to matter, so the diagram never has to invent a false sequence.
- **A partition or outcome dispatch is an `alt` fragment** (stacked exclusive branches), with the refinement predicates as the branch guards.
- **A backstop cadence is a loop on the schedule's lifeline** — "and this re-checks every Hourly tick until the guard disarms."

## Three tiers of concreteness

1. **Static, per exposed act** (compile time): the may-fire cascade from the derived trigger sets, with unresolved conditions left as guards on the arrows. Complete, but branchy for deep cascades.
2. **Example-grounded**: a spec-carried `example` declaration (README §22; `testgen.md` phase 3) supplies concrete instances, collapsing every `alt` to the branch actually taken — one clean diagram per named scenario, generated alongside the test that executes it.
3. **Runtime trace**: the deferred `why`/provenance item is a firing record mapped back to source, which is precisely the data needed to render *what actually happened* for one real commit — provenance's most legible output form.

## Worked example: deposits and the lending cap

The spec (the OQ40 running world, made whole):

```
shape Account { balance: Money }

expose shape Deposit {
    account: one Account
    amount: Money
}

shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}

shape Institution {
    lendingCap: Money            -- $1,000,000 in the scenario below
    alertThreshold: Money        -- $800,000
}

shape Loan { amount: Money }     -- deliberately unrelated to Institution (OQ40, solution 3)

expose shape LoanApproval { loan: one Loan }
shape Approved = Loan where exists LoanApproval for this

never (Institution where sum(Loan where Approved, amount) > lendingCap)
    tolerates contention

rule NotifyCompliance
    when (Institution where sum(Loan where Approved, amount) > alertThreshold)
    tolerates contention {
    ComplianceAlert from { institution: this, raisedOn: now }
}
```

**Scenario A — a deposit** (`acct-17` holds $1,200; $500 arrives). The interesting content: the declared boundary, the disarmed guard, and the backstop that has nothing to heal.

```
caller            runtime                  acct-17          Hourly
  │                  │                        │                │
  │ commitDeposit(acct-17, $500)              │                │
  │─────────────────▶│                        │                │
  │        ╔═ T1: the deposit envelope ═════════════╗          │
  │        ║ insert Deposit d1                      ║          │
  │        ║ ApplyDeposit does not fire here —      ║          │
  │        ║ its `after commit` declares a boundary ║          │
  │        ╚════════════════════════════════════════╝          │
  │ accepted (d1)    │                        │                │
  │◀─────────────────│                        │                │
  │                  │                        │                │
  │                  │ ┄┄ async: T1 durable, firing begins ┄┄  │
  │        ╔═ T2: ApplyDeposit(d1) ══════════════════╗         │
  │        ║ read balance ─────────▶ $1,200          ║         │
  │        ║ balance = $1,700 ─────▶│                ║         │
  │        ║ insert DepositApplication a1            ║         │
  │        ║   — disarms UnappliedDeposit: the       ║         │
  │        ║   guard's own predicate is now false    ║         │
  │        ╚═════════════════════════════════════════╝         │
  │                  │                        │                │
  │                  │  ┌ loop: every Hourly tick ┐            │
  │                  │◀─┤ members of              ├── tick ────│
  │                  │  │ UnappliedDeposit? none  │            │
  │                  │  │ — a1 stands. (Had T2    │            │
  │                  │  │ been lost, d1 would     │            │
  │                  │  │ still be a member and   │            │
  │                  │  │ the sweep would redo    │            │
  │                  │  │ it — self-healing.)     │            │
  │                  │  └─────────────────────────┘            │
```

**Scenario B — a loan approval** ($780,000 already approved; `L-9` for $50,000 arrives → new total $830,000: over the alert threshold, under the cap). The interesting content: an in-envelope firing, the transaction-end `never` check, and the queue the tolerances accepted.

```
caller            runtime                    L-9           Institution
  │                  │                        │                │
  │ commitLoanApproval(L-9)                   │                │
  │─────────────────▶│                        │                │
  │     (queue note: this envelope holds the Loan-wide queue — │
  │      both the cap and the alert read every Loan;           │
  │      both declare `tolerates contention`)                  │
  │        ╔═ T1: the approval envelope ══════════════════╗    │
  │        ║ insert LoanApproval ap1                      ║    │
  │        ║ L-9 enters Approved ──▶│  (membership flip)  ║    │
  │        ║                                              ║    │
  │        ║ NotifyCompliance — condition newly true?     ║    │
  │        ║   sum(Approved) was $780k, now $830k;        ║    │
  │        ║   $830k > $800k threshold: entered → fires   ║    │
  │        ║   ┌ C1: the rule's commit (same envelope) ┐  ║    │
  │        ║   │ insert ComplianceAlert c1             │  ║    │
  │        ║   └───────────────────────────────────────┘  ║    │
  │        ║                                              ║    │
  │        ║ transaction-end never check:                 ║    │
  │        ║   $830k > $1,000k cap? no — pass             ║    │
  │        ╚══════════════════════════════════════════════╝    │
  │ accepted (ap1)   │                        │                │
  │◀─────────────────│                        │                │
```

The refusal branch reads straight off the frame: had the new total exceeded the $1,000,000 cap, the `never` check fails, the *entire* frame rolls back — `LoanApproval` and `ComplianceAlert` both, since a frame is one atomic unit — and the caller receives a refusal naming the violated invariant. In the static (tier 1) rendering that branch appears as the `alt` fragment's other arm; the concrete scenario collapses it away.

## Limits

- **Time stays symbolic** ("next `Hourly` tick") until the schedule-definition construct lands — Velle knows cadence names, not durations, so no artifact may imply wall-clock timing.
- **Branch explosion** in deep cascades is real for tier 1; tier 2 (example-grounded) is the answer, not more compact notation.
- **UML's timing diagram proper** (state versus a time axis) maps to a *different* artifact than cascades: one instance's refinement memberships over its lifetime (entered `Delinquent` at commit 3, left at commit 7) — the episode machinery would render it naturally, but it is its own diagram kind, not designed here.

## Threads

- **The within/between pairing.** A sequence diagram is the *within*-envelope view — causality, OQ16's territory (sibling commutation is what licenses the `par` fragment). The contention map (OQ40) is the *between*-envelope view — queueing. Together they answer "what does this system do when things happen," and neither substitutes for the other.
- **Notation is presentation, not semantics.** Whether the render target is strict UML, PlantUML/Mermaid source the engineer's tooling displays, or the ASCII above, the load-bearing content is the derived structure — frames, arrows, guards. Choosing targets is a tooling decision, made per audience.
- **Tests and diagrams from one derivation.** When `example` lands, every generated test scenario should emit its tier-2 diagram; drift between "what the picture says" and "what the test asserts" becomes structurally impossible, since both are rendered from the same derivation.
