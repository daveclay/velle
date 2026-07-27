import type { RecordShapeDecl, RefinementShapeDecl, ValueExpr, Predicate } from '../ast.js';
import { getField, findInverseOneField, type ResolvedFile, type AccessStep } from '../resolver.js';
import { tableName, columnName, fkColumnName, derivedViewName, viewName } from './naming.js';

// ---- Derived-property views ----
// A record shape with >=1 derived field gets a `<table>_derived` view exposing every
// stored column plus the computed ones. Refinements are always built on top of this
// view (or the raw table, if the shape has no derived fields) so `balance`-style
// properties are available wherever a refinement's predicate needs them.

export function generateDerivedViews(resolved: ResolvedFile): string {
  const statements: string[] = [];
  for (const shape of resolved.recordShapes.values()) {
    const derivedFields = shape.fields.filter((f) => f.derivedExpr);
    if (derivedFields.length === 0) continue;
    const alias = 't';
    const computedColumns = derivedFields.map(
      (f) => `  ${renderDerivedExpr(resolved, shape.name, alias, f.derivedExpr!)} AS ${columnName(f.name)}`,
    );
    statements.push(
      `CREATE VIEW ${derivedViewName(shape.name)} AS\nSELECT ${alias}.*,\n${computedColumns.join(',\n')}\nFROM ${tableName(shape.name)} ${alias};`,
    );
  }
  return statements.join('\n\n');
}

export function baseSourceFor(resolved: ResolvedFile, shapeName: string): string {
  const shape = resolved.recordShapes.get(shapeName);
  if (shape && shape.fields.some((f) => f.derivedExpr)) return derivedViewName(shapeName);
  return tableName(shapeName);
}

// Renders a derived-property value expression. Scoped deliberately to exactly what
// this PoC's example needs: plain field reads, `+`/`-` arithmetic, and `sum(...)` over
// a chain of `many` relationships. A bare relation-typed field or a `.`-chain used
// directly (outside `sum`) is not supported — not because Postgres can't express it,
// but because this compiler doesn't need to yet; see compiler/README.md.
function renderDerivedExpr(resolved: ResolvedFile, scopeShape: string, alias: string, expr: ValueExpr): string {
  switch (expr.kind) {
    case 'identifier': {
      const field = getField(resolved, scopeShape, expr.name);
      if (field.cardinality !== 'scalar') {
        throw new Error(`derived-property codegen: '${expr.name}' is not a scalar field (unsupported in this PoC)`);
      }
      return `${alias}.${columnName(expr.name)}`;
    }
    case 'binary': {
      const left = renderDerivedExpr(resolved, scopeShape, alias, expr.left);
      const right = renderDerivedExpr(resolved, scopeShape, alias, expr.right);
      return `(${left} ${expr.op} ${right})`;
    }
    case 'sum': {
      const { finalTable, whereClause, elementShapeName } = resolveManyHopFilter(resolved, scopeShape, alias, expr.collection);
      const sumField = getField(resolved, elementShapeName, expr.field);
      return `COALESCE((SELECT sum(${columnName(sumField.name)}) FROM ${finalTable} WHERE ${whereClause}), 0)`;
    }
    default:
      throw new Error(`derived-property codegen: '${expr.kind}' is not supported in this PoC`);
  }
}

function resolveManyHopFilter(
  resolved: ResolvedFile,
  scopeShape: string,
  outerAlias: string,
  collectionExpr: ValueExpr,
): { finalTable: string; whereClause: string; elementShapeName: string } {
  const path = walkManyHopPath(resolved, scopeShape, collectionExpr);
  const lastStep = path[path.length - 1];

  function parentIdExpr(uptoIndexExclusive: number): { expr: string; isSet: boolean } {
    if (uptoIndexExclusive === 0) return { expr: `${outerAlias}.id`, isSet: false };
    const step = path[uptoIndexExclusive - 1];
    const parent = parentIdExpr(uptoIndexExclusive - 1);
    const childField = findInverseOneField(resolved, step.toShape, step.fromShape);
    const fk = fkColumnName(childField.name);
    const childTable = tableName(step.toShape);
    const cmp = parent.isSet ? 'IN' : '=';
    return { expr: `(SELECT id FROM ${childTable} WHERE ${fk} ${cmp} ${parent.expr})`, isSet: true };
  }

  const parent = parentIdExpr(path.length - 1);
  const childField = findInverseOneField(resolved, lastStep.toShape, lastStep.fromShape);
  const fk = fkColumnName(childField.name);
  const cmp = parent.isSet ? 'IN' : '=';
  return { finalTable: tableName(lastStep.toShape), whereClause: `${fk} ${cmp} ${parent.expr}`, elementShapeName: lastStep.toShape };
}

function walkManyHopPath(resolved: ResolvedFile, scopeShape: string, expr: ValueExpr): AccessStep[] {
  if (expr.kind === 'identifier') {
    const field = getField(resolved, scopeShape, expr.name);
    if (field.cardinality !== 'many') throw new Error(`'${expr.name}' is not a 'many' relationship`);
    return [{ fromShape: scopeShape, viaField: expr.name, toShape: field.typeName }];
  }
  if (expr.kind === 'path') {
    const basePath = walkManyHopPath(resolved, scopeShape, expr.base);
    const baseElement = basePath[basePath.length - 1].toShape;
    const field = getField(resolved, baseElement, expr.field);
    if (field.cardinality !== 'many') throw new Error(`'${expr.field}' is not a 'many' relationship`);
    return [...basePath, { fromShape: baseElement, viaField: expr.field, toShape: field.typeName }];
  }
  throw new Error(`sum(...)'s first argument must be a chain of 'many' relationships`);
}

