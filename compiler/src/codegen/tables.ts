import type { RecordShapeDecl } from '../ast.js';
import type { ResolvedFile } from '../resolver.js';
import { tableName, columnName, fkColumnName, scalarSqlType } from './naming.js';

// Record shapes in an order safe for CREATE TABLE ... REFERENCES (every shape a table's
// `one` fields reference is created before the table itself).
export function topoSortShapes(resolved: ResolvedFile): RecordShapeDecl[] {
  const visited = new Set<string>();
  const ordered: RecordShapeDecl[] = [];

  function visit(name: string) {
    if (visited.has(name)) return;
    visited.add(name);
    const shape = resolved.recordShapes.get(name);
    if (!shape) return;
    for (const field of shape.fields) {
      if (field.cardinality === 'one' && resolved.recordShapes.has(field.typeName)) {
        visit(field.typeName);
      }
    }
    ordered.push(shape);
  }

  for (const name of resolved.recordShapes.keys()) visit(name);
  return ordered;
}

export function generateTables(resolved: ResolvedFile): string {
  const shapes = topoSortShapes(resolved);
  const statements = shapes.map((shape) => generateTable(shape));
  return statements.join('\n\n');
}

function generateTable(shape: RecordShapeDecl): string {
  const lines: string[] = [`CREATE TABLE ${tableName(shape.name)} (`];
  const columns: string[] = ['  id bigserial PRIMARY KEY'];

  for (const field of shape.fields) {
    if (field.cardinality === 'many') continue; // inverse-only, no column
    if (field.cardinality === 'scalar' && field.derivedExpr) continue; // computed, not stored

    const notNull = field.optional ? '' : ' NOT NULL';
    if (field.cardinality === 'one') {
      columns.push(
        `  ${fkColumnName(field.name)} bigint REFERENCES ${tableName(field.typeName)}(id)${notNull}`,
      );
    } else {
      columns.push(`  ${columnName(field.name)} ${scalarSqlType(field.typeName)}${notNull}`);
    }
  }

  lines.push(columns.join(',\n'));
  lines.push(');');
  return lines.join('\n');
}
