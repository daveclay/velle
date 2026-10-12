# OQ49 — `requires`: a requirement stated on a shape, the positive twin of `never`

**Status:** open (2026-10-11) — raised while reading the pharmacy spec's prohibitions; absorbs OQ32 (`requires`, re-derived) the same day
**In plain terms:** `never` says which records may not exist. Should the author also be able to say what every record of a shape must satisfy, so that "only a licensed pharmacist verifies a fill" is written as `VerifyFill requires pharmacist is LicensedPharmacist` instead of as a double negative?
**See:** README §21 (`never`; refusal, constraint, guarantee) · README §10 (negation; `is not` as the spelling of non-membership) · README §6 (a relationship targets a base shape, never a refinement) · README §4 "Every request gets a response" · README §18 "No guard sugar" and §12 "No act-level sugar" (the two sugars the language refused, and why) · README §22 (the deferred `requires` item) · `grammar.md` `neverDecl`, `declaration` · `checks.md` F2 (the diagnostic that prescribes a `never` on the creating act), A4 · `evaluation.md` U3 (serialization) · `testgen.md` (the `never` row of the case catalog) · `examples/pharmacy/pharmacy.velle`

---

## 1. The evidence

The pharmacy spec declares 40 prohibitions. 18 of them have `is not` or `!=` as the main connective of their predicate: a positive requirement written inside out. They fall into three families.

**Role gates on transient acts.** Every "who may do this" check is a double negative:

```
never (VerifyFill where pharmacist is not LicensedPharmacist)
never (FillPrescription where filledBy is not Technician and filledBy is not Pharmacist)
never (SetReorderPoint where requestedBy is not Administrator and requestedBy is not Pharmacist)
```

Nine of the 18 are of this shape. The structural alternative, typing the field `pharmacist: one Pharmacist`, is refused by the language (README §6, `checks.md` F2): a relationship targets a base shape, because a refinement's membership drifts and a refinement-typed field would be violated by a commit that never wrote it. README §6 routes the business sentence to "a gate refinement or a `never` over the act". So the language sends the most common refusal in the spec to the one spelling where `never` reads worst.

**Enumerations.** A value drawn from a fixed list, written as the conjunction of everything it is not:

```
never (Staff where role != "pharmacist" and role != "technician" and role != "admin")
never (Interaction where severity != "major" and severity != "moderate")
never (VerifyFill where outcome != "approved" and outcome != "rejected")
never (Adjudication where outcome != "paid" and outcome != "rejected")
```

**Definitions and agreements.** An equality with its sign flipped:

```
never (Fill where fillNumber != count(prescription.fills where fillNumber <= this.fillNumber))
never (FillPrescription where lot.drug != prescription.drug)
never (Discontinuation where prescriber != prescription.order.prescriber)
```

The other 22 read well as prohibitions. Sign and range checks (`never (PriceChange where unitPrice < 0)`, `never (Prescription where quantity <= 0)`), self-reference (`never (Interaction where drugA == drugB)`), and forbidden combinations (`never (Prescription where drug is ScheduleTwo and refillsAuthorized > 0)`) are naturally negative sentences. The owner's books rule is "the records never look like this", and for those the spelling matches the sentence.

## 2. The candidate

A top-level declaration whose grammatical subject is a shape or refinement name, followed by an ordinary predicate:

```
VerifyFill requires pharmacist is LicensedPharmacist
FillPrescription requires filledBy is Technician or filledBy is Pharmacist
Fill requires fillNumber == count(prescription.fills where fillNumber <= this.fillNumber)
ScheduleTwoPrescription requires refillsAuthorized == 0
```

`X requires P` means every X satisfies P, which is `never (X where not P)`. The compiler normalizes to emptiness and everything downstream of the parser is unchanged: enforcement at the boundary and at transaction end, the guarantee prover, spending an established prohibition as a proof input, V29. The three kinds of prohibition are still told apart by the subject, exactly as README §21 does it: a transient act gives a refusal, a persistent shape gives a constraint, rule-written data gives a guarantee.

A first draft of this question proposed `always (X where P)`, keeping `never`'s argument form. The sentence form was preferred (2026-10-11) for three reasons: the shape stands first, which is how the kinds are distinguished; there is no `where` and no parenthesis to read past; and the named-refinement form that `always SomeRefinement` would have inherited from `never SomeRefinement`, asserting a refinement is total, does not arise, because a refinement name here is the subject rather than the whole statement.

## 3. Why it is sugar, and why the earlier sugar refusals do not apply

The desugaring is one `not`. The predicate grammar already has general negation, `is not` is defined as the same predicate as `not (... is ...)` (README §10), and predicates are two-valued: dot access through an optional must be narrowed first, so there is no absent-value case where `X requires P` and `never (X where not P)` could disagree.

The language has refused sugar twice, each time for a reason that does not hold here:

