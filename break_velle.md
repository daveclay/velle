# Stress test: break Velle with a complicated system

Domain: an ICU patient monitoring and medication administration system. Chosen deliberately — it's real, high-stakes (so "the compiler should catch this" actually matters), and it forces several dimensions that the invoice/payment and support-ticket examples never touched, rather than mashing together unrelated hard cases artificially.

## 1. Concurrent writes to the same shape

Two nurses record vitals for the same patient within seconds of each other. Every rule and `produces` guard built so far quietly assumes something like single-writer, one-thing-happens-at-a-time semantics. What does `produces` even mean when two legitimate, independent writes race to satisfy the same refinement first?

> The human system design layer of abstraction is to define the requirements around rules/conditions.

### Resolution, part 1: capture-and-aggregate is already the answer for independent contributions

Two nurses recording readings "at the same time" isn't two writes racing for the same field — it's two new shape instances, the same as two `Payment` instances against one `Invoice`:

```
shape VitalReading {
    patient: one Patient
    recordedBy: one Nurse
    systolicBP: integer
    recordedOn: DateTime
}
```

Nothing conflicts, because nothing is overwritten. `Invoice.balance` was already "derived from immutable facts, not stored" — the same idiom, just not previously connected to concurrency. Any data with multiple independent contributors should default to this shape, and the write-conflict question stops applying.

For a genuinely single-owner mutable fact (`Patient.room`), last-in-wins is correct and atomicity of the write is a computer concern, not a human one — a compiled guardrail (same category as forced prepared statements), not new Velle syntax. The one case that looks like it needs a mutable counter with read-modify-write (`availableBeds -= 1`) is itself a signal to model it as events-plus-derivation instead (`Admission`/`Discharge` shapes, `available` derived) rather than inventing a third mechanism — the derived-property idiom structurally discourages the antipattern that causes lost updates in ordinary code.

### Resolution, part 2: the sharper problem is check-then-act atomicity, not field writes

The actually hard case isn't a value getting corrupted — it's a *decision* made against a snapshot of derived state that's stale by the time the effect commits. Concrete case: `available = totalBeds - count(activeAdmissions)`. Two admission requests both check `available > 0`, both see it true because neither's `Admission` has committed yet, both proceed — the ward is now over capacity. `produces` doesn't help: it guards a rule against firing twice *for the same input*, not two *different* inputs racing over a shared, finite resource.

This needs a new rule modifier, `requires`, checked atomically with the rule's effect rather than filtered like an ordinary `where`. It's the concrete form the human's note above takes — the human states the requirement (the invariant), not the concurrency mechanism:

```
rule AdmitPatient on AdmissionRequest produces Admission
    requires count(ward.admissions where ActiveAdmission) < ward.totalBeds
{
    Admission for ward patient: request.patient
}
```

The distinction from `where`: a refinement is purely observational — checking it never blocks anything (per "refinements are pure predicates, not triggers"). `requires` is enforced: the compiler must guarantee that if two concurrent `AdmissionRequest`s would each satisfy it independently but not jointly, only as many succeed as the resource allows. The human states *what* invariant must hold, not *how* to enforce it (lock, transaction, optimistic retry) — that mechanism is pushed into compiled guardrails, consistent with the human/computer-concerns split in Philosophy.

What happens to the request that loses the race doesn't need new machinery: it becomes a `DeniedAdmissionRequest` refinement, the same errors-are-refinements pattern as `FailedCharge`. No `return false`, no exception — a different outcome shape that other rules (waitlist it, notify the requester) can react to.

## 2. Rolling/windowed conditions, not point-in-time ones

"Alert if systolic BP is below 90 for 3 consecutive readings within a 10-minute window." Every refinement built so far (`balance <= 0`, `count(...) >= 3`) evaluates against a snapshot of current data. This needs a condition over an ordered *sequence* of past readings — a fundamentally different shape of predicate than anything in `where` so far.

>

## 3. Event-anchored timeouts, not calendar schedules

"If the on-call nurse hasn't acknowledged within 5 minutes, escalate to the attending physician." This isn't `on Daily` — it's "N minutes after event E, if condition C still holds, do X." The postfix schedule mechanism only handles fixed cadence; it has no notion of a countdown that starts from a specific, arbitrary event.

>

## 4. Rendezvous / join conditions

A medication order requires independent verification from both a pharmacist *and* a nurse before administration is allowed — two separate produced shapes, from two separate people, both required before a third effect fires. `produces` as built is single-evidence, single-guard; nothing yet expresses "wait for two independent things."

>

## 5. Viewer-relative refinements

The same patient chart looks different to the patient, the treating doctor, and billing — some fields redacted, some visible. Every refinement so far is a pure function of the shape's own data plus `today`; this needs a refinement parameterized by *who's asking*, a dimension that doesn't exist yet.

>

## 6. Retroactive invalidation

A lab result was entered wrong, corrected an hour later — but a treatment decision was already made based on the bad value. This is reversal (#5 from `example_invoice_payment.md`) turned up several notches: it's not just "un-flag the account," it's "a past decision was made in a context that's since been proven false," which smells like it needs some notion of causality/provenance Velle hasn't had to reckon with yet.

>
