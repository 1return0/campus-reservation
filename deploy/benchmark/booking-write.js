// Local HTTP write benchmark. Uses synthetic JWTs and isolated, removable SQL fixtures.
// Not a login, RabbitMQ, multi-instance or production-capacity benchmark.
import { createHmac, randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { resolve } from 'node:path';

const root = process.cwd();
for (const line of readFileSync(resolve(root, 'deploy/.env'), 'utf8').split(/\r?\n/)) {
  const match = /^([A-Z][A-Z0-9_]*)=(.*)$/.exec(line);
  if (match && process.env[match[1]] === undefined) process.env[match[1]] = match[2];
}
const mode = process.env.BOOKING_BENCHMARK_MODE || 'baseline';
if (!['baseline', 'limited'].includes(mode)) throw new Error('Unknown benchmark mode');
const url = process.env.BOOKING_BENCHMARK_URL || 'http://127.0.0.1:18500';
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(url)) throw new Error('Local benchmark URL required');
const run = `bw${randomBytes(5).toString('hex')}`;
const db = new URL(process.env.CAMPUS_RESERVE_DB_URL.replace(/^jdbc:/, ''));
const mysql = 'D:/MySQL/MySQL/MySQL Shell 8.0/bin/mysqlsh.exe';
const report = { run, mode, startedAt: new Date().toISOString(), cases: [], cleanup: 'pending' };
const requestCount = Number(process.env.BOOKING_BENCHMARK_REQUESTS || 120);
const levels = (process.env.BOOKING_BENCHMARK_CONCURRENCY || '10,50,100').split(',').map(Number);
const repeats = Number(process.env.BOOKING_BENCHMARK_REPEATS || 1);
if (!Number.isInteger(requestCount) || requestCount < 20 || requestCount > 500
    || !Number.isInteger(repeats) || repeats < 1 || repeats > 3
    || !levels.length || levels.some(n => !Number.isInteger(n) || n < 1 || n > 500)) {
  throw new Error('Invalid bounded benchmark workload');
}
report.workload = { requestCount: mode === 'limited' ? 500 : requestCount, levels, repeats, closedLoop: true,
  identity: 'synthetic signed STUDENT JWT; login and team creation excluded',
  logging: 'INFO', serviceInstances: 1, jvmMaxHeapMiB: 512, datasourcePool: 'Hikari default 10',
  nodeVersion: process.version,
  limiter: mode === 'limited' ? { windowMillis: 60000, perScopeMaxRequests: 20,
    note: 'Controlled one-window burst comparison, not a recommended production quota' } : 'disabled' };
