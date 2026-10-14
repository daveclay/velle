# OQ58 — Runtime follow-ons: candidate narrowing, the production clock, timestamp ties

**Status:** open (2026-10-14) — the runtime model is settled (README §22; `evaluation.md` "The universal transaction"); these are the items it left open, plus one the peptides pass found
**In plain terms:** the runtime still reads a watched shape's whole table once per transaction where the committed record names the one row that could have changed; the generated system's clock is harness-controlled where production wants real time; and two records committed in one clock instant tie on a `timestamp on create`, which breaks any ledger ordered by it.
**See:** README §10, §12 (the ledger), §22 · `compiler/src/main/kotlin/velle/Runtime.kt`, `Hydration.kt`, `Codegen.kt` (`clock()`)

---

## 1. Reverse-path candidate narrowing (`Runtime.kt`: relevance gating and the candidate pre-filter)

A non-self-contained commit watcher (`PaidInvoice` reads `Payment` rows) still scans its base once per envelope. The right fix derives the affected instances from the mutation itself: the committed `Payment` names its `invoice`. That is impact analysis applied per commit, walking the predicate's read paths (not just its read sets) backward from the mutation. Two smaller items ride with it: bare-shape conditions (`when Payment`) scan their own table only to diff entrants that are, by construction, exactly the commit's creates; and aggregate pre-filters (`count(...) == 0` as NOT EXISTS, sums as aggregation subqueries), which the query IR does not carry yet.

## 2. Real time by default (`Codegen.kt`, `clock()`)

The generated `System` has a harness-controlled clock (`setTime`, `advanceSeconds`, `advanceDays`; `Codegen.kt`, `clock()`). Production use wants real time as the default, with the controllable clock kept as the test affordance. The billing app passes real time explicitly today.

## 3. A ledger ordered by `timestamp on create` ties within one clock instant (peptides S4)

The ledger spelling from README §12, the score in force being the latest correction:

```
shape ObservationCorrection {
    observation: one PlotObservation? initially required
    previousSeverity: decimal
    correctedSeverity: decimal
    reason: text
    correctedOn: timestamp on create
}

act PlotObservation {
    plot: one GreenhousePlot
    dayAfterTreatment: integer
    recordedSeverity: decimal
    observedOn: Date
    diseaseSeverity: decimal = if observationCorrections is empty then recordedSeverity
                               else latest(observationCorrections by correctedOn).correctedSeverity
}
```

fails loudly the moment two corrections to one observation land at the same clock instant: the harness clock only moves when a test advances it, so two consecutive commits share `now`, and `latest(... by correctedOn)` ties. README §10 already says same-transaction siblings share a timestamp by definition; two transactions at one instant are the same hazard, and a real clock at millisecond resolution can produce it too. The test advances the clock sixty seconds between corrections (`greenhouse - a correction is a ledger entry the curve reads, never an overwrite` in `PeptidesPipelineTest.kt`). README §12's own `EmailCorrection` example carries the same exposure.

**To decide:** either README §10/§12 note the exposure and recommend an author-carried sequence datum beside the timestamp (the caller supplies `sequence: integer`, as it supplies the correlation key), or the harness clock advances by one unit per commit so the tie cannot happen in tests, which hides it rather than answering it.

## 4. The sixteen compiler tests failing at HEAD

`BillingFixtureTest`'s billing fixture inventory, `RuntimeTest` committing `Customer` directly (billing enters through `SignUp` now), and fixture drift in `RuleGraphGenTest`, `StateFlowGenTest`, `DiagramGenTest`, `QueryCompilerTest`. Verified present on a clean checkout before the store pass (2026-09-14); still the same sixteen on 2026-10-14.
