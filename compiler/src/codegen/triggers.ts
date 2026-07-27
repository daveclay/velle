import { findInverseOneField, getField, type AccessStep, type ResolvedFile, type ResolvedRule } from '../resolver.js';
import { tableName, columnName, fkColumnName, viewName, triggerFunctionName, triggerName } from './naming.js';
import { EFFECTS_OUTBOX_TABLE } from './outbox.js';

// Computes a SQL expression, valid in a trigger function referencing `NEW`, that yields
// the id of the trigger's base-shape instance affected by a write to `drivingShape`'s
// table. `path` is the (possibly empty) access path from the trigger base shape to
// `drivingShape`, walked in reverse here since we're going from a written row back up
// to the root it affects — the inverse of how `views.ts` walks the same kind of path
// forward when aggregating with `sum(...)`.
function affectedRootIdExpr(resolved: ResolvedFile, path: AccessStep[]): string {
  if (path.length === 0) return 'NEW.id';

  let expr = 'NEW';
  for (let idx = path.length - 1; idx >= 0; idx--) {
    const step = path[idx];
    const childField = findInverseOneField(resolved, step.toShape, step.fromShape);
    const fk = fkColumnName(childField.name);
    if (idx === path.length - 1) {
      expr = `${expr}.${fk}`;
    } else {
      const childTable = tableName(step.toShape);
      expr = `(SELECT ${fk} FROM ${childTable} WHERE id = ${expr})`;
    }
  }
  return expr;
}

interface TriggerCheck {
  rule: ResolvedRule;
  affectedIdExpr: string;
}

export function generateTriggers(resolved: ResolvedFile): string {
  // Group rules-with-guards by every table whose writes should re-check them.
  const byTable = new Map<string, TriggerCheck[]>();
  for (const rule of resolved.rules) {
    if (!rule.guard) continue; // only produces-guarded rules have an entering-trigger in this PoC
    for (const [drivingShape, path] of rule.drivingShapes) {
      const table = tableName(drivingShape);
      const check: TriggerCheck = { rule, affectedIdExpr: affectedRootIdExpr(resolved, path) };
      if (!byTable.has(table)) byTable.set(table, []);
      byTable.get(table)!.push(check);
    }
  }

  const statements: string[] = [];
  for (const [table, checks] of byTable) {
    statements.push(generateTriggerFunctionAndTrigger(resolved, table, checks));
  }
  return statements.join('\n\n');
}

function generateTriggerFunctionAndTrigger(resolved: ResolvedFile, table: string, checks: TriggerCheck[]): string {
  const fnName = triggerFunctionName(table);
  const trigName = triggerName(table);

  const blocks = checks.map((check) => {
    const { rule, affectedIdExpr } = check;
    const guardTable = tableName(rule.guard!.producedShape);
    const guardColumn = guardColumnName(resolved, rule.guard!.producedShape, rule.guard!.guardField);
    const isBareRecordTrigger = resolved.recordShapes.has(rule.triggerName);
    const membershipCheck = isBareRecordTrigger
      ? 'TRUE'
      : `EXISTS (SELECT 1 FROM ${viewName(rule.triggerName)} WHERE id = affected_id)`;

    return `    affected_id := ${affectedIdExpr};
    IF affected_id IS NOT NULL
       AND ${membershipCheck}
       AND NOT EXISTS (SELECT 1 FROM ${guardTable} WHERE ${guardColumn} = affected_id)
    THEN
      INSERT INTO ${EFFECTS_OUTBOX_TABLE} (effect_name, payload)
      VALUES ('${rule.name}', jsonb_build_object('root_id', affected_id));
    END IF;`;
  });

  return `CREATE OR REPLACE FUNCTION ${fnName}() RETURNS trigger AS $$
DECLARE
  affected_id bigint;
BEGIN
${blocks.join('\n')}
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ${trigName}
AFTER INSERT OR UPDATE ON ${table}
FOR EACH ROW EXECUTE FUNCTION ${fnName}();`;
}

function guardColumnName(resolved: ResolvedFile, shapeName: string, guardField: string): string {
  const field = getField(resolved, shapeName, guardField);
  return field.cardinality === 'one' ? fkColumnName(field.name) : columnName(field.name);
}
