# OQ47 — Rejections: the author's choice, the advisory that makes it visible, and what the person is told

**Status:** open (2026-10-09). Carries what was OQ45 (a `never` over a persistent act whose predicate can flip after the act lands, opened 2026-09-15 by the peptides stress pass, `break-peptides.md` S5) and the refusal-message TODO line, merged because they are one question with one principle.
**In plain terms:** when the system refuses to record something because a `never` forbids the state it would produce, two things have to be true. The author must have chosen that outcome on purpose, knowing the rule can also refuse things that arrive long after the record it protects (a halt arriving after a spray, a permit lapsing under a planting). And the person affected must be told exactly what was refused and why, in the words the rule was written in, even when the refusal came from a scheduled rule rather than from anyone's request. Today neither is true: the spelling validates silently, the message names a rule number, and a scheduled rule's rejection reaches nobody.
**v0 stance:** the persistent spelling validates with no diagnostic and the runtime does what README §21 says (the invariant constrains every settled state). An act's refusal reaches the caller as `CommitResult.Refused("never #N over Shape is violated")` (`compiler/src/main/kotlin/velle/Runtime.kt`, `checkNevers`, `commitShape`). A tick's rejected transaction is returned to the harness as an error; `evaluation.md` "Ticks" does not mention it. The peptides example spells the halt rule on a transient request (`examples/peptides/peptides.velle`, `ApplyTreatment`, `PlantTrial`), exercised in `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt` (`field - a severe phytotoxicity report halts the trial and later applications are refused at the boundary`; `field - the request needs an effective greenhouse result on record, and planting needs a permit in force`).
**See:** README §21 (`never`: transaction-end enforcement; the boundary guardrail's moment) · README §4 "Transient acts" · README §22 "External input" (the generated commit function is the surface the engineer's transport calls) · `checks.md` A4 (the drift-exposed act partition, the same hazard for rules) · `evaluation.md` "An exposed call", "Ticks", "Errors, refusals, and retry", "The universal transaction" (the commit callback, the one existing engineer-facing hook) · `diagrams.md` (the accept/refuse split already drawn in the rule graph and sequence diagrams) · `patterns.md` "Validation rejection is data"

---

## The principle

Dave, 2026-10-09. A `never` says "this is not a state the system can be in". A rejection is the system refusing to move to such a state, whoever proposed the move: the caller of an act, or a scheduled rule firing at a tick. Rejections and validations are as important and valid as rules and acts: a human consumes the system's information in two forms, the committed state and the rejections. Whether a given situation belongs in the rejected category, or should instead be accepted and recorded as valid state with the finding shown to a person, is a human decision per `never`. The language does not make that call. Its job is to make sure the author sees which of the two sentences they wrote, and, when a rejection happens, to tell the person affected exactly what was rejected and why.

Two readings of one English sentence, which this question keeps apart:

- **A door rule.** "If someone tries to spray a halted trial, turn them away." About the act being recorded, at the moment it is submitted. Once a spray has been accepted the rule has nothing more to say about it. Velle's structural spelling is a `never` over a transient act: the act exists only inside its own commit, so a `never` on it can only ever be violated by that act, at its arrival.
- **A books rule.** "The records never show a spray on a halted trial." About valid committed state, at every moment, for as long as the record exists. Velle's spelling is a `never` over a persistent shape. It can be violated by a record accepted long ago, when a later act, a later tick, or a later deletion changes what the predicate reads.

A human reading the sentence usually hears the door rule. The `never` over a persistent shape always means the books rule. Both are legitimate. What is not acceptable is the author writing the door-rule sentence and getting the books-rule behaviour without knowing it.

## The case

A field trial halts when any of its plots reports severe crop injury, and nothing may be sprayed on a halted trial:

```
expose shape FieldTrial {
    name: text
}

expose shape FieldPlot {
    trial: one FieldTrial
    block: integer
}

expose shape PhytotoxicityReport {
    plot: one FieldPlot
    observedOn: Date
    severity: integer
}

shape HaltedTrial = FieldTrial where
    exists (fieldPlots where exists (phytotoxicityReports where severity >= 3))

expose shape TreatmentApplication {
    trial: one FieldTrial
    appliedOn: Date
}

never (TreatmentApplication where trial is HaltedTrial)
```

A spray on trial T-12 is recorded on 2 October. On 9 October a severity-3 report arrives for plot 7 of T-12. At the report's transaction end the application on file is a `TreatmentApplication where trial is HaltedTrial`, and the report is refused: "never #1 over TreatmentApplication is violated". The injury happened in the field; refusing the report does not un-injure the plot. If the owner meant the door rule, this is wrong. If the owner meant the books rule, this is exactly right, and someone now has to review the refused report.

The permit version reads the clock:

```
expose shape Permit {
    trial: one FieldTrial
    expiresOn: Date
}

shape PermittedTrial = FieldTrial where exists (permits where expiresOn >= today)

expose shape Planting {
    trial: one FieldTrial
    plantedOn: Date
}

never (Planting where not trial is PermittedTrial)
```

The permit on T-12 expires on 8 October. At the first tick on 9 October the planting on T-12 is in the forbidden state. The tick's transaction is rejected, and because a `never` that reads the clock is re-checked at every transaction end (`evaluation.md`, the relevance rule's time exception), so is every transaction after it, acts included, until a person renews the permit or removes the planting. Nobody asked for anything, so there is no caller to tell.

The door-rule spelling of the first case, which the peptides example uses:

