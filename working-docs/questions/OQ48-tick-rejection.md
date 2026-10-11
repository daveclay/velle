# OQ48 — A scheduled rule that could be rejected by a `never`: error, advisory, or allowed

**Status:** open (2026-10-11). Split out of OQ47 at its close; the v0 behaviour below is built and is a placeholder, not a ruling.

**In plain terms:** a rule on a schedule records something on its own, with nobody asking. If a `never` can forbid what it records, the rule will be refused at the tick, and refused again at every tick after, because nobody is there to change their request. Should the spec be rejected for containing such a rule, should the author be warned at the line and asked to acknowledge it, or should it be allowed and left to the running system to report?

**v0 stance (built 2026-10-11, "for now"):** allowed. The validator says nothing. At runtime the tick finishes its sweep, every firing being its own transaction, then throws `TickRejected` (`compiler/src/main/kotlin/velle/Rejection.kt`) carrying the schedule, the moment, and one `Rejection` per rejected firing — the same data an act's `CommitResult.Refused` carries, with the rule and the record it ran for in the act's place. The harness owns what to do with the exception. Exercised in `examples/shipping/`: a nightly rule cancels unpaid orders and meets `never (Shipment where order is CancelledOrder)` on an order that has shipped (`output/src/test/kotlin/velle/ShippingRejectionsTest.kt`, the two tick tests).

