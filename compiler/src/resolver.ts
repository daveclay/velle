import type {
  VelFile,
  ShapeDecl,
  RecordShapeDecl,
  RefinementShapeDecl,
  FieldDecl,
  RuleDecl,
  ValueExpr,
  Predicate,
  SourceLoc,
} from './ast.js';

export class ResolveError extends Error {
  constructor(message: string, public loc?: SourceLoc) {
    super(loc ? `${message} (line ${loc.line}, col ${loc.col})` : message);
  }
}

const SCALAR_TYPES = new Set(['text', 'integer', 'decimal', 'boolean', 'Date', 'DateTime', 'Money']);

export type ResolvedType =
  | { kind: 'scalar'; type: string }
  | { kind: 'relation'; shapeName: string; cardinality: 'one' | 'many'; optional: boolean };

// One hop in a chain of `many` relationships (the only kind of hop this PoC resolver
// walks automatically — see collectDrivingShapes below for why `one`-relationship
// targets are deliberately NOT treated as driving shapes).
export interface AccessStep {
  fromShape: string;
  viaField: string;
  toShape: string;
}

export interface ResolvedField extends FieldDecl {
  ownerShape: string;
}

export interface ResolvedRefinement {
  name: string;
  baseRecordShape: string; // the ultimate record shape underlying this refinement
  chain: RefinementShapeDecl[]; // ordered outermost-base-first down to `name`
}

export interface ProducesGuard {
  producedShape: string;
  guardField: string; // column on the produced shape's table used as the UNIQUE guard
}

export interface ResolvedRule extends RuleDecl {
  triggerBaseShape: string; // ultimate record shape of the trigger
  guard?: ProducesGuard;
  // Every shape whose table's writes can change trigger-refinement membership,
  // mapped to the access path from triggerBaseShape to that shape (empty = is the
  // trigger base shape itself).
  drivingShapes: Map<string, AccessStep[]>;
}

export interface ResolvedFile {
  recordShapes: Map<string, RecordShapeDecl>;
  refinementShapes: Map<string, RefinementShapeDecl>;
  rules: ResolvedRule[];
}

function isRecord(decl: ShapeDecl | undefined): decl is RecordShapeDecl {
  return !!decl && decl.kind === 'record';
}

export class Resolver {
  private recordShapes = new Map<string, RecordShapeDecl>();
  private refinementShapes = new Map<string, RefinementShapeDecl>();

  constructor(private file: VelFile) {
    for (const shape of file.shapes) {
      if (shape.kind === 'record') {
        if (this.recordShapes.has(shape.name) || this.refinementShapes.has(shape.name)) {
          throw new ResolveError(`shape '${shape.name}' declared more than once`, shape.loc);
        }
        this.recordShapes.set(shape.name, shape);
      } else {
        if (this.recordShapes.has(shape.name) || this.refinementShapes.has(shape.name)) {
          throw new ResolveError(`shape '${shape.name}' declared more than once`, shape.loc);
        }
        this.refinementShapes.set(shape.name, shape);
      }
    }
  }

  resolve(): ResolvedFile {
    // Validate every field's type reference and every refinement's base/predicate up front.
    for (const shape of this.recordShapes.values()) {
      for (const field of shape.fields) {
        this.validateFieldType(shape.name, field);
      }
    }
    for (const refinement of this.refinementShapes.values()) {
      const chain = this.getRefinementChain(refinement.name);
      for (const link of chain) {
        this.checkPredicate(this.getBaseRecordShape(link.baseName), link.predicate);
      }
    }

    const rules: ResolvedRule[] = [];
    for (const rule of this.file.rules) {
      rules.push(this.resolveRule(rule));
    }

    return { recordShapes: this.recordShapes, refinementShapes: this.refinementShapes, rules };
  }

  // ---- Shape/field lookups ----

  private validateFieldType(ownerShape: string, field: FieldDecl) {
    const isScalar = SCALAR_TYPES.has(field.typeName);
    const isShape = this.recordShapes.has(field.typeName) || this.refinementShapes.has(field.typeName);
    if (!isScalar && !isShape) {
      throw new ResolveError(
        `field '${ownerShape}.${field.name}' has unknown type '${field.typeName}'`,
        field.loc,
      );
    }
    if (field.cardinality !== 'scalar' && isScalar) {
      throw new ResolveError(
        `field '${ownerShape}.${field.name}' uses 'one'/'many' with scalar type '${field.typeName}'`,
        field.loc,
      );
    }
    if (field.derivedExpr) {
      this.resolveExprType(ownerShape, field.derivedExpr);
    }
  }

  getRecordFields(shapeName: string): FieldDecl[] {
    const record = this.recordShapes.get(shapeName);
    if (record) return record.fields;
    throw new ResolveError(`'${shapeName}' is not a record shape`);
  }

