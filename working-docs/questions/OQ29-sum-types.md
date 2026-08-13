# OQ29 — Sum types / union shapes

**Status:** open — workaround exists (two optional fields + xor `never`)
**In plain terms:** can a field point at "either an Alert or a prior Escalation" without faking it as two optional fields plus an exclusivity invariant?

---

A field anchored to *either* of two shapes (an `Escalation` chaining from an `Alert` or a prior `Escalation`); left open by the retired `example_predicates.md`'s Datalog comparison — its remaining residue. Today's workaround is two optional fields plus a `never` xor-invariant; whether that is the idiom or a union construct is warranted is undecided.

An adjacent early sketch ("shape interfaces" — a refinement as a union of shapes sharing fields) survives in `random_notes.md`:

```
shape Vehicle = Car | Truck | Bicycle | Boat

rule DriveVehicle when Vehicle {
    startedOn: now      -- startedOn is shared across the union, so valid
}
```

Whether unions-as-field-anchors and unions-as-rule-subjects are one design or two is part of the question.
