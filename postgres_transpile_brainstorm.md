# Transpile target brainstorm: Postgres

Runtime for Velle has been proposed as transpilation to some other language/target. This is a first pass comparing options, then a concrete sketch of a `produces`/refinement pair compiled to Postgres.

## 1. Candidate runtimes

The relevant semantics to match: refinements are live predicate membership over data, rules react to *entering*/*leaving* that membership (not just "is a member"), `produces` is an idempotency/evidence guard, and derived properties/refinements may be self-referential. This is closer to incremental view maintenance over a relational/Datalog engine than to ordinary imperative code, which reframes "what to transpile to" as three different bets:

### Option 1 — Mainstream host language (TypeScript/Java/Kotlin) + generated ORM

Shapes → classes, refinements → boolean methods, rules → event handlers or poll loops.

- **Efficacy**: weakest fit — "entering a refinement" isn't a primitive in any of these, so the compiler must hand-roll diffing (snapshot old vs. new state) and reimplement guard scoping, narrowing analysis, and exactly-once semantics as generated boilerplate, separately per target.
- **Maintainability**: best for humans downstream — familiar stack, debuggable, fits existing infra.
- **Ease of implementation**: hardest for the compiler author — effectively building a mini reactive database inside generated app code.

### Option 2 — SQL (views/materialized views/triggers) on Postgres, effects via an outbox pattern

Refinements → views, `produces` → unique-constraint-guarded insert, aggregates → SQL aggregates, self-reference → recursive CTEs.

- **Efficacy**: strong 1:1 conceptual match — a refinement *is* a `WHERE` clause, recursion is native, "entering a refinement" is a view-diff (triggers, or logical replication + a worker).
- **Maintainability**: good — boring, well-understood, ops teams already run Postgres; rule *effects* still need a thin generated host-language shim (transactional outbox → worker), but it's small and uniform.
- **Ease of implementation**: moderate — leans on the database for the hard part instead of reimplementing it.

### Option 3 — Compile straight to a Datalog/incremental-dataflow engine (Soufflé, Materialize, RisingWave, Feldera) as the runtime, skipping codegen entirely

- **Efficacy**: best fit to the language's own philosophy ("compiling validates a spec," not primarily emits an executable) — incremental view maintenance *is* "entering/leaving a refinement," recursion is free, no diffing to hand-build.
- **Maintainability**: weaker — younger ecosystem, no generated source for a team to read/own, harder to hire for; effects still need an external shim.
- **Ease of implementation**: best of the three for the compiler author, worst for adoption.

### Recommendation

**Postgres (Option 2)** is the most reasonable first target — it keeps "compile" closest to "validate + emit views" rather than "reimplement a database," while landing on infrastructure any engineering org already trusts. Option 3 is the more interesting long-term bet if the goal is closest fidelity to the spec's own semantics. Option 1 is worth avoiding as a *first* target — it forces the compiler to solve the hardest problems (diffing, idempotency, narrowing) with the least help from the runtime under it.

## 2. Sketch: `produces`/refinement pair compiled to Postgres

Using the spec's own worked example (`README.md` §10–11):

```
shape SettledInvoice = Invoice where balance <= 0

rule SendReceipt on SettledInvoice produces Receipt {
    Receipt for invoice sentOn: now
}
```

### Step 1 — The refinement compiles to a view, nothing more

```sql
CREATE VIEW settled_invoice AS
SELECT * FROM invoices WHERE balance <= 0;
```

This is the direct payoff of refinements being pure predicates: no separate "status" column, no imperative branch — the view *is* the refinement.

### Step 2 — `produces Receipt` compiles to a UNIQUE constraint, not application logic

```sql
ALTER TABLE receipts
  ADD CONSTRAINT receipts_invoice_unique UNIQUE (invoice_id);
```

The desugared guard from the spec (`not exists Receipt for this`) becomes real, DB-enforced exactly-once — a second attempt to insert a receipt for the same invoice fails (or no-ops with `ON CONFLICT DO NOTHING`), regardless of what triggered it. This is the piece that's hard to get right in Option 1-style generated app code (races between two workers) and falls out for free here.

### Step 3 — `on SettledInvoice` (entering, not merely "is") compiles to a row trigger that diffs OLD vs. NEW

```sql
CREATE OR REPLACE FUNCTION trg_invoices_settled() RETURNS trigger AS $$
DECLARE
  was_settled boolean := (TG_OP = 'UPDATE') AND (OLD.balance <= 0);
  is_settled  boolean := NEW.balance <= 0;
BEGIN
  IF is_settled AND NOT was_settled
     AND NOT EXISTS (SELECT 1 FROM receipts WHERE invoice_id = NEW.id) THEN
    INSERT INTO effects_outbox (effect_name, payload)
    VALUES ('SendReceipt', jsonb_build_object('invoice_id', NEW.id));
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER invoices_settled_entry
AFTER INSERT OR UPDATE ON invoices
FOR EACH ROW EXECUTE FUNCTION trg_invoices_settled();
```

The `was_settled`/`is_settled` pair is exactly `on Refinement`'s "entering, not currently a member" semantics — cheap to get right here since Postgres hands you `OLD`/`NEW` natively; it's the part a hand-rolled app-code diff would most often get subtly wrong.

### Step 4 — The rule body never runs inside the trigger

The trigger only *records that the rule should fire* (the outbox row) — it doesn't call the effect. A small generated worker (whatever host language — this is the one place a host language is still needed) drains the outbox:

```sql
CREATE TABLE effects_outbox (
  id           bigserial PRIMARY KEY,
  effect_name  text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);
```

```sql
-- worker, per SendReceipt row in the outbox:
INSERT INTO receipts (invoice_id, sent_on)
VALUES ((payload->>'invoice_id')::bigint, now())
ON CONFLICT (invoice_id) DO NOTHING;
```

The `ON CONFLICT DO NOTHING` here is what makes outbox redelivery (worker crash-and-retry, at-least-once delivery) safe — the UNIQUE constraint from Step 2 is doing the same job it would do anywhere else, so the worker doesn't need its own idempotency bookkeeping.

### What this buys, concretely

Four spec constructs map to four well-worn Postgres primitives:

| Velle construct | Postgres primitive |
|---|---|
| refinement (`where`) | view |
| `produces` | UNIQUE constraint |
| `on X` (entering) | row trigger computing an OLD/NEW diff |
| rule body (effect) | outbox-driven worker |

The "exactly once" guarantee the spec cares about (`README.md` §11) lives in the database's own constraint system rather than in generated code that has to reprove it. The one honest gap is the worker — effects that leave the database (an actual email) always need *some* external process, no matter the target.

### Open thread

Sketch the `for <field>` guard-scoping case next (e.g. `produces Referral for referrer`), where the UNIQUE constraint isn't on the whole natural key but on the specific scoped field.

## 3. Gaps found compiling the full invoice/payment example

The sketch above used the simplified README snippet (`SettledInvoice`/`Receipt` with `balance` implied to live on `Invoice`). Walking the same exercise against the full consolidated model in `example_invoice_payment.md` — `Customer`, `Invoice`, `Payment`, `Refund`, `Order`, `ChargeAttempt`/`ChargeResponse`, and all seven rules — surfaces several real gaps. Most are not "Postgres can't express this"; they're compiler-analysis work Postgres gives no help with, plus one place where the *example spec itself* is under-specified for codegen.

### What compiles cleanly

- Shapes → tables; `balance`'s derivation (`amount - sum(payments, amount) + sum(payments.refunds, amount)`) → a view with joins, including the two-hop `payments.refunds` case.
- Most refinements (`OverdueInvoice`, `PartiallyPaidInvoice`, `SettledInvoice`, `PendingChargeAttempt`, `SuccessfulCharge`, `FailedCharge`) → plain views.
- `FlaggedCustomer`'s aggregate (`count(invoices where OverdueInvoice) >= 3`) → a view with `GROUP BY`/`HAVING`, consumed by the daily sweep.
- `each FlaggedCustomer produces AccountFlag` → a set-based `INSERT ... SELECT ... WHERE NOT EXISTS`, no loop construct needed.
- `then` (`AuditLogEntry then ChargeAttempt`) → two sequential `INSERT`s in one function body.
- Simple `produces` (`SendReceipt`, `ReleaseInventory`, `NotifyCustomerOfFlag`) → `UNIQUE` constraints, same pattern as the earlier sketch.

### Gap A — trigger placement for aggregate-derived refinements

The earlier sketch's entering-trigger lived on `invoices`, diffing `OLD.balance`/`NEW.balance`. That doesn't actually work against the real schema: `balance` isn't a column on `invoices` — it's a view over `payments` (and `refunds`). No write ever touches the `invoices` row when a payment arrives, so a trigger on `invoices` alone would never fire.

The trigger has to live on **`payments`** (and, once refunds exist, on **`refunds`** too) — whichever table's write can move the aggregate. It also turns out not to need OLD/NEW diffing at all: since edge-trigger semantics are already provided by the `produces` guard (`not exists Receipt for this`), the trigger only needs to check, after the write, "does this invoice now satisfy `SettledInvoice`, and does no `Receipt` exist yet" — no "was it settled before" bookkeeping required.

The real work here is a **compiler task, not a Postgres limitation**: for any refinement whose predicate reaches through a relationship, codegen has to walk the dependency graph and attach a check to every table that feeds it, not just the refinement's "subject" table.

### Gap B — two rules in the example have no field to hang the `produces` guard on

The sharpest finding, and a spec gap rather than a SQL one. `produces`'s implicit guard (`README.md` §11) works by type-matching a field on the produced shape against the triggering shape. Two rules in the consolidated example don't have one:

- `rule RecordPayment on SuccessfulCharge produces Payment` — but `Payment { invoice, amount, receivedOn, refunds }` has **no field referencing `Order`/`ChargeAttempt`** at all.
- `rule StartGracePeriod on ReopenedInvoice produces GracePeriod` — but `GracePeriod { customer, startedOn, endsOn }` has **no field referencing `Invoice`**.

Trying to generate a `UNIQUE` constraint for either forces the question the prose glossed over: there's no column to constrain. Either the shape is missing a field (e.g. `Payment.chargeAttempt: one ChargeAttempt`) or the guard is meant to key off something else entirely and needs an explicit `for`. Postgres can't paper over this — attempting the compilation just makes the omission concrete, which is arguably exactly what "compiling forces explicitness" (`README.md` §1) is supposed to do.

### Gap C — cross-table invariants have no declarative form

`ActiveAccountFlag = AccountFlag where not exists AccountFlagResolved for this` is used to keep "at most one active flag per customer" true by construction, across two cooperating rules (`FlagOverdueAccounts` / `ResolveFlagIfCleared`). Postgres's declarative constraints — `UNIQUE`, `CHECK`, `EXCLUDE` — only ever see one row or one table's own columns; none can express "at most one row in this table joined against another table satisfies X." Enforcing it defensively (rather than trusting the two rules stay in sync) needs a **constraint trigger** — procedural code again, the kind of imperative machinery the declarative approach was meant to avoid. Same story for `GracePeriod`/`InGracePeriod`.

### Gap D — no scheduler, and effects that leave the database

- `on Daily` has no core-Postgres equivalent — needs the `pg_cron` extension (not guaranteed available on every hosted Postgres) or an external cron calling into the DB.
- `ChargeAttempt.response` only gets filled by an actual call to a payment processor. That call, and the eventual callback writing the result back, is fundamentally outside anything a database can do — the same outbox/worker boundary as `## 2`, just concrete here: the *initiation* (`ChargeAttempt` row) is fully DB-native, the *resolution* (`ChargeResponse`) always crosses out to an external system first.

### Not a Postgres gap, but worth naming

Narrowing analysis (`.` vs `?.`), refinement exhaustiveness/overlap, and proving "at most one match" for query-`for` expressions like `(ActiveAccountFlag for this)` are static checks Postgres has no facility for at all — that has to happen entirely in the Velle compiler *before* SQL is generated, regardless of target. The one accidental assist Postgres gives here: a scalar subquery that returns more than one row raises a runtime error, so a violated at-most-one assumption fails loudly instead of silently picking a row — a backstop, not a substitute for the compiler proving it in advance.

### Next step

Work through Gap B concretely by adding the missing `Payment.chargeAttempt` field (or an explicit `for`) and see what the rest of the compiled model looks like once the guard has somewhere to attach.
