import type { ResolvedFile } from '../resolver.js';
import { tableName, fkColumnName, columnName, guardConstraintName } from './naming.js';
import { getField } from '../resolver.js';

export function generateGuards(resolved: ResolvedFile): string {
  const statements: string[] = [];
  for (const rule of resolved.rules) {
    if (!rule.guard) continue;
    const field = getField(resolved, rule.guard.producedShape, rule.guard.guardField);
    const column = field.cardinality === 'one' ? fkColumnName(field.name) : columnName(field.name);
    const table = tableName(rule.guard.producedShape);
    statements.push(
      `ALTER TABLE ${table} ADD CONSTRAINT ${guardConstraintName(rule.guard.producedShape, rule.guard.guardField)} UNIQUE (${column});`,
    );
  }
  // A produced shape can be the guard target of at most one rule in this PoC's model,
  // but de-duplicate defensively in case two rules key on the same (shape, field).
  return [...new Set(statements)].join('\n');
}