**See:** README §21 (a constraint binds the books; a tick's rejection reaches the harness as an exception) · `evaluation.md` "Ticks" and "Errors, refusals, and retry" · `checks.md` A4 (the read-summary analysis that would detect the case) · `working-docs/investigate-delete.md` R18 (the error-versus-advisory split) · `TODO.md`, the after-commit firing line and the levels-of-advisory line.

---

## 1. The question

Dave, 2026-10-11: the question is whether a rule on a schedule that *could* result in a rejection should be an error, an advisory, or allowed. Everything else here serves that choice.

The case is a spec that says two things at once. The rule says "every night, cancel an order unpaid a week after it was placed". The constraint says "a shipment is never on the books for a cancelled order". For an unpaid order that has shipped, the spec instructs its own runtime to do what the spec forbids. An act in the same position is an outside request the spec turns away; a rule is the spec's own instruction, and turning it away is the spec contradicting itself for that subject.

## 2. What the compiler can see

The validator already computes, for every rule, the shapes its effects create and the fields they assign, and for every `never`, the shapes and fields its predicate reads (the footprint analysis `checks.md` A4 uses; the runtime's `checkNevers` gates on the same data). A schedule-triggered rule whose effects intersect a constraint's reads is detectable today. In the shipping case: `CancelUnpaid` creates a `Cancellation`; the constraint reads `Order.cancellations` through `Shipment.order`; they meet.

What the intersection says is "the firing can reach the constraint", not "the firing can violate it". A rule whose condition already excludes the forbidden case, `UnpaidOrder where ... and not exists (shipments)`, still intersects, and proving that the guard makes violation impossible is an implication over the predicates that the validator does not attempt. So the detection is sound and over-approximate: every rule that can be rejected is flagged, and so are some that cannot.

## 3. The three answers

1. **Error.** The spec is refused until the rule cannot meet the constraint. Case for it: by the recorded split (an error is for meaning the spec leaves undefined; an advisory is for defined meaning the author may not have intended, `working-docs/investigate-delete.md` R18), a spec that instructs and forbids the same record for the same subject arguably has no defined meaning for that subject, and the runtime's "throw and retry forever" is a behaviour nobody wrote. Case against: the over-approximation. An error on a reachable-but-guarded rule blocks a correct spec, and the discharge would have to be either a provable guard (not available) or an acknowledgement spelling, at which point it is an advisory with a harsher default.

2. **Advisory.** The rule is flagged at the line, in the author's names, naming the constraint and the record the firing would produce, with the discharges: guard the condition so the firing skips the forbidden case; move the prohibition to a transient request so the rule's record is never the one constrained; or acknowledge that the firing may be rejected and that the tick's exception is the intended report. Case for it: this is the one place where OQ47's rejected advisory has a hazard beyond phrasing. An act's one-sided refusal is self-correcting through its message; a tick's rejection has no reader to correct it, and the author cannot see the repetition in any message, only at the line. Case against: a spec with many scheduled rules and many constraints may carry many acknowledgements, as A5 does.

3. **Allowed.** Nothing at the line; the runtime throws; the harness reports. Case for it: the behaviour is defined, the data is complete, and the rejection is still a correct refusal of a forbidden state. Case against: the author who did not intend it learns at the first tick in production, repeatedly, and the fix is a spec change that validation could have asked for.

The documenting levels on `TODO.md` (warn at the line down to a generated test or note) are a way to pick 2 without the noise of 1: the detection is recorded, as a generated test that ticks the rule into the constraint and asserts the throw, so the assumption is executable whether or not it also warns.

## 4. Is there a valid use case? (assessed 2026-10-11)

Dave asked whether any spec legitimately wants a scheduled rule that a constraint can refuse. The assessment: no. Every case constructed is better spelled another way, and velle already treats the analogous situation as an error.

The cases, each a spelling problem rather than a design choice:

- *"Try, and let the constraint arbitrate."* The author writes the rule simply ("cancel unpaid orders nightly") and relies on the constraint to stop it where it must. Nothing records the stopped case and nothing ever resolves it: the spec has no decision for the overlap, which is underspecification. The record-and-show spelling exists for exactly this; the rule records what it found instead of being refused.
- *"Don't double-record."* A nightly rule creates a reading per plot, a human already entered one, and a sibling-uniqueness constraint refuses the rule's copy. The intent is valid, but the guard `not exists (...)` is the spelling, and the rule needs it anyway, since a scheduled rule must disarm itself or it fires every tick.
- *"The failure is the alarm."* A watchdog rule whose refusal is the signal: error as control flow, for which velle's pattern is rejection as data.
- *A constraint that reads the clock.* A lapsed permit makes every later transaction touching that state fail, scheduled or not (`checkNevers` checks a clock-reading `never` on every transaction). No rule produces the state, so it is a different question from this one, and the guard on the rule's condition (`trial is PermittedTrial`) is again the better spelling.

Two existing facts point the same way:

1. *A scheduled rule's sweep must be idempotent.* `evaluation.md` "Ticks" says the guards make the sweep idempotent and self-healing, and the generated specs test it ("the guard makes re-evaluation harmless"). A firing that can be rejected is a firing whose guard does not disarm it for that subject, so it retries forever; the rejection is a symptom of an incomplete guard.
2. *Velle already errors on the rule side of a guarantee.* `checks.md` V10 derives an obligation per potential violator, and for a guarantee the writers get an inductive proof that is fail-closed, with one connected diagnostic naming both sides and the discharges: condition the writer, restore within the transaction, or retract the prohibition (README §21). A constraint differs from a guarantee only in also having outside violators; its rule-side writers are in the same position as a guarantee's, and nothing in the design says the spec's own instruction gets a pass because outsiders can break the rule too.

So the answer that fits is **error** (answer 1 of §3), with the guarantee's discharges plus the transient-request one, and with the over-approximation accepted the way the guarantee prover already accepts it: the one real cost is a correct spec whose guard the prover cannot see through, and the guarantee proof carries that cost today. Not yet ruled; recorded as the leading answer.

## 5. What stays open under any answer

- **Delivery.** The exception reaches the harness, whose binding code logs it. The person who can clear the condition is reached only if the binding code routes it. Rule-owner awareness is outside the language (OQ47's ruling); what the runtime offers that logging — a consecutive-rejection count per rule and subject, say — is a smaller follow-on.
- **Repetition.** The rule's guard never disarms, because its effect never committed, so the same subject is selected at every tick. Whether the runtime dampens this (skip a rejected firing until a transaction touches the constraint's footprint for that subject) is a runtime-state question with a cost, and moot if the answer removes the case from valid specs, as §4 proposes.
- **A constraint that reads the clock.** The permit case from the peptides stress pass (`examples/peptides/peptides.velle`, the comment above `PermittedTrial`): declared on the persistent planting, the `never` would be violated the day the permit expired, and every later act touching that state would be refused, not just the tick. No rule is involved, so the detection in §2 does not catch it; it is a `never` whose predicate reads the clock, which the read summary records. Whether it is the same advisory, its own, or a generated test is part of this question.
- **After-commit firings.** `drainAfterQueue` records a rejected after-commit firing as a string in the failures list (`TODO.md`). It has a caller one transaction back whose act already stood. Same detection, one trigger family over; whether it shares the answer is to decide here.
