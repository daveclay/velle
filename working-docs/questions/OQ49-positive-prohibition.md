# OQ49 — `requires`: a requirement stated on a shape, the positive twin of `never`

**Status:** open (2026-10-11) — raised while reading the pharmacy spec's prohibitions; absorbs OQ32 (`requires`, re-derived) the same day; R1–R3 ruled the same day (spelling, rendering, vocabulary); one question open, refinement subjects and totality (§10); §11 is the promotion checklist
**In plain terms:** `never` says which records may not exist. Should the author also be able to say what every record of a shape must satisfy, so that "only a licensed pharmacist verifies a fill" is written as `VerifyFill requires pharmacist is LicensedPharmacist` instead of as a double negative?
**See:** README §21 (`never`; refusal, constraint, guarantee) · README §10 (negation; `is not` as the spelling of non-membership) · README §6 (a relationship targets a base shape, never a refinement) · README §4 "Every request gets a response" · README §18 "No guard sugar" and §12 "No act-level sugar" (the two sugars the language refused, and why) · README §22 (the deferred `requires` item) · `grammar.md` `neverDecl`, `declaration` · `checks.md` F2 (the diagnostic that prescribes a `never` on the creating act), A4 · `evaluation.md` U3 (serialization) · `testgen.md` (the `never` row of the case catalog) · `examples/pharmacy/pharmacy.velle`

---

| ruling | date | one line |
|---|---|---|
| R1 | 2026-10-11 | membership after `requires` is spelled `is`; no `to be` alias (§7) |
| R2 | 2026-10-11 | a violated requirement renders as the requirement unmet, in the author's polarity (§8) |
| R3 | 2026-10-11 | one noun: a `requires` declares a prohibition, its complement (§9) |

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

## 7. `to be` as a membership spelling

**The question.** Should `to be <Refinement>` be admitted after `requires` as an alias for `is <Refinement>`?

The language has one spelling of membership, `is`, and one of non-membership, `is not` (README §10, the spelling the diagnostics and examples use). The general form of the declaration must take an ordinary predicate, because most requirements are not membership tests. Drawn from `examples/pharmacy/pharmacy.velle`:

```
expose shape Staff {
    name: text
    role: text
}

shape Pharmacist = Staff where role == "pharmacist"
shape LicensedPharmacist = Pharmacist where exists (licenceRenewals where expiresOn >= today)

expose transient shape VerifyFill {
    fill: one Fill
    pharmacist: one Staff
    outcome: text
    note: text
}

VerifyFill requires pharmacist is LicensedPharmacist                  -- the ordinary predicate
VerifyFill requires pharmacist to be LicensedPharmacist               -- the alias under consideration
VerifyFill requires outcome == "approved" or outcome == "rejected"    -- a requirement no `to be` can spell
```

The alias reads as English in the one position it is allowed. The cost shows up in three places:

- **Negation.** A requirement over non-membership invites `not to be`: `FillPrescription requires lot not to be ExpiredLot`, beside the one spelling `lot is not ExpiredLot`. Admitting `to be` and refusing `not to be` is a rule a reader has to learn; admitting both is a second spelling of non-membership.
- **Rendering.** The rejection renderer and the validator's diagnostics quote predicates as `is` / `is not` (`compiler/src/main/kotlin/velle/Rejection.kt`, the `evaluation:` block). A requirement written `to be` would be read back as `is`, so the author's sentence and the person's sentence would differ by a word.
- **Scope.** `by`, `with`, and `required` are contextual words because each reads as a keyword in exactly one grammatical position (`grammar.md`, keywords). `to be` fits that pattern. `to` is otherwise unused.

**Ruled (R1, 2026-10-11).** `is`. No alias; the one spelling of membership and of non-membership holds in this position as everywhere else.

## 8. Diagnostics: how a violation is reported

**The question.** When a requirement is violated, is the rejection rendered as the requirement unmet, or as the complement refinement inhabited?

Today's renderer speaks in the prohibition's terms. A technician's `VerifyFill` against the current `never` renders roughly as:

