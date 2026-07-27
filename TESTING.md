# Testing this repo

`README.md` is the language spec — theory, on purpose. This file is the opposite: the
least-theoretical set of steps to actually prove the one thing in this repo that isn't
just prose, `compiler/` (see `compiler/README.md` for what it is and exactly what it
does and doesn't support). Everything below is copy-pasteable from a fresh clone.

Prerequisite: Node.js >= 20. That's it — no Docker, no separately installed Postgres,
no database credentials, no account signups.

## 1. Clone and install

```
git clone <this repo> && cd velle
npm install
```

## 2. Run the automated proof

```
npm test
```

Expect:

```
 Test Files  2 passed (2)
      Tests  6 passed (6)
```

What that green checkmark actually proved, concretely:
- **`compile.test.ts`**: the compiler turns `compiler/examples/invoice_payment.vel`
  (real `.vel` source) into byte-for-byte the SQL committed at
  `compiler/test/golden/invoice_payment.sql` — open that file directly if you want to
  read the generated Postgres DDL/views/triggers without running anything at all.
- Two negative fixtures in the same file prove the compiler *rejects* an invalid model
  with a specific error message, not just happens to succeed on the one blessed input
  (see step 5 below to reproduce this by hand).
- **`apply.test.ts`** boots a real Postgres engine in-process
  (`@electric-sql/pglite` — Postgres itself compiled to WASM, not a mock/stub), applies
  the generated SQL to it, and drives real insert/update traffic through it to prove
  the trigger → outbox → worker chain actually does what it claims, including two
  idempotency re-runs and one documented, intentionally-unfixed gap (a refund
  correctly recomputes the invoice balance but leaves the earlier receipt in place —
  see `README.md` #18 and `compiler/README.md`'s "out of scope" list).

## 3. Watch it happen with your own eyes

`npm test` proves it via assertions; this runs the identical chain but prints every
step, so you can watch rows appear instead of trusting a pass/fail:

```
npm run demo
```

Expect (abbreviated — yours will match exactly, this is a fresh, deterministic
database every run):

```
=== 3. Insert an Order — the trigger enqueues, it does not apply anything yet ===
effects_outbox (enqueued, not yet applied): [ { effect_name: 'InitiateCharge', payload: { root_id: 1 } } ]

=== 4. Run the worker — this is where an effect actually gets applied ===
worker processed 1 effect(s)
charge_attempts: [ { id: 1, order_id: 1, ... } ]

=== 5. Simulate the payment processor approving the charge (the one external boundary) ===
worker processed 2 effect(s) — RecordPayment, and SendReceipt cascading from the same call
payments: [ { id: 1, invoice_id: 1, charge_attempt_id: 1, amount: '100.00' } ]
receipts: [ { id: 1, invoice_id: 1, sent_on: ... } ]

=== 6. Prove idempotency — run the worker again with nothing new pending ===
worker processed 0 effect(s) (expect 0) — payments: 1, receipts: 1 (expect 1 each, no duplicates)
```

The "trigger enqueues, worker applies" split in step 3→4 is the outbox pattern from
`postgres_transpile_brainstorm.md` made visible: nothing is inserted by the database
trigger itself, only recorded as "this should happen" — the actual `INSERT` happens in
a separate call, which is also where a *real* external effect (an actual payment-gateway
call) would eventually plug in.

## 4. Read the generated SQL yourself (zero execution required)

`compiler/test/golden/invoice_payment.sql` is exactly what step 2/3 above run — a
human-readable Postgres file: `CREATE TABLE`s, refinement `CREATE VIEW`s, `UNIQUE`
guard constraints, and `plpgsql` trigger functions. If you trust nothing else in this
repo, read that one file; it's not a snippet, it's the literal compiler output.

## 5. Prove the compiler is real: break it, watch it fail correctly, undo

This is the actual proof that the compiler is doing analysis, not just replaying two
files it was tuned against. `compiler/examples/invoice_payment.vel` has a field,
`chargeAttempt`, added specifically so a `produces` guard has somewhere to attach (see
`compiler/README.md`'s "Fixes applied" section for why). Delete it and recompile:

```
sed -n '23p' compiler/examples/invoice_payment.vel   # confirm you're deleting the right line
sed -i '23d' compiler/examples/invoice_payment.vel
npm run compile -- examples/invoice_payment.vel
```

Expect compilation to fail with:

```
vel-compile: rule 'RecordPayment': produces 'Payment' for 'chargeAttempt', but 'Payment' has no such field (line 91, col 1)
```

Then undo it (either `git checkout -- compiler/examples/invoice_payment.vel`, or
re-add the deleted line `    chargeAttempt: one ChargeAttempt?` back into the
`Payment` shape) and confirm `npm test` is green again.

Two more fixtures reproduce the same class of failure directly, without editing
anything:

```
npm run compile -- test/fixtures/gap_b_zero_match.vel
npm run compile -- test/fixtures/gap_b_ambiguous.vel
```

Both are expected to fail — that's the point. Their exact expected error text is
asserted in `compiler/test/compile.test.ts`.

## If something doesn't match

- `compiler/README.md` — exact scope: what this compiler supports, what it
  deliberately doesn't (with citations to the open questions in this repo's other
  docs), and the documented assumptions it makes.
- `postgres_transpile_brainstorm.md` — the design rationale and gap analysis this
  compiler implements.
