# OQ46 — Which runs first at a commit: the rules that read a series, or the `never` that keeps the series well-ordered?

**Status:** open (2026-09-30) — surfaced by the peptides stress pass (`break-peptides.md`, S3); the example's test asserts today's behaviour (a runtime error, nothing landed) rather than the refusal the author wrote
**In plain terms:** the spec says "two readings at the same hour mark are impossible", and the author expects a second hour-24 reading to be refused. Today the rules that read the series run first, trip over the duplicate while picking "the latest earlier reading", and the caller gets a runtime error instead of the refusal. Should the refusal come first, and if so, what state does the refusal check look at?
**v0 stance:** none ruled. The runtime evaluates every `never` at transaction end only (`compiler/src/main/kotlin/velle/Runtime.kt`: `checkNevers`, called from `inTransaction` after the body has run). The boundary validation that `evaluation.md` describes as the first step of an exposed call (quoted below) promises a guardrail for input-constrained invariants; it is not implemented: the commit path (`Runtime.kt`, `commitShape`) does the type-level checks and then opens the transaction.
**See:** README §21 (`never`: enforced at transaction end; input-constrained invariants compiled into the boundary) · README §10 (selector ordering: a tie at the winning position fails loudly) · `evaluation.md` "An exposed call" (steps 1 and 5) and "Errors, refusals, and retry" · `checks.md` V10 · `examples/peptides/peptides.velle` (`AssayReading` and its uniqueness `never`) · `examples/peptides/output/src/test/kotlin/velle/PeptidesPipelineTest.kt` (`lab - a reading entered late slots into its hour mark and the running peak re-derives`)

---

## The case