// ---- Refinement views ----

export function generateRefinementViews(resolved: ResolvedFile): string {
  const statements: string[] = [];
  const chainCache = new Map<string, RefinementShapeDecl[]>();

  function chainFor(name: string): RefinementShapeDecl[] {
    if (chainCache.has(name)) return chainCache.get(name)!;
    const chain: RefinementShapeDecl[] = [];
    let current = name;
    while (resolved.refinementShapes.has(current)) {
      const r = resolved.refinementShapes.get(current)!;
      chain.unshift(r);
      current = r.baseName;
    }
    chainCache.set(name, chain);
    return chain;
  }

  for (const refinement of resolved.refinementShapes.values()) {
    const chain = chainFor(refinement.name);
    const baseRecordShape = chain.length > 0 ? findBaseRecordShape(resolved, chain[0].baseName) : refinement.baseName;
    const source = baseSourceFor(resolved, baseRecordShape);

    const clauses = chain.map((link) => renderPredicate(resolved, baseRecordShape, 't', link.predicate));
    statements.push(
      `CREATE VIEW ${viewName(refinement.name)} AS\nSELECT * FROM ${source} t\nWHERE ${clauses.join('\n  AND ')};`,
    );
  }

  return statements.join('\n\n');
}

function findBaseRecordShape(resolved: ResolvedFile, name: string): string {
  let current = name;
  while (!resolved.recordShapes.has(current)) {
    const r = resolved.refinementShapes.get(current);
    if (!r) throw new Error(`unknown shape '${current}'`);
    current = r.baseName;
  }
  return current;
}

function renderPredicate(resolved: ResolvedFile, scopeShape: string, alias: string, predicate: Predicate): string {
  switch (predicate.kind) {
    case 'comparison': {
      const left = renderPredicateExpr(resolved, scopeShape, alias, predicate.left);
      const right = renderPredicateExpr(resolved, scopeShape, alias, predicate.right);
      const sqlOp = predicate.op === '==' ? '=' : predicate.op;
      return `${left} ${sqlOp} ${right}`;
    }
    case 'isNone': {
      const { sql, isJoinNarrowed } = renderOptionalRelationExpr(resolved, scopeShape, alias, predicate.expr);
      return isJoinNarrowed ? `${sql} IS NULL` : `${sql} IS NULL`;
    }
    case 'isSome': {
      const { sql } = renderOptionalRelationExpr(resolved, scopeShape, alias, predicate.expr);
      return `${sql} IS NOT NULL`;
    }
  }
}

// `is none`/`is some` on a `one X?` relationship field checks its FK column for NULL.
function renderOptionalRelationExpr(
  resolved: ResolvedFile,
  scopeShape: string,
  alias: string,
  expr: ValueExpr,
): { sql: string; isJoinNarrowed: boolean } {
  if (expr.kind === 'identifier') {
    const field = getField(resolved, scopeShape, expr.name);
    if (field.cardinality !== 'one') {
      throw new Error(`'is none'/'is some' codegen only supports a 'one' relationship field in this PoC`);
    }
    return { sql: `${alias}.${fkColumnName(expr.name)}`, isJoinNarrowed: false };
  }
  throw new Error(`'is none'/'is some' codegen only supports a bare field reference in this PoC`);
}

// A refinement's predicate reaching through a `one` relationship (e.g.
// `response.outcome == "approved"`) is rendered as a scalar correlated subquery against
// the target table, rather than requiring the resolver to prove narrowing (`.` vs `?.`)
// holds. This sidesteps the open narrowing question (see resolver.ts's
// `resolveExprType` doc comment and compiler/README.md's documented assumptions)
// without silently deciding it: if the `one` field's FK column is NULL, the subquery
// returns NULL, any comparison against NULL is NULL (never true), and the row is
// correctly excluded from the view — the same net effect a JOIN would have, without
// needing a second FROM-clause alias per hop.
function renderPredicateExpr(resolved: ResolvedFile, scopeShape: string, alias: string, expr: ValueExpr): string {
  switch (expr.kind) {
    case 'stringLiteral':
      return `'${expr.value.replace(/'/g, "''")}'`;
    case 'numberLiteral':
      return String(expr.value);
    case 'identifier': {
      const field = getField(resolved, scopeShape, expr.name);
      if (field.cardinality !== 'scalar') {
        throw new Error(`predicate codegen: '${expr.name}' is not a scalar field`);
      }
      return `${alias}.${columnName(expr.name)}`;
    }
    case 'path': {
      if (expr.base.kind !== 'identifier') {
        throw new Error(`predicate codegen only supports one-hop '.' access in this PoC`);
      }
      const baseField = getField(resolved, scopeShape, expr.base.name);
      if (baseField.cardinality !== 'one') {
        throw new Error(`predicate codegen: '.${expr.field}' requires a 'one' relationship base`);
      }
      const targetShape = baseField.typeName;
      const targetTable = baseSourceFor(resolved, targetShape);
      const targetField = getField(resolved, targetShape, expr.field);
      return `(SELECT ${columnName(targetField.name)} FROM ${targetTable} WHERE id = ${alias}.${fkColumnName(baseField.name)})`;
    }
    default:
      throw new Error(`predicate codegen: '${expr.kind}' is not supported in this PoC`);
  }
}
