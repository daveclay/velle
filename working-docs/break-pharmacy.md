# Breaking v0 with a domain: a retail pharmacy

**Status:** first pass complete (2026-10-11) — the spec validates, runs in memory and over SQLite through both store surfaces, and every finding below is either fixed in the compiler, spelled around in the spec with the cost recorded, or handed on as a TODO item or a question for Dave. Scorecard at the bottom.
**Residue:** the TODO items under "Pharmacy stress pass" in `TODO.md`, and the one question in "What needs Dave's input" below (a wording conflict between README §21 and `checks.md` V18 about when a refusal is checked).

---

The peptides pass (`break-peptides.md`) was the first spec written against a whole business rather than around one construct. This is the second, with a different shape of business: not a pipeline with a time series at every stage, but a workplace where four kinds of people act on the same records — customers (patients), pharmacists, assistants (pharmacy technicians), and administrators — and where most of the rules are about who may do what, in what order, and what is answered how. The spec is `examples/pharmacy/pharmacy.velle`; its generated module, the hand-written givens, and the flow tests are under `examples/pharmacy/output/`.

What the domain brings that no earlier example did:

- **A request that arrives as several records.** A prescriber's order carries one or more prescriptions. The order and its prescriptions are one commit through the exposure closure (`expose PrescriberOrder with prescriptions`), and the prohibitions on a prescription (no refills on a schedule II drug, at most five on schedules III to V) turn the whole order away.
- **Relationships that run both ways and across edge sets.** A drug interaction is one fact about a pair of drugs, found from either drug; an allergy is a match between a drug's ingredients and a patient's allergies, two declared `many` edge sets with no shape in common but the ingredient.
- **Role-gated acts.** Only a licensed pharmacist verifies; nobody verifies a controlled fill they filled themselves; only an administrator withdraws a price; a technician or a pharmacist fills and counts. Every one of these is a refusal over a transient request.
- **Two kinds of answer at the counter.** A fill request is turned away with nothing kept when the data is wrong (the wrong drug's lot, an expired lot, more than was prescribed), and answered on record with a reason when the patient's situation is the problem (no refills left, expired, discontinued, an unresolved conflict), so the person at the counter can explain.
- **Time in the records, not in the rules.** A prescription expires by age; a licence lapses; a fill left at the counter goes back to stock after ten days; a price change takes effect on its date; a monitoring check is good for a day.

Verdict scale, as in `break-peptides.md`: **HOLDS** (the language handles it), **TAX** (it handles it at a cost — a restructured spelling, a coarse check to work around), **GAP** (a check, the runtime, or the generator is wrong or missing, and something had to change).

## The findings

Each finding names the checked-in artifact that shows it. Numbers P1–P14; the scorecard at the end lists them all.

### P1 — GAP, fixed: the time-dependence check (V3) refused every partition of a transient act over a time-dependent state

A fill request is answered by two rules partitioned on the prescription's state:

```
expose transient shape FillPrescription {
    requestKey: text
    prescription: one Prescription
    lot: one StockLot
    quantity: integer
    fillNumber: integer
    filledBy: one Staff
    dispensable: boolean = if prescription is Fillable and quantity <= lot.onHand then true else false
}

shape ActivePrescription = Current where expiresOn >= today
shape Fillable = ActivePrescription and Cleared

shape ApplicableFill = FillPrescription where dispensable
shape RefusedFill    = FillPrescription where not dispensable

rule RecordFill when ApplicableFill { ... }
rule RefuseFill when RefusedFill { ... }
```

`Fillable` reads `today` through `ActivePrescription`. The validator's V3 check reported, for both rules, "its condition depends on the passage of time, which no act commit changes: entry by aging is unobserved. Add a schedule to 'on'". The same diagnostic hit the refill request's two rules and the price withdrawal's two rules — seven rules in all. The prescribed fix cannot be applied: a schedule on a rule whose subject is a transient act is itself an error (V17), because the act is gone when the tick runs. The two checks contradicted each other for any transient request partitioned on a time-dependent state, which is the ordinary way to say "is this prescription still good today?" at the counter.

The diagnostic is wrong for a transient act: its partition is evaluated exactly once, at its own commit (README §4, "Transient acts"), so there is no later moment at which it could age into a state. Fixed in `compiler/src/main/kotlin/velle/Validator.kt` (`checkReachability` skips the time-dependence error when every base of the rule's condition is transient). Probe: `V3 - a transient act partitioned on a time-dependent state is read at the request, not by aging` in `compiler/src/test/kotlin/velle/ValidatorTest.kt`. The compiler suite's failing set is unchanged by the fix (the sixteen failures `TODO.md` already lists).

### P2 — GAP, fixed: the new uncalled-firing check (V29) paired tick rules with refusals over transient acts

V29 (settled 2026-10-11) refuses a scheduled or after-commit rule that creates a record a prohibition consults, since the firing has no caller to hand a rejection to. It reported eleven pairs in this spec, among them:

```
rule QuarantineExpired when (ExpiredLot where onHand > 0 and not exists Quarantine for this) on Nightly {
    Quarantine from { lot: this, reason: "expired" }
}

never (FillPrescription where lot is ExpiredLot or lot is QuarantinedLot)
```

"rule 'QuarantineExpired' on Nightly creates a Quarantine, which never #27 (FillPrescription where ...) consults — the firing can end its transaction in the forbidden state with no caller to refuse". It cannot: the prohibition is a refusal over a transient act (README §21), and no `FillPrescription` instance exists at a tick for the firing to leave in any state. The same false pairing hit `ReturnUncollected`, `RemindLicence`, `Reorder`, `NagRefillTask`, `QueueAutoRefill`, `NoticeExpiry`, `FollowUpClaim`, and `SnapshotControlled`.

Fixed in `Validator.kt` (`checkUncalledCreations` skips a prohibition whose base shape is transient). Probe: `V29 - a refusal over a transient act binds no tick - a scheduled rule creating a record it consults is allowed` in `ValidatorTest.kt`; the probe keeps a constraint over a persistent shape in the same spec and asserts it is still refused, so the fix narrows the check to refusals only.

### P3 — TAX, by design — and a question for Dave: a refusal that reads a value the request's own handling moves turns its own request away

Four refusals in the first draft were about the request's moment and read values the request's own consequences change:

```
never (FillPrescription where fillNumber != prescription.fillsRecorded + 1)   -- the next number
never (FillPrescription where quantity > lot.onHand)                          -- what the shelf holds
never (VerifyFill where exists Verification for fill)                         -- verified once
never (PickUp where fill is not ReadyForPickup)                               -- at the counter
```

Every correctly numbered fill was refused: `fillsRecorded` is `count(fills)`, the request's own rule creates the fill, and a `never` is checked at the end of the settled transaction (README §21), by which time the count has moved and `1 != 1 + 1` is true. The verify-once refusal refused every verification for the same reason; the pickup refusal refused every pickup, since the pickup is what ends `ReadyForPickup`; the shelf bound refused any fill of more than half the lot. Sixty-one of the hundred and five tests failed on the first spelling, all on the fill request.

`checks.md` V18 says this is deliberate: "a rule's partition evaluates at the act's commit, while a `never` constrains the settled transaction (§21) — for an atom the act's own consequences can flip, the `never` is the stronger statement, refusing even the act that would create the configuration." Taken that way, each of the four was mis-stated: the sentence was about the record, not the request, and on the record it means what it says.

```
never (Fill where fillNumber != count(prescription.fills where fillNumber <= this.fillNumber))
never (Fill where count(verifications) > 1)
never (Fill where count(pickups) > 1)
never (PickUp where fill is not VerifiedFill or exists ReturnToStock for fill)
```

The first counts the arriving fill among its siblings exactly as the peptides spec's hour-mark uniqueness does (`break-peptides.md` S3), and catches gaps and repeats in one sentence. The shelf bound went a different way (P4). Evidence: `fill - data refusals at the boundary keep nothing, business refusals land on record with the reason` and `verify - a licensed pharmacist verifies once, never their own controlled fill, and a rejection returns the stock` in `examples/pharmacy/output/src/test/kotlin/velle/PharmacyFlowTest.kt`.

The cost is real and the lesson is general: a refusal over a request may read only what the request's handling leaves alone. What the pass could not settle is a wording conflict in the finalized docs; see section 1 under "What needs Dave's input".

### P4 — TAX, open: V29 cannot see that a record only raises an aggregate

The shelf bound, restated as a constraint on the books —

```
expose shape StockLot {
    ...
    onHand: integer = received - sum(fills where ConsumingFill, quantity) + sum(stockAdjustments, delta)
}

shape ConsumingFill = Fill where this is not RejectedFill and not exists ReturnToStock for this

never (StockLot where onHand < 0)

rule ReturnUncollected when Uncollected on Nightly {
    ReturnToStock from { fill: this, returnedOn: today }
}
```

— was refused by V29: the nightly rule creates a `ReturnToStock`, the balance reads it through `ConsumingFill`, and v0 cannot prove the firing's condition keeps the lot out of the forbidden state. It always does: a return to stock removes a fill from the consuming set, which only raises the balance. V29 reasons by shape, not by direction, and fails closed (rejects what it cannot prove rather than warning).

The spec decides the bound in the request's partition instead — `dispensable` on `FillPrescription` reads `quantity <= lot.onHand` once, at the request, over the shelf as it stands, and the refusal lands on record with the reason "the lot does not hold that much". That is a good answer for the counter, but the books rule "stock never goes below zero" cannot be stated. Recorded as a TODO item: let V29 (and V10, V26, which share the posture) discharge a creation whose only effect on the consulted aggregate is in the safe direction.

### P5 — TAX, open (rides OQ16; `break-peptides.md` S7 again): a `never` over a rule-written field fails closed

Two bounds were refused as "rule-maintained ... v0 cannot yet prove the invariant inductively":

```
never (Drug where reorderPoint < 0)          -- ApplyReorderPoint writes reorderPoint
never (Prescription where quantity <= 0)     -- the first draft's ApplyCorrection wrote quantity
```

The reorder-point bound moved to the act (`never (SetReorderPoint where reorderPoint < 0)`), which leaves the drug's own committed value unbounded. The correction act shrank to the one field no prohibition bounds — a technician may correct the directions of an unfilled prescription in place, but not its quantity or days' supply — so that the quantity bounds on the closure part could stand. Both are the spec bending to the check, and the second changed what the business can do.

### P6 — TAX, by design: deleting an unfilled prescription strands fourteen referrers

The first draft voided a never-filled prescription entered against the wrong patient with a `delete`. V24 (referential completeness) listed every shape holding a required `one Prescription`: discontinuations, correction refusals, void refusals, overrides, alerts, monitoring checks, fills, fill refusals, refill tasks, refill refusals, authorisation requests, enrolments, cancellations — fourteen, each to be deleted in the same commit, declared `? initially required`, or replaced by copied fields. The deleter's scope (`prescription is not FilledPrescription`) excludes the fills, but V24 does not reason from the scope, and the other thirteen are legitimately present on an unfilled prescription.

The spec keeps the prescription and records the void (`EntryVoid`; `Voided = Prescription where entryVoids is not empty`; `Current` excludes it) — reversal as data (README §22, "Reversal"). In a regulated domain that is the right model anyway, and `FilledPrescription` lost its `undeletable` clause, which with no deleter left would have been dead machinery. Evidence: `order - corrections and voids are refused once a prescription has been filled` in `PharmacyFlowTest.kt`. The deletion the spec does keep is a future-dated price change (P9).

### P7 — TAX, open: the disarm proof does not look through a named refinement over `is not empty`

```
shape QuarantinedLot = StockLot where quarantines is not empty

rule QuarantineRecalled when (RecalledLot where this is not QuarantinedLot) {
    Quarantine from { lot: this, reason: "recalled" }
}
```

V16 reported "rule cascade may not quiesce: QuarantineRecalled -> QuarantineRecalled — no disarming guard breaks the cycle". Creating the `Quarantine` makes `quarantines is not empty` true and so leaves the trigger state, but the disarm proof (V2) recognises `not exists W for this` and flag assignments, not a membership test against a refinement whose predicate is an inverse collection's emptiness. The spec spells the guard `not exists Quarantine for this` in both quarantine rules. Recorded as a TODO item.

### P8 — TAX, open: an aggregate over a refinement-filtered collection cannot reach the refinement's own property

```
shape PaidClaim = Claim where exists (claimResponses where outcome == "paid") {
    patientPays: decimal = latest(claimResponses where outcome == "paid" by respondedAt).patientPays
}

shape Fill {
    ...
    amountDue: decimal = if exists (claims where PaidClaim) then sum(claims where PaidClaim, patientPays) else total
}
```

The name check reported "sum(..., patientPays) — 'Claim' has no member 'patientPays'": the element of `claims where PaidClaim` is resolved as a `Claim`, not as a `PaidClaim`, so a refinement-body property is out of reach from a filtered aggregate even though every element is a member. The spec moves `patientPays` onto `Claim` (zero until a paid response exists). Recorded as a TODO item; it belongs with the narrowing items under OQ44, since the filter is a narrowing the name check does not carry.

### P9 — HOLDS: what the domain asked for and got

Each line names its evidence in `PharmacyFlowTest.kt`, run three times (in memory, over SQLite through the generic protocol, over SQLite through the generated typed store).

- **The order as a closure.** A schedule II line with refills refuses the whole order and the clean line does not land either; the back-reference is populated; a post-dated order is refused; each prescription derives its own expiry from its drug's schedule with a conditional over Date arithmetic (`order - an order lands with all its prescriptions or not at all, and each prescription knows its own expiry`).
- **A two-field relationship with an `or` view.** `Interaction` has two `one Drug` fields; `Drug.interactions = (Interaction where drugA == this or drugB == this)` finds the pair from either side, and the conflict is detected whichever prescription arrives second (`clinical - an interaction is found from either side, against the patient's current prescriptions`).
- **A sibling join over two edge sets.** `exists (drug.ingredients as ing, order.patient.allergies as al where ing == al)` matches an allergy through the drug's ingredients, including when the allergy is reported after the fill is at the counter, by whole-set replacement or by `+` (`clinical - an allergy is matched ...`; `counter - an allergy reported while a fill waits raises one alert ...`).
- **A two-hop derived view.** `Patient.prescriptions = (Prescription where order.patient == this)` serves the interaction check and the multiple-prescribers flag.
- **A capture read by a sweep and at exit.** `ReadyForPickup { captured readyOn: Date = today }`; `Uncollected = ReadyForPickup where readyOn < today - 10 days` is the nightly sweep's condition; `when leaving ReadyForPickup` reads `readyOn` as its last reader at both exits, the pickup and the tick-caused return (`counter - the patient is told ...`; `counter - a fill uncollected for ten days ...`).
- **A `when leaving` rule naming only a schedule.** `rule NoticeExpiry when leaving ActivePrescription on Daily` fires for a prescription that expires by age at the daily tick and not for one discontinued by a commit — the first exercise of the tick-exit reading `evaluation.md` "Ticks" derives and flags for stress-testing; the reading held (`expiry - a prescription that expires by age gets a notice at the daily tick, one used up or discontinued does not`).
- **A ledger ordered by a business date and read through `today`.** The price in force is `latest(priceChanges where effectiveOn <= today by effectiveOn)`; a future-dated change waits its turn, and the fill copies the price so a later change never moves it (`prices - the price in force is the dated entry ...`).
- **Deletion gated by a time-dependent state.** `EffectivePrice = PriceChange where effectiveOn <= today { undeletable }`; a future-dated change is deleted with a record of what was removed, and a change in force is refused on record (same test).
- **The static union as a notice's recipient.** `Recipient = Patient or Prescriber`; `contact = if recipient is Patient then recipient.phone else recipient.fax` reads a different field on each side (`counter - the patient is told ...`; `refills - a refused request carries its reason, and want of refills asks the prescriber once`).
- **Refusals over requests.** Role gates, the licence in force, "not your own controlled fill", the wrong drug's lot, an expired or quarantined lot, a controlled fill without a clear monitoring check within a day, underpayment, counselling owed (`fill - data refusals ...`; `fill - a controlled fill needs a clear monitoring check ...`; `verify - ...`).
- **The derived boolean as a partition key.** V18's complement slice accepts `where dispensable` / `where not dispensable` where it would not accept the two-conjunct predicate behind them; a derived property on the transient act fixes the arrival-time value once (README §22, "External input") and reads as the business word.
- **A rule chained off a refusal record.** Want of refills asks the prescriber once, from a rule on `RefillRefusal where reason == "no refills remaining"`, where a third partition of the request would have failed V18's coarse slice (`refills - a refused request carries its reason ...`).
- **Stock as a derivation.** `onHand` over receipts, consuming fills, and count adjustments; a rejected verification and a return to stock both restore the shelf and the refill; the controlled-substance log copies the balance as it stood at the fill; the reorder sweep is guarded by the open purchase order it creates and closed by the lot received against it (`stock - ...` tests; `fill - a controlled fill ...`).
- **A recall as a fan-out.** One `Recall` flips `lot is RecalledLot` for every fill of the lot; every patient is told once, in the recall's transaction (`stock - a recall quarantines the lot and tells every patient who had medicine from it, once`).
- **The constraint on the closure part.** `never (Prescription where drug is ScheduleTwo and refillsAuthorized > 0)` reads through the part to the drug and is enforced at the order's expose site.
- **Identity inequality between paths.** `never (Discontinuation where prescriber != prescription.order.prescriber)` and `other != this` inside the interaction join.

### P10 — TAX, open: three generator blind spots

- **A closure part is "not exposed".** `SPEC_INDEX-Pharmacy.md` lists the five prohibitions over `Prescription` under "Not yet generated — shape not exposed". A prescription enters only through `expose PrescriberOrder with prescriptions`, and the spec generator does not count a closure part as exposed, so no boundary case is generated for the schedule II and five-refill bounds.
- **One "any Staff" given serves every role-gated act.** The generated boundary cases for `CountLot` and `SetReorderPoint` both fetch their staff member through `someStaff()`, and each act carries a role refusal the generator cannot see (`break-peptides.md` S11's remaining half). The first draft's administrator-only reorder point and technician-or-pharmacist count had no staff member in common; the spec now lets a pharmacist set reorder points, and the given returns a pharmacist. The coupling is documented on the given (`examples/pharmacy/output/src/test/kotlin/velle/Givens.kt`, `someStaff`) and invisible from the generated test.
- **A schedule-only `when leaving` rule gets a case that asserts nothing.** The generated case for `NoticeExpiry` drives a commit-caused exit (`exitActivePrescription`) and checks only that membership ended; the rule names only `Daily`, so no notice is expected and none is asserted. The generator has no way to exit a state by aging. The hand-written `expiry -` test carries the coverage.

### P11 — TAX, open: fifty-seven width advisories, one of them from a mis-resolved join

`Validator.advisories` reports 57 A5 contention-width advisories on the spec and none of A2, A4, or A6. Most have the peptides shape (S12): a rule reads a series through an inferred inverse (`verifications`, `fills`, `recalls`, `quarantines`) and the derivation finds no key. One is a precision bug rather than a width: for `RecordFill` and `RefuseFill` the derivation reports "'order' cannot be resolved on 'Ingredient'". The read is the allergy join, `exists (drug.ingredients as ing, order.patient.allergies as al where ing == al)`, and the derivation resolves the second binding's path, `order.patient.allergies`, against the first binding's element shape (`Ingredient`) instead of the subject. Recorded as a TODO item beside the S12 one.

### P12 — TAX, by design: the same three spellings as the peptides pass

- `then` must share the `if`'s line (the existing TODO item); the first draft's price derivation broke the parser on a wrapped conditional.
- A duration takes a literal count only (`break-peptides.md` S13), so auto-refill cannot say "when the days' supply is nearly used up": the spec says twenty-five days.
- A boolean cannot be written as a comparison; `dispensable` is `if ... then true else false`.

### P13 — HOLDS, with a note: the shape of the refusal story at the counter

The counter ended up with three answering mechanisms on one request, and they are not interchangeable:

- a **refusal** (`never` over `FillPrescription`) for data that is wrong on arrival and stays wrong — the caller is told, nothing is kept;
- a **refusal recorded as data** (the `RefusedFill` partition, README §8's rejection-as-data idiom) for the patient's situation — evaluated once at the request, kept with a reason the counter reads back;
- a **constraint** on the record the request produces (`never (Fill where ...)`) for a sentence about the books that the request's own handling makes true.

Which of the three a sentence belongs to is decided by one question — does the request's own handling change what the sentence reads? — and the first draft got four of them wrong (P3). The spec's comments on `FillPrescription`, `VerifyFill`, and `PickUp` say which is which and why; `patterns.md` and README §21 may want the same three-way statement.

### P14 — observation: an inline exposure and a standalone one

`expose shape PrescriberOrder { ... }` together with `expose PrescriberOrder with prescriptions` is "shape 'PrescriberOrder' is exposed twice" — correct, and the fix (`shape` without `expose`, then the standalone `expose ... with`) is what `examples/orders/orders.velle` does. The grammar allows the closure on the inline form too (`expose shape X { ... } with parts`); nothing in the README shows it.

## What needs Dave's input

### 1. When is a refusal checked: once at the request, or at the request and again at the settled transaction?

The three finalized documents say three things about a `never` over a transient act.

README §21: "A **refusal** binds the caller: the prohibition is on a transient act (§4), it is checked once, at the request, and the record the request leaves behind carries nothing."

`checks.md` V18: "Note the moments differ: a rule's partition evaluates at the act's commit, while a `never` constrains the settled transaction (§21) — for an atom the act's own consequences can flip, the `never` is the stronger statement, refusing even the act that would create the configuration."

`evaluation.md`, "An exposed call": the boundary guardrail (not yet built; the TODO item from the peptides pass) evaluates refusals and constraints over pre-state plus the act before any rule runs, "in addition to step 5, not instead of it".

The pharmacy spec is where the difference shows. Take the simplest of the four from P3, with every shape it needs:

```
expose shape Prescription {
    quantity: integer
    fillsRecorded: integer = count(fills)
}

expose transient shape FillPrescription {
    prescription: one Prescription
    fillNumber: integer
}

shape Fill {
    prescription: one Prescription
    fillNumber: integer
}

-- a fill request must carry the next number
never (FillPrescription where fillNumber != prescription.fillsRecorded + 1)

rule RecordFill when FillPrescription {
    Fill from { prescription: prescription, fillNumber: fillNumber }
}
```

A request numbered 1 against a prescription with no fills: at the request the prohibition is false (1 is the next number). The rule records the fill; `fillsRecorded` is now 1; at the end of the transaction the prohibition reads `1 != 2`, true, and the request is refused. Under README §21's words the request is accepted; under V18's words it is refused, and v0's runtime refuses it (it checks every `never` at the end only).

The spec took V18's reading and moved the sentence onto the fill (P3), which works and is arguably the better statement. But one of the two wordings is wrong as written, and the answer decides what an author may say with a refusal:

- If a refusal is checked **once, at the request** (README §21 as written), then `never (FillPrescription where fillNumber != prescription.fillsRecorded + 1)` means what it says, and the runtime should evaluate refusals at the boundary and skip them at step 5. V18's note and `evaluation.md`'s "in addition to" would then apply to constraints only.
- If a refusal is checked **at both moments** (V18 and `evaluation.md` as written), then README §21's "checked once, at the request" should say so differently, and the guidance to authors is P3's: a refusal may read only what the request's handling leaves alone, and a sentence about a value the handling moves belongs on the record as a constraint.

The spec and its tests are consistent with the second reading today. Nothing in the pass needs the first; the question is which sentence to fix.

## Scorecard

| # | finding | verdict | where it stands |
|---|---|---|---|
| P1 | V3 refused every partition of a transient act over a time-dependent state | GAP | fixed; probe in `ValidatorTest.kt` |
| P2 | V29 paired tick rules with refusals over transient acts | GAP | fixed; probe in `ValidatorTest.kt` |
| P3 | a refusal reading a value the request's own handling moves refuses its own request | TAX | by design per `checks.md` V18; spelled as constraints on the record; wording question in section 1 |
| P4 | V29 cannot see that a record only raises an aggregate (`never (StockLot where onHand < 0)`) | TAX | spelled around (partition at the request); TODO |
| P5 | `never` over a rule-written field fails closed (V10) | TAX | spelled around (bound on the act; correction shrunk to directions); rides OQ16 |
| P6 | deleting an unfilled prescription strands fourteen referrers (V24) | TAX | by design; void as a record |
| P7 | disarm proof does not look through a refinement over `is not empty` (V2/V16) | TAX | spelled `not exists Quarantine for this`; TODO |
| P8 | filtered aggregate cannot reach a refinement-body property | TAX | property moved to the base; TODO (OQ44's narrowing list) |
| P9 | closure, two-field view, edge-set join, two-hop view, captures, schedule-only exit, dated ledger, gated deletion, union recipient, refusals | HOLDS | `PharmacyFlowTest.kt` |
| P10 | generator: closure part "not exposed"; one staff given for every role gate; schedule-only exit case asserts nothing | TAX | open; TODO |
| P11 | 57 A5 widths; the allergy join's second binding resolved against the first binding's element | TAX | open; TODO |
| P12 | `then` on the `if` line; no duration from data; no boolean from a comparison | TAX | by design; spellings recorded |
| P13 | three answering mechanisms on one request, chosen by one question | HOLDS | spec comments; a candidate for `patterns.md` |
| P14 | inline and standalone exposure together is "exposed twice" | — | correct; the inline-with-closure form is undocumented |
