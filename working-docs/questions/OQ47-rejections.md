# OQ47 — Rejections: the author's choice, the advisory that makes it visible, and what the person is told

**Status:** open (2026-10-09). Carries what was OQ45 (a `never` over a persistent act whose predicate can flip after the act lands, opened 2026-09-15 by the peptides stress pass, `break-peptides.md` S5) and the refusal-message TODO line, merged because they are one question with one principle.

**In plain terms:** when the system refuses to record something because a `never` forbids the state it would produce, two things have to be true. The author must have chosen that outcome on purpose, knowing the rule can also refuse things that arrive long after the record it protects (a cancellation arriving after a shipment, a permit lapsing under a planting). And the person affected must be told exactly what was refused and why, in the words the rule was written in, even when the refusal came from a scheduled rule rather than from anyone's request. Today neither is true: the spelling validates silently, the message names a rule number, and a scheduled rule's rejection reaches nobody.

**v0 stance:** the persistent spelling validates with no diagnostic and the runtime does what README §21 says (the prohibition constrains every settled state). An act's refusal reaches the caller as `CommitResult.Refused("never #N over Shape is violated")` (`compiler/src/main/kotlin/velle/Runtime.kt`, `checkNevers`, `commitShape`). A tick's rejected transaction is returned to the harness as an error; `evaluation.md` "Ticks" does not mention it. The shipment case is checked in as `examples/shipping/shipping.velle` (added 2026-10-10, the smallest spec: Order, Cancellation, Shipment, the two named refinements, and the one constraint), with both rejections exercised in `examples/shipping/output/src/test/kotlin/velle/ShippingRejectionsTest.kt`: each test pins today's message and then builds the wanted message by hand from the typed surface, to show every ingredient is in the state at the refusal. The spec generator writes no case for the constraint (`examples/shipping/output/SPEC_INDEX-Shipping.md`, "Not yet generated: never #1, non-comparison predicate"), which is the gap the generated-tests idea in Part 1 would fill. The peptides example spells the halt rule on a transient request (`examples/peptides/peptides.velle`, `ApplyTreatment`, `PlantTrial`), exercised in `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt` (`field - a severe phytotoxicity report halts the trial and later applications are refused at the boundary`; `field - the request needs an effective greenhouse result on record, and planting needs a permit in force`).

**See:** README §21 (`never`: transaction-end enforcement; the boundary guardrail's moment) · README §4 "Transient acts" · README §22 "External input" (the generated commit function is the surface the engineer's transport calls) · `checks.md` A4 (the drift-exposed act partition, the same hazard for rules) · `evaluation.md` "An exposed call", "Ticks", "Errors, refusals, and retry", "The universal transaction" (the commit callback, the one existing engineer-facing hook) · `diagrams.md` (the accept/refuse split already drawn in the rule graph and sequence diagrams) · `patterns.md` "Validation rejection is data"

---

## The principle

Dave, 2026-10-09. A `never` says "this is not a state the system can be in". A rejection is the system refusing to move to such a state, whoever proposed the move: the caller of an act, or a scheduled rule firing at a tick.

Rejections and validations are as important and valid as rules and acts: a human consumes the system's information in two forms, the committed state and the rejections.

Whether a given situation belongs in the rejected category, or should instead be accepted and recorded as valid state with the finding shown to a person, is a human decision per `never`. The language does not make that call. Its job is to make sure the author sees which of the two sentences they wrote, and, when a rejection happens, to tell the person affected exactly what was rejected and why.

Dave, 2026-10-10. Two different kinds of statement, which an English sentence does not distinguish and velle does. They are not two readings of one rule. They have different subjects, and README §21 already sorts prohibitions by who they bind.

- **A door rule**, README §21's refusal, binds the caller. Its subject is a request: "if someone tries to ship a cancelled order, turn them away." The author names the act. It is checked once, when that act arrives, and the record the act leaves behind carries nothing. One door, chosen by the author. Velle's spelling is a `never` over a transient act: the act exists only inside its own commit, so the prohibition can only ever be violated by that act, at its arrival.
- **A books rule**, README §21's constraint, binds the books. Its subject is the records: "the records never show a shipment on a cancelled order." The author names a state and names no act. Whoever would put the books in that state is refused, by whatever route: an act on the shape, an act on anything the predicate reads through, a rule's effect inside a transaction, a deletion, a tick. The compiler derives the enforcement points from the predicate's reads, and that derived set is closed under acts and rules added later. Which act gets refused is not the author's to choose. Velle's spelling is a `never` over a persistent shape.