```
VerifyFill refused: this VerifyFill would be a VerifyFill where pharmacist is not LicensedPharmacist.
  never #14: VerifyFill where pharmacist is not LicensedPharmacist
  this VerifyFill: fill = Fill#3, pharmacist = Staff#17, outcome = "approved", note = ""
  through Staff#17: name = "Ana", role = "technician"
  evaluation:
    Staff#17 is not LicensedPharmacist
```

Two renderings are possible for `VerifyFill requires pharmacist is LicensedPharmacist`:

```
-- (a) the requirement unmet, in the author's polarity
VerifyFill refused: this VerifyFill requires pharmacist is LicensedPharmacist; Staff#17 is not.
  requires #14: VerifyFill requires pharmacist is LicensedPharmacist
  ...

-- (b) the complement inhabited, in the normalized form
VerifyFill refused: this VerifyFill would be a VerifyFill where not (pharmacist is LicensedPharmacist).
  never #14: VerifyFill where not (pharmacist is LicensedPharmacist)
  ...
```

Rendering (b) is what falls out of normalizing at the parser and changing nothing else. It also throws away the reason the construct exists: the rejection is first-class information rendered from the author's declaration (README §21), and (b) renders a declaration the author did not write. Rendering (a) means the `Rejection` carries the declaration's polarity, or the source text, alongside the normalized predicate.

The same choice recurs in the validator. The guarantee prover's diagnostic today reads "`SuspendManually` can end a transaction violating `never (Account where balance >= 0 and suspended)`"; for a requirement it should quote the requirement. V29's diagnostic for a scheduled rule that creates a record a constraint consults, likewise.

**Ruled (R2, 2026-10-11).** (a). The `Rejection` carries the declaration as written, and the rendering quotes it; the `evaluation:` block already shows the membership test that failed in `is not` terms, so the complement is visible to anyone who needs it. The validator's diagnostics quote the requirement the same way.

## 9. Vocabulary: what a `requires` declares

**The question.** README §21 opens "a `never` declares a prohibition, and a prohibition is one of three things, by who it binds." Does a `requires` declare a prohibition too, or does the glossary gain a second noun?

The two spellings are one statement. From `examples/shipping/shipping.velle`:

```
expose shape Order {
    reference: text
}

shape CancelledOrder = Order where exists (cancellations)

expose shape Shipment {
    order: one Order
}

never (Shipment where order is CancelledOrder)        -- today
Shipment requires order is not CancelledOrder          -- the same constraint
```

Either spelling rejects a shipment arriving for a cancelled order and a cancellation arriving for a shipped order alike, names the shipment either way, and is spendable as a proof input once established. Two ways to say that in the README:

- **One noun.** "A `never` or a `requires` declares a prohibition; a requirement's prohibition is its complement." Refusal, constraint, and guarantee stay as they are. The glossary's *prohibition* entry grows one clause.
- **Two nouns.** *Requirement* joins *prohibition* in the glossary, each with the three kinds beneath it. Every sentence in §21 that says "the prohibition" has to say "the prohibition or requirement", or pick one.

**Ruled (R3, 2026-10-11).** One noun. A `never` or a `requires` declares a prohibition; a requirement's prohibition is its complement. The split that matters is by subject, and the polarity is spelling.

## 10. Refinement subjects and totality

**The question.** May the subject of a `requires` be a refinement, and does the form let an author assert that a refinement is total by accident?

A refinement subject is the natural way to state a requirement that holds only in a state. From `examples/pharmacy/pharmacy.velle`:

```
expose shape Drug {
    name: text
    schedule: integer
}

shape ScheduleTwo = Drug where schedule == 2

shape Prescription {
    drug: one Drug
    refillsAuthorized: integer
}

shape ScheduleTwoPrescription = Prescription where drug is ScheduleTwo

never (Prescription where drug is ScheduleTwo and refillsAuthorized > 0)     -- today
ScheduleTwoPrescription requires refillsAuthorized == 0                       -- the same constraint, on the state
```

The desugaring is unchanged: `never (ScheduleTwoPrescription where refillsAuthorized != 0)`. The subject may be any shape or refinement name, the same set `never SomeRefinement` accepts today.

