import { describe, it, expect, beforeEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { PGlite } from '@electric-sql/pglite';
import { compileVelSource } from '../src/compile.js';
import { processOutbox } from '../src/worker.js';
import type { RuleEffectPlan } from '../src/codegen/outbox.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const source = readFileSync(path.join(here, '../examples/invoice_payment.vel'), 'utf-8');

let db: PGlite;
let effectPlans: RuleEffectPlan[];

beforeEach(async () => {
  const compiled = compileVelSource(source);
  effectPlans = compiled.effectPlans;
  db = new PGlite();
  await db.exec(compiled.sql);
});

async function insertCustomer(): Promise<number> {
  const r = await db.query(`INSERT INTO customers (name, email) VALUES ('Ada', 'ada@example.com') RETURNING id`);
  return (r.rows[0] as { id: number }).id;
}

async function insertInvoice(customerId: number, amount: string): Promise<number> {
  const r = await db.query(
    `INSERT INTO invoices (customer_id, amount, due) VALUES ($1, $2, '2026-08-01') RETURNING id`,
    [customerId, amount],
  );
  return (r.rows[0] as { id: number }).id;
}

async function insertOrder(customerId: number, invoiceId: number, amount: string): Promise<number> {
  const r = await db.query(
    `INSERT INTO orders (customer_id, invoice_id, amount) VALUES ($1, $2, $3) RETURNING id`,
    [customerId, invoiceId, amount],
  );
  return (r.rows[0] as { id: number }).id;
}

async function count(table: string, whereCol: string, whereVal: number): Promise<number> {
  const r = await db.query(`SELECT count(*)::int AS n FROM ${table} WHERE ${whereCol} = $1`, [whereVal]);
  return (r.rows[0] as { n: number }).n;
}

describe('end-to-end: Order -> ChargeAttempt -> Payment -> Receipt', () => {
  it('runs the full happy-path chain and is idempotent on re-runs', async () => {
    const customerId = await insertCustomer();
    const invoiceId = await insertInvoice(customerId, '100.00');
    const orderId = await insertOrder(customerId, invoiceId, '100.00');

    // InitiateCharge should have enqueued from the orders-table trigger.
    let processed = await processOutbox(db, effectPlans);
    expect(processed).toBe(1);
    expect(await count('charge_attempts', 'order_id', orderId)).toBe(1);
    expect(await count('audit_log_entries', 'order_id', orderId)).toBe(1);

    // Idempotency: nothing pending, nothing new happens.
    processed = await processOutbox(db, effectPlans);
    expect(processed).toBe(0);
    expect(await count('charge_attempts', 'order_id', orderId)).toBe(1);

    const attempt = await db.query(`SELECT id FROM charge_attempts WHERE order_id = $1`, [orderId]);
    const attemptId = (attempt.rows[0] as { id: number }).id;

    // Simulate the external payment-processor callback approving the charge.
    const resp = await db.query(
      `INSERT INTO charge_responses (outcome, processed_on) VALUES ('approved', now()) RETURNING id`,
    );
    await db.query(`UPDATE charge_attempts SET response_id = $1 WHERE id = $2`, [
      (resp.rows[0] as { id: number }).id,
      attemptId,
    ]);

    // This single call must drain the cascade: RecordPayment enqueues, its own INSERT
    // settles the invoice, which enqueues SendReceipt in the same processOutbox() call.
    processed = await processOutbox(db, effectPlans);
    expect(processed).toBe(2);
    expect(await count('payments', 'invoice_id', invoiceId)).toBe(1);
    expect(await count('receipts', 'invoice_id', invoiceId)).toBe(1);

    // Re-running the worker with nothing pending must not create duplicates anywhere.
    processed = await processOutbox(db, effectPlans);
    expect(processed).toBe(0);
    expect(await count('payments', 'invoice_id', invoiceId)).toBe(1);
    expect(await count('receipts', 'invoice_id', invoiceId)).toBe(1);
  });

  it('releases inventory on a declined charge instead of recording a payment', async () => {
    const customerId = await insertCustomer();
    const invoiceId = await insertInvoice(customerId, '50.00');
    const orderId = await insertOrder(customerId, invoiceId, '50.00');

    await processOutbox(db, effectPlans);
    const attempt = await db.query(`SELECT id FROM charge_attempts WHERE order_id = $1`, [orderId]);
    const attemptId = (attempt.rows[0] as { id: number }).id;

    const resp = await db.query(
      `INSERT INTO charge_responses (outcome, processed_on) VALUES ('declined', now()) RETURNING id`,
    );
    await db.query(`UPDATE charge_attempts SET response_id = $1 WHERE id = $2`, [
      (resp.rows[0] as { id: number }).id,
      attemptId,
    ]);

    await processOutbox(db, effectPlans);
    expect(await count('inventory_releases', 'order_id', orderId)).toBe(1);
    expect(await count('payments', 'invoice_id', invoiceId)).toBe(0);

    // Re-running must not double-release.
    await processOutbox(db, effectPlans);
    expect(await count('inventory_releases', 'order_id', orderId)).toBe(1);
  });

  it('recomputes balance after a refund but leaves the already-sent receipt untouched (documented reversal gap, README.md #18)', async () => {
    const customerId = await insertCustomer();
    const invoiceId = await insertInvoice(customerId, '100.00');
    const orderId = await insertOrder(customerId, invoiceId, '100.00');

    await processOutbox(db, effectPlans);
    const attempt = await db.query(`SELECT id FROM charge_attempts WHERE order_id = $1`, [orderId]);
    const resp = await db.query(
      `INSERT INTO charge_responses (outcome, processed_on) VALUES ('approved', now()) RETURNING id`,
    );
    await db.query(`UPDATE charge_attempts SET response_id = $1 WHERE id = $2`, [
      (resp.rows[0] as { id: number }).id,
      (attempt.rows[0] as { id: number }).id,
    ]);
    await processOutbox(db, effectPlans);
    expect(await count('receipts', 'invoice_id', invoiceId)).toBe(1);

    const payment = await db.query(`SELECT id FROM payments WHERE invoice_id = $1`, [invoiceId]);
    await db.query(`INSERT INTO refunds (payment_id, amount, refunded_on) VALUES ($1, 100.00, now())`, [
      (payment.rows[0] as { id: number }).id,
    ]);
    await processOutbox(db, effectPlans);

    const derived = await db.query(`SELECT balance FROM invoices_derived WHERE id = $1`, [invoiceId]);
    expect(Number((derived.rows[0] as { balance: string }).balance)).toBeGreaterThan(0);

    // This PoC does not implement the reversal pattern (README.md #18 / stress test #5)
    // — a stale Receipt from before the refund is expected to remain exactly as-is.
    expect(await count('receipts', 'invoice_id', invoiceId)).toBe(1);
  });
});
