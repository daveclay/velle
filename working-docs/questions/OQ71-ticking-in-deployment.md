# OQ71 — Ticking in a deployment: more than one instance, the last tick, a missed tick, who hears a rejection, shutdown

**Status:** opened 2026-10-28 at Dave's request. The same day the starter's ticker moved onto Spring's scheduler (one cron task per schedule on the application's `TaskScheduler`, a zone per schedule, `SchedulesConfigured` in place of a `@ConditionalOnProperty` that never matched a map). Everything below is what that change did not do. D1 and D2 were ratified by Dave and built the same day (sections 1 and 2 say what landed). D3's placement was revised the same day after Dave asked where its verb would live, and D3 was then ratified and built with his two reservations answered in section 3. D4 was redirected to OQ34 the same day at Dave's prompt (catch-up is the schedule's meaning, not a deployment setting); D5 was ratified piecewise and built in full the same day; D6 and D7 are open; each section leads with the proposed decision for Dave to answer inline.
**In plain terms:** Velle says a scheduled rule runs "at each run of `Daily`" and leaves cadence to the engineer's scheduler (Reference §17). The starter now hands cadence to Spring. What is left is everything a real deployment adds around a cadence: two copies of the application both reaching 06:00 Monday, a restart that loses the runtime's memory of the previous tick, a Monday the application was down for, a rejected firing nobody reads in a log, and a shutdown in the middle of a sweep.
**See:** `apis/spring/src/main/kotlin/velle/api/spring/VelleAutoConfiguration.kt` (`Ticker`, `SchedulesConfigured`) · `apis/spring/src/main/kotlin/velle/api/spring/VelleProperties.kt` (`Schedule`: `cron`, `zone`) · `compiler/src/main/kotlin/velle/Runtime.kt` (`tickInScope`: the membership re-check before `inTransaction`, `lastTick`, `failures`) · `stores/sql/src/main/kotlin/velle/sql/SqlDialect.kt` (`serializesKeys`, `lock`) and `stores/postgres/.../PostgresDialect.kt` (advisory locks on key text) · `wiki/Serving-a-system.md`, `wiki/API-layer.md` ("apis/spring: the starter") · Reference §17 (what a scheduled rule sees), §21 and OQ47/OQ48 in the settled table (a tick's rejection must reach a human) · OQ70 (the unit of work and the keys a firing takes) · OQ58 (the production clock).

---

## 1. A firing re-checks its condition under the lock (D1)

**Ratified and built 2026-10-28.** `Runtime.isSubject` is the one predicate for a tick's subject (a member now, or for `leaving` a member at the previous tick and not now); `tickInScope` applies it before the lock as a cheap skip and again inside `inTransaction`, after `serialize` and the post-lock snapshot, firing only if it holds; `drainAfterQueue` does the same for an after-commit firing. `DuplicateFiringTest` runs a second instance's whole tick between the first instance's selection and its lock, through a store decorator, and asserts one reminder; it fails on the previous runtime. `evaluation.md` "Ticks" and `Concurrency.md` "Scheduled rules" state it.

**Proposed decision:** the runtime re-evaluates the rule's condition inside the keyed transaction, after the lock on the firing's keys is held, and fires only if it still holds. A second firing for the same subject then sees the first one's result, finds the condition false, and does nothing. This holds for every way a tick arrives: the starter's scheduler on one instance or many, the HTTP endpoint, the GraphQL mutation, a generated `tick<Schedule>()` call, and a tick racing a commit on the same subject.

What is there. `tickInScope` selects subjects against settled state, then for each subject runs `if (!rule.leaving && !evaluator.memberOfRefExpr(subject, rule.condition)) continue` and only then `inTransaction(keys = { firingKeys(rule, subject) }) { fire(rule, subject) }`. The re-check is outside the lock. Two instances at 06:00 both see the overdue invoice with no reminder, both pass the re-check, and the advisory lock only orders their two `Reminder` creations. `fire` does not re-evaluate the condition under the lock: it binds the subject and runs the body (`Runtime.fire`, `execute`). So two instances do create two reminders today.

Costs. One more evaluation per firing under the lock, which the firing's own reads mostly warm. A `leaving` rule needs the same treatment with its previous-clock membership, which D3 makes meaningful across instances.

## 2. Which instance ticks (D2)

**Ratified and built 2026-10-28.** `velle.ticker.enabled` (`VelleProperties.Ticker`), a `@ConditionalOnProperty` on `VelleAutoConfiguration.Ticker` beside `SchedulesConfigured`; `TickerConfigurationTest` shows the scheduler absent and `VelleApi.tick` still working with it off. Serving-a-system says when to set it. No lease.

**Proposed decision:** the starter gains `velle.ticker.enabled` (default true). On an instance where it is false the `Ticker` configuration is skipped: Velle enables no scheduling and registers no cron task there. Nothing else changes. The HTTP ticks endpoint, the GraphQL `tick` mutation, and the generated `tick<Schedule>()` methods still work on that instance, and the runtime is untouched. A lease per schedule is not built unless a spec shows it is needed.

What the switch is for. With D1 it saves work: N replicas would run N sweeps and N minus one of them would find nothing to do. Without D1 it is the only guard against duplicate firings, and correctness then depends on every replica but one being configured, which is the discipline-over-structure trade the design avoids. It is a cost decision once D1 is built, a correctness decision until then.

Costs. A property and a condition. A lease per schedule (try an advisory lock named for the schedule, tick if held, skip otherwise) is the ShedLock shape; it fits `SqlDialect.lock` but needs a non-transactional or session-level lock, which the dialect does not have today, and it means the store knows about ticks, which it does not today.

## 3. The previous tick is remembered in the process (D3)

**Ratified and built 2026-10-28.** Dave agreed to the built-in shape with two reservations: that it is "special", and that it hides among the engineer's own shapes. What landed answers both:

- *Not special.* The shape is written in Velle, `Model.PRELUDE`, and parsed into every model ahead of the spec's declarations, so the parser, the validator, the store schema, codegen, Describe, GraphQL, and the diagrams meet `Tick` as they meet any shape; `Model.builtin` names it and is consulted in exactly three places: the F1 message when a spec declares the name, V32 (the shape cannot carry a clause of its own, so it takes the spec-wide default or is visible to anyone), and Describe's `velle: true`. The tick creates the row through `applyCommit` under `AdmissionKey.Shape("Tick")`, the same path a rule body's creation takes, so a rule or a `never` over `Tick` is served; and reads the previous tick with an ordinary `Query` (`schedule == S`, `at` descending, one row). `Runtime.lastTick` is gone. A refused `Tick` row (only the V30 backstop can cause one) joins the tick's rejections.
- *Not hidden.* `Tick` is readable over REST, GraphQL, and the console as any shape; Describe marks it `velle: true`; Reference §17 has a section on it, and checks.md F1 and V32 say what the compiler does with the name. Nothing about it is filtered out of the generators: every example's output was regenerated the same day, so each generated system reads and stores `Tick` and each class diagram shows it. A typed store generated before this change has no `Tick` table and silently drops the row, and a `leaving` rule then fires at every tick, which is what the stale pharmacy store did before regeneration.

`TickRecordTest`: a tick writes one `Tick` row with its schedule and moment; a `leaving` rule notices a record that left while the process was down, because a fresh system over the same store reads the previous tick from it, and a further tick notices nothing new; a spec declaring `shape Tick` gets the F1 message. `AdmissionKeysTest` and `TickRejectionTest` were updated for the tick's own unit of work and for the backstop refusing the record too. The sixteen baseline compiler failures are unchanged.

**Proposed decision (revised 2026-10-28, after Dave asked where a new verb would live):** no new verb. The tick's record is a row like any other: a built-in shape every model carries, on the order of `Tick { schedule: text  at: timestamp }`, created by the tick's own envelope and read back through the ordinary resolver. A tick is already "a commit whose changed datum is `today`" (`evaluation.md`, "Ticks"); this gives that commit one row of its own. Every store gets it with no new method: both SQL dialects, `MemoryStore`, and any store an engineer writes over a REST call or a file. The store contract stays ignorant of ticks, as it is of every other shape.

What is there. `Runtime.lastTick` is a `MutableMap<String, Instant>`; `tickInScope` reads `lastTick[schedule] ?: startInstant`. A `when leaving R on Weekly` rule decides membership "at the previous tick" with that instant. After a restart the previous tick is the start instant, so a record that left `R` during the outage is never seen leaving. With two instances each has its own map, and D1's re-check for a `leaving` rule compares against each instance's own idea of the previous tick.

The placements considered.

- **A built-in shape through the ordinary path** (proposed). Costs: a system shape the validator, `Describe`, the console, codegen, and the diagrams must leave out; `createSchema` makes one more table; the row's write is a new envelope at the end of the sweep, after the firings and whether or not any was rejected, so a failed firing never loses the tick's time. Velle then uses its own machinery for its own memory, which is the direction OQ68 set for the builtin types.
- **Two methods on `StateResolver`**, `lastTick(schedule)` and `tickDone(schedule, at)`, with defaults that keep today's in-memory behaviour. Costs: every store that wants the memory implements them separately; the agnostic contract learns the word "tick"; a store that forgets them silently keeps the per-process behaviour.
- **A separate optional interface** the store may also implement, with the runtime falling back to memory. The same costs as the methods, and the fallback hides a misconfiguration.

## 4. A missed tick (D4)

**Proposed decision (revised 2026-10-28, after Dave asked whether catch-up should be a Velle specification):** yes. Whether a run that did not happen is owed is part of what the schedule means, so it belongs in the spec, with the rest of what a schedule means, and that is OQ34's question. No `velle.schedules.<S>.catch-up` property in the starter. D4 closes here and becomes an item under OQ34.

Why it is the spec's. "The weekly reminder must go out every week, late if it must" and "the hourly refresh needs no making up" are statements about the rule's purpose, the kind the author makes and the product owner can read. Written in a deployment file they are one setting per environment, invisible to the spec's reader, and the design avoids putting meaning where only discipline keeps it right (OQ70's and D2's reasoning). It is also the same statement in every deployment of the spec, which a property is not.

Why it cannot be written today. In Velle a schedule is a name in an `on` clause; its cadence lives in whatever runs the system (Reference §17). "Owed" needs a cadence to be measured against: a run is missed when the schedule's next moment after the latest `Tick` row (D3) is before now. The starter has the cron; the spec has nothing to say it with. OQ34 is where the spec gains a way to say when a schedule runs, and "a missed run is made up at the next start" is one more thing that definition says, beside the cadence, the event-anchored timeout, and the data-derived duration.

What the starter does meanwhile: nothing. An engineer who needs a made-up run today ticks by hand at startup through the escape hatch (D7), which is a deployment decision made in code and visibly so. When OQ34 lands, the starter reads the policy from the model, compares the schedule's next moment after the latest `Tick` with the clock, and ticks once; the mechanism is three lines and is not the question.

Why a late run is safe, for the record: a scheduled rule reacts to what is still true when it runs (Reference §17), and a `leaving` rule compares against the previous `Tick` (D3), so one late run covers the gap.

## 5. Who hears a rejection (D5)

**Proposed decision:** a tick's outcome becomes one typed report that every layer passes along unchanged, and a listener the application may declare hears it from the API layer, whichever transport fired the tick. Broken out by layer below, at Dave's request (2026-10-28); each piece can be ratified on its own.

What is there. The runtime's `tick` throws `TickRejected(schedule, at, rejections)` when a `never` refused a firing (OQ47, settled: the rejection reaches the caller as an exception with the rule and subject in the act's place) and appends a string to `VelleSystem.failures` when a firing threw anything else; nothing reads that list. `VelleApi.tick` turns the exception into `{schedule, at, rejections}` and says nothing about failures. The starter's `Ticker.tick` logs: info on a clean run, warn with the rejection messages, error on an exception. There is no hook and no count. Spring observes the scheduled task's duration and exceptions once an `ObservationRegistry` exists, and sees a rejection as a success, since it is data.

### 5.1 Runtime (`compiler`, `Runtime.kt`, `Rejection.kt`)

**Ratified and built 2026-10-28.** `TickReport(schedule, at, firings, failures)` and `FiringFailure(rule, subject, cause)` in `Rejection.kt`; `tick` returns the report and `TickRejected(report, rejections)` carries it. `VelleSystem.failures` and the system-wide `afterCommitRejections` list are gone: both accumulated for the life of the process and nothing read them, which is the history Dave ruled out in 5.8. An act's after-commit outcomes ride on its own `CommitResult.Accepted` (`afterCommitRejections`, `afterCommitFailures`), and a tick firing's after-commit outcomes join the tick's report. An `internal var onFiringFailure` tells a test harness of each failure as it happens, so the audit tests still see them without the runtime keeping any. Dave's question of the day, the difference between the two after-commit lists: a rejection is a `never` speaking about the books, the spec's meaning, with the full evaluation; a failure is the store, a lock, or the runtime breaking, the machinery's fault, carried as the cause. `TickReportTest`.


- `tick(schedule)` returns a `TickReport`: the schedule, the moment, how many firings ran per rule, and the failures as typed records, `TickFailure(rule, subject, cause)`, in place of strings. `TickRejected` keeps being thrown when a `never` refused a firing, as OQ47 settled, and carries the report, so a caller that catches it has the whole picture.
- `VelleSystem.failures` goes. The after-commit side keeps `afterCommitRejections` as data (OQ52) and gains `afterCommitFailures` of the same typed record on the commit result, since a failed after-commit firing is as silent today as a failed tick firing; that is the one change outside the tick and can be deferred.
- Nothing else: the rejection data is already complete (`Rejection` has the never, the rule, the subject, the produced and violating records, the evaluation, the rendered message).

### 5.2 Generated code (`Codegen.kt`)

**Ratified and built 2026-10-28.** No text change was needed: `fun tickWeekly() = system.tick("Weekly")` infers the report, so the generated surface returns it without regeneration. The typed `Commit.Accepted` carries the two after-commit lists beside the reference. Binding-API gained "Ticking a schedule" and a sentence under the commit function.


- `fun tick<Schedule>()` returns the report; otherwise unchanged. Every example regenerates, as for D3. Binding-API gains one paragraph.

### 5.3 to 5.7, revised (Dave, 2026-10-28)

Dave's direction: a REST or GraphQL tick is a development affordance, and no architecture is shaped around it. In a real system the cadence is a cron of some kind calling `tick`, and the listener is registered with the Velle runtime, so it hears every tick whoever called it. An error during a tick must reach someone, since it will keep happening and an engineer has to fix it, and must not halt the runtime. The one production case for the HTTP tick, a platform scheduler that only speaks HTTP (Cloud Scheduler, EventBridge Scheduler, a CronJob running curl), argues the same way: the wire differs, the ear is the runtime's listener.

### 5.3 to 5.7: ratified and built 2026-10-28

`TickListener` (`Rejection.kt`): `ticked(report, rejections)` and `afterCommit(act, rejections, failures)` with an empty default; `VelleSystem.listen`, `tickListeners`; told by `tell` after the tick's row lands (a listener's exception rides as suppressed on a `TickRejected`, else propagates after every listener had its turn) and after an accepted act whose after-commit lists are not empty. The `internal onFiringFailure` hook is gone; the audit tests use `FailureCollector`, a `TickListener`. Starter: `TickListeners.kt` with `StarterTickListener`, `LoggingTickListener`, `MetricsTickListener` (Micrometer `compileOnly`); `velleTickListeners` registers the application's beans or the log, plus the metrics listener from the class-conditional `Metrics` configuration; `Ticker.tick` only catches. REST, GraphQL, console unchanged; `velle.api.ticks` documented as a development affordance. Serving-a-system "Hearing a tick", API-layer, evaluation.md, Binding-API. `TickListenerTest`, two cases in `TickerConfigurationTest`. The sixteen baseline compiler failures are unchanged. D5 is closed; D6 and D7 remain.