Totality is not an accident the form invites; it is a spelling the form allows on purpose. "Every staff member is a pharmacist, a technician, or an administrator" is a requirement over the base shape whose predicate is a membership test on `this`:

```
shape Pharmacist = Staff where role == "pharmacist"
shape Technician = Staff where role == "technician"
shape Administrator = Staff where role == "admin"

Staff requires this is Pharmacist or this is Technician or this is Administrator
```

That is today's `never (Staff where role != "pharmacist" and role != "technician" and role != "admin")` stated over the refinements instead of the text values, and it is the closest the language gets to the enumeration without an enumerated scalar type (§6 above). The author wrote `this is`, so nothing is asserted by accident. What `always SomeRefinement` would have allowed, and this form does not, is a bare refinement name standing for "every instance is a member" with no predicate on the page.

**Recommendation.** Any shape or refinement name as subject; `this is R` as a predicate is ordinary and stays legal.

## 11. Promotion at settling

Nothing here is a decision. Each item follows from the sentence form (§2) and R1–R3, and is listed so the settling pass has its checklist.


**Grammar.** A new top-level declaration:

```
declaration  := shapeDecl | refinementDecl | ruleDecl | neverDecl | requiresDecl | exposeDecl
requiresDecl := ShapeName "requires" predicate ("tolerates" "contention")?
```

Every declaration today begins with a keyword; this one begins with a shape name, which one token of lookahead settles. `tolerates contention` carries over from `neverDecl`: it answers the A5 serialization-width advisory for a read the declaration makes (`checks.md` A5, `evaluation.md` U3), and a requirement reads exactly what its complement reads, so the clause means the same on either spelling.

**Reserved word.** `requires` joins the keyword list in `grammar.md`, so `requires: boolean` stops being a legal property. No example spec uses it as an identifier.

**F2's diagnostic.** A refinement-typed field is refused today with "State the condition on the act that creates 'VerifyFill' — a gate refinement or a never" (`compiler/src/main/kotlin/velle/Validator.kt`). The role-gate case is the requirement's home, so the diagnostic should offer it first:

```
expose transient shape VerifyFill {
    pharmacist: one Pharmacist              -- F2: relationships target base shapes, not refinements
}

-- the diagnostic's prescribed rewrite
expose transient shape VerifyFill {
    pharmacist: one Staff
}
VerifyFill requires pharmacist is Pharmacist
```

**README.** §21 gains the requirement as a spelling (R3); §6's "a gate refinement or a `never` over the act" names the requirement; §22's deferred `requires` item is removed, since the construct has landed with a different subject. The merged OQ32 leaves no trail (history is git).


**Generated cases.** `testgen.md`'s catalog derives two cases from a `never` with a single comparison against a literal on an exposed act. The same row derives from a requirement by inverting the comparison.

Today, from `examples/pharmacy/pharmacy.velle` and its `output/SPEC_INDEX-Pharmacy.md`:

```
expose transient shape SetReorderPoint {
    drug: one Drug
    requestedBy: one Staff
    reorderPoint: integer
}

never (SetReorderPoint where reorderPoint < 0)

-- generated case titles
-- never - a SetReorderPoint where reorderPoint below 0 is refused
-- never - a SetReorderPoint with reorderPoint 0 is accepted
```

The requirement form inverts the comparison, and the derivation has to pick the failing and passing values from the inverted side:

```
SetReorderPoint requires reorderPoint >= 0

-- generated case titles
-- requires - a SetReorderPoint with reorderPoint -1 is refused
-- requires - a SetReorderPoint with reorderPoint 0 is accepted
```

Three details for the generator: the title's keyword follows the author's spelling (R2); the boundary value is found on the complement (`>= 0` fails first at `-1`, where `< 0` failed at `-1` too, but `> 0` fails at `0`); and a requirement written as a disjunction of literals (`VerifyFill requires outcome == "approved" or outcome == "rejected"`) is the same shape as the enumeration `never` the catalog already declines to derive from, so the row's "single comparison" scope is unchanged.
