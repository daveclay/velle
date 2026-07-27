import { tokenize, Token } from './lexer.js';
import type {
  VelFile,
  ShapeDecl,
  RecordShapeDecl,
  RefinementShapeDecl,
  FieldDecl,
  FieldCardinality,
  RuleDecl,
  EffectStatement,
  FromMapping,
  ValueExpr,
  Predicate,
  ComparisonOp,
  SourceLoc,
} from './ast.js';

export class ParseError extends Error {
  constructor(message: string, public line: number, public col: number) {
    super(`${message} (line ${line}, col ${col})`);
  }
}

const COMPARISON_OPS: ComparisonOp[] = ['==', '!=', '<=', '>=', '<', '>'];

class Parser {
  private tokens: Token[];
  private pos = 0;

  constructor(source: string) {
    this.tokens = tokenize(source);
  }

  private peek(offset = 0): Token {
    return this.tokens[Math.min(this.pos + offset, this.tokens.length - 1)];
  }

  private loc(): SourceLoc {
    const t = this.peek();
    return { line: t.line, col: t.col };
  }

  private next(): Token {
    const t = this.tokens[this.pos];
    if (this.pos < this.tokens.length - 1) this.pos++;
    return t;
  }

  private error(message: string): never {
    const t = this.peek();
    throw new ParseError(message, t.line, t.col);
  }

  private isIdent(text: string): boolean {
    const t = this.peek();
    return t.type === 'ident' && t.text === text;
  }

  private isPunct(text: string): boolean {
    const t = this.peek();
    return t.type === 'punct' && t.text === text;
  }

  private expectIdent(text?: string): Token {
    const t = this.peek();
    if (t.type !== 'ident' || (text !== undefined && t.text !== text)) {
      this.error(`expected ${text ? `identifier '${text}'` : 'an identifier'}, got '${t.text || t.type}'`);
    }
    return this.next();
  }

  private expectPunct(text: string): Token {
    const t = this.peek();
    if (t.type !== 'punct' || t.text !== text) {
      this.error(`expected '${text}', got '${t.text || t.type}'`);
    }
    return this.next();
  }

  parseFile(): VelFile {
    const shapes: ShapeDecl[] = [];
    const rules: RuleDecl[] = [];
    while (this.peek().type !== 'eof') {
      if (this.isIdent('shape')) {
        shapes.push(this.parseShapeDecl());
      } else if (this.isIdent('rule')) {
        rules.push(this.parseRuleDecl());
      } else {
        this.error(`expected 'shape' or 'rule' declaration, got '${this.peek().text}'`);
      }
    }
    return { shapes, rules };
  }

  private parseShapeDecl(): ShapeDecl {
    const loc = this.loc();
    this.expectIdent('shape');
    const name = this.expectIdent().text;

    if (this.isPunct('=')) {
      // refinement: shape Name = Base where predicate
      this.next();
      const baseName = this.expectIdent().text;
      this.expectIdent('where');
      const predicate = this.parsePredicate();
      const decl: RefinementShapeDecl = { kind: 'refinement', name, baseName, predicate, loc };
      return decl;
    }

    // record: shape Name { fields }
    this.expectPunct('{');
    const fields: FieldDecl[] = [];
    while (!this.isPunct('}')) {
      fields.push(this.parseFieldDecl());
    }
    this.expectPunct('}');
    const decl: RecordShapeDecl = { kind: 'record', name, fields, loc };
    return decl;
  }

  private parseFieldDecl(): FieldDecl {
    const loc = this.loc();
    const name = this.expectIdent().text;
    this.expectPunct(':');

    let cardinality: FieldCardinality = 'scalar';
    if (this.isIdent('one')) {
      this.next();
      cardinality = 'one';
    } else if (this.isIdent('many')) {
      this.next();
      cardinality = 'many';
    }

    const typeName = this.expectIdent().text;

    let optional = false;
    if (this.isPunct('?')) {
      this.next();
      optional = true;
    }

    let derivedExpr: ValueExpr | undefined;
    if (this.isPunct('=')) {
      this.next();
      derivedExpr = this.parseValueExpr();
    }

    return { name, cardinality, typeName, optional, derivedExpr, loc };
  }