A trimmed, self-contained version of the lab stage of `examples/peptides/peptides.velle`. A plate is read at hour marks; each reading's running peak is the larger of its own value and its predecessor's running peak, where the predecessor is the latest reading at an earlier hour mark (README §19's predecessor recurrence). The plate's peak is the running peak of its last reading. A plate whose peak clears sixty percent is a strong plate, recorded once.

```
expose shape Assay {
    replicate: integer
    peakInhibition: decimal = if assayReadings is empty then 0.0
                              else latest(assayReadings by hoursElapsed).runningPeak
}

expose shape AssayReading {
    assay: one Assay
    hoursElapsed: integer
    inhibitionPercent: decimal
    previous: one AssayReading? = latest(AssayReading where assay == this.assay and hoursElapsed < this.hoursElapsed by hoursElapsed)
    runningPeak: decimal = if previous is some then max(previous.runningPeak, inhibitionPercent) else inhibitionPercent
}

-- two readings at one hour mark would leave the series unordered
never (AssayReading where count(assay.assayReadings where hoursElapsed == this.hoursElapsed) > 1)

shape StrongPlate = Assay where peakInhibition >= 60.0

shape StrongPlateRecord {
    assay: one Assay
    recordedOn: timestamp on create
}

rule RecordStrongPlate when (StrongPlate where not exists StrongPlateRecord for this) {
    StrongPlateRecord from { assay: this }
}
```

The `never` is input-constrained in `checks.md` V10's sense: its predicate reads only `hoursElapsed` and the inferred inverse `assayReadings`, and no rule writes either. README §21 says such an invariant is compiled into the boundary, and `evaluation.md` says the guardrail runs before the transaction begins, so the author expects a duplicate hour mark to be a refusal that names the `never`.

The algorithm this question is about, quoted from `evaluation.md` "An exposed call" (the step numbers below refer to it):

```
commitAct(actInstance):
  1. boundary validation: type-level checks first, then the input-constrained
     `never` guardrails compiled to this expose site (§21) — violation → refusal
     naming what was violated; nothing begins
  2. begin transaction T
  3. C0 := apply the act commit — insert the instance with the committer's fields
  4. process(C0)                                   -- may recurse, growing T
  5. transaction-end `never` check over settled post-state (§21)
  6. any error in 3–5 → roll back T entirely; report the error to the caller
  7. make T durable
  8. drain the after-commit queue accumulated in T
```

The two evaluation moments for a `never` are the boundary validation (step 1, before the transaction) and the transaction-end check (step 5, after processing).

## What happens today, step by step

**Before the commit.** One plate, three readings, entered in the order 0, 48, 24 (the hour-24 reading arrived late, which README §19 says is fine: it slots in by its hour mark). The derived values as they read at this moment:

| reading | `hoursElapsed` | `inhibitionPercent` | `previous` | `runningPeak` |
|---|---|---|---|---|
| r0 | 0 | 10 | none | 10 |
| r24 | 24 | 80 | r0 | 80 |
| r48 | 48 | 30 | r24 | 80 |

The plate's `peakInhibition` is r48's running peak, 80. The plate is a `StrongPlate`, and a `StrongPlateRecord` is already on file for it from the commit that made it one.

**The event.** The caller commits a second reading at hour 24: `commitAssayReading(plate, hoursElapsed = 24, inhibitionPercent = 81)`.

**What the runtime does**, numbered as in the algorithm quoted above:

1. Boundary validation. The type-level checks pass (an integer, a decimal, a reference to a plate that exists). No `never` is evaluated here: v0 has no boundary guardrail.
2. The transaction opens.
3. The act lands: a fourth reading, call it r24b, with `hoursElapsed` 24 and `inhibitionPercent` 81.
4. Processing. The commit created an `AssayReading`, so every refinement whose predicate reads that shape is re-evaluated for the instances it could affect. `StrongPlate` reads `peakInhibition`, which reads `assayReadings`, so the plate is re-evaluated:
   - `peakInhibition` = `latest(assayReadings by hoursElapsed).runningPeak`. The candidates are r0, r24, r48, r24b. The winner is r48, and nothing else shares hour 48, so this selection succeeds.
   - r48's `runningPeak` reads r48's `previous` = `latest(AssayReading where assay == this.assay and hoursElapsed < 48 by hoursElapsed)`. The candidates are r0, r24, r24b. Sorted by hour mark, the last position is hour 24, and two readings sit there: r24 and r24b. The selector was asked for one reading and has two that are equally entitled to be picked. README §10's rule for this situation, in plain words: `latest` and `first` order the candidates only by the datums the author named in the `by` clause, nothing else (no creation order, no `id`). If the named datums leave two or more candidates tied for the winning position, the language does not pick one of them by some hidden rule, because any such pick would be arbitrary and could silently change the value the author reads. Instead it treats the tie as a fault in the model: the author's `by` clause does not say enough to choose, and the fix is to name a further datum that breaks the tie. So the evaluation stops with an error. The runtime throws `VelleRuntimeError`: "latest(... by hoursElapsed) does not discriminate: 2 instances share the ordering datum — declare a further criterion (README §10)".
5. Never reached. The transaction-end `never` check would have found r24b with a count of 2 at its hour mark and refused the act by name.
6. Rollback. The caller receives the runtime error. Nothing landed; the table above is unchanged.

The caller cannot tell from the error that the act was the problem. The message names a datum (`hoursElapsed`) and a selector, not the act, and says the model is underspecified. The model is not: the author stated the invariant that rules this configuration out.

**The outcome depends on things the author cannot see from the `never`.** Two variations of the same event:

- Remove `StrongPlate` and `RecordStrongPlate` from the spec, so nothing reads the series during processing. Step 4 touches nothing, step 5 runs, the `never` finds count 2 for r24b, and the caller receives the refusal "never #1 over AssayReading is violated". Same act, same invariant, the outcome the author wrote. Whether a duplicate is refused or errors depends on whether some rule elsewhere in the spec happens to read the series.
- Duplicate hour 48 instead of hour 24. Then the plate's own `latest(assayReadings by hoursElapsed)` ties at the winning position, and the error comes from a different selector. Which selector fails depends on where in the series the duplicate lands.

## Why the boundary validation step as written does not settle it

The boundary validation (step 1 of the algorithm quoted above) says the input-constrained guardrails run before the transaction and that a violation is a refusal, but it does not say what state the predicate is evaluated against. The two readings give different answers for this `never`:

- **Pre-state plus the act, as if the act had landed and nothing else.** The count at hour 24 is 2, the predicate `> 1` is true, the act is refused. The spelling is the same one the transaction-end check evaluates.
- **Pre-state only, with the act's fields in scope.** The count at hour 24 is 1 (the act itself is not among the instances counted), `> 1` is false, and the guardrail never fires. The author would have to write `>= 1` for the boundary. But `>= 1` cannot also serve the transaction-end check in step 5: in settled state every reading counts itself, so every reading would violate it. One spelling cannot serve both moments under this reading, which rules it out as long as the transaction-end check remains.

So the first reading is the only one under which an input-constrained `never` keeps a single meaning at both of its evaluation moments.

## Candidates

### 1. Implement the boundary validation's `never` guardrail over pre-state plus the act's closure, before processing

The guardrail (step 1 of the algorithm quoted above) evaluates each input-constrained `never` as the state would stand with the act's closure landed and nothing processed, for the instances the act's own commit could affect. If any is in the forbidden configuration, the act is refused by name and processing never starts.

What this is in the existing machinery: `Runtime.kt`'s `checkNevers` already takes a footprint and checks only the invariants that footprint makes relevant. Running it once more with a footprint of the act's commit alone, restricted to the input-constrained invariants (the classification `Validator.spendableNevers` computes today, which the runtime would need from the model), immediately after the act's insert and before `process(C0)`, is the whole change. Doing it inside the runtime's in-memory transaction rather than literally before it is observably the same as the documented boundary validation: nothing has been processed, and a refusal reaches the engineer's store as nothing at all, which is what `evaluation.md` "Errors, refusals, and retry" promises.

What the README would say: one sentence under §21's "compiled into the boundary", that the boundary check evaluates the invariant over the state as it would be with the act landed and nothing else, so the spelling is the same as the transaction-end spelling, and the boundary check is in addition to the transaction-end check, not instead of it (the same sentence OQ45 asks for).

What it does not change: a tie inside a `never`'s own predicate (a `never` written with `latest`) is still a runtime error, because that predicate's meaning is still undefined at the tie. And the transaction-end check still runs, which is what catches the clock and deletions.

A question this leaves open: a deleting commit is also an exposed act, and deleting can make a `not exists` invariant true. Whether the deleting commit gets the same pre-processing check is `evaluation.md` "The deleting commit" territory and is not decided here.

### 2. Keep the order and make a selector tie a refusal category of its own

The error already names the datum; a refusal would name the act: "the act would leave `assayReadings` unordered by `hoursElapsed`". Against it: a tie is the model being underspecified, which is a meaning the spec leaves undefined, and the design philosophy records that as an error, not a refusal. Attributing the tie to the act that first observed it is also wrong in general. Without the `never`, a duplicate lands silently, and the tie is first observed by whatever later commit happens to make a series-reading rule relevant, which may be a reading on a different plate. With candidate 1 in place and the `never` present, the tie cannot arise from this act at all, so the category would exist only for specs that forgot the invariant.

### 3. Keep both as they are and document that uniqueness of an ordering datum is the caller's to guarantee

As the correlation key is (README §4). Against it: the author wrote the `never` precisely because the caller is not trusted with it, and candidate 1 costs no language surface.

## What the author meant by writing the `never`

Dave's observation (2026-10-09): the author wrote the `never` explicitly to prevent an invalid series reading from entering the state. That intent only makes sense if the `never` is read before anything else happens to the reading. An invariant meant to keep bad input out is a statement about the act's arrival, and checking it after the rules have already run against the bad input is checking it too late.

The same point from the rules' side. Every rule that reads the series goes through `latest(... by hoursElapsed)`, and that selector is well-defined only while no two readings share an hour mark. So the invariant is a precondition of every read of the series: the rules assume it, they do not establish it. Evaluating those rules before the invariant has been checked on the new reading evaluates them against a state the spec has declared impossible, and the selector's error is the symptom of that. The order that respects what the spec says is: establish that the state the act would produce is a possible one, then run the rules against it.

## Recommendation

Candidate 1. It is the order the `never`'s own purpose implies, as the section above says. It is also what the rejection owes the person who receives it (OQ47): a rejection must say which `never` was violated, in the author's words, and by what. A selector error that says the model is underspecified, when the model is not, tells the caller the wrong thing about their own act. It implements the guardrail that README §21 and the boundary validation step of `evaluation.md`'s exposed-call algorithm already promise, it fixes the state the guardrail reads so that one spelling serves both moments, and it keeps the error/refusal split where the design philosophy puts it: a refusal is the spec saying no, an error is the spec saying nothing.