### 5.3 Runtime (`compiler`): the listener

- `TickListener` beside `TickReport`: `fun ticked(report: TickReport, rejections: List<Rejection>)`. Registered on the `VelleSystem` (`listen(listener)`, several allowed), told at the end of every tick once the tick's own `Tick` row has landed and before `TickRejected` is thrown, on the ticking thread.
- A listener that throws does not undo the tick and does not reach the other listeners' turns; its exception propagates to the caller after the tick is complete. A tick never halts the runtime: it is one call, and the next cron firing is a new call.
- The `internal onFiringFailure` hook from 5.1 folds into this: the audit tests register a listener.
- Decided (Dave, 2026-10-28): the listener also hears an act's after-commit rejections and failures. A second method, `afterCommit(act: RecordRef?, rejections: List<Rejection>, failures: List<FiringFailure>)`, told once per accepted commit whose `after commit` firings came to anything, after the commit has returned its result to the caller is built; empty default, so a listener that only cares about ticks implements one method. The result still carries both lists for the committing code; the listener is for the people nobody's code tells.

### 5.4 Starter (`apis/spring`)

- Every `TickListener` bean the application declares is registered on the system bean. With none, `LoggingTickListener`: today's log lines.
- `MetricsTickListener` when a `MeterRegistry` bean exists and Micrometer is on the classpath (`compileOnly`): a timer `velle.tick` tagged `schedule` and `outcome` (`clean`, `rejected`, `failed`), counters `velle.tick.firings`, `velle.tick.rejections`, `velle.tick.failures` tagged `schedule` and `rule`. Names are a sub-decision.
- `Ticker.tick` keeps its catch, so a throwing tick or listener never stops Spring's scheduler.

