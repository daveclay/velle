# Velle → Postgres compiler (proof of concept)

A minimal, real compiler — lexer, parser, resolver, codegen — for a deliberately
restricted subset of Velle, targeting Postgres. This is not a demonstration made of two
hand-paired files; `examples/invoice_payment.vel` is genuine `.vel` source, and
`test/golden/invoice_payment.sql` is what this program produces from it, verified
end-to-end against a real Postgres engine (`@electric-sql/pglite` — Postgres compiled to
WASM, not a reimplementation) in `test/apply.test.ts`.

The design rationale this implements lives in [`../postgres_transpile_brainstorm.md`](../postgres_transpile_brainstorm.md),
including the two real gaps in `example_invoice_payment.md` that this compiler's example
had to fix to compile at all (see "Fixes applied" below).

## Try it

```
git clone <repo> && cd velle
npm install
npm test
```

Three commands, nothing else — no Docker, no separately-installed Postgres, no root
access. `npm test` compiles `examples/invoice_payment.vel`, diffs it against the
committed golden SQL, boots an in-memory PGlite instance, applies the generated SQL, and
runs the full `Order → ChargeAttempt → Payment/InventoryRelease → Receipt` chain through
it — including two idempotency re-runs and the documented reversal gap below.

To just see the generated SQL:
```
npm run compile -- examples/invoice_payment.vel
```

## What this compiles

Exactly the subset used by `examples/invoice_payment.vel`:
- `shape` record declarations: scalar fields (`text`/`integer`/`decimal`/`boolean`/`Date`/`DateTime`/`Money`), `one`/`many` relationships, optional (`?`) fields, and derived properties (`balance: Money = amount - sum(payments, amount) + ...`).
- `shape X = Y where <predicate>` refinements: comparisons (`==`, `!=`, `<`, `<=`, `>`, `>=`), `is none`/`is some`, dot access through a `one` relationship, `+`/`-` arithmetic, `sum(<many-relationship chain>, field)`.
- `rule X on Y produces Z [for field] { ... then ... }`: `from {}` effect mappings, multi-stage ordering via `then`, and the `produces` idempotency guard (both inferred and explicit `for <field>`).

Maps to Postgres as: shape → table, refinement → view, `produces` → `UNIQUE` constraint,
a rule's entering-trigger → a `plpgsql` trigger function per affected table (grouped,
not one per rule) enqueuing to a shared `effects_outbox` table, consumed by one generic,
metadata-driven worker (`src/worker.ts`) rather than per-rule generated code.

## Deliberately out of scope

Every exclusion below is either a documented open problem in this repo's own design
docs, or a gap this compiler's own analysis surfaced — not an arbitrary cut:

- **`on Daily` / schedule triggers** — no scheduling mechanism is designed anywhere in
  this repo. `investigate_time.md` treats the mechanism itself as unresolved, floating
  three candidate designs with "none proposed seriously yet"; `TODO.md`'s "Scheduling
  framework mechanism itself" lists it as an open design question. Nothing for this
  compiler to target yet.
- **`on leaving` (exit triggers)** — `README.md` §12. Not exercised by this example.
- **`as` bindings, sibling joins, `Mapping`, cross-shape structural mixins** — all listed
  under `TODO.md`'s "Open design questions"; not needed by this example.
- **The reversal pattern** (`AccountFlagResolved`/`GracePeriod`-style un-flagging) —
  `README.md` §18 "Reversal" and `example_invoice_payment.md` stress test #5, both
  explicitly unresolved. Concretely: `test/apply.test.ts` proves a refund correctly
  recomputes `balance` back above zero, but the earlier `Receipt` is untouched —
  matching what the source docs already say is unresolved, not a bug this PoC
  introduces. Enforcing "at most one active flag"-style cross-table invariants would
  also require a Postgres constraint trigger (procedural), not a declarative
  `UNIQUE`/`CHECK`/`EXCLUDE` constraint — see `postgres_transpile_brainstorm.md`'s Gap C.
- **A `one`-relationship's target mutating independently** — this compiler's
  trigger-placement analysis (`resolver.ts`'s `collectDrivingShapes`) deliberately does
  not treat the target of a dereferenced `one` relationship (e.g. `ChargeResponse`,
  reached via `ChargeAttempt.response`) as its own driving table. In this example the
  only way a `ChargeResponse` gets linked is the `charge_attempts` write that sets
  `response_id` in the first place, which *is* covered — but if a linked
  `ChargeResponse` were ever updated in place afterward, no rule would re-fire.
- **Narrowing analysis (`.` vs `?.`)** — not implemented. Dot access through an optional
  relationship is permitted unconditionally in the resolver. Refinement-view codegen
  (`codegen/views.ts`) sidesteps the question rather than silently deciding it: a
  scalar correlated subquery through a `one` relationship returns `NULL` when the FK is
  absent, and any comparison against `NULL` is `NULL` (never true), so the row is
  correctly excluded regardless of what the *language's* narrowing rule turns out to be.

## Documented assumptions (compiler judgment calls, not settled language semantics)

- Omitted optional fields in a `from {}` mapping default to `NULL`/`none`; totality
  checking (`resolver.ts`'s `checkTotality`) only requires every non-optional,
  non-`many`, non-derived field.
- `produces`'s implicit guard (no explicit `for <field>`) requires exactly one field on
  the produced shape whose type matches the trigger's ultimate base record shape — zero
  or multiple matches are both compile errors (see "Fixes applied" below). An explicit
  `for <field>` never requires a type match, matching `README.md` §11's own
  `produces Referral for referrer` precedent.

## Fixes applied to compile `example_invoice_payment.md` at all

Two rules in the source doc's consolidated example have an implicit `produces` guard
with no field to attach to — trying to compile them is what surfaced this:

- `RecordPayment on SuccessfulCharge produces Payment` — `Payment` had no field of type
  `ChargeAttempt` (the trigger's base shape). Fixed by adding
  `chargeAttempt: one ChargeAttempt?` to `Payment` and writing
  `produces Payment for chargeAttempt` explicitly.
- `ReleaseInventory on FailedCharge produces InventoryRelease` — same class of issue,
  not previously named in `postgres_transpile_brainstorm.md`: `InventoryRelease` has no
  field of type `ChargeAttempt` either. Fixed with an explicit `for order` (`order`'s
  type, `Order`, doesn't match the trigger's type, `ChargeAttempt` — an explicit `for`
  never needs to, per the precedent above).

Also corrected: `response.outcome = "approved"` → `== "approved"` (`README.md` §9 is
explicit that `=` is reserved for shape definition; the source doc predates that
settling). `response: ChargeResponse?` → `response: one ChargeResponse?` (matching the
explicit-`one` convention used elsewhere, e.g. `example_refinements.md`).

## Layout

```
src/
  lexer.ts, ast.ts, parser.ts    — hand-written recursive-descent front end
  resolver.ts                    — symbol table, produces-guard inference, totality
                                    checking, trigger-placement dependency analysis
  codegen/                       — pure functions: AST/resolved-IR -> Postgres DDL
  worker.ts                      — generic, metadata-driven outbox consumer
  cli.ts                         — `vel-compile <in.vel> [--out out.sql]`
examples/invoice_payment.vel     — the example this README describes
test/
  compile.test.ts, golden/       — golden-file diff + negative guard-inference fixtures
  apply.test.ts                  — full PGlite end-to-end scenarios
```