  private parseRuleDecl(): RuleDecl {
    const loc = this.loc();
    this.expectIdent('rule');
    const name = this.expectIdent().text;
    this.expectIdent('on');
    const triggerName = this.expectIdent().text;

    let producesName: string | undefined;
    let producesForField: string | undefined;
    if (this.isIdent('produces')) {
      this.next();
      producesName = this.expectIdent().text;
      if (this.isIdent('for')) {
        this.next();
        producesForField = this.expectIdent().text;
      }
    }

    this.expectPunct('{');
    const stages: EffectStatement[][] = [[]];
    stages[0].push(this.parseEffectStatement());
    while (!this.isPunct('}')) {
      if (this.isIdent('then')) {
        this.next();
        stages.push([]);
      }
      stages[stages.length - 1].push(this.parseEffectStatement());
    }
    this.expectPunct('}');

    return { name, triggerName, producesName, producesForField, stages, loc };
  }

  private parseEffectStatement(): EffectStatement {
    const loc = this.loc();
    const shapeName = this.expectIdent().text;
    this.expectIdent('from');
    this.expectPunct('{');
    const mapping: FromMapping[] = [];
    while (!this.isPunct('}')) {
      const fieldLoc = this.loc();
      const field = this.expectIdent().text;
      this.expectPunct(':');
      const value = this.parseValueExpr();
      mapping.push({ field, value, loc: fieldLoc });
    }
    this.expectPunct('}');
    return { shapeName, mapping, loc };
  }

  // ---- Predicates ----

  private parsePredicate(): Predicate {
    const loc = this.loc();
    const left = this.parseValueExpr();

    if (this.isIdent('is')) {
      this.next();
      if (this.isIdent('none')) {
        this.next();
        return { kind: 'isNone', expr: left, loc };
      }
      if (this.isIdent('some')) {
        this.next();
        return { kind: 'isSome', expr: left, loc };
      }
      this.error("expected 'none' or 'some' after 'is'");
    }

    const opToken = this.peek();
    if (opToken.type === 'punct' && (COMPARISON_OPS as string[]).includes(opToken.text)) {
      this.next();
      const right = this.parseValueExpr();
      return { kind: 'comparison', op: opToken.text as ComparisonOp, left, right, loc };
    }

    this.error(`expected 'is' or a comparison operator, got '${opToken.text}'`);
  }

  // ---- Value expressions ----
  // valueExpr := term (('+'|'-') term)*
  private parseValueExpr(): ValueExpr {
    let expr = this.parseTerm();
    while (this.isPunct('+') || this.isPunct('-')) {
      const op = this.next().text as '+' | '-';
      const right = this.parseTerm();
      expr = { kind: 'binary', op, left: expr, right, loc: expr.loc };
    }
    return expr;
  }

  // term := primary ('.' Ident)*
  private parseTerm(): ValueExpr {
    let expr = this.parsePrimary();
    while (this.isPunct('.')) {
      const loc = this.loc();
      this.next();
      const field = this.expectIdent().text;
      expr = { kind: 'path', base: expr, field, loc };
    }
    return expr;
  }

  private parsePrimary(): ValueExpr {
    const loc = this.loc();
    const t = this.peek();

    if (t.type === 'string') {
      this.next();
      return { kind: 'stringLiteral', value: t.text, loc };
    }
    if (t.type === 'number') {
      this.next();
      return { kind: 'numberLiteral', value: Number(t.text), loc };
    }
    if (t.type === 'ident' && t.text === 'this') {
      this.next();
      return { kind: 'this', loc };
    }
    if (t.type === 'ident' && t.text === 'now') {
      this.next();
      return { kind: 'now', loc };
    }
    if (t.type === 'ident' && t.text === 'sum' && this.peek(1).type === 'punct' && this.peek(1).text === '(') {
      this.next(); // 'sum'
      this.expectPunct('(');
      const collection = this.parseTerm();
      this.expectPunct(',');
      const field = this.expectIdent().text;
      this.expectPunct(')');
      return { kind: 'sum', collection, field, loc };
    }
    if (t.type === 'ident') {
      this.next();
      return { kind: 'identifier', name: t.text, loc };
    }

    this.error(`unexpected token '${t.text || t.type}' in expression`);
  }
}

export function parseVelFile(source: string): VelFile {
  return new Parser(source).parseFile();
}
