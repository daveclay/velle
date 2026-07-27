import type { EffectValuePlan, RuleEffectPlan, EffectStatementPlan } from './codegen/outbox.js';

// A generic, hand-written outbox consumer — NOT generated per rule. It's driven
// entirely by the `RuleEffectPlan[]` metadata the compiler emits alongside the SQL.
// This is the one piece of this proof-of-concept that stands in for wherever a *real*
// Velle rule effect would eventually call out to something genuinely external (a
// payment processor, an email provider) — every effect in this PoC's example is just
// an INSERT, so there's nothing external to call, but the outbox/worker boundary is
// kept anyway so the architecture is honest about where that would plug in.

export interface SqlExecutor {
  query(sql: string, params?: unknown[]): Promise<{ rows: Record<string, unknown>[] }>;
}

interface OutboxRow {
  id: number;
  effect_name: string;
  payload: { root_id: number };
}

async function resolveValue(db: SqlExecutor, plan: EffectValuePlan, rootId: number): Promise<unknown> {
  if (plan.kind === 'now') return new Date().toISOString();
  if (plan.kind === 'literal') return plan.value;

  let currentId: unknown = rootId;
  for (const read of plan.reads) {
    const result = await db.query(`SELECT ${read.column} AS value FROM ${read.table} WHERE id = $1`, [currentId]);
    if (result.rows.length === 0) {
      throw new Error(`worker: no row in ${read.table} with id ${currentId}`);
    }
    currentId = result.rows[0].value;
  }
  return currentId;
}

async function applyEffectStatement(db: SqlExecutor, stmt: EffectStatementPlan, rootId: number): Promise<void> {
  const values = await Promise.all(stmt.columns.map((c) => resolveValue(db, c.plan, rootId)));
  const columnList = stmt.columns.map((c) => c.column).join(', ');
  const placeholders = values.map((_, i) => `$${i + 1}`).join(', ');
  let sql = `INSERT INTO ${stmt.table} (${columnList}) VALUES (${placeholders})`;
  if (stmt.isProducesTarget && stmt.guardColumn) {
    sql += ` ON CONFLICT (${stmt.guardColumn}) DO NOTHING`;
  }
  await db.query(sql, values);
}

// Applies every unprocessed outbox row exactly once, each inside its own transaction:
// if any effect statement in a rule's body fails, the whole row's effects roll back and
// the row stays unprocessed for a later retry (crash-safety). The produces-target
// statement's own `ON CONFLICT DO NOTHING` (see applyEffectStatement) is the separate,
// stronger guarantee that survives even a duplicate outbox entry for the same instance.
export async function processOutbox(db: SqlExecutor, effectPlans: RuleEffectPlan[]): Promise<number> {
  const plansByName = new Map(effectPlans.map((p) => [p.ruleName, p]));
  let processedCount = 0;

  // A rule's own effect can itself write a row that a trigger reacts to (e.g.
  // RecordPayment inserting a Payment causes SendReceipt to be enqueued) — that new
  // outbox row is written *during* this same call, after any snapshot of "pending" was
  // already taken. Loop passes until a full pass finds nothing left, so cascaded
  // effects are drained in one processOutbox() call rather than requiring the caller
  // to know how many rounds of cascading to expect.
  for (;;) {
    const pending = await db.query(
      `SELECT id, effect_name, payload FROM effects_outbox WHERE processed_at IS NULL ORDER BY id`,
    );
    if (pending.rows.length === 0) break;

    for (const row of pending.rows as unknown as OutboxRow[]) {
      const plan = plansByName.get(row.effect_name);
      if (!plan) {
        throw new Error(`worker: no effect plan for outbox effect '${row.effect_name}'`);
      }
      const rootId = row.payload.root_id;

      await db.query('BEGIN');
      try {
        for (const stage of plan.stages) {
          for (const stmt of stage) {
            await applyEffectStatement(db, stmt, rootId);
          }
        }
        await db.query('UPDATE effects_outbox SET processed_at = now() WHERE id = $1', [row.id]);
        await db.query('COMMIT');
        processedCount++;
      } catch (err) {
        await db.query('ROLLBACK');
        throw err;
      }
    }
  }
  return processedCount;
}
