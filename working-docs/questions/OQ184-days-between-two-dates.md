# OQ184 — How does an author get the number of days between two dates?

**Status:** opened 2026-11-26 from a trial design session (OQ174). D1 and D2 await Dave.

**In plain terms:** a tool library charges 2 for each day a tool comes back late. Velle can say how long passed between two dates, but only as a span of time, and nothing turns a span into a number. So the fee cannot be multiplied out.

**See:** Reference §10 "Dates and durations" (says two dates subtract to "a whole number of days", which the compiler does not do); Reference §5 (the `duration` type, and "The built-in functions"); `compiler/src/main/kotlin/velle/ext/builtin/BuiltinFunctions.kt` (where a built-in function is written); the survey files `working-docs/examples/survey/37-days-between-dates-refused.velle` and `38-days-between-dates-workaround.velle`.

---

## 1. What runs today

- Two `Date` values subtracted give a `duration`, in whole days. The line is in `Evaluator.kt`: `if (op == "-" && l is Value.VDate && r is Value.VDate) return Value.VDuration(DAYS.between(r.v, l.v), "days")`. Two `DateTime` values subtracted give a `duration` in seconds.
- Reference §5 says the same: a `duration` "is the difference of two dates or two moments". Reference §10 contradicts it in one sentence: "`done - planned` on two `Date` values is a whole number of days". The compiler follows §5.
- A `duration` can be compared with another, added to or subtracted from another, summed, passed to `abs`, and used to move a `Date`, a `DateTime`, or a `time`. It cannot be multiplied, divided, or read as a number.
- `working-docs/examples/survey/37-days-between-dates-refused.velle`, act `Return`, shows each spelling an author might try and the message that refuses it: declaring the difference an `integer`, `/ 1 days`, `2 * (returnedOn - dueOn)`, `days(...)`, and `.days`.
- `working-docs/examples/survey/38-days-between-dates-workaround.velle`, rule `CountLateDay`, is the only spelling that validates: a record made each night per late loan, counted. The fee then counts the nights the schedule ran, not the days between the two dates.

## 2. What to decide

Roster: D1 (the spelling that gives a number), D2 (what a part of a unit becomes).

### D1. A built-in function reads a span of time as a number of days

Does Velle get built-in functions `days`, `weeks`, `hours`, `minutes`, and `seconds`, each taking one `duration` and giving a whole number?

I recommend yes. The fee is then written as it is said:

```
act Return {
    dueOn: Date                                          -- a calendar date
    returnedOn: Date                                     -- a calendar date
    daysLate: integer = days(returnedOn - dueOn)         -- the subtraction is a duration; days(...) reads it as a whole number
    lateFee: decimal = 2 * daysLate                      -- a number times a number, accepted today
}
```

The names are the five units a duration literal is written in (`3 days`), so an author already knows them. Each is one more registered function written through `VelleFunction`, as `abs` and `year` are.

Costs:

- `hours` and `minutes` sit beside the existing `hour` and `minute`, which read a part of a `time`. `hour(closesAt)` is 17; `hours(closesAt - opensAt)` is 8. The types differ, so the wrong one is refused by the compiler, but the names are one letter apart.
- A date difference stays a `duration`, so the author must write `days(...)` every time a number is wanted. A product owner reads `days(returnedOn - dueOn)` as "the days from due to returned", which I think reads well.
- Nothing is removed. Every spec that validates today still does.

The two other spellings, against the same act:

```
act Return {
    dueOn: Date
    returnedOn: Date
    daysLate: integer = returnedOn - dueOn               -- alternative A: two dates subtract to an integer, as §10 says
    lateFee: decimal = 2 * daysLate
}
```

Alternative A is a removal. A date difference is a `duration` today, so `returnedOn - dueOn > 3 days` and `abs(due - today)` as a duration stop validating, and two `Date` values would subtract to a number while two `DateTime` values subtract to a `duration`. No spec under `examples/` subtracts two dates today, so no example breaks, but the two types would no longer behave alike.

```
act Return {
    dueOn: Date
    returnedOn: Date
    daysLate: integer = (returnedOn - dueOn) / 1 days    -- alternative B: a duration divided by a duration is a number
    lateFee: decimal = 2 * daysLate
}
```

Alternative B adds no name, but "divided by one day" is arithmetic the engineer has to explain to the product owner. Velle avoids that.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ext/builtin/BuiltinFunctions.kt`: five functions `days`, `weeks`, `hours`, `minutes`, `seconds`, each taking a `duration` and returning an `integer`.
- The F2 message "is declared an integer and computed as a duration; declare it ... duration, or compute an integer" names the spelling: "or read it as a number with `days(...)`". Its spec goes in `compiler/src/test/resources/diagnostics/` and `wiki/Normative/diagnostics.md` is regenerated.
- `wiki/Reference/Reference-10-Predicate-expressions.md`, "Dates and durations": the sentence "is a whole number of days" becomes "is the duration between them, in whole days", and gains `days(done - planned)` as the number.
- `wiki/Reference/Reference-05-Scalars.md`, "The built-in functions": one bullet for the five functions. `wiki/Normative/checks.md` F2 if it lists the built-in names.
- An example under `examples/` gains a fee or a count computed with `days(...)`, and a compiler test pins each function.
- Survey files 37 and 38 leave `working-docs/examples/survey/`: 37's first spelling stays refused and is covered by the diagnostics spec; 38 is a workaround the new spelling replaces and is deleted.

### D2. A part of a unit is dropped

Is `days(36 hours)` the whole number 1, with the half day dropped?

I recommend yes. Two `Date` values always differ by whole days, so this only matters for two `DateTime` values, a `duration` field, or a larger unit than the span was measured in (`weeks(10 days)` is 1). Dropping the part is what Kotlin's `Duration.toDays()` does, and Reference §5 says each type behaves as its Kotlin counterpart. A span before zero drops toward zero: `days(-36 hours)` is -1.

Costs:

- A business that charges for a started day, so 36 hours late is 2 days, cannot say so with `days` alone. It writes the comparison itself, or the question of rounding a number up comes back as its own OQ. Velle has no built-in function that rounds a number today.
- The alternative is a `decimal`, `days(36 hours)` is 1.5. Then `daysLate: integer = days(returnedOn - dueOn)` on two dates is refused as a decimal given to an integer, which brings back the problem this question is about.

#### Proposed changes

- The five functions of D1 drop the part toward zero. The bullet in `Reference-05-Scalars.md` says so with `days(36 hours)` as the example, and a test pins it for a positive and a negative span.

## 3. The build

Written when D1 and D2 are decided.

## 4. Work items

- [ ] Decide D1 and D2 (Dave).
