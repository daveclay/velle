# compiler/

Rules for changes under this directory. The `implement` skill has the procedure
for regenerating examples and running the suite.

## Code changes follow ub-mode

Before writing or changing any code under `compiler/`, load the `ub-mode`
skill installed at `.claude/skills/ub-mode/SKILL.md` (Dave,
2026-11-11) and follow it: first make it work, then make it right, then
follow practice. Where ub-mode says to commit and push, this repo's rule
holds instead: commit and push only when Dave asks.

## Every builtin goes through the extension interfaces

The API Velle gives an engineer to extend it is the API Velle uses to
implement its own builtins: `velle.ext.VelleType` for types, the function
and predicate interfaces for functions, the store bindings for storage and
rendering. A builtin type, function, or rendering is one more registered
implementation of the public interface, found by the same dispatch, never a
parallel hard-coded `when` branch. When adding one, implement it through the
interface. When designing an extension interface, ask whether every existing
builtin could be written through it, and treat any that could not as a hole
in the interface. When a compromise must land somewhere, it lands on the
type side: custom functions outrank custom types.

## The store is not a database

A store may be a relational database, a key store, a REST call, an event
queue, an email, a file on disk, or any combination, behind the one
`StateResolver` and `CommitCallback` contract. The contract constrains what
the reads return and that a commit lands whole; it never names a mechanism.
The runtime stays SQL-free. Never write "the database" for the store, and
never design a store-side feature that assumes SQL or one backend. SQL lives
in `stores/sql` behind `SqlDialect`.

## Errors and advisories

An error is for a statement whose meaning the spec leaves undefined. An
advisory is for a statement with a defined meaning the author may not have
intended, and is silenced by an explicit spelling, not by a marker. Use that
split for every new check. It has not been applied back over the existing
checks; do not claim they follow it.

## Diagnostics

A diagnostic is read by a Velle author, not a compiler engineer. Name the
property or field the author wrote and the spelling that would be accepted.
No internal vocabulary (certificate, whitelist, witness, discharge, rung).
One message per reason. OQ78 in `working-docs/QUESTIONS.md` holds the
rewrite of V14's message as the model.

## Checks

Every check has a number in `wiki/checks.md` (V-series validator, F-series
functions and types, E-series extensions, A-series advisories) and a test
that pins it. Add the `checks.md` entry and the test in the same change as
the check. The header of `Validator.kt` lists the gaps still open; keep it
current.

## Jars

`working-docs/examples/survey/check.sh` runs `compiler/build/libs/compiler.jar`.
Rebuild it from committed source before reading its output. Never leave a
jar built from uncommitted validator edits behind.
