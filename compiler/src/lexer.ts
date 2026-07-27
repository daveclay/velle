export type TokenType =
  | 'ident'
  | 'string'
  | 'number'
  | 'punct'
  | 'eof';

export interface Token {
  type: TokenType;
  text: string;
  line: number;
  col: number;
}

export class LexError extends Error {
  constructor(message: string, public line: number, public col: number) {
    super(`${message} (line ${line}, col ${col})`);
  }
}

const PUNCTUATION_TWO_CHAR = ['==', '!=', '<=', '>='];
const PUNCTUATION_ONE_CHAR = ['{', '}', '(', ')', ',', '.', ':', '?', '=', '+', '-', '<', '>'];

export function tokenize(source: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  let line = 1;
  let col = 1;

  function advance(n = 1) {
    for (let k = 0; k < n; k++) {
      if (source[i] === '\n') {
        line++;
        col = 1;
      } else {
        col++;
      }
      i++;
    }
  }

  while (i < source.length) {
    const ch = source[i];

    // whitespace
    if (ch === ' ' || ch === '\t' || ch === '\r' || ch === '\n') {
      advance();
      continue;
    }

    // line comment
    if (ch === '/' && source[i + 1] === '/') {
      while (i < source.length && source[i] !== '\n') advance();
      continue;
    }

    const startLine = line;
    const startCol = col;

    // string literal
    if (ch === '"') {
      let value = '';
      advance();
      while (i < source.length && source[i] !== '"') {
        if (source[i] === '\n') {
          throw new LexError('unterminated string literal', startLine, startCol);
        }
        value += source[i];
        advance();
      }
      if (i >= source.length) {
        throw new LexError('unterminated string literal', startLine, startCol);
      }
      advance(); // closing quote
      tokens.push({ type: 'string', text: value, line: startLine, col: startCol });
      continue;
    }

    // number literal (integer or decimal)
    if (/[0-9]/.test(ch)) {
      let text = '';
      while (i < source.length && /[0-9.]/.test(source[i])) {
        text += source[i];
        advance();
      }
      tokens.push({ type: 'number', text, line: startLine, col: startCol });
      continue;
    }

    // identifier / keyword
    if (/[A-Za-z_]/.test(ch)) {
      let text = '';
      while (i < source.length && /[A-Za-z0-9_]/.test(source[i])) {
        text += source[i];
        advance();
      }
      tokens.push({ type: 'ident', text, line: startLine, col: startCol });
      continue;
    }

    // two-char punctuation
    const two = source.slice(i, i + 2);
    if (PUNCTUATION_TWO_CHAR.includes(two)) {
      advance(2);
      tokens.push({ type: 'punct', text: two, line: startLine, col: startCol });
      continue;
    }

    // one-char punctuation
    if (PUNCTUATION_ONE_CHAR.includes(ch)) {
      advance();
      tokens.push({ type: 'punct', text: ch, line: startLine, col: startCol });
      continue;
    }

    throw new LexError(`unexpected character '${ch}'`, startLine, startCol);
  }

  tokens.push({ type: 'eof', text: '', line, col });
  return tokens;
}