A guarantee is the third kind, binds the spec's own rules, and rejects nobody. To the author the first two are different beasts. Writing a refusal is choosing a door. Writing a constraint is stating an invariant and accepting every refusal it entails, including refusals phrased over a record the refused person never touched. A product owner's sentence is usually ambiguous between the two; the velle spelling is not. What is not acceptable is an author who meant to choose a door, wrote a constraint because the English sounded the same, and does not know they have stated an invariant.

## The case

An order can be cancelled, and nothing may be shipped on a cancelled order:

```
expose shape Order {
    reference: text
}

expose shape Cancellation {
    order: one Order
    requestedOn: Date
}

shape CancelledOrder = Order where exists (cancellations)

expose shape Shipment {
    order: one Order
    shippedOn: Date
}

never (Shipment where order is CancelledOrder)
```

A shipment on order 4471 is recorded on 6 October. On 7 October a cancellation arrives for order 4471. At the cancellation's transaction end the shipment on file is a `Shipment where order is CancelledOrder`, and the cancellation is refused: "never #1 over Shipment is violated". The person recording the cancellation did not submit a shipment and may not know one exists. Nothing read the clock and no rule fired: a plain act on a different shape changed what the predicate reads through `order`.

Dave, 2026-10-10. The runtime is not wrong here and must not be made lenient: the `never` as written must hold, so an act that would put a shipment on the books for a cancelled order is rejected, whichever act it is. Two readings of the author's intent remain, and both are resolved by changing the velle, never by the runtime bending. Either the owner meant cancelling after shipment to be allowed, in which case the prohibition is rewritten to the door-rule spelling below. Or the owner meant the rejection and did not realise it would be stated in terms of shipments when the act refused was a cancellation, in which case the velle is made explicit about cancellations. In both cases the person who was refused has to be told enough to raise it, and it is the humans' call whether the velle changes. The language's job is to show the author the books-rule case at the line where it is written, and it can, because the case is statically identifiable (Part 1).

The books rule is symmetric. `never (Shipment where order is CancelledOrder)` refuses whichever of a shipment or a cancellation arrives second, and names Shipment both times. The mirror, `shape ShippedOrder = Order where exists (shipments)` with `never (Cancellation where order is ShippedOrder)`, is the same rule and refuses a later shipment too, naming Cancellation. The choice of target shape is a choice of which act the refusal is phrased around, not of which direction is forbidden. Only the transient spelling is one-sided.

The predicate is what makes this a books rule. A `never` over a persistent shape whose predicate reads only that shape's own stored fields can be violated only by the act that creates or edits the record, so in practice it behaves as a door rule. One that reads through to other records, or reads the clock, can be violated by a later act on those records, by a deletion, or by a tick.

The permit version, from the peptides stress pass, reads the clock:

```
expose shape FieldTrial {
    name: text
}

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

The door-rule spelling of the shipment case:

```
expose transient shape ShipOrder {
    order: one Order
    shippedOn: Date
}

shape Shipment {
    order: one Order
    shippedOn: Date
    recordedOn: timestamp on create
}

never (ShipOrder where order is CancelledOrder)