```
expose transient shape ApplyTreatment {
    trial: one FieldTrial
    appliedOn: Date
}

shape TreatmentApplication {
    trial: one FieldTrial
    appliedOn: Date
    recordedOn: timestamp on create
}

never (ApplyTreatment where trial is HaltedTrial)

rule RecordApplication when ApplyTreatment {
    TreatmentApplication from { trial: trial, appliedOn: appliedOn }
}
```

The request is checked at its moment and gone; the record it leaves carries no rule. A halt is always recorded. If the owner wants a spray that preceded a halt to be visible, that is a refinement over the records (a spray on a now-halted trial), shown rather than refused: the record-and-show spelling, `patterns.md` "Validation rejection is data".

## Part 1 — making the choice visible

The books-rule spelling has a defined meaning the author may not have intended, which by the recorded split (an error is for undefined meaning; a defined meaning the author may not have intended is an advisory) is advisory territory.

1. **An advisory that asks the owner's question (recommended).** Detection is the read-summary analysis `checks.md` A4 already uses, applied to `never` targets: the target is a persistent exposed shape and the predicate consults anything beyond the act's own stored fields, or reads the clock. The text is the owner's question, not a mechanism. For the halt: "`never (TreatmentApplication where trial is HaltedTrial)` can become true for an application already on file, when a later report halts its trial. Then the report is rejected and must be reviewed by a person. If that is the rule, acknowledge it; if sprays should be turned away at the door and halts always recorded, state the `never` on a transient request (`expose transient shape ApplyTreatment`) and let a rule record the application." For the permit: "can become true on a tick, when the permit lapses; from then on every transaction is rejected until a person acts." The acknowledgement is an explicit spelling, as every advisory's discharge is; the form is undecided (`tolerates`-style is the existing family).
2. **A README §21 note only**, recommending the transient spelling for door rules. Rejected: the author must see the choice at the line where they wrote it.
3. **A restriction**: a `never` over an exposed persistent shape may read only its own stored fields. Rejected: it takes the decision away from the human, and it forbids legitimate books rules (a spending cap across all of a customer's orders).

README §21 should also say in plain words what the two spellings mean to a person: a `never` on a request is a door rule, checked once, at the request; a `never` on a record is a books rule, checked at the end of every transaction for as long as the record exists, and violable by later acts, ticks, and deletions.

## Part 2 — what the rejection carries, and who hears it

Once the choice is "rejected", the person has to be able to act on the rejection. For the halt case, with the books rule kept on purpose, today's "never #1 over TreatmentApplication is violated" is not actionable. What the system knows at the moment of the rejection, and what the message must carry:

1. **What was refused**: the act, with its fields. "The phytotoxicity report for plot 7 of trial T-12, observed 2026-10-09, severity 3."
2. **Which rule refused it, in the author's words.** Today the only words are the predicate. An author-supplied sentence on the `never` is the obvious source: "a spray is never on the books for a halted trial".
3. **Which records would have been in the forbidden state.** Not the act: the application of 2 October on T-12. The rejection is about a record other than the one being submitted, and the person needs to see that record to understand it.
4. **The path from the act to the violation**, when it is indirect: the report makes T-12 a `HaltedTrial`, the application's `trial` is T-12. When a rule firing stands in between (the act triggered a rule whose effect produced the state), the rule's name belongs here too.

For the permit case the actor is the tick: which schedule, which moment, the rule, the planting, and the permit whose lapse did it.

Who hears it:

- **An act's caller.** The generated commit function returns the refusal; the engineer's transport code carries it to whoever submitted the act. This exists. Its content is the gap.
- **A tick.** There is no caller. The rejection goes back to the harness as an error, `evaluation.md` says nothing about it, and it is a standing condition: it repeats at every tick, and when the `never` reads the clock it rejects every act's transaction too. Someone must be told once, clearly, and the system must not simply fail quietly until they notice.
- **A consequence inside an act's transaction.** The act was fine; a rule it triggered produced the forbidden state. The caller receives the rejection, but the person who needs it may be the owner of the rule, not the submitter of the act. The message has to carry the rule.

Candidates:

1. **An author-supplied sentence on the `never`, plus a rejection surface in the engineer contract (recommended).** The sentence is item 2 above; syntax undecided (a string after the predicate is the smallest form). The surface is a second engineer-facing hook beside the commit callback in `evaluation.md` "The universal transaction": every rejection, act-caused or tick-caused, is handed to it as one structured payload carrying the four items, and wiring it to a person is the engineer's, exactly as the transport and the store are. For an act the payload also comes back as the commit function's result, so the existing path is unchanged. A rejection is never committed state (nothing lands on a refusal, and that stays), but the payload is the engineer's to persist outside the state if the owner wants a rejection log.
2. **The sentence only**, leaving delivery to the engineer's error handling. Rejected by the principle: a tick's rejection today is an error with no reader.
3. **Rejections as state**: commit a rejection record so a rule can react to it. Rejected: a refusal means the proposed state does not exist, and a record of it inside the same state is the proposed state leaking in. The owner who wants a rejection to be a fact in the books chooses the record-and-show spelling instead.

## What settling this asks of the docs

`checks.md`: the advisory of Part 1, in A4's family, with its discharge spelling. README §21: the door-rule/books-rule paragraph, and the author-supplied sentence. `evaluation.md`: "Ticks" gains the rejected-tick paragraph; "Errors, refusals, and retry" names the rejection payload; "The universal transaction" gains the rejection hook beside the commit callback. `diagrams.md`: the accept/refuse split carries the sentence as its label.
