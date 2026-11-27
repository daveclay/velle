# OQ203 — What happens to a span of time that arrives with a part of a second?

**Status:** opened 2026-11-26 while building OQ184. D1 awaits Dave.

**In plain terms:** a `duration` in Velle holds whole seconds. The outside world can hand Velle a span with a part of a second, such as one and a half seconds. Today Velle quietly changes it to a whole number of seconds and tells nobody, and it changes a span before zero in the opposite direction from everything else in Velle.

**See:** Reference §5 (the `duration` type; "The built-in functions", where `days`, `hours` and the others drop a part of a unit toward zero); `compiler/src/main/kotlin/velle/Value.kt`, `VDuration.ofJava` (where the part of a second is lost); `apis/json/src/main/kotlin/velle/api/Wire.kt` (where a span arrives as text such as `PT1.5S`); `wiki/Guides/Act-API.md` (a `duration` field is a `java.time.Duration` in the engineer's code).

---

## 1. What runs today

- A Velle author cannot write a part of a second. A span in a spec is a whole number and a unit, `90 seconds`, and two moments subtract to whole seconds.
- A span with a part of a second can only come from outside the spec, in three ways: the engineer's Kotlin code commits an act with `java.time.Duration.ofMillis(1500)`; a request over the JSON, GraphQL or MCP API carries the text `PT1.5S`, which is one and a half seconds in the standard text form for a span of time (ISO 8601: `P` opens it, `T` says the units that follow are hours, minutes and seconds, and a leading `-` means before zero); or a store hands back a value it kept with a part of a second.
- All three pass through one line, `VDuration.ofJava` in `compiler/src/main/kotlin/velle/Value.kt`, which keeps `javaDuration.seconds` and discards the rest. For one and a half seconds that gives 1 second. For minus one and a half seconds it gives minus 2 seconds, because Java counts the whole seconds downward.
- Nothing is reported either way. The record holds a different span from the one supplied.
- This is read from the source. No test commits a span with a part of a second.

## 2. What to decide

Roster: D1 (what Velle does with the part of a second).

### D1. A span with a part of a second is refused at the door

Should an act that supplies a span with a part of a second be refused, with a message saying a duration holds whole seconds?

I recommend yes.

```
act Shift {
    worker: text                                   -- supplied by the committer
    breakTaken: duration                           -- supplied by the committer; holds whole seconds
    breakMinutes: integer = minutes(breakTaken)    -- reads the span as a whole number of minutes
}
```

The engineer's code, or a request over the API, commits a `Shift` with `breakTaken` of ninety and a half seconds, sent as the text `PT90.5S`.

- **Today:** accepted. The record holds 90 seconds. With minus ninety and a half seconds, `-PT90.5S`, it holds minus 91 seconds.
- **Recommended:** refused before anything is written, as a text that is not a date is refused for a `Date` field: "'breakTaken' holds whole seconds; PT90.5S has a part of a second". The caller rounds it the way their business wants and sends it again.
- **The alternative:** accepted, with the part dropped toward zero both ways, so ninety and a half seconds is 90 seconds and minus ninety and a half is minus 90 seconds. That matches how `days` and `hours` drop a part of a unit.

Costs of refusing:

- A caller whose clock gives parts of a second, which most do, must round before committing. A span measured by a stopwatch in the engineer's code arrives with a part of a second nearly every time, so this refusal would be met often by an engineer who subtracts two instants in Kotlin and passes the result.
- It leans on two principles in the root `CLAUDE.md`: a wrong state cannot be represented (the record never holds a span the caller did not send), and arbitrary input is pushed to the APIs (rounding is the caller's choice, not Velle's).

Costs of dropping the part:

- The record silently differs from what was supplied, by less than a second. Nobody is told.
- It is the smaller change: one line, and no caller that works today starts being refused.

Whichever is chosen, a value a store hands back with a part of a second is a separate case, since nothing can be refused there. I would drop the part toward zero on a read from a store under both answers.

#### Proposed changes

- `compiler/src/main/kotlin/velle/ActInput.kt`: a `duration` supplied with a part of a second is refused with the message above (recommended), or `VDuration.ofJava` in `Value.kt` drops the part toward zero (alternative).
- `VDuration.ofJava` drops toward zero for a value read from a store, under both answers.
- A test for each of the three ways in: the engineer's code, the JSON API, and a store.
- `wiki/Reference/Reference-05-Scalars.md`, the `duration` paragraph: one sentence saying a duration holds whole seconds and what happens to a supplied part of a second. `wiki/Guides/Act-API.md` the same for the input class.

## 3. The build

Written when D1 is decided.

## 4. Work items

- [ ] Decide D1 (Dave).