  getBaseRecordShape(shapeName: string): string {
    let current = shapeName;
    const seen = new Set<string>();
    while (!this.recordShapes.has(current)) {
      if (seen.has(current)) {
        throw new ResolveError(`circular refinement base chain involving '${shapeName}'`);
      }
      seen.add(current);
      const refinement = this.refinementShapes.get(current);
      if (!refinement) {
        throw new ResolveError(`unknown shape '${current}' referenced from '${shapeName}'`);
      }
      current = refinement.baseName;
    }
    return current;
  }

  // Ordered outermost-base-first chain of refinements from the base record shape down
  // to `shapeName` (empty if `shapeName` is itself a record shape).
  getRefinementChain(shapeName: string): RefinementShapeDecl[] {
    const chain: RefinementShapeDecl[] = [];
    let current = shapeName;
    while (this.refinementShapes.has(current)) {
      const refinement = this.refinementShapes.get(current)!;
      chain.unshift(refinement);
      current = refinement.baseName;
    }
    return chain;
  }

  private lookupField(shapeName: string, fieldName: string): ResolvedField {
    const fields = this.getRecordFields(shapeName);
    const field = fields.find((f) => f.name === fieldName);
    if (!field) {
      throw new ResolveError(`shape '${shapeName}' has no field '${fieldName}'`);
    }
    return { ...field, ownerShape: shapeName };
  }

  // ---- Predicate / expression type resolution ----

  private checkPredicate(scopeShape: string, predicate: Predicate): void {
    switch (predicate.kind) {
      case 'comparison':
        this.resolveExprType(scopeShape, predicate.left);
        this.resolveExprType(scopeShape, predicate.right);
        return;
      case 'isNone':
      case 'isSome':
        this.resolveExprType(scopeShape, predicate.expr);
        return;
    }
  }

  // Resolves the type of a value expression evaluated relative to `scopeShape`
  // (the record shape `this`/bare identifiers refer to). This PoC does not implement
  // narrowing analysis (`.` vs `?.`) — dot access through an optional relationship is
  // permitted unconditionally. That's a deliberate, documented simplification (see
  // compiler/README.md), not an oversight: this compiler's codegen sidesteps the
  // question entirely by using INNER JOINs for refinement views (see codegen/views.ts).
  resolveExprType(scopeShape: string, expr: ValueExpr): ResolvedType {
    switch (expr.kind) {
      case 'this':
        return { kind: 'relation', shapeName: scopeShape, cardinality: 'one', optional: false };
      case 'now':
        return { kind: 'scalar', type: 'Date' };
      case 'stringLiteral':
        return { kind: 'scalar', type: 'text' };
      case 'numberLiteral':
        return { kind: 'scalar', type: 'decimal' };
      case 'identifier': {
        const field = this.lookupField(scopeShape, expr.name);
        return this.fieldResolvedType(field);
      }
      case 'path': {
        const baseType = this.resolveExprType(scopeShape, expr.base);
        if (baseType.kind !== 'relation') {
          throw new ResolveError(`cannot access '.${expr.field}' on a scalar value`, expr.loc);
        }
        if (baseType.cardinality === 'many') {
          throw new ResolveError(
            `cannot access '.${expr.field}' directly on a 'many' relationship outside of sum(...)`,
            expr.loc,
          );
        }
        const field = this.lookupField(baseType.shapeName, expr.field);
        return this.fieldResolvedType(field);
      }
      case 'binary': {
        const leftType = this.resolveExprType(scopeShape, expr.left);
        const rightType = this.resolveExprType(scopeShape, expr.right);
        if (leftType.kind !== 'scalar' || rightType.kind !== 'scalar') {
          throw new ResolveError(`'+'/'-' require scalar operands`, expr.loc);
        }
        return leftType;
      }
      case 'sum': {
        const { elementShapeName } = this.resolveManyHopChain(scopeShape, expr.collection);
        const field = this.lookupField(elementShapeName, expr.field);
        if (field.cardinality !== 'scalar') {
          throw new ResolveError(`sum(...)'s field argument must be scalar`, expr.loc);
        }
        return this.fieldResolvedType(field);
      }
    }
  }

  private fieldResolvedType(field: ResolvedField): ResolvedType {
    if (field.cardinality === 'scalar') {
      return { kind: 'scalar', type: field.typeName };
    }
    return {
      kind: 'relation',
      shapeName: field.typeName,
      cardinality: field.cardinality,
      optional: field.optional,
    };
  }

