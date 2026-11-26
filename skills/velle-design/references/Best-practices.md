# Best practices

This page collects ways of writing a spec that keep it readable by the product owner and the engineer who wrote it together. Each practice is a choice between two spellings the compiler accepts alike. After reading this page you can pick the spelling that reads closest to what the product owner said.

## Name the parts of a long condition

A rule or a `never` ([§21](Reference-21-never)) is easiest to read when its condition is a few named [refinements](Reference-08-Refinements) joined by `and`, `or`, and `not`. A long condition written out in full makes the reader work out what it means. Give each business idea in it a name with `shape ... = ... where`, then use the names.

A pharmacy says "a fill is not handed over while counselling is due. Counselling is due on a verified fill when its drug needs counselling or it is the patient's first fill, until a pharmacist has counselled the patient."

Written out in full, the refusal is one line the reader has to take apart:

```
never (PickUp where fill is VerifiedFill and (fill.lot.drug.requiresCounselling or fill.fillNumber == 1) and not exists Counselling for fill)
```

This example is not in the example files. It compiles in place of the spelling below and means the same thing.

With the business idea named, from `examples/pharmacy/pharmacy.velle`:

```
transient act PickUp {
    fill: one Fill
    collectedBy: text
    amountPaid: decimal
}

shape VerifiedFill = Fill where exists (verifications where outcome == "approved")

shape CounsellingDue = VerifiedFill where
    (lot.drug.requiresCounselling or fillNumber == 1) and not exists Counselling for this

never (PickUp where fill is CounsellingDue)
```

- `PickUp` is the patient collecting a fill.
- `VerifiedFill` names "a fill a pharmacist has approved."
- `CounsellingDue` names "a verified fill that still owes counselling." Its condition reads the fill's own fields, because a refinement's condition is about one record.
- The `never` is now the product owner's sentence: a pickup is refused when the fill is one with counselling due.

What the names give the reader:

- **The prohibition reads as a sentence.** A reviewer checks `never (PickUp where fill is CounsellingDue)` against what the product owner said without parsing it.
- **Each part is checked on its own.** The product owner can confirm what "counselling due" means once, in one place, apart from the pickup.
- **A name is used again.** A query, another rule, or a [composite](Reference-09-Composing-refinements) reads `CounsellingDue` instead of repeating the condition, so the meaning cannot drift between copies.
- **The engineer sees the same word.** The generated code carries each refinement by name, so a fill read by the engineer's code carries `isCounsellingDue` ([Read API](Read-API)).
- **A name costs nothing.** A refinement stores nothing and fires nothing ([§8](Reference-08-Refinements)).

Names also compose. A prohibition built from names reads as a list of business reasons. From `examples/pharmacy/pharmacy.velle`:

```
shape ExpiredLot     = StockLot where expiresOn < today
shape QuarantinedLot = StockLot where quarantines is not empty

never (FillPrescription where lot is ExpiredLot or lot is QuarantinedLot)
```

The `never` says "a prescription is not filled from an expired or quarantined lot," which is the sentence the pharmacy would say.

### When a condition can stay inline

A short comparison on the record's own fields reads well as it is. From `examples/pharmacy/pharmacy.velle`:

```
never (PickUp where amountPaid < fill.amountDue)
```

Name a condition when it is a word the business already uses, when it reaches through more than one relationship, when it mixes `and` with `or`, or when a second rule or query needs it too.

## Related pages

- [§8 Refinements](Reference-08-Refinements), for `where` and what a refinement is.
- [§9 Composing refinements](Reference-09-Composing-refinements), for joining names with `and`, `or`, and `not`.
- [§10 Predicate expressions](Reference-10-Predicate-expressions), for what a condition may say.
- [§21 never](Reference-21-never), for refusals and constraints.