### 5.5 REST and GraphQL (`apis/json`, `apis/graphql`)

- No change. `velle.api.ticks` stays off by default and is documented as a development and console affordance; the one-tick answer keeps today's shape. A deployment whose cron is an HTTP platform scheduler turns it on and hears outcomes through the listener like any other.

### 5.6 Console

- No change.

### 5.7 Docs

- Serving-a-system: "Hearing a tick", the listener bean and the metric names, and a sentence placing the ticks endpoint. API-layer: the endpoint is for development. `evaluation.md` "Ticks": the listener is told after the record lands.

### 5.8 No history of firings (Dave, 2026-10-28)

Velle keeps no history of firings across ticks. The runtime holds no list of past firings, rejections, or failures; the tick answer over REST and GraphQL describes the one tick the call ran; the listener (5.3) hears each tick as it happens and Velle retains nothing afterwards. An application that wants a record keeps it itself.

### What stays open inside D5

- Whether `tick` should stop throwing and return rejections as data too, which would reopen OQ47's ruling; the proposal keeps the throw and attaches the report.
- Whether the listener belongs in `apis/json` (proposed) or the starter; the argument for `apis/json` is that the console's button and a test's HTTP tick are ticks too.
- The metric names, and whether firings per rule are worth a counter or only a report field.

## 6. Shutdown in the middle of a sweep (D6)

**Proposed decision:** the example application sets `spring.task.scheduling.shutdown.await-termination: true` and a period, and Serving-a-system says why; the starter sets nothing, since the scheduler is the application's.

Why it matters little: each firing is its own commit and the next tick retries what did not run, so a sweep cut off loses nothing. Why it matters at all: with virtual threads (`spring.threads.virtual.enabled`) the scheduler is `SimpleAsyncTaskScheduler` and a long sweep is one task; without awaiting it, the DataSource closes under the firings and the log fills with connection errors that look like a fault.

## 7. The escape hatch (D7)

**Proposed decision:** one paragraph in Serving-a-system: an application that wants its own cadence leaves `velle.schedules` unset and calls the generated `tickWeekly()` (Codegen emits `fun tick<Schedule>()` per schedule) from a `@Scheduled` method of its own, or `api.tick("Weekly")` for a spec without generated code; the ticks endpoint stays for the console and tests.

Nothing to build. It is here so that the item is not lost and so the page is written once D1 to D6 are known, since the paragraph changes if a lease or a listener exists.