  // Resolves a chain of `many` relationships (e.g. `payments`, `payments.refunds`) —
  // the only shape sum(...)'s collection argument is allowed to take in this PoC.
  resolveManyHopChain(scopeShape: string, expr: ValueExpr): { elementShapeName: string; path: AccessStep[] } {
    if (expr.kind === 'identifier') {
      const field = this.lookupField(scopeShape, expr.name);
      if (field.cardinality !== 'many') {
        throw new ResolveError(`'${expr.name}' is not a 'many' relationship`, expr.loc);
      }
      return { elementShapeName: field.typeName, path: [{ fromShape: scopeShape, viaField: expr.name, toShape: field.typeName }] };
    }
    if (expr.kind === 'path') {
      const base = this.resolveManyHopChain(scopeShape, expr.base);
      const field = this.lookupField(base.elementShapeName, expr.field);
      if (field.cardinality !== 'many') {
        throw new ResolveError(`'${expr.field}' is not a 'many' relationship`, expr.loc);
      }
      return {
        elementShapeName: field.typeName,
        path: [...base.path, { fromShape: base.elementShapeName, viaField: expr.field, toShape: field.typeName }],
      };
    }
    throw new ResolveError(`sum(...)'s first argument must be a chain of 'many' relationships`);
  }

  // ---- Driving-shape (trigger placement) analysis ----
  //
  // For every table whose writes could change trigger-refinement membership, per the
  // simplified rule this PoC uses: a plain field read adds its OWNING shape; a derived
  // field also recurses into its formula; sum(...)'s many-relationship chain adds every
  // shape it walks through. Dereferencing a `one` relationship's target (e.g.
  // `response.outcome`) deliberately does NOT add the target shape (ChargeResponse) as
  // a driving shape — see compiler/README.md's documented limitations: if a linked
  // ChargeResponse were mutated in place after linking, no rule would re-fire. In this
  // PoC's model the only way a response reaches a ChargeAttempt is via the one write
  // that sets `response` in the first place, which IS on the driving shape.
  private collectDrivingShapes(scopeShape: string, expr: ValueExpr, acc: Map<string, AccessStep[]>): void {
    switch (expr.kind) {
      case 'this':
      case 'now':
      case 'stringLiteral':
      case 'numberLiteral':
        return;
      case 'identifier': {
        const field = this.lookupField(scopeShape, expr.name);
        if (!acc.has(scopeShape)) acc.set(scopeShape, []);
        if (field.derivedExpr) {
          this.collectDrivingShapes(scopeShape, field.derivedExpr, acc);
        }
        return;
      }
      case 'path': {
        this.collectDrivingShapes(scopeShape, expr.base, acc);
        // Deliberately not recursing into the target of a `one` relationship — see above.
        return;
      }
      case 'binary':
        this.collectDrivingShapes(scopeShape, expr.left, acc);
        this.collectDrivingShapes(scopeShape, expr.right, acc);
        return;
      case 'sum': {
        const { path } = this.resolveManyHopChain(scopeShape, expr.collection);
        let prefix: AccessStep[] = [];
        for (const step of path) {
          prefix = [...prefix, step];
          if (!acc.has(step.toShape)) acc.set(step.toShape, prefix);
        }
        return;
      }
    }
  }

  private resolveRule(rule: RuleDecl): ResolvedRule {
    const triggerBaseShape = this.getBaseRecordShape(rule.triggerName);
    const chain = this.getRefinementChain(rule.triggerName);

    const drivingShapes = new Map<string, AccessStep[]>();
    drivingShapes.set(triggerBaseShape, []); // the trigger's own table is always a driving shape
    for (const link of chain) {
      this.collectDrivingShapes(triggerBaseShape, exprsOfPredicate(link.predicate).left, drivingShapes);
      const right = exprsOfPredicate(link.predicate).right;
      if (right) this.collectDrivingShapes(triggerBaseShape, right, drivingShapes);
    }

    let guard: ProducesGuard | undefined;
    if (rule.producesName) {
      guard = this.resolveProducesGuard(rule, triggerBaseShape);
    }

    this.checkTotality(rule);

    return { ...rule, triggerBaseShape, guard, drivingShapes };
  }

  private resolveProducesGuard(rule: RuleDecl, triggerBaseShape: string): ProducesGuard {
    const producedShape = rule.producesName!;
    const fields = this.getRecordFields(producedShape);

    if (rule.producesForField) {
      const field = fields.find((f) => f.name === rule.producesForField);
      if (!field) {
        throw new ResolveError(
          `rule '${rule.name}': produces '${producedShape}' for '${rule.producesForField}', but '${producedShape}' has no such field`,
          rule.loc,
        );
      }
      return { producedShape, guardField: field.name };
    }

    const matches = fields.filter(
      (f) => f.cardinality === 'one' && f.typeName === triggerBaseShape,
    );
    if (matches.length === 0) {
      throw new ResolveError(
        `rule '${rule.name}': produces '${producedShape}' has no field of type '${triggerBaseShape}' to guard on — ` +
          `add a '${triggerBaseShape}'-typed field to '${producedShape}', or an explicit 'for <field>' if the guard should key on something else`,
        rule.loc,
      );
    }
    if (matches.length > 1) {
      throw new ResolveError(
        `rule '${rule.name}': produces '${producedShape}' is ambiguous — multiple fields of type '${triggerBaseShape}' ` +
          `(${matches.map((f) => f.name).join(', ')}) — add an explicit 'for <field>'`,
        rule.loc,
      );
    }
    return { producedShape, guardField: matches[0].name };
  }

