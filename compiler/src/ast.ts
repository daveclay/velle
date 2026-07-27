// AST for the restricted subset of Velle this proof-of-concept compiler supports.
// See compiler/README.md for exactly what's in and out of scope.

export interface SourceLoc {
  line: number;
  col: number;
}

export type FieldCardinality = 'scalar' | 'one' | 'many';

export interface FieldDecl {
  name: string;
  cardinality: FieldCardinality;
  typeName: string; // a scalar type name (text/integer/decimal/boolean/Date/DateTime/Money) or a shape name
  optional: boolean; // trailing `?`
  derivedExpr?: ValueExpr; // present iff this field is `name: Type = expr`
  loc: SourceLoc;
}

export interface RecordShapeDecl {
  kind: 'record';
  name: string;
  fields: FieldDecl[];
  loc: SourceLoc;
}

export interface RefinementShapeDecl {
  kind: 'refinement';
  name: string;
  baseName: string;
  predicate: Predicate;
  loc: SourceLoc;
}

export type ShapeDecl = RecordShapeDecl | RefinementShapeDecl;

// ---- Value expressions ----
// Used both inside derived-property definitions and inside rule-effect `from {}` mappings.
// `this` means: the rule's/refinement's trigger subject. A bare `identifier` means a field
// read relative to `this` (Velle's bare-name-means-innermost-scope rule, restricted here to
// the single-scope case only — no nested `as`/collection-filter scopes in this subset).
export type ValueExpr =
  | { kind: 'this'; loc: SourceLoc }
  | { kind: 'now'; loc: SourceLoc }
  | { kind: 'identifier'; name: string; loc: SourceLoc }
  | { kind: 'path'; base: ValueExpr; field: string; loc: SourceLoc }
  | { kind: 'stringLiteral'; value: string; loc: SourceLoc }
  | { kind: 'numberLiteral'; value: number; loc: SourceLoc }
  | { kind: 'binary'; op: '+' | '-'; left: ValueExpr; right: ValueExpr; loc: SourceLoc }
  | { kind: 'sum'; collection: ValueExpr; field: string; loc: SourceLoc };

// ---- Predicates (inside `where`) ----
export type ComparisonOp = '==' | '!=' | '<' | '<=' | '>' | '>=';

export type Predicate =
  | { kind: 'comparison'; op: ComparisonOp; left: ValueExpr; right: ValueExpr; loc: SourceLoc }
  | { kind: 'isNone'; expr: ValueExpr; loc: SourceLoc }
  | { kind: 'isSome'; expr: ValueExpr; loc: SourceLoc };

// ---- Rules ----
export interface FromMapping {
  field: string;
  value: ValueExpr;
  loc: SourceLoc;
}

export interface EffectStatement {
  shapeName: string;
  mapping: FromMapping[];
  loc: SourceLoc;
}

export interface RuleDecl {
  name: string;
  triggerName: string;
  producesName?: string;
  producesForField?: string;
  // Effect statements grouped into ordered stages; statements within one stage are
  // unordered relative to each other, stages themselves run in declared order (`then`).
  stages: EffectStatement[][];
  loc: SourceLoc;
}

export interface VelFile {
  shapes: ShapeDecl[];
  rules: RuleDecl[];
}
