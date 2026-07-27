import type { ValueExpr } from '../ast.js';
import { getField, resolveChainShape, type ResolvedFile, type ResolvedRule } from '../resolver.js';
import { tableName, columnName, fkColumnName } from './naming.js';

export const EFFECTS_OUTBOX_TABLE = 'effects_outbox';

export function generateOutboxTable(): string {
  return `CREATE TABLE ${EFFECTS_OUTBOX_TABLE} (
  id bigserial PRIMARY KEY,
  effect_name text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);`;
}

// ---- Effect plans consumed by the generic, hand-written worker at runtime ----
// (see src/worker.ts — the codegen never emits per-rule worker code; it emits this
// metadata instead, which the same generic function interprets for every rule.)

export type EffectValuePlan =
  | { kind: 'lookup'; reads: { table: string; column: string }[] } // reads: [] means "the root id itself"
  | { kind: 'now' }
  | { kind: 'literal'; value: string | number };

export interface EffectColumnPlan {
  column: string;
  plan: EffectValuePlan;
}

export interface EffectStatementPlan {
  table: string;
  columns: EffectColumnPlan[];
  isProducesTarget: boolean;
  guardColumn?: string; // set iff isProducesTarget and the rule has a produces guard
}

export interface RuleEffectPlan {
  ruleName: string;
  triggerBaseTable: string;
  stages: EffectStatementPlan[][];
}

export function compileEffectValueExpr(
  resolved: ResolvedFile,
  triggerBaseShape: string,
  expr: ValueExpr,
): EffectValuePlan {
  switch (expr.kind) {
    case 'now':
      return { kind: 'now' };
    case 'stringLiteral':
      return { kind: 'literal', value: expr.value };
    case 'numberLiteral':
      return { kind: 'literal', value: expr.value };
    case 'this':
      return { kind: 'lookup', reads: [] };
    case 'identifier': {
      // A bare field name inside a rule body means the same thing a predicate's bare
      // name means: relative to `this` (the triggering instance) — equivalent to
      // writing `this.<name>` explicitly.
      const field = getField(resolved, triggerBaseShape, expr.name);
      const column = field.cardinality === 'one' ? fkColumnName(field.name) : columnName(field.name);
      return { kind: 'lookup', reads: [{ table: tableName(triggerBaseShape), column }] };
    }
    case 'path': {
      const basePlan = compileEffectValueExpr(resolved, triggerBaseShape, expr.base);
      if (basePlan.kind !== 'lookup') {
        throw new Error(`cannot access '.${expr.field}' on a non-relation value`);
      }
      const baseShape = resolveChainShape(resolved, triggerBaseShape, expr.base);
      const field = getField(resolved, baseShape, expr.field);
      const column = field.cardinality === 'one' ? fkColumnName(field.name) : columnName(field.name);
      return { kind: 'lookup', reads: [...basePlan.reads, { table: tableName(baseShape), column }] };
    }
    case 'binary':
    case 'sum':
      throw new Error(`'${expr.kind}' expressions are not supported inside rule effect bodies in this PoC`);
  }
}

export function compileRuleEffectPlan(resolved: ResolvedFile, rule: ResolvedRule): RuleEffectPlan {
  const stages: EffectStatementPlan[][] = rule.stages.map((stage) =>
    stage.map((stmt) => {
      const isProducesTarget = stmt.shapeName === rule.producesName;
      const columns: EffectColumnPlan[] = stmt.mapping.map((m) => {
        const field = getField(resolved, stmt.shapeName, m.field);
        const column = field.cardinality === 'one' ? fkColumnName(field.name) : columnName(field.name);
        return { column, plan: compileEffectValueExpr(resolved, rule.triggerBaseShape, m.value) };
      });
      return {
        table: tableName(stmt.shapeName),
        columns,
        isProducesTarget,
        guardColumn: isProducesTarget ? rule.guard?.guardField && columnNameForGuard(resolved, stmt.shapeName, rule.guard.guardField) : undefined,
      };
    }),
  );
  return { ruleName: rule.name, triggerBaseTable: tableName(rule.triggerBaseShape), stages };
}

function columnNameForGuard(resolved: ResolvedFile, shapeName: string, guardField: string): string {
  const field = getField(resolved, shapeName, guardField);
  return field.cardinality === 'one' ? fkColumnName(field.name) : columnName(field.name);
}

export function generateEffectPlans(resolved: ResolvedFile): RuleEffectPlan[] {
  return resolved.rules.filter((r) => r.producesName || r.stages.some((s) => s.length > 0)).map((r) => compileRuleEffectPlan(resolved, r));
}