let nextId = BigInt(Date.now()) * 1000n;
const resources = [], teams = [];
const id = () => String(nextId++);
function persist() {
  const output = process.env.BOOKING_BENCHMARK_OUTPUT;
  if (output) writeFileSync(output, JSON.stringify({ ...report,
    fixtureIds: { resources, teams } }, null, 2));
}
const q = value => `'${String(value).replaceAll("'", "''")}'`;
const list = values => values.length ? values.join(',') : '-1';
const sql = statement => {
  const result = spawnSync(mysql, ['--sqlc', `--host=${db.hostname}`, `--port=${db.port || 3306}`,
    `--user=${process.env.CAMPUS_RESERVE_DB_USERNAME}`,
    `--password=${process.env.CAMPUS_RESERVE_DB_PASSWORD}`, `--database=${db.pathname.slice(1)}`,
    '--result-format=tabbed', `--execute=${statement}`], { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) throw new Error(`Benchmark SQL failed (${result.error?.code || result.status}): ${(result.stderr || '').replace(/password[^\n]*/gi, '[redacted]')}`);
  return result.stdout;
};
const query = statement => JSON.parse(sql(`SELECT ${statement} AS result;`).trim().split(/\r?\n/).at(-1));
function lockStatus() {
  const rows = sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_current_waits');");
  return Object.fromEntries(rows.trim().split(/\r?\n/).slice(1).map(line => {
    const [key, value] = line.split('\t'); return [key, Number(value)];
  }));
}
function token(user, student) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(JSON.stringify({ iss: process.env.CAMPUS_JWT_ISSUER || 'campus-reserve',
    sub: user, studentNo: student, roles: ['STUDENT'], iat: now, exp: now + 7200 })).toString('base64url');
  const data = `${header}.${payload}`;
  return `${data}.${createHmac('sha256', Buffer.from(process.env.CAMPUS_JWT_HMAC_SECRET, 'base64')).update(data).digest('base64url')}`;
}
const percentile = (values, p) => values[Math.max(0, Math.ceil(values.length * p) - 1)];
function fixtures(kind, count, roomCount, label) {
  const roomIds = Array.from({ length: roomCount }, id);
  resources.push(...roomIds);
  persist();
  const day = new Date(Date.now() + 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
  for (const room of roomIds) {
    sql(`INSERT INTO reservable_resource (id,resource_code,resource_name,resource_type,status,no_show_grace_minutes,min_participants,max_participants)
      VALUES (${room},${q(`${run}_${room}`)},'Booking benchmark',${q(kind === 'lecture' ? 'LECTURE' : 'STUDY_ROOM')},'PUBLISHED',15,1,8);`);
    if (kind !== 'lecture') sql(`INSERT INTO study_room_open_window (id,resource_id,starts_at,ends_at,status)
      VALUES (${id()},${room},'${day} 07:30:00','${day} 21:30:00','OPEN');`);
  }
  const slot = id();
  if (kind === 'lecture') sql(`INSERT INTO resource_slot (id,resource_id,starts_at,ends_at,capacity,available_capacity,status,event_title,speaker_name,activity_description)
    VALUES (${slot},${roomIds[0]},'${day} 14:00:00','${day} 16:00:00',20,20,'OPEN','Benchmark lecture','Synthetic lecturer','Isolated benchmark fixture');`);
  const requests = [];
  let statements = [];
  for (let i = 0; i < count; i++) {
    const user = `${run}_${label}_${i}`;
    const student = `${run}${label}${i}`;
    const team = id();
    const room = roomIds[i % roomCount];
    if (kind !== 'lecture') {
      teams.push(team);
      statements.push(`INSERT INTO study_team (id,leader_reference,invite_code_hash,invite_code_expires_at,status)
        VALUES (${team},${q(user)},SHA2(${q(user)},256),DATE_ADD(NOW(),INTERVAL 1 DAY),'FORMING');
        INSERT INTO study_team_member (id,team_id,student_number,user_reference,status,accepted_at)
        VALUES (${id()},${team},${q(student)},${q(user)},'ACCEPTED',NOW());`);
    }
    requests.push({ path: kind === 'lecture' ? `/api/v1/slots/${slot}/reservations` : '/api/v1/study-rooms/bookings',
      token: token(user, student), key: `${run}_${label}_${i}`,
      body: kind === 'lecture' ? undefined : { resourceId: room, teamId: team,
        startsAt: `${day}T14:00:00`, endsAt: `${day}T16:00:00` } });
    if (statements.length >= 25) { persist(); sql(statements.join('\n')); statements = []; }
  }
  if (statements.length) { persist(); sql(statements.join('\n')); }
  return { requests, roomIds, slot, kind, count };
}
async function exercise(fixture, concurrency, label) {
  let next = 0;
  const samples = [], statuses = {}, outcomes = {}, errors = {}, samplesByStatus = {};
  const locksBefore = lockStatus();
  const started = performance.now();
  await Promise.all(Array.from({ length: Math.min(concurrency, fixture.count) }, async () => {
    while (next < fixture.count) {
      const request = fixture.requests[next++];
      const begin = performance.now();
      try {
        const response = await fetch(url + request.path, { method: 'POST',
          headers: { Authorization: `Bearer ${request.token}`, 'Idempotency-Key': request.key,
            'Content-Type': 'application/json' }, body: request.body ? JSON.stringify(request.body) : undefined,
          signal: AbortSignal.timeout(120000) });
        const body = await response.json();
        statuses[response.status] = (statuses[response.status] || 0) + 1;
        (samplesByStatus[response.status] ||= []).push(performance.now() - begin);
        const outcome = response.status === 200 ? body.data?.status || 'unexpected-body' : body.message || 'unknown';
        outcomes[outcome] = (outcomes[outcome] || 0) + 1;
      } catch (error) { errors[error.name] = (errors[error.name] || 0) + 1; }
      samples.push(performance.now() - begin);
    }
  }));
  const duration = performance.now() - started;
  const locksAfter = lockStatus();
  samples.sort((a, b) => a - b);
  const rooms = list(fixture.roomIds);
  const state = fixture.kind === 'lecture'
    ? query(`JSON_OBJECT('orders',(SELECT COUNT(*) FROM reservation_order WHERE slot_id=${fixture.slot}),
      'confirmed',(SELECT COUNT(*) FROM reservation_order WHERE slot_id=${fixture.slot} AND status='CONFIRMED'),
      'waitlisted',(SELECT COUNT(*) FROM reservation_order WHERE slot_id=${fixture.slot} AND status='WAITLISTED'),
      'waitingEntries',(SELECT COUNT(*) FROM reservation_waitlist_entry WHERE slot_id=${fixture.slot} AND status='WAITING'),
      'remaining',(SELECT available_capacity FROM resource_slot WHERE id=${fixture.slot}),
      'duplicateActive',(SELECT COUNT(*) FROM (SELECT requester_reference FROM reservation_order WHERE slot_id=${fixture.slot} AND active_marker=1 GROUP BY requester_reference HAVING COUNT(*)>1) d),
      'outbox',(SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_type='reservation' AND aggregate_id IN (SELECT id FROM reservation_order WHERE slot_id=${fixture.slot})))`)
    : query(`JSON_OBJECT('orders',(SELECT COUNT(*) FROM study_room_booking WHERE resource_id IN (${rooms})),
      'confirmed',(SELECT COUNT(*) FROM study_room_booking WHERE resource_id IN (${rooms}) AND status='CONFIRMED'),
      'waitlisted',(SELECT COUNT(*) FROM study_room_booking WHERE resource_id IN (${rooms}) AND status='WAITLISTED'),
      'buckets',(SELECT COUNT(*) FROM study_room_member_bucket WHERE booking_id IN (SELECT id FROM study_room_booking WHERE resource_id IN (${rooms}))),
      'overlaps',(SELECT COUNT(*) FROM study_room_booking a JOIN study_room_booking b ON a.resource_id=b.resource_id AND a.id<b.id AND a.starts_at<b.ends_at AND a.ends_at>b.starts_at WHERE a.resource_id IN (${rooms}) AND a.status='CONFIRMED' AND b.status='CONFIRMED'),
      'outbox',(SELECT COUNT(*) FROM reservation_outbox_event WHERE aggregate_type='study-room-booking' AND aggregate_id IN (SELECT id FROM study_room_booking WHERE resource_id IN (${rooms}))))`);
  const expectedConfirmed = fixture.kind === 'lecture' ? Math.min(20, state.orders) : Math.min(fixture.roomIds.length, state.orders);
  const expectedAdmitted = mode === 'limited' ? Math.min(20, fixture.count) : fixture.count;
  const responsesCorrect = (statuses[200] || 0) === expectedAdmitted
    && (statuses[429] || 0) === fixture.count - expectedAdmitted
    && Object.keys(statuses).every(status => status === '200' || (mode === 'limited' && status === '429'));
  const correct = responsesCorrect && state.confirmed === expectedConfirmed && state.confirmed + state.waitlisted === state.orders
    && state.orders === (statuses[200] || 0) && !Object.keys(errors).length
    && (fixture.kind === 'lecture' ? state.remaining + state.confirmed === 20 && state.waitingEntries === state.waitlisted && state.duplicateActive === 0
      : state.overlaps === 0 && state.buckets === state.confirmed * 4)
    && state.outbox === state.orders;
  const result = { label, requests: fixture.count, concurrency, roomCount: fixture.roomIds.length,
    wallSeconds: +(duration / 1000).toFixed(3), completedRequestsPerSecond: +(fixture.count / (duration / 1000)).toFixed(2),
    p50Millis: +percentile(samples, .50).toFixed(2), p95Millis: +percentile(samples, .95).toFixed(2),
    p99Millis: +percentile(samples, .99).toFixed(2), maxMillis: +samples.at(-1).toFixed(2), statuses, outcomes, errors, state, correct,
    latencyByHttpStatus: Object.fromEntries(Object.entries(samplesByStatus).map(([status, values]) => {
      values.sort((a, b) => a - b);
      return [status, { count: values.length, p50Millis: +percentile(values, .50).toFixed(2),
        p95Millis: +percentile(values, .95).toFixed(2), p99Millis: +percentile(values, .99).toFixed(2) }];
    })),
    globalInnodbLockDelta: {
      waits: locksAfter.Innodb_row_lock_waits - locksBefore.Innodb_row_lock_waits,
      waitTimeMillis: locksAfter.Innodb_row_lock_time - locksBefore.Innodb_row_lock_time,
      note: 'Server-global delta, may include unrelated connections; not per-request tracing',
    } };
  console.log(JSON.stringify(result));
  report.cases.push(result);
  persist();
  if (!correct) throw new Error(`Correctness failed: ${label}`);
}
function cleanup() {
  for (let offset = 0; offset < resources.length; offset += 10) {
  const rooms = list(resources.slice(offset, offset + 10));
  // Every delete is constrained by IDs allocated by this run. Never delete shared user data.
  sql(`START TRANSACTION;
    DELETE FROM reservation_outbox_event WHERE
      (aggregate_type='reservation' AND aggregate_id IN (SELECT id FROM reservation_order WHERE slot_id IN (SELECT id FROM resource_slot WHERE resource_id IN (${rooms}))))
      OR (aggregate_type='study-room-booking' AND aggregate_id IN (SELECT id FROM study_room_booking WHERE resource_id IN (${rooms})));
    DELETE FROM reservation_waitlist_entry WHERE slot_id IN (SELECT id FROM resource_slot WHERE resource_id IN (${rooms}));
    DELETE FROM reservation_order WHERE slot_id IN (SELECT id FROM resource_slot WHERE resource_id IN (${rooms}));
    DELETE FROM study_room_member_bucket WHERE booking_id IN (SELECT id FROM study_room_booking WHERE resource_id IN (${rooms}));
    DELETE FROM study_room_booking_member WHERE booking_id IN (SELECT id FROM study_room_booking WHERE resource_id IN (${rooms}));
    DELETE FROM study_room_booking WHERE resource_id IN (${rooms});
    DELETE FROM study_room_open_window WHERE resource_id IN (${rooms});
    DELETE FROM resource_slot WHERE resource_id IN (${rooms});
    DELETE FROM reservable_resource WHERE id IN (${rooms}); COMMIT;`);
  const remaining = query(`JSON_OBJECT('resources',(SELECT COUNT(*) FROM reservable_resource WHERE id IN (${rooms})))`);
  if (remaining.resources) throw new Error('Resource cleanup incomplete');
  }
  for (let offset = 0; offset < teams.length; offset += 50) {
    const group = list(teams.slice(offset, offset + 50));
    sql(`START TRANSACTION; DELETE FROM study_team_member WHERE team_id IN (${group});
      DELETE FROM study_team WHERE id IN (${group}); COMMIT;`);
    const remaining = query(`JSON_OBJECT('teams',(SELECT COUNT(*) FROM study_team WHERE id IN (${group})))`);
    if (remaining.teams) throw new Error('Team cleanup incomplete');
  }
  report.cleanup = 'passed';
}
try {
  report.database = query(`JSON_OBJECT('version',VERSION(),'isolation',@@transaction_isolation,
    'maxConnections',@@max_connections,'innodbLockWaitTimeout',@@innodb_lock_wait_timeout)`);
  // Warmup writes have independent fixtures and are excluded from the reported cases.
  await exercise(fixtures('lecture', 10, 1, 'warm'), 2, 'warmup');
  report.cases = [];
  if (mode === 'baseline') {
    for (let repeat = 0; repeat < repeats; repeat++) {
    for (const concurrency of levels) {
      await exercise(fixtures('lecture', requestCount, 1, `l${repeat}${concurrency}`), concurrency, 'lecture-hot-slot');
      await exercise(fixtures('room', requestCount, 1, `r${repeat}${concurrency}`), concurrency, 'room-hot-interval');
      await exercise(fixtures('room', requestCount, 12, `s${repeat}${concurrency}`), concurrency, 'room-spread-12');
      cleanup(); resources.length = 0; teams.length = 0;
    }
    }
  } else {
    for (let repeat = 0; repeat < repeats; repeat++) {
    for (const concurrency of levels) {
      await exercise(fixtures('lecture', 500, 1, `ll${repeat}${concurrency}`), concurrency, 'lecture-hot-slot-limited');
      await exercise(fixtures('room', 500, 1, `rl${repeat}${concurrency}`), concurrency, 'room-hot-interval-limited');
      cleanup(); resources.length = 0; teams.length = 0;
    }
    }
  }
} finally {
  try { cleanup(); } catch (error) { report.cleanup = 'failed'; throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    const output = process.env.BOOKING_BENCHMARK_OUTPUT;
    if (output) writeFileSync(output, JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ run, cleanup: report.cleanup, output }));
  }
}
