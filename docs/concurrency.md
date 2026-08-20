# Concurrency: consequences of the settled design

**What this document is.** The serialization-domain design is settled: the compiler derives, for every act, the queue keys its work must wait its turn on (`evaluation.md` U3), and a read that correlates to no key widens the domain to a whole shape under the A5 advisory (`checks.md`). This document is not that design. It records the *consequences* of settled decisions — the situations an author actually lands in, what the surfaces show them there, and why the rulings sit where they do. It grows as accepted velle accumulates consequences worth naming; the open questions stay in `working-docs/QUESTIONS.md`.

## Wide domains: the two authors

A **wide domain** (a *widening*) is the fail-closed answer the derivation gives when a read correlates to no key. Rather than guess which rows the read depends on, the compiler declares: every commit touching this shape shares one queue. The A5 check warns, naming the read and the declaration carrying it, and lists the discharges — correlate the read (a model fact the model was missing), move the rule to a schedule, or declare `tolerates contention`.

Two very different authors receive that warning, and the advisory-versus-required ruling is really a decision about which of them to serve by default.

### The author who meant it: serialization is the feature

Some computations are serial by nature, and no compiler setting can make them concurrent:

```
expose shape Order {
    amount: decimal
}

shape OrderNumber {
    order: one Order
    n: int
}

rule AssignOrderNumber when Order {
    OrderNumber from { order: this, n: count(OrderNumber) + 1 }
}
```

Handing out sequential numbers cannot run two at a time — if it did, two orders would read the same count and take the same number. The `count(OrderNumber)` read must see every row, the domain widens to the whole `OrderNumber` shape, and the resulting global queue is not a defect: it is the requirement, stated in the only vocabulary the requirement has.

The consequence for this author: the advisory tells them what they already know, and `tolerates contention` is the one-line declaration that turns the knowledge into record — the warning goes quiet, and the contention map shows the queue as deliberate rather than suspicious. Neither position of the severity dial troubles this author; at most, *required* would cost them the one declaration up front.

### The author who didn't: a global lock hiding in a guard

The other author writes a read that looks nothing like a lock:

```
expose shape Order {
    amount: decimal
}

shape Promotion {
    startsOn: Date
    endsOn: Date
}
expose Promotion

shape Discount {
    order: one Order
    rate: decimal
}

rule ApplyPromotion
    when (Order where exists (Promotion where startsOn <= today and endsOn >= today)) {
    Discount from { order: this, rate: 0.1 }
}
```

The intent is "orders placed while a promotion is live get a discount." But the promotion read correlates to no key — nothing links an order to a promotion, and date comparisons are not correlations — so every order commit widens to the whole `Promotion` shape. Since every order commit carries the same widening, the entire order intake shares one queue: a global lock nobody asked for, hiding inside a guard that reads like a harmless config check.

The consequence for this author: a throughput ceiling of one commit at a time, and under the advisory ruling the compiler will not stop them from shipping it — the warning is the only line of defense. The natural discharge here is the schedule move: a rule fired on a tick prices the wide read once per tick instead of inside every commit (and a width living only in a schedule-fired rule's own firing never warns — the cadence discharge). A promotion going live once per tick is plenty.

## Why the width warning is an advisory

Ruled 2026-08-18, and reaffirmed at the resolution of OQ42 item 4: **the dial stays at advisory.** A wide domain's failure is throughput, not a wrong value — the system stays correct, only slower — so failing open costs less here than anywhere else in the tolerance family. The *required* position remains arguable (the comprehension framing that produced the obligation leans that way, and the accidental author above is its whole case), and the ruling is held deliberately revisitable: stress-testing against realistic specs follows once the OQ16 calibration question resolves.

## Where a width shows up

An author or engineer meets a wide domain on three surfaces:

- **At compile time**: the A5 warning, naming the uncorrelated read, the declaration carrying it, and the discharges.
- **On the contention map** (`diagrams.md`): the ⚠-marked rows, readable by both the engineer and the product owner.
- **On the generated commit function**: the queue-key contract ("Queue key: [...]"), where a whole-shape queue is visible to the engineer wiring the store. `DomainKeys.kt` in the compiler is the reference implementation of evaluating that contract against a concrete commit — resolve each path key to a row, each value key to the committed value, and conflict when any resolved token is shared. The evaluation moment is part of the contract (`evaluation.md` U3): keys resolve *at admission*, against pre-commit state plus the act's own inputs — and pre-commit state includes captured members (committed state recorded at membership entry, typically real columns), so a key may hop through a capture and the store must keep captures readable at admission time (ruled 2026-08-19).
