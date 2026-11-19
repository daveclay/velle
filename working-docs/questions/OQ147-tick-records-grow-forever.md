# OQ147 — Does Velle keep every tick of a schedule, or only the latest?

**Status:** opened 2026-11-19 from Dave's reading of the pharmacy app's stored records. D1, D2, and D4 decided 2026-11-19; D3 is moot under D2. D5 and D6 await Dave. No work items yet.

**In plain terms:** every time a schedule runs, Velle writes down that it ran, and it never removes what it wrote. A schedule that runs every minute leaves about 526,000 records a year. Velle itself only ever looks at the most recent one.

**See:** Reference §17 "What Velle records about a tick" (the promise that a spec reads `Tick` as any shape); `evaluation.md` "Ticks", the bullet "The tick's own record" (why the record goes through the ordinary commit path); OQ71 in the settled table of `working-docs/QUESTIONS.md` (D3 there made `Tick` a shape with one record per tick, which this question reopens in part); `working-docs/questions/OQ34-schedule-definition.md` (the missed-run item reads the latest `Tick`); OQ112 in `working-docs/QUESTIONS.md` (the pharmacy seed's tick count); `working-docs/examples/survey/23-tick-history-read.velle`.

---

## 1. What runs today

**Who decides that ticks are stored.** Velle does, for every system and every store. It is not a choice of `examples/pharmacy/pharmacy.velle`, which never names `Tick`, and not a choice of a store: nothing under `stores/` names a tick.

- The compiler declares the shape ahead of every spec, in `Model.PRELUDE` (`compiler/src/main/kotlin/velle/Model.kt`):

  ```
  shape Tick {                      -- declared by Velle; a spec that declares its own `Tick` is refused (checks.md F1)
      schedule: text                -- the schedule's name, written by the runtime
      at: timestamp on create       -- the moment the tick ran, set once by the commit that creates the record
  }
  ```

- The runtime creates one `Tick` record after a tick's firings, through the ordinary commit path (`compiler/src/main/kotlin/velle/Runtime.kt`, `tickInScope`).
- Nothing in the runtime removes a `Tick` record.

**What Velle reads back.** One record per schedule. `previousTick` in `Runtime.kt` queries the latest `Tick` for the schedule, ordered by `at`, one result. A `when leaving` rule on a schedule compares against that moment (Reference §13). The missed-run item in OQ34 would also read only the latest.

**What a spec may read.** All of them. Reference §17 says a spec reads `Tick` "over the API, in a query, or in a rule's condition". `working-docs/examples/survey/23-tick-history-read.velle` validates with no diagnostic: it counts every `Tick` of a schedule and also reads the latest one.

**Who uses that today.** No spec under `examples/` names `Tick`, and no survey spec did before file 23.

**How it grows.** One record per run of each schedule, whether or not anything happened in the business.

- `compiler/src/test/kotlin/velle/TickRecordTest.kt`, "a leaver between two ticks is noticed after a restart, because the previous tick is in the store", ends by asserting three `Tick` records after three ticks.
- The pharmacy seed in `examples/pharmacy/app` writes 274 `Tick` records over ninety days (OQ112 in `working-docs/QUESTIONS.md`).
- A schedule that runs every minute would write 1,440 a day.

**What Dave has already said nearby.** On OQ71 D5 (2026-10-28) he removed the system-wide lists of firing outcomes with "no history of firings across ticks". OQ71 D3, the same day, kept one `Tick` record per tick. That is the part reopened here.

## 2. What to decide

The decisions in this file:

- **D1.** Whether Velle keeps only the latest tick of each schedule.
- **D2.** Whether a spec can still read when a schedule last ran.
- **D3.** Whether the older `Tick` records of an existing system are removed. Moot under D2's answer.
- **D4.** Whether the store contract gains members of its own for ticks, one moment kept per schedule.
- **D5.** Whether an engineer can read when a schedule last ran from the running system.
- **D6.** Whether a system that already holds `Tick` records carries its latest one over.

D1 is a removal: it takes away something that runs today, the reading of tick history by a spec. D2 and D3 only apply when D1 is answered yes. D4, D5, and D6 were added on 2026-11-19 after D2 was answered: they are the build questions its answer raised.

### D1. Velle keeps only the latest tick of each schedule · Decided 2026-11-19

Does Velle keep only the latest tick of each schedule, and stop keeping one record per tick?

Recommendation: yes. Velle needs one moment per schedule, and a table that grows with the clock, not with the business, is a cost every system pays for a history none of the examples reads.

What is lost: a spec can no longer read the history of a schedule's runs. This runs today and would stop meaning what it says.

The spec in `working-docs/examples/survey/23-tick-history-read.velle`, shown whole, with what each line means today and after a yes:

```
-- declared by Velle ahead of this spec (Model.PRELUDE); shown here for the field types
shape Tick {
    schedule: text                              -- the schedule's name
    at: timestamp on create                     -- the moment the tick ran
}

act Permit { site: text  expiresOn: Date
  nightlyRuns: integer = count(NightlyRun)      -- today: every Nightly tick since the system started, 365 after a year.
                                                -- after a yes: at most 1, so the count says nothing
  lastNightlyRun: DateTime? = latest(NightlyRun by at)?.at }
                                                -- today and after a yes: the moment Nightly last ran, absent before the first run

shape NightlyRun = Tick where schedule == "Nightly"
                                                -- the compiler accepts a refinement over Velle's own shape

shape Valid = Permit where expiresOn >= today   -- a permit still in force

shape Lapse { permit: one Permit  noticedOn: Date }

rule NoticeLapse when leaving Valid on Nightly {
  Lapse from { permit: this, noticedOn: today } -- fires for a permit that was Valid at the previous Nightly tick and is
}                                               -- not now; the previous tick's moment is all Velle reads of `Tick`
```

A system that wants a history of runs after a yes records it with a rule of its own, as `examples/pharmacy/pharmacy.velle` already does for its monthly inventory with the rule `SnapshotControlled` and the shape `ControlledInventorySnapshot`: one record per subject per tick, written because the business asked for it.

Costs:

- A product owner can no longer ask "how many times has `Nightly` run" or "did `Nightly` run on the 3rd" of Velle's own record. They say it with a rule that records each run, and the growth is then their decision.
- A spec that counts `Tick` records still validates and silently means something else. The meaning is defined, so by the principle that the compiler advises when a meaning is defined but may not be intended, this is at most an advisory. No spec in the repo is affected.
- It leans on the principle that a system's state is what the rules record: after a yes, the only thing Velle keeps on its own account is the one moment it needs.
- The alternative that keeps history is a retention period on `Tick` (delete records older than some age). That is a second mechanism, it needs a number someone has to pick, and Velle would be deleting records on a timer of its own, which nothing else in Velle does. Not recommended.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`, `tickInScope`: the tick writes its schedule's one record instead of creating a new one.
- `compiler/src/main/kotlin/velle/Runtime.kt`, `previousTick`: reads the schedule's one record, with no ordering by `at`.
- `compiler/src/test/kotlin/velle/TickRecordTest.kt`: the restart test asserts one `Tick` record after three ticks, where it asserts three today; a new test pins that two schedules keep one record each.
- Reference §17 "What Velle records about a tick": "Every tick leaves one record behind" becomes one record per schedule, holding its latest run; a sentence says a history of runs is a rule of the author's own.
- `evaluation.md` "Ticks", the bullet "The tick's own record": "creates one row" becomes writes the schedule's row.
- `wiki/Guides/Concurrency.md`, the row for the hourly tick's own record, and `checks.md` F1 ("the record of a schedule's ticks"): reworded to the latest tick.
- `working-docs/examples/survey/23-tick-history-read.velle` and its README line: updated to what the spec means after the change.

**Decided:** yes, 2026-11-19. Dave: the growing table is the actual cost of having made `Tick` a shape, which he was hesitant about when he decided it in OQ71. He cannot imagine an engineer wanting a stored record for every run of a task that runs every five minutes, outside of a logging framework. Today's schedules are examples of what the schedule feature will be capable of, so the lack of a five-minute schedule today is no argument. An engineer who wants a record of an occurrence writes a rule, a shape, or a field for it, explicitly.

### D2. A spec can still read when a schedule last ran · Decided 2026-11-19

After D1, does `Tick` stay a shape a spec can read, holding one record per schedule, or does it leave the language?

Recommendation: it stays. One record per schedule, its moment moved forward at each tick.

Option A, `Tick` stays, with the declaration Velle would make and what the spec from D1 means under it:

```
-- declared by Velle ahead of every spec
shape Tick {
    schedule: text                              -- the schedule's name; one record per schedule, created at its first tick
    at: timestamp on update                     -- moved forward by the commit that records each tick (Reference §5,
}                                               -- "Timestamps"); `on create` would stay at the first tick forever

act Permit { site: text  expiresOn: Date
  lastNightlyRun: DateTime? = latest(NightlyRun by at)?.at }
                                                -- accepted: the moment Nightly last ran

shape NightlyRun = Tick where schedule == "Nightly"
                                                -- accepted: zero records before the first Nightly tick, one after
```

Option B, `Tick` leaves the language, against the same spec:

```
act Permit { site: text  expiresOn: Date
  lastNightlyRun: DateTime? = latest(NightlyRun by at)?.at }
                                                -- refused: `NightlyRun` is over a shape that does not exist

shape NightlyRun = Tick where schedule == "Nightly"
                                                -- refused, F1: unknown name `Tick`. The author who wants the moment
                                                -- of the last run writes a rule on Nightly that records it
```

Costs of A:

- A record of a shape changes at every tick with no rule of the spec writing it. That is already true of its creation today, and Reference §17 says so.
- "One record per schedule" is upheld by the runtime, under the shape's queue key (a queue key is what serializes commits that touch the same records, `wiki/Guides/Concurrency.md`). It is not something the shape's declaration makes impossible to violate. A second record for one schedule would be a wrong state that is prevented by how the runtime writes, not by construction.

Costs of B:

- The moment of the previous tick still has to survive a restart and be one value for every process over one store (`evaluation.md` "Ticks"). With no shape to hold it, the store API gains a place for a value that is not a record, and every store binding has to implement it. Today the ordinary commit path gives both properties for nothing.
- A product owner can no longer say "when did `Nightly` last run" without writing a rule for it.
- The name `Tick` becomes free for a spec to use, and the special cases for Velle's own shape go away (`checks.md` F1 and V32, `velle: true` in the API description).
- B is where the principle "an incoming act is an input to the state, not a member of it" would point if it were read as covering ticks too. That reading is mine, not Dave's, and the principle as written speaks of acts.

#### Proposed changes

For A:

- `compiler/src/main/kotlin/velle/Model.kt`, `PRELUDE`: `at: timestamp on create` becomes `at: timestamp on update`.
- Every example's generated output is regenerated if the generated `Tick` code differs.
- Reference §17: the shown declaration changes with it.

For B, in place of the above:

- `Model.PRELUDE` and `Model.TICK` are removed, with the F1 and V32 special cases in `Validator.kt` and `velle: true` in `apis/json/src/main/kotlin/velle/api/Describe.kt`.
- The store API gains a read and a write of a schedule's previous moment; `stores/sql`, `stores/sqlite`, `stores/postgres`, and `MemoryStore` implement it.
- Reference §17 "What Velle records about a tick" is removed; `wiki/Guides/Store-API.md` gains the new surface.
- `working-docs/examples/survey/23-tick-history-read.velle` is renamed `-refused`.

**Decided:** option B, 2026-11-19: `Tick` leaves the language. Dave: the store is involved in persisting and hydrating the tick, not as any other shape but as its own thing, and it should be explicit that ticks are a special thing. A system that stored every tick so it could be undone and played back is novel and interesting, but it is not worth the tax of someone having to identify the `Tick` shape and handle that one shape in a special way. The recommendation above was option A and was not taken.

### D3. The older `Tick` records of an existing system are removed · Moot under D2, 2026-11-19

When a system that already holds many `Tick` records per schedule starts on the new runtime, does the first tick of each schedule delete that schedule's older records?

Recommendation: yes. The first tick of a schedule after the change keeps the schedule's latest record, moves it forward, and deletes the rest in the same commit.

Costs:

- A history the system held is gone after one tick, with no step in which an engineer chose that. An engineer who wants it copies the table before upgrading; the release note has to say so.
- Without it, an upgraded system holds many `Tick` records per schedule indefinitely, which is the wrong state D2's option A relies on the runtime to prevent, and `previousTick` has to keep ordering by `at` to stay correct.
- The alternative is to leave it to the engineer: a statement they run against their own database. Velle then never deletes a record on its own account, at the price of a runtime that must tolerate both layouts.

This decision is moot under D2's option B, where the records are no longer of a shape and the old table is the engineer's to drop.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`, `tickInScope`: the commit that records a tick deletes every other `Tick` record of its schedule.
- `compiler/src/test/kotlin/velle/TickRecordTest.kt`: a test that starts from a store holding three `Tick` records of one schedule and finds one after the next tick, with `leaving` still noticing a leaver across that tick.
- Reference §17: one sentence saying what happens to records written by an earlier version.

**Decided:** not asked. D2 was answered with option B on 2026-11-19, under which this section's own text says the question is moot. What remains of it is D6.

### D4. The store contract gains members of its own for ticks, one moment kept per schedule · Decided 2026-11-19

Does the store contract gain two members that are only about ticks, a read of the moment a schedule last ran and a write of the moment a tick ran, with the store keeping one moment per schedule?

Recommendation: yes, as a third interface beside the two a store implements today, so a store that has not been taught about ticks does not compile against the new runtime.

Today a store is two interfaces in `compiler/src/main/kotlin/velle/Hydration.kt`: `StateResolver`, the reads, and `CommitCallback`, the landing of a commit. `VelleSystem.connect(resolver, onCommit)` in `Runtime.kt` takes both. Neither names a tick. The proposal, with names that are mine and open to change:

```kotlin
/** What a store keeps about ticks: for each schedule, the moment it last ran. Nothing else, and no history. */
interface TickMemory {
    /** The moment [schedule] last ran, or null before its first tick. */
    fun previousTick(schedule: String): Instant?

    /** [schedule] ran at [moment]: replaces what the store held for the schedule. Runs inside a unit of work; throw, and nothing is kept. */
    fun tickRan(schedule: String, moment: Instant)
}
```

How the runtime uses it, each point the same as today except where it says otherwise:

- `tickRan` is called once per tick, after the tick's firings, in a unit of work of its own. A sweep that is cut off leaves the previous moment in place and the next tick covers the gap (`evaluation.md` "Ticks").
- That unit of work is serialized under a queue key of its own kind, one per schedule. Today it is the whole-shape key `Tick`, so the ticks of two different schedules wait on each other; after the change they do not.
- The unit of work commits no records, so no rule fires on it and no `never` is evaluated over it. Today a constraint can refuse the `Tick` record in the V30 backstop state (`evaluation.md` "Ticks", last sentence of "The tick's own record"); that case goes away.
- `previousTick` survives a restart and is one value for every process over one store, because the store holds it.

Costs:

- Every store implements two more members: `MemoryStore`, `stores/sql` (for both `stores/sqlite` and `stores/postgres`), and any store an engineer has written. For the SQL store it is one two-column table with the schedule as its key.
- The store contract was two interfaces about records. It becomes three, one of which is about something that is not a record. That is the explicitness Dave asked for in D2, and it is also a permanent widening of what every store author has to read.
- It stays within the principle that the store API is agnostic to how and where records are stored: the members say what is kept, a moment per schedule, and name no mechanism.
- A product owner loses nothing further; D1 and D2 already removed the reading of `Tick`.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Hydration.kt`: the new interface, and a new kind of `AdmissionKey` for a schedule.
- `compiler/src/main/kotlin/velle/Runtime.kt`: `connect` takes the third interface; `tickInScope` calls `tickRan` in place of creating a `Tick` record; `previousTick` asks the store in place of running a query.
- `compiler/src/main/kotlin/velle/MemoryStore.kt` and `stores/sql`: implement it; `stores/sqlite` and `stores/postgres` gain the table through `SqlDialect`.
- `compiler/src/main/kotlin/velle/Model.kt`: `PRELUDE` and `TICK` removed (already listed under D2, option B).
- `compiler/src/main/kotlin/velle/StoreGen.kt` and `Codegen.kt`: no `Tick` in generated code; every example's output regenerated.
- `wiki/Guides/Store-API.md`: a section for the tick members; `evaluation.md` "Ticks": "The tick's own record" rewritten; `wiki/Guides/Concurrency.md`: the row for the tick's own record names the per-schedule key.
- `compiler/src/test/kotlin/velle/TickRecordTest.kt`: rewritten against the store's tick members; `BillingOverSqliteTest` and `AdmissionKeysTest` updated where they name `Tick`.

**Decided:** yes, 2026-11-19, with Dave's names for the two members: `latestTick` for the read, where the proposal above says `previousTick`, and `persistTick` for the write, where it says `tickRan`. Dave did not speak to the interface's name, `TickMemory` in the proposal, which stays mine and open to change.

### D5. An engineer can read when a schedule last ran from the running system

Once a spec cannot read `Tick`, can an engineer still ask the running system when a schedule last ran?

Recommendation: yes in the engineer's own Kotlin code, and no over REST and GraphQL.

- In Kotlin: `VelleSystem` (`compiler/src/main/kotlin/velle/Runtime.kt`) is the class every commit, tick, and query of a running application goes through. The generated class for a spec wraps it, and an engineer already reaches it as `sys.system`, for example to register a tick listener with `sys.system.listen(listener)` (`wiki/Guides/Act-API.md`, "Ticking a schedule"). A yes adds one read there, `sys.system.latestTick("Nightly")`, giving the moment `Nightly` last ran or nothing before its first run. The name follows D4.
- Over REST and GraphQL: the moment is not served. It is served today only because `Tick` is a shape.

Today `Tick` is listed by `GET /api` as a readable shape marked `velle: true` (`wiki/Guides/API-layer.md`), so it is readable over REST, GraphQL, and the console like any shape. After D2 it is not a shape and that listing has nothing to list. What an engineer would want the moment for is a health check ("has `Nightly` run in the last day") and the missed-run item in `working-docs/questions/OQ34-schedule-definition.md`. Both are deployment concerns, and the starter already reports every tick through `MetricsTickListener` and `LoggingTickListener` (`wiki/Guides/Serving-a-system.md`, "Hearing a tick").

Costs:

- With a yes, `VelleSystem` gains one public read, the moment a schedule last ran. It is information about the deployment, not about the business, which is why the recommendation keeps it out of the shapes the API serves.
- With a no, the starter's own missed-run check (OQ34) has to reach the store directly, and an engineer's health check reads the metric or the store.
- The console loses its `Tick` listing either way. Whether the console shows each schedule's last run some other way is a question for the console and is not decided here.

#### Proposed changes

- `compiler/src/main/kotlin/velle/Runtime.kt`: the private `previousTick(schedule)` becomes the public `latestTick(schedule)` on `VelleSystem`.
- `apis/json/src/main/kotlin/velle/api/Describe.kt`: `velle: true` removed; `VelleApiTest` updated.
- `wiki/Guides/API-layer.md` and `wiki/Guides/Act-API.md` "Ticking a schedule": the `Tick` shape removed, `latestTick` on `VelleSystem` added.

**Decided:**

### D6. A system that already holds `Tick` records carries its latest one over

When a system that holds `Tick` records starts on the new runtime, does Velle read each schedule's latest `Tick` record as that schedule's previous moment?

Recommendation: no. The new runtime starts each schedule with no previous moment, as a new system does, and the old `Tick` records are the engineer's to drop.

Costs:

- With a no, the first tick of each schedule after the change compares against the system's start time (`evaluation.md` "Ticks"). A record that left a refinement between the last tick on the old runtime and the first start on the new one is not noticed by a `when leaving` rule on a schedule. An engineer closes the gap by ticking once on the old runtime immediately before the change.
- With a yes, every store carries code that reads a shape Velle no longer declares, for one start of each system, and that code stays until someone removes it.
- Nothing checked in shows a deployed system that would be affected. That is an absence, not evidence, and Dave knows better than the repo whether one exists.

#### Proposed changes

- With a no: one sentence in `wiki/Guides/Store-API.md` saying what an engineer does with a `Tick` table left by an earlier version.
- With a yes: `stores/sql` reads the latest row per schedule from the old table when the new one is empty; a test over SQLite pins it.

**Decided:**

## 3. The build

Written when D5 and D6 are decided. It gathers the "Proposed changes" lists above in build order.