  private checkTotality(rule: RuleDecl): void {
    for (const stage of rule.stages) {
      for (const stmt of stage) {
        const fields = this.getRecordFields(stmt.shapeName);
        const requiredFields = fields.filter((f) => f.cardinality !== 'many' && !f.optional && !f.derivedExpr);
        const providedNames = new Set(stmt.mapping.map((m) => m.field));

        for (const required of requiredFields) {
          if (!providedNames.has(required.name)) {
            throw new ResolveError(
              `rule '${rule.name}': '${stmt.shapeName} from {}' is missing required field '${required.name}'`,
              stmt.loc,
            );
          }
        }
        for (const mapping of stmt.mapping) {
          const field = fields.find((f) => f.name === mapping.field);
          if (!field) {
            throw new ResolveError(
              `rule '${rule.name}': '${stmt.shapeName}' has no field '${mapping.field}'`,
              mapping.loc,
            );
          }
          if (field.cardinality === 'many') {
            throw new ResolveError(
              `rule '${rule.name}': cannot assign to 'many' field '${mapping.field}' in a from {} mapping`,
              mapping.loc,
            );
          }
          if (field.derivedExpr) {
            throw new ResolveError(
              `rule '${rule.name}': cannot assign to derived field '${mapping.field}'`,
              mapping.loc,
            );
          }
          // Resolve the value expression's type relative to the rule's trigger base shape
          // (bare identifiers / `this` inside a rule body refer to the triggering instance).
          const triggerBaseShape = this.getBaseRecordShape(rule.triggerName);
          this.resolveExprType(triggerBaseShape, mapping.value);
        }
      }
    }
  }
}

function exprsOfPredicate(predicate: Predicate): { left: ValueExpr; right?: ValueExpr } {
  switch (predicate.kind) {
    case 'comparison':
      return { left: predicate.left, right: predicate.right };
    case 'isNone':
    case 'isSome':
      return { left: predicate.expr };
  }
}

export function resolveVelFile(file: VelFile): ResolvedFile {
  return new Resolver(file).resolve();
}

// Standalone helpers for codegen, operating on an already-resolved file (no need for a
// live Resolver instance — rule bodies only ever reference plain record-shape fields).
export function getField(resolved: ResolvedFile, shapeName: string, fieldName: string): FieldDecl {
  const shape = resolved.recordShapes.get(shapeName);
  if (!shape) throw new ResolveError(`'${shapeName}' is not a record shape`);
  const field = shape.fields.find((f) => f.name === fieldName);
  if (!field) throw new ResolveError(`shape '${shapeName}' has no field '${fieldName}'`);
  return field;
}

// Finds the (assumed unique, in this PoC's model) `one` field on `childShape` pointing
// back to `parentShape` — the standard one/many inverse pairing (e.g. Payment.invoice
// is the inverse of Invoice.payments).
export function findInverseOneField(resolved: ResolvedFile, childShape: string, parentShape: string): FieldDecl {
  const shape = resolved.recordShapes.get(childShape);
  if (!shape) throw new ResolveError(`'${childShape}' is not a record shape`);
  const matches = shape.fields.filter((f) => f.cardinality === 'one' && f.typeName === parentShape);
  if (matches.length === 0) {
    throw new ResolveError(`'${childShape}' has no 'one ${parentShape}' field to serve as the inverse of a 'many' relationship`);
  }
  if (matches.length > 1) {
    throw new ResolveError(
      `'${childShape}' has more than one 'one ${parentShape}' field (${matches.map((f) => f.name).join(', ')}) — ` +
        `inverse-relationship resolution is ambiguous in this PoC`,
    );
  }
  return matches[0];
}

// Resolves the shape at the end of a `this`/`path`-only expression chain (the subset of
// ValueExpr that can appear as the base of a field access inside a rule's effect body).
export function resolveChainShape(resolved: ResolvedFile, rootShape: string, expr: ValueExpr): string {
  if (expr.kind === 'this') return rootShape;
  if (expr.kind === 'path') {
    const baseShape = resolveChainShape(resolved, rootShape, expr.base);
    const field = getField(resolved, baseShape, expr.field);
    if (field.cardinality !== 'one') {
      throw new ResolveError(`cannot chain through non-relation field '${expr.field}'`, expr.loc);
    }
    return field.typeName;
  }
  throw new ResolveError('expected a `this`/path chain expression in an effect value');
}
