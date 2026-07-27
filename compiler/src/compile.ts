import { parseVelFile } from './parser.js';
import { resolveVelFile, type ResolvedFile } from './resolver.js';
import { generateSql } from './codegen/sql.js';
import { generateEffectPlans, type RuleEffectPlan } from './codegen/outbox.js';

export interface CompileResult {
  resolved: ResolvedFile;
  sql: string;
  effectPlans: RuleEffectPlan[];
}

export function compileVelSource(source: string): CompileResult {
  const file = parseVelFile(source);
  const resolved = resolveVelFile(file);
  const sql = generateSql(resolved);
  const effectPlans = generateEffectPlans(resolved);
  return { resolved, sql, effectPlans };
}