rule RecordShipment when ShipOrder {
    Shipment from { order: order, shippedOn: shippedOn }
}
```

The request is checked at its moment and gone; the record it leaves carries no rule. A cancellation always lands. If the owner wants a shipment that went out before the cancellation to be visible, that is a refinement over the records (a shipment on a now-cancelled order), shown rather than refused: the record-and-show spelling, `patterns.md` "Validation rejection is data". This is the spelling the peptides example uses for its halt rule (`ApplyTreatment` as the request, `TreatmentApplication` as the record).

## Part 1 — making the choice visible

The books-rule spelling has a defined meaning the author may not have intended, which by the recorded split (an error is for undefined meaning; a defined meaning the author may not have intended is an advisory) is advisory territory.

1. **An advisory that shows the author what they bound (recommended).** Detection is the read-summary analysis `checks.md` A4 already uses, applied to `never` targets. Its facts are derived, not guessed: the compiler already computes every act's read-and-write footprint for the serialization domain (`evaluation.md`, OQ40), so for each persistent `never` the validator can name every transaction the constraint binds, and state that the refusal will be phrased over the target shape. The acknowledgement is an explicit spelling, as every advisory's discharge is; the form is undecided (see "The acknowledgement" below). The worked form follows, drafted 2026-10-10 in the format A5 uses, a statement of fact naming the declaration and then the discharges.

   For the shipment case:

   ```
   A? never #1 (Shipment where order is CancelledOrder) can reject more than
      a Shipment. Because it looks at the order's cancellations, it rejects:
       - a Shipment for an order that already has a Cancellation
       - a Cancellation for an order that already has a Shipment
      When a Cancellation is rejected, the message says that a Shipment
      would be on a cancelled order.
      If that is what you mean, mark it: <spelling undecided>.
      If the rejection should say that the Cancellation is refused, write
      the never over Cancellation instead:
          never (Cancellation where order is ShippedOrder)
      If only a shipment should be rejected, and a cancellation should
      always be recorded, make the shipment a request:
          expose transient shape ShipOrder { ... }
          never (ShipOrder where order is CancelledOrder)
      and let a rule record the Shipment (README §4, §21).
   ```

   For the permit case, the same form with two more lines in the list, because the predicate has a `not` and reads the clock:

   ```
       - a Permit removed, or moved to another trial, while the trial has
         a Planting
       - every tick once a Permit's expiresOn has passed, and every act
         after it, until the permit is renewed or the planting removed
   ```

   The message names acts, in the author's shape names, and rules where a rule produces one of them. It says nothing about reads, footprints, or binding; that vocabulary is for this document and `checks.md`, not for the author (Dave, 2026-10-10).

   *The trigger.* A `never` whose target is a persistent exposed shape and whose predicate reads any shape other than the target's own stored fields, or reads the clock. The list of acts is the predicate's read footprint turned into acts: for each shape read, creation and the edits that change the path; deletion where the read is under a `not`; ticks where the clock is read. Rules that produce any of those are named too. Three cases do not fire: a transient target, which is a refusal by construction; a predicate over the target's own fields only, which behaves as a refusal because only the act that creates or edits the record can violate it; and a prohibition with no external writer, which is a guarantee and is proven rather than rejected.

   *The acknowledgement.* Following A5, an acknowledgement on a `never` this analysis would not fire on is dead and flagged. The spelling is the open piece. The `tolerates` family is the existing pattern, but "tolerates" is the wrong word for a constraint the author means; a spelling that says the author is binding the books on purpose fits better. Keyword undecided.

   *Calibration.* Every legitimate books rule fires this once: a spending cap across a customer's orders, a uniqueness over siblings. That is by design, since each needs the author to have seen its list of acts once, but a spec with many constraints carries many acknowledgements. A5 accepted the same trade.

   *Objection* (Dave, 2026-10-10, not yet convinced the advisory earns its place). In the normal case the author intends both acts to be invalid: a shipment on a cancelled order and a cancellation of a shipped order are both wrong, and one constraint says so. The "write the never over Cancellation instead" discharge then forces a redundant prohibition to fix what is only a message problem, and the message problem is Part 2's to solve. If that is the normal case, the advisory is noisy: it fires on every cross-shape constraint to catch the one author who meant a one-sided refusal.

   *Decision* (Dave, 2026-10-10). If the rejection message is clear enough to the end user, the advisory is not needed. (The spec generator today writes no case at all for a `never` with a non-comparison predicate, so the shipping example's constraint has no generated test; see the v0 stance.) What remains worth keeping is the calculation: velle can identify these cases, and there could be a legitimate gap the author did not intend, so the identification should be exposed and documented rather than raised as a warning. One form: generated automated tests whose purpose is to make the assumption visible and executable, one test per act the constraint can reject, each asserting the refusal and carrying the rendered message. The spec then documents its own constraints in the generated suite, and an author who did not intend one of them sees it there. A second form, levels of advisory checks as a documenting artifact of validation, is on `TODO.md` for after this question closes.

   *Proposed narrowing* (superseded by the decision above; kept for the clock case, which may still want a generated test or a documented note rather than a warning). Drop the general advisory. With Part 2's rendering in place the one-sided-refusal author is self-correcting: the first unexpected rejection names the protected record and the path, and the business changes the velle, which is the remedy already recorded. The argument against option 2 (a README note only) was made when the message was "never #1 over Shipment is violated", and is weaker now. What survives is one narrow trigger with a hazard beyond phrasing: a constraint that reads the clock. The permit case rejects on every tick and blocks every act's transaction until a person acts, with the tick method throwing each time, which Dave has called likely undesired in any velle. That advisory keeps the permit-style message and the transient-request and acknowledgement discharges, and fires nowhere else. README §21 carries the two-kinds explanation with the symmetric rejection as its worked example.

2. **A README §21 note only**, recommending the transient spelling for door rules. Rejected: the author must see the choice at the line where they wrote it.
3. **A restriction**: a `never` over an exposed persistent shape may read only its own stored fields. Rejected: it takes the decision away from the human, and it forbids legitimate books rules (a spending cap across all of a customer's orders).

README §21 should also present the two as different kinds of statement with different subjects, not as one rule that diverges when the world moves: a `never` on a request binds that request and nothing else; a `never` on a record is an invariant over the books, enforced against whoever would break it, with the enforcement points derived rather than chosen. Its current sentence, that the two "differ exactly when the world moves after the record is accepted", carries the one-thing impression and should be reconsidered at settlement.

## Part 2 — what the rejection carries, and who hears it

Once the choice is "rejected", the person has to be able to act on the rejection. For the shipment case, with the books rule kept on purpose, today's "never #1 over Shipment is violated" is not actionable. What the system knows at the moment of the rejection, and what the message must carry:

1. **What was refused**: the act, with its fields. "The cancellation of order 4471, requested 2026-10-07."
2. **Which rule refused it, in the author's words.** Today the only words are the predicate. An author-supplied sentence on the `never` is the obvious source: "a shipment is never on the books for a cancelled order". Because the sentence is written over state rather than over an act, it reads correctly whichever act was refused, which is what makes the symmetric books rule bearable without choosing a side.
3. **Which records would have been in the forbidden state.** Not the act: the shipment of 6 October on order 4471. The rejection is about a record other than the one being submitted, and the person needs to see that record to understand it.
4. **The path from the act to the violation**, when it is indirect: the cancellation makes order 4471 a `CancelledOrder`, the shipment's `order` is 4471. When a rule firing stands in between (the act triggered a rule whose effect produced the state), the rule's name belongs here too.

For the permit case the actor is the tick: which schedule, which moment, the rule, the planting, and the permit whose lapse did it.

**What velle can derive for the payload** (recorded 2026-10-10). Nearly all of the four items come from machinery that already exists; the author's own words are the one thing that does not.

- *From the spec.* The prohibition's target shape and predicate, decomposed as a path of named things: Shipment, through `order`, to Order, to the refinement CancelledOrder, to "exists a Cancellation". The names along the path are the author's higher layer: the author chose to name CancelledOrder rather than write the `exists` inline, and the rendering speaks in that name. The read footprint (the serialization-domain derivation, `evaluation.md`, OQ40) says which acts can reach this constraint at all.
- *From the evaluation.* The guardrail evaluates the predicate over pre-state plus the act. Refinement membership between two states is what entry rules already compare (the transition law, README §11), so the exact set of target records that would newly enter the forbidden refinement is a diff, not a search: here one shipment, 6 October on order 4471. An evaluation that keeps its witnesses gives the chain of bindings that made each level true: this shipment's `order` is 4471; 4471 is a CancelledOrder because its cancellations are non-empty; the cancellation that makes it non-empty is the act being refused. The path from the act to the violation is the evaluation trace, not an inference.
- *From the transaction.* When a rule's effect produced the state, the firing graph names the act, the rule, and the effect. When a tick did it, the actor is the schedule and the moment, and the bottom witness is the clock read (the permit expired 2026-10-08; today is 2026-10-09).
- *Everything else in scope at the evaluation* (Dave, 2026-10-10). The witness trace is the spine, not the limit. Velle can hand over all the context it holds while evaluating the prohibition: the arriving act with its fields, every record and field value the predicate read along the way, the moment of the check (the boundary guardrail or the transaction end), the transaction's commits so far when the check is at the end, and the clock value when the predicate read it. The binding code decides how much of that to show; velle's job is not to have thrown any of it away.

Rendered top-down in the author's names, with nothing authored beyond the spec:

```
Refused: Cancellation of order 4471, requested 2026-10-07.

