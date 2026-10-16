import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { settings, sql, jsonQuery } from './audit-common.mjs';
const config = settings();
const action = process.argv[2];
if (action === 'create') {
  const run = randomBytes(6).toString('hex');
  const schema = `campus_audit_${run}`;
  // No IF NOT EXISTS: never reuse or later drop a preexisting schema.
  sql(config, `CREATE DATABASE ${schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`);
  const uri = new URL(config.CAMPUS_RESERVE_DB_URL.replace(/^jdbc:/, ''));
  uri.pathname = '/' + schema;
  const output = resolve(`target/booking-audit-${run}/environment.json`);
  mkdirSync(dirname(output), { recursive: true });
  const data = { run, schema, createdByThisRun: true, url: 'jdbc:' + uri.toString(), output,
    createdAt: new Date().toISOString(), database: jsonQuery(config,
      "JSON_OBJECT('version',VERSION(),'isolation',@@transaction_isolation,'performanceSchema',@@performance_schema)") };
  writeFileSync(output, JSON.stringify(data, null, 2));
  console.log(JSON.stringify(data));
} else if (action === 'cleanup') {
  const file = resolve(process.argv[3] || 'invalid');
  if (!file.startsWith(resolve('target') + '\\') && !file.startsWith(resolve('target') + '/')) throw Error('unsafe-manifest-path');
  const data = JSON.parse(readFileSync(file, 'utf8'));
  if (!data.createdByThisRun || !/^[a-f0-9]{12}$/.test(data.run)
      || data.schema !== 'campus_audit_' + data.run) throw Error('invalid-audit-schema-manifest');
  sql(config, `DROP DATABASE ${data.schema}`);
  data.cleanedAt = new Date().toISOString();
  writeFileSync(file, JSON.stringify(data, null, 2));
  console.log(JSON.stringify({ schema: data.schema, cleanup: 'dropped-only-this-run-schema' }));
} else { throw Error('create-or-cleanup-required'); }
