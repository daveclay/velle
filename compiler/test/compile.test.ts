import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { compileVelSource } from '../src/compile.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (relPath: string) => readFileSync(path.join(here, relPath), 'utf-8');

describe('compileVelSource — golden output', () => {
  it('compiles examples/invoice_payment.vel to exactly the committed golden SQL', () => {
    const source = read('../examples/invoice_payment.vel');
    const golden = read('golden/invoice_payment.sql');
    const { sql } = compileVelSource(source);
    expect(sql).toBe(golden);
  });
});

describe('compileVelSource — produces-guard inference errors (Gap B)', () => {
  it('rejects a produces target with zero fields matching the trigger type', () => {
    const source = read('fixtures/gap_b_zero_match.vel');
    expect(() => compileVelSource(source)).toThrow(/no field of type 'ChargeAttempt' to guard on/);
  });

  it('rejects a produces target with multiple fields matching the trigger type', () => {
    const source = read('fixtures/gap_b_ambiguous.vel');
    expect(() => compileVelSource(source)).toThrow(/is ambiguous — multiple fields of type 'Customer'/);
  });
});