Shipment on order 4471, shipped 2026-10-06, would be a
  Shipment where order is CancelledOrder     (never #1)
because order 4471 would be a CancelledOrder
because of this cancellation.
```

The other direction uses the same trace: a Shipment arriving on an already-cancelled order is refused with itself as the violating record, and the witness is the existing cancellation, so the person is pointed at a record that already exists rather than at their own act.

**Narrating from the act, through the pivot** (Dave's requirement and the derivation, 2026-10-10). The rendering above is correct and awkward: it tells the story from the violating record down, and the user's story runs the other way. The message the user wants is "you can't cancel this order because the order has been shipped": the act and what it is about, a fact about that same thing, and the rule that the two cannot coexist. The user never thinks about a Shipment being in a forbidden state. They think about the order, because the order is what they acted on and both facts are facts about it.

That order is a structural fact in the trace: the record the act references that lies on the witness path, where the act's path and the violating record's path meet. Call it the pivot. Narrated from the act, through the pivot, the same trace reads:

```
Cancellation for order 4471 refused.
Order 4471 has a Shipment (shipped 2026-10-06), and a Shipment is never
on an order that has a Cancellation.                           (never #1)
```

Nothing new is computed. The one restatement is the `never` read at the pivot, "an Order never has both a Shipment and a Cancellation", which is mechanical when the predicate has the shape "target references pivot, and pivot is in a refinement defined by the existence of something", the common shape for cross-record constraints. It generalises: the permit case pivots on the trial ("Trial T-12's permit expired 2026-10-08. A Planting is never on a trial without a Permit in force."); a spending cap pivots on the customer ("Customer 88 has orders totalling 950. This Order of 100 would make 1050, and a customer's orders never total more than 1000."); a sibling uniqueness pivots on the parent ("Assay A-3 already has a reading at hour 24."); a self-reference has no pivot beyond the record and degenerates to "Customer 12 cannot refer themselves".

*Status of the rendering* (checked 2026-10-10). Grounded: `checkNevers` (`compiler/src/main/kotlin/velle/Runtime.kt`) already keeps a per-prohibition read summary, a clock flag, and footprint-based relevance gating, so "which acts can this reject" reads data the runtime holds. Across the checked-in specs there are 38 `never` declarations: 26 read only the target's own fields, 2 are self-reference, and all 10 cross-record ones reach other records through exactly one reference on the target, so every real case has a single pivot. The violating-record-is-not-the-act phenomenon is real: the five sibling-uniqueness prohibitions (`examples/peptides/peptides.velle` lines 119, 299, 578, 745, and `Treatment` at 271) put both siblings in violation and the runtime throws on whichever id it visits first, and `PlotObservation where not plot.trial is TreatedTrial` (line 304) can flip for an observation on file. Speculative: the witness trace does not exist (the check is a boolean membership test that throws a string, with no bindings kept, and the evaluator has not been assessed for a witness-keeping mode); the restatement of the `never` at the pivot is hand-written for four cases with no algorithm, and the real predicates come in several shapes (`count(...) > 1`, `not ... is`, a comparison of two paths in `examples/gallery/gallery.velle` line 41); and the shipment rendering, two different shapes meeting at an order, has no checked-in example, the nearest being the sibling counts where both records are the same shape.

Two limits, both vocabulary rather than structure. Velle has shape names, not verbs: "a Cancellation for order 4471", not "cancel this order"; "has a Shipment", not "has been shipped". The author's names are the ceiling, which is also where naming pays: an author who names `ShippedOrder` gets "order 4471 is a ShippedOrder and would become a CancelledOrder", nearly the user's sentence. And when an act has several references on the witness path, velle lists each as a pivot, which is correct but longer.

Dave, 2026-10-10, on the three things velle cannot compute:

- **Who hears it: deferred.** Velle provides a rejection shape, the structured payload above, to the binding code (the engineer's transport and store wiring), and the binding code formats the human-readable response. Velle does not deliver to a person.
- **What to do about it: the business's.** The likely answer is a more refined velle. Velle's only obligation is that the rejection carries enough information for that decision to be made.
- **The intent sentence: the requirement is context, not a sentence.** Velle must provide enough context for the business and the users to understand what is happening. The derived rendering above is the floor and must be sufficient on its own; the author-supplied sentence remains a candidate addition on top of it, undecided.

Who hears it:

- **An act's caller.** The generated commit function returns the refusal; the engineer's binding code carries it to whoever submitted the act. This exists. Its content is the gap. This includes a forbidden state produced by a rule the act triggered (Dave, 2026-10-10): the act caused it, so the submitter is told, the same as any other refusal, and the payload carries the rule and the effect because the trace does. Making the rule's owner aware is outside the language; it is a concern for the runtime's error logging, which the binding code owns.
- **A tick.** There is no caller. The rejection goes back to the harness as an error, `evaluation.md` says nothing about it, and it is a standing condition: it repeats at every tick, and when the `never` reads the clock it rejects every act's transaction too. Dave, 2026-10-10: a tick rejection is an edge case and likely undesired in any velle, since it produces repeated errors. For now, the generated tick method throws an exception carrying the same rejection data model and the same rendered message as the exposed act's commit function returns. The harness sees the exception; what it does with the repetition is the binding code's.

Candidates:

1. **A rejection shape in the engineer contract, carrying the derived context, with an author-supplied sentence as an optional top line (recommended; the shape and its delivery to binding code confirmed by Dave 2026-10-10).** Dave, 2026-10-10, on what the runtime returns when it rejects an act: two things. First, a rejection data model carrying all the context the glue layer (the binding code) would need to generate a more specific message, both to the user and to a logging or reporting system; this is the derived context above, held as data rather than as text. Second, for now, a rejection message rendered by velle itself from that model, with enough context that writing glue for every rejection is not required. The default rendering above is what that message looks like for the shipment case. The sentence is item 2 above; syntax undecided (a string after the predicate is the smallest form). The surface is a second engineer-facing hook beside the commit callback in `evaluation.md` "The universal transaction": every rejection, act-caused or tick-caused, is handed to it as one structured payload carrying the four items with the witness trace behind them, and wiring it to a person is the engineer's, exactly as the transport and the store are. For an act the payload also comes back as the commit function's result, so the existing path is unchanged. For a tick, the v0 answer is narrower than the hook: the tick method throws with the same model and message (see "A tick" above), and the hook is a later refinement if the repetition needs handling inside velle. A rejection is never committed state (nothing lands on a refusal, and that stays), but the payload is the engineer's to persist outside the state if the owner wants a rejection log.
2. **The sentence only**, leaving delivery to the engineer's error handling. Rejected by the principle: a tick's rejection today is an error with no reader.
3. **Rejections as state**: commit a rejection record so a rule can react to it. Rejected: a refusal means the proposed state does not exist, and a record of it inside the same state is the proposed state leaking in. The owner who wants a rejection to be a fact in the books chooses the record-and-show spelling instead.

## What settling this asks of the docs

`checks.md`: the advisory of Part 1, in A4's family, with its discharge spelling. README §21: refusal and constraint as different kinds with different subjects (replacing the "differ exactly when" sentence), and the author-supplied sentence. `evaluation.md`: "Ticks" gains the rejected-tick paragraph; "Errors, refusals, and retry" names the rejection data model and the default message rendered from it; "The universal transaction" gains the rejection hook beside the commit callback. `diagrams.md`: the accept/refuse split carries the sentence as its label.
