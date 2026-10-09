# OQ47 — What a rejection says, and who hears it

**Status:** open (2026-10-09) — promoted from the TODO one-liner "decide an author-supplied refusal message on `never`" after the discussion that reframed OQ45
**In plain terms:** when the system refuses to record something because a `never` forbids the state it would produce, a person has to be told exactly what was refused and why, in the words the rule was written in. Today the message names a rule number, and when the refusal comes from a scheduled rule rather than from someone's request, nobody is told at all. What must a rejection carry, and how does it reach the person who needs it?
**v0 stance:** an act's refusal reaches the caller as `CommitResult.Refused("never #N over Shape is violated")` (`compiler/src/main/kotlin/velle/Runtime.kt`, `checkNevers`, `commitShape`). A tick's rejected transaction is returned to the harness as an error; `evaluation.md` "Ticks" does not mention it, and nothing carries it to a person.
**See:** README §21 (`never`) · README §22 "External input" (the generated commit function is the surface the engineer's transport calls) · `evaluation.md` "An exposed call" (steps 5 and 6), "Ticks", "Errors, refusals, and retry", "The universal transaction" (the commit callback, the one existing engineer-facing hook) · OQ45 and OQ46 in `QUESTIONS.md` · `diagrams.md` (the accept/refuse split already drawn in the rule graph and sequence diagrams)

---

## The principle this serves

Dave, 2026-10-09: rejections and validations are as important and valid as rules and acts. A human consumes the system's information in two forms, the committed state and the rejections, and Velle's goal is to make it explicit and clear when a `never` results in an invalid state that will be rejected, telling the human exactly what was rejected and why. The human is sometimes the author of the act and sometimes a product owner reviewing rejections that came out of a scheduled rule. Whether a situation should be rejected or recorded is the author's choice per `never` (OQ45); this question is about what happens once the choice is "rejected".

## What a rejection must carry

The example from OQ45, with the persistent spelling kept on purpose: the owner has decided that a halt arriving after a spray is a situation to reject and review.

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

A spray on trial T-12 is recorded on 2 October. On 9 October a severity-3 report arrives for plot 7 of T-12. Today the caller receives: "never #1 over TreatmentApplication is violated". A product owner cannot act on that. What they need, and what the system knows at the moment of the rejection:

1. **What was refused**: the act, with its fields. "The phytotoxicity report for plot 7 of trial T-12, observed 2026-10-09, severity 3."
2. **Which rule refused it, in the author's words.** Today the only words are the predicate. An author-supplied sentence on the `never` is the obvious source: "a spray is never on the books for a halted trial".
3. **Which records would have been in the forbidden state.** Not the act: the application of 2 October on T-12. The rejection is about a record other than the one being submitted, and the person needs to see that record to understand it.
4. **The path from the act to the violation**, when it is indirect. The report makes T-12 a `HaltedTrial`; the application's `trial` is T-12. When a rule firing stands in between (the act triggered a rule whose effect produced the state), the rule's name belongs here too.

The same four items for the clock case, where the tick is the actor:

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

The permit on T-12 expires on 8 October. At the first tick on 9 October the planting on T-12 is in the forbidden state. The tick's transaction is rejected, and so is every transaction after it until a person renews the permit or removes the planting. The person needs: the tick (which schedule, which moment), the rule, the planting, and the permit whose lapse did it. Nobody asked for anything, so there is no caller to tell.

## Who hears it

- **An act's caller.** The generated commit function returns the refusal; the engineer's transport code carries it to whoever submitted the act. This exists. Its content is the gap.
- **A tick.** There is no caller. The tick is a harness call, and the rejection goes back to the harness as an error. `evaluation.md` says nothing about it. And a tick's rejection is a standing condition: it repeats at every tick, and, when the `never` reads the clock, it rejects every act's transaction too, because the `never` is re-checked at every transaction end. Someone must be told once, clearly, and the system must not simply fail quietly until they notice.
- **A consequence inside an act's transaction.** The act was fine; a rule it triggered produced the forbidden state. The caller receives the rejection, but the person who needs it may be the owner of the rule, not the submitter of the act. The message has to carry the rule.

## Candidates

1. **An author-supplied sentence on the `never`, plus a rejection surface in the engineer contract.** The sentence is the second item above; syntax undecided (a string after the predicate is the smallest form). The surface is a second engineer-facing hook beside the commit callback in `evaluation.md` "The universal transaction": every rejection, act-caused or tick-caused, is handed to it as one structured payload carrying the four items, and wiring it to a person is the engineer's, exactly as the transport and the store are. For an act the payload also comes back as the commit function's result, so the existing path is unchanged. A rejection is never committed state (nothing lands on a refusal, and that stays), but the payload is the engineer's to persist outside the state if the owner wants a rejection log.
2. **The sentence only.** Improve the message, leave delivery to the engineer's handling of errors. Rejected by the principle: a tick's rejection today is an error with no reader, and "a human is told" is the requirement.
3. **Rejections as state.** Commit a rejection record so a rule can react to it. Rejected: a refusal means the proposed state does not exist, and a record of it inside the same state is the proposed state leaking in. The owner who wants a rejection to be a fact in the books chooses the record-and-show spelling instead (OQ45; `patterns.md`, "Validation rejection is data").

Candidate 1 is the recommendation. What it asks of the docs: `evaluation.md` "Ticks" gains the rejected-tick paragraph; "Errors, refusals, and retry" names the payload; README §21 names the sentence; the generated artifacts' accept/refuse split (`diagrams.md`) should carry the sentence as its label.
