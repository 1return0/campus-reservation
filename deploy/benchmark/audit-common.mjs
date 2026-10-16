import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

export function settings() {
  const result = { ...process.env };
  for (const line of readFileSync('deploy/.env', 'utf8').split(/\r?\n/)) {
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
    if (match && result[match[1]] === undefined) result[match[1]] = match[2];
  }
  return result;
}

export function sql(config, statement, database) {
  if(config.AUDIT_SQL_TIMESTAMP)statement=`SET timestamp=${Number(config.AUDIT_SQL_TIMESTAMP)};\n`+statement;
  const uri = new URL(config.CAMPUS_RESERVE_DB_URL.replace(/^jdbc:/, ''));
  const result = spawnSync('D:/MySQL/MySQL/MySQL Shell 8.0/bin/mysqlsh.exe',
    ['--sqlc', `--host=${uri.hostname}`, `--port=${uri.port || 3306}`,
      `--user=${config.CAMPUS_RESERVE_DB_USERNAME}`, '--passwords-from-stdin', '--password',
      '--save-passwords=never', `--database=${database || uri.pathname.slice(1)}`,
      '--result-format=tabbed', `--execute=${statement}`],
    { input: config.CAMPUS_RESERVE_DB_PASSWORD + '\n', encoding: 'utf8', windowsHide: true,
      maxBuffer: 16 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`audit-sql-failed: ${(result.stderr || result.error?.code || '').replace(/password[^\n]*/gi, '[redacted]')}`);
  return result.stdout.trim();
}

export function jsonQuery(config, expression, database) {
  return JSON.parse(sql(config, `SELECT ${expression} AS result`, database).split(/\r?\n/).at(-1));
}
