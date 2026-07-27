#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { compileVelSource } from './compile.js';

function main() {
  const args = process.argv.slice(2);
  if (args.length < 1) {
    console.error('usage: vel-compile <input.vel> [--out <output.sql>]');
    process.exit(1);
  }

  const inputPath = args[0];
  const outIndex = args.indexOf('--out');
  const outPath = outIndex !== -1 ? args[outIndex + 1] : undefined;

  const source = readFileSync(inputPath, 'utf-8');

  try {
    const { sql, effectPlans } = compileVelSource(source);
    if (outPath) {
      writeFileSync(outPath, sql, 'utf-8');
      writeFileSync(outPath.replace(/\.sql$/, '') + '.effects.json', JSON.stringify(effectPlans, null, 2), 'utf-8');
      console.error(`wrote ${outPath} and effects.json`);
    } else {
      process.stdout.write(sql);
    }
  } catch (err) {
    console.error(`vel-compile: ${err instanceof Error ? err.message : String(err)}`);
    process.exit(1);
  }
}

main();