- **No guard sugar** (README §18): there was no common desugaring. Every guard is a triple whose correlation predicate differs per use case, so a sweeter phrase would either grow a clause for the join or hide a product decision. Here there is exactly one desugaring and it hides nothing.
- **No act-level sugar** (README §12): the positional meaning of `=` was load-bearing, and the sugar abbreviated a rarity. Here nothing positional is spent, and the case is 18 of 40 prohibitions in a realistic spec.

## 4. What is gained

Rejections are first-class human-facing information rendered from the author's declaration (README §21). A refusal of a `VerifyFill` by a technician would render from `VerifyFill requires pharmacist is LicensedPharmacist` as a requirement that was not met, and from the `never` as a double negative that was true. For a refusal that is the difference between the product owner's own sentence and a translation of it.

## 5. The old `requires`, and why this is its re-derivation

OQ32 deferred `requires` past v0 as "the rule modifier that demands a condition hold, as against `where`, which filters", to be re-derived rather than restored. It was born in the July working notes for a bed-capacity case: two admission requests both read "beds available", both pass, both commit, and the ward is over capacity. The remedy was a modifier on the admitting rule, `requires count(ward.admissions where ActiveAdmission) < ward.totalBeds`, enforced atomically with the rule's effect, with the losing request becoming a refinement other rules answer. Three things defined it: it attached to a rule, it was atomic, and the loser was recorded.

Each of those jobs has since found a home, and none of them is a rule modifier:

- **Atomicity** is the serialization clause of the transaction contract (`evaluation.md` U3). Two envelopes that read the same data take turns, and write skew is named there as the failure snapshot isolation permits. The spec never asks for it.
- **The bound over the aggregate** is a constraint on the ward, `never (Ward where count(admissions where ActiveAdmission) > totalBeds)`, enforced at the boundary and at transaction end and binding whoever would break it, where a modifier on one rule would bind that rule only, and a second admitting path added later would not inherit it.
- **The loser's fate** is the author's refuse-or-record choice (README §21): refuse with a prohibition over the act, or record by partitioning the transient act on the condition, which is evaluated once at arrival.

A rule-level precondition also has no coherent failure case. Acts persist when they commit. A rule whose precondition fails leaves a committed act that nothing answered. For a transient act that is what README §4's "every request gets a response" forbids; for a persistent act it is the unhandled record `checks.md` A4 surfaces. The language already says what an answer is: a rule's condition, or a `never` over the act. A modifier on the rule would be a third spelling hiding which of those two the author meant.

So the keyword re-enters with a different subject. `requires` on a rule is dropped as subsumed; `requires` on a shape is the construct this question proposes. One keyword, one meaning. Neither `requires` nor `always` is used as an identifier in any example spec.

## 6. What it would not fix

The enumeration family is better in the positive form but still wrong. `Staff requires role == "pharmacist" or role == "technician" or role == "admin"` checks a text field; the structural answer is an enumerated scalar type, which makes the bad value impossible instead of checked. That belongs with extensible data types (README §22), not here. The requirement form should be motivated by the role gates and the definitions, and must not become the reason the enumeration gap stays open.

## 7. Questions to settle

1. **`to be` as a membership spelling.** `VerifyFill requires pharmacist to be LicensedPharmacist` reads better than `... pharmacist is LicensedPharmacist`. The language has one spelling of membership, `is`, and one of non-membership, `is not`, and the general form of this declaration must take an ordinary predicate because most requirements are not membership tests (`PriceChange requires unitPrice >= 0`). Decide whether `to be X` is admitted as a contextual alias for `is X` in this one position, like `by` and `with` are contextual elsewhere, accepting that the rendered rejection, the diagnostics, and the glossary would say `is` for a predicate the author wrote as `to be`. Recommendation: settle the keyword first with `is`, and take this as a separate yes-or-no.
2. **Diagnostics.** The rendered rejection message should follow the author's polarity. The validator's diagnostics quote `is not` as the spelling of non-membership (README §10); decide whether a violation of a requirement is reported as the requirement unmet or as the complement refinement inhabited.
3. **Vocabulary.** README §21 opens "a `never` declares a prohibition". Either both declarations declare a prohibition, with the complement taken for `requires`, or the glossary gains a second noun (requirement). The first keeps the three-way split as it is.
4. **Generated cases.** `testgen.md`'s catalog derives two cases from a `never` with a single comparison against a literal on an exposed act. The same derivation applies to a requirement with the comparison inverted; the generated case titles name the keyword and would need both.
5. **Surface.** `grammar.md`'s `declaration` gains `requiresDecl := ShapeName "requires" predicate`; every top-level declaration today begins with a keyword and this one begins with a shape name, which one token of lookahead settles. `requires` becomes reserved. `checks.md` F2's diagnostic, which today offers "a gate refinement or a `never` over the act", would offer the requirement first for the role-gate case. README §22's deferred `requires` item is rewritten or removed at settling.
6. **Refinement subjects and totality.** `ScheduleTwoPrescription requires refillsAuthorized == 0` has a refinement as subject. Confirm that the subject may be any shape or refinement name, and that nothing in the form lets an author assert a refinement is total by accident.
