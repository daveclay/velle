# OQ204 — How does an author say "charge for each started day"?

**Status:** opened 2026-11-26 from OQ184 D2. D1 awaits Dave.

**In plain terms:** a tool library charges 2 for each day a tool is late, and a day that has started counts as a whole day, so a tool 36 hours late owes for 2 days. Velle's `days` gives 1 for 36 hours, because it drops the half day. Nothing in Velle rounds up.

**See:** Reference §5, "The built-in functions" (`days`, `hours` and the others drop a part of a unit toward zero; no function rounds a number); `compiler/src/main/kotlin/velle/ext/builtin/BuiltinFunctions.kt`, `SpanInUnits` (where those functions are written); `wiki/Guides/Extension-functions.md` (how an engineer registers a function of their own); `working-docs/examples/survey/60-started-day-workaround.velle` (what an author writes today).

---

## 1. What runs today

- `days(36 hours)` is 1. Reference §5 says a part of a unit is dropped toward zero, decided in OQ184.
- Velle has no built-in function that rounds a number up, down, or to the nearest whole.
- The fee can be written today, in `working-docs/examples/survey/60-started-day-workaround.velle`, act `Return`:

```
act Return {
    dueAt: DateTime                                   -- when the tool was due
    returnedAt: DateTime                              -- when it came back
    late: duration = returnedAt - dueAt               -- two moments subtract to a span, in whole seconds
    daysLate: integer = if late <= 0 seconds then 0 else days(late - 1 seconds) + 1
                                                      -- one second off, whole days counted, one day added back
    lateFee: decimal = 2 * daysLate                   -- a number times a number
}
```

  It validates. I have not run it. Nobody reading `days(late - 1 seconds) + 1` would hear "each started day" in it.
- An engineer can also register a function of their own, say `startedDays`, through the extension interface (`wiki/Guides/Extension-functions.md`). Then each team writes and names its own.

## 2. What to decide

Roster: D1 (what Velle gains so the sentence can be said).

### D1. Velle gains functions that count each started unit of a span

Does Velle get built-in functions `startedDays`, `startedWeeks`, `startedHours`, and `startedMinutes`, each taking one `duration` and counting a unit that has begun as a whole one?

I recommend yes.

```
act Return {
    dueAt: DateTime                                   -- when the tool was due
    returnedAt: DateTime                              -- when it came back
    daysLate: integer = startedDays(returnedAt - dueAt)   -- 36 hours is 2; exactly 24 hours is 1; nothing late is 0
    lateFee: decimal = 2 * daysLate                   -- a number times a number
}
```

The line reads as the product owner said it. `days` stays what it is, the whole days in a span, and the two stand side by side: `days(36 hours)` is 1, `startedDays(36 hours)` is 2.

Costs:

- Four more names in the language, beside the five from OQ184. There is no `startedSeconds`, since a duration holds whole seconds and it would equal `seconds`.
- A span before zero needs a rule. I would have it count away from zero, so `startedDays(-36 hours)` is minus 2, the mirror of `days` dropping toward zero. A fee on an early return would then need its own `if`, as it does today.
- It answers only spans of time. A business that rounds something else up, such as boxes needed for a number of items, is not helped.

The alternative, a general function that rounds a number up:

```
act Return {
    dueAt: DateTime
    returnedAt: DateTime
    daysLate: integer = roundUp(hours(returnedAt - dueAt) / 24)   -- hours as a number, divided, rounded up
    lateFee: decimal = 2 * daysLate
}
```

- It serves every number, not only spans, and would come with `roundDown` and probably `round`.
- For this fee it is worse to read: the author converts hours to days by dividing by 24, which is arithmetic the engineer has to explain to the product owner. It is also wrong by up to an hour, because `hours` has already dropped the part of an hour: 24 hours and 30 minutes gives `roundUp(24 / 24)`, which is 1 and should be 2.
- A general rounding function may still be wanted for other reasons. OQ93 asks whether the value vocabulary grows, and rounding would belong there. It can be decided apart from this question.

The other alternative is to add nothing: the Reference shows the spelling in §1, or the engineer registers `startedDays` as their own function. No language change, and every team that charges by the started day writes it again.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ext/builtin/BuiltinFunctions.kt`: four functions `startedWeeks`, `startedDays`, `startedHours`, `startedMinutes`, written through `VelleFunction` beside `SpanInUnits`, each counting a begun unit as a whole one, away from zero.
- `wiki/Reference/Reference-05-Scalars.md`, "The built-in functions": one bullet, with `startedDays(36 hours)` is 2 beside `days(36 hours)` is 1. `checks.md` F2, `grammar.md`, `wiki/Guides/Extension-functions.md` and `skills/velle-design/references/expressions.md` list the four names.
- A test per function: a span with a part, a span that is a whole number of the unit, zero, and a span before zero.
- An example under `examples/` gains a value computed with one of them, or the survey file's act `Return` moves into a test with the new spelling. The survey file `60-started-day-workaround.velle` is deleted.

## 3. The build

Written when D1 is decided.

## 4. Work items

- [ ] Decide D1 (Dave).
