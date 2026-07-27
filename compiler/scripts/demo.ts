// A narrated walk through the same chain apply.test.ts asserts silently — run this
// when you want to *watch* the compiler + Postgres engine work instead of trusting a
// green checkmark. See ../../TESTING.md.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PGlite } from '@electric-sql/pglite';
import { compileVelSource } from '../src/compile.js';
import { processOutbox } from '../src/worker.js';

const here = path.dirname(fileURLToPath(import.meta.url));

function section(title: string) {
  console.log(`\n=== ${title} ===`);
}

async function main() {
  section('1. Compile examples/invoice_payment.vel to Postgres SQL');
  const source = readFileSync(path.join(here, '../examples/invoice_payment.vel'), 'utf-8');
  const { sql, effectPlans } = compileVelSource(source);
  console.log(`compiled ${sql.split('\n').length} lines of SQL — full text also at compiler/test/golden/invoice_payment.sql`);

  section('2. Apply it to a real Postgres engine (PGlite: Postgres compiled to WASM, not a mock)');
  const db = new PGlite();
  await db.exec(sql);
  console.log('applied cleanly: tables, views, UNIQUE guards, and plpgsql triggers all created');

  section('3. Insert an Order — the trigger enqueues, it does not apply anything yet');
  const cust = await db.query(`INSERT INTO customers (name, email) VALUES ('Ada', 'ada@example.com') RETURNING id`);
  const inv = await db.query(
    `INSERT INTO invoices (customer_id, amount, due) VALUES ($1, 100.00, '2026-08-01') RETURNING id`,
    [cust.rows[0].id],
  );
  const order = await db.query(
    `INSERT INTO orders (customer_id, invoice_id, amount) VALUES ($1, $2, 100.00) RETURNING id`,
    [cust.rows[0].id, inv.rows[0].id],
  );
  const pendingOutbox = await db.query(`SELECT effect_name, payload FROM effects_outbox WHERE processed_at IS NULL`);
  console.log('effects_outbox (enqueued, not yet applied):', pendingOutbox.rows);

  section('4. Run the worker — this is where an effect actually gets applied');
  const n1 = await processOutbox(db, effectPlans);
  console.log(`worker processed ${n1} effect(s)`);
  console.log('charge_attempts:', (await db.query(`SELECT id, order_id, requested_on FROM charge_attempts`)).rows);
  console.log('audit_log_entries:', (await db.query(`SELECT id, order_id, logged_on FROM audit_log_entries`)).rows);

  section('5. Simulate the payment processor approving the charge (the one external boundary)');
  const attempt = await db.query(`SELECT id FROM charge_attempts WHERE order_id = $1`, [order.rows[0].id]);
  const resp = await db.query(`INSERT INTO charge_responses (outcome, processed_on) VALUES ('approved', now()) RETURNING id`);
  await db.query(`UPDATE charge_attempts SET response_id = $1 WHERE id = $2`, [resp.rows[0].id, attempt.rows[0].id]);

  const n2 = await processOutbox(db, effectPlans);
  console.log(`worker processed ${n2} effect(s) — RecordPayment, and SendReceipt cascading from the same call`);
  console.log('payments:', (await db.query(`SELECT id, invoice_id, charge_attempt_id, amount FROM payments`)).rows);
  console.log('receipts:', (await db.query(`SELECT id, invoice_id, sent_on FROM receipts`)).rows);

  section('6. Prove idempotency — run the worker again with nothing new pending');
  const n3 = await processOutbox(db, effectPlans);
  const paymentCount = await db.query(`SELECT count(*)::int AS n FROM payments`);
  const receiptCount = await db.query(`SELECT count(*)::int AS n FROM receipts`);
  console.log(`worker processed ${n3} effect(s) (expect 0) — payments: ${paymentCount.rows[0].n}, receipts: ${receiptCount.rows[0].n} (expect 1 each, no duplicates)`);

  section('Done — real compiler, real Postgres engine, real (generic, non-generated) worker.');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
