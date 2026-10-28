# OQ61 — Whether a `never` may carry an author-supplied sentence in its rejection

**Status:** deferred (2026-10-14) — split out of OQ52 §4, where it had been deferred from OQ47
**In plain terms:** a rejection today renders a readable message from its data: the declaration as the author wrote it, the act, the records in the forbidden state, the record the act was about, and the evaluation. The question is whether the author may also write the sentence a person at the counter should see ("a shipment is never on the books for a cancelled order") and have it stand above that rendering. It only matters if the generated rendering turns out not to be enough, and the alternative is already in place: the engineer builds their own message from the rejection data the act API hands back.
**See:** Reference §21 · `evaluation.md` "Errors, refusals, and retry" (the rejection's data and its default rendering) · `compiler/src/main/kotlin/velle/Rejection.kt`

---

## 1. The question

The smallest syntax is a string after the predicate:

```
never (Shipment where order is CancelledOrder) "a shipment is never on the books for a cancelled order"
```

The rejection would carry it as one more field and the default rendering would put it first.

## 2. Why it waits

Two things have to be true before this is worth a language feature, and neither is known yet:

- **The generated rendering is not sufficient.** Every rejection already renders from its data, and that rendering has not been read by anyone at a counter. Until a real binding wants a sentence the data cannot produce, the feature has no case.
- **Letting the engineer build it is not sufficient either.** `evaluation.md` says the binding code builds a more specific message from the rejection data when it wants to. If that covers every case, the author-supplied sentence is a convenience, not a capability, and a convenience that adds a string to the grammar, the rejection, the testgen, and the diagrams.

**To decide, when a case arrives:** whether the sentence belongs in the spec, where the product owner writes it next to the prohibition, or in the binding, where the engineer writes it next to the display. The spec-side argument is that the sentence is the owner's, the same way the prohibition is. The binding-side argument is that wording is presentation, and the spec declares business facts, not screens.
