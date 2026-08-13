# OQ14 — Diagnostic-led guard adoption

**Status:** open — calibration; answered by using v0, not before it
**In plain terms:** when the compiler demands the author write a run-once guard, is the required form pleasant enough that the demand reads as teaching rather than hostility?
**v0 stance:** v0 ships with the fold diagnostics suggesting the full canonical guard form (§18) as the fix; whether that form is pleasant enough is then answered empirically, by how authors respond to being asked to write it.
**See:** README §19 · TODO.md's spec-writing item (the empirical answer)

---

Fold enforcement (README §19) means the compiler will be *proposing* guards to authors mid-error. With guard sugar dropped, the fix-it suggestion writes the canonical form itself, so the question is now exactly this: is the canonical form pleasant enough to be what a diagnostic asks an author to write? Ergonomics and enforcement aren't separable — a required guard that's miserable to write makes enforcement hostile; one that reads as the business rule makes it a teaching moment.

## The exchange in question

The author writes the natural first draft — a fold, unguarded:

```
rule ApplyDeposit when Deposit after commit, Hourly {
    account.balance = account.balance + amount
}
```

The compiler refuses it (§19's fold obligation — the `Hourly` backstop would re-fold every existing `Deposit`):

> this value's correctness depends on each `Deposit` being folded exactly once, and nothing ensures it. Gate the rule on a dischargeable state, e.g.:
>
> ```
> shape UnappliedDeposit = Deposit where not exists DepositApplication for this
> ```
>
> …or define `balance` as a derivation over deposits, add a reconciliation sweep, or declare `tolerates duplication` on the field.

Taking the suggestion, the author must write:

```
shape DepositApplication {
    deposit: one Deposit
    appliedOn: DateTime
}

shape UnappliedDeposit = Deposit where not exists DepositApplication for this

rule ApplyDeposit when UnappliedDeposit after commit, Hourly {
    account.balance = account.balance + amount
    DepositApplication from { deposit: this, appliedOn: now }
}
```

The delta the diagnostic demands: two new shapes (witness + guard refinement), a changed trigger, and a witness-producing line the disarm proof (§18) checks. OQ14 is whether an author hitting this mid-task reads it as "the compiler just taught me the double-deposit bug" or as ceremony — and that's what author reactions will tell us.
