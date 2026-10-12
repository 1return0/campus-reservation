import { createHmac } from 'node:crypto';
import { performance } from 'node:perf_hooks';

const url = process.env.CAMPUS_BENCHMARK_URL;
const secret = process.env.CAMPUS_JWT_HMAC_SECRET;
const requests = Number(process.env.CAMPUS_BENCHMARK_REQUESTS || 400);
const concurrency = Number(process.env.CAMPUS_BENCHMARK_CONCURRENCY || 16);
const warmup = Number(process.env.CAMPUS_BENCHMARK_WARMUP || 40);
if (!url || !secret || !Number.isInteger(requests) || requests < 1
    || !Number.isInteger(concurrency) || concurrency < 1
    || !Number.isInteger(warmup) || warmup < 0) {
  throw new Error('Invalid catalog benchmark configuration');
}

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}

// A synthetic, read-only STUDENT identity: no account or production data is created.
const now = Math.floor(Date.now() / 1000);
const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
const payload = base64url(JSON.stringify({
  iss: 'campus-reserve', sub: 'benchmark-student', roles: ['STUDENT'],
  iat: now, exp: now + 3600,
}));
const unsigned = `${header}.${payload}`;
const signature = createHmac('sha256', Buffer.from(secret, 'base64'))
  .update(unsigned).digest('base64url');
const authorization = `Bearer ${unsigned}.${signature}`;

async function readCatalog() {
  const start = performance.now();
  const response = await fetch(url, { headers: { authorization } });
  const body = await response.json();
  const elapsed = performance.now() - start;
  if (response.status !== 200 || body.code !== 0 || !Array.isArray(body.data)) {
    throw new Error(`Catalog request failed: HTTP ${response.status}, code ${body.code}`);
  }
  return { elapsed, resources: body.data.length };
}

for (let index = 0; index < warmup; index++) await readCatalog();
const samples = [];
let next = 0;
let resourceCount;
const wallStart = performance.now();
await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, async () => {
  while (next < requests) {
    next += 1;
    const sample = await readCatalog();
    if (resourceCount !== undefined && resourceCount !== sample.resources) {
      throw new Error('Catalog changed during benchmark');
    }
    resourceCount = sample.resources;
    samples.push(sample.elapsed);
  }
}));
const wallSeconds = (performance.now() - wallStart) / 1000;
samples.sort((a, b) => a - b);
const percentile = fraction => samples[Math.ceil(fraction * samples.length) - 1];
console.log(JSON.stringify({
  endpoint: '/api/v1/resources',
  mode: process.env.CAMPUS_BENCHMARK_MODE,
  authentication: 'synthetic signed STUDENT JWT through Gateway',
  resource_count: resourceCount,
  requests, concurrency, warmup,
  success_count: samples.length, error_count: requests - samples.length,
  wall_seconds: Number(wallSeconds.toFixed(3)),
  throughput_rps: Number((requests / wallSeconds).toFixed(3)),
  latency_ms: {
    p50: Number(percentile(0.5).toFixed(3)),
    p95: Number(percentile(0.95).toFixed(3)),
    p99: Number(percentile(0.99).toFixed(3)),
  },
}));
