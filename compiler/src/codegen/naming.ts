const SCALAR_SQL_TYPES: Record<string, string> = {
  text: 'text',
  integer: 'integer',
  decimal: 'numeric(14,2)',
  boolean: 'boolean',
  Date: 'date',
  DateTime: 'timestamptz',
  Money: 'numeric(14,2)',
};

export function scalarSqlType(typeName: string): string {
  const sql = SCALAR_SQL_TYPES[typeName];
  if (!sql) throw new Error(`no SQL mapping for scalar type '${typeName}'`);
  return sql;
}

export function snakeCase(name: string): string {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
    .toLowerCase();
}

function pluralize(word: string): string {
  if (/[a-z]y$/.test(word)) return word.slice(0, -1) + 'ies';
  if (/(s|x|z|ch|sh)$/.test(word)) return word + 'es';
  return word + 's';
}

export function tableName(shapeName: string): string {
  return pluralize(snakeCase(shapeName));
}

export function columnName(fieldName: string): string {
  return snakeCase(fieldName);
}

export function fkColumnName(fieldName: string): string {
  return `${snakeCase(fieldName)}_id`;
}

export function viewName(refinementName: string): string {
  return snakeCase(refinementName);
}

export function derivedViewName(shapeName: string): string {
  return `${tableName(shapeName)}_derived`;
}

// These two take an already-computed table name (not a shape name) — callers in
// triggers.ts group rules by table and don't retain a shape name for it.
export function triggerFunctionName(table: string): string {
  return `trg_${table}_check`;
}

export function triggerName(table: string): string {
  return `${table}_after_write`;
}

export function guardConstraintName(shapeName: string, guardField: string): string {
  return `${tableName(shapeName)}_${columnName(guardField)}_unique`;
}
