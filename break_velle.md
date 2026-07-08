# Stress test: break Velle with a complicated system

Domain: an ICU patient monitoring and medication administration system. Chosen deliberately — it's real, high-stakes (so "the compiler should catch this" actually matters), and it forces several dimensions that the invoice/payment and support-ticket examples never touched, rather than mashing together unrelated hard cases artificially.

## 1. Concurrent writes to the same shape

Two nurses record vitals for the same patient within seconds of each other. Every rule and `produces` guard built so far quietly assumes something like single-writer, one-thing-happens-at-a-time semantics. What does `produces` even mean when two legitimate, independent writes race to satisfy the same refinement first?

>

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
