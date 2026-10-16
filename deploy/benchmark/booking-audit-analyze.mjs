import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';

// Offline only: derive paired durations, never subtract unrelated percentiles.
const directory=resolve(process.argv[2]);
const report=JSON.parse(readFileSync(resolve(directory,'summary.json'),'utf8'));
report.auditExecution={runnerFinishedNormally:Boolean(report.finishedAt),
  performanceCases:report.cases.filter(c=>!c.label.startsWith('same-key-')).length,
  note:report.finishedAt?'Runner completed; correctness findings must still be inspected.':
    'Runner interrupted after performance workloads; do not interpret as a fully passing suite. See separate safety rerun.'};
function stats(values){
  const a=values.filter(Number.isFinite).sort((x,y)=>x-y);
  if(!a.length)return{count:0};
  const p=n=>+a[Math.max(0,Math.ceil(a.length*n)-1)].toFixed(4);
  return{count:a.length,mean:+(a.reduce((x,y)=>x+y,0)/a.length).toFixed(4),p50:p(.5),p95:p(.95),p99:p(.99),max:a.at(-1)};
}
for(const c of report.cases){
  const path=resolve(directory,c.label+'-requests.json');
  const bytes=readFileSync(path);
  const raw=JSON.parse(bytes);
  c.evidence={file:c.label+'-requests.json',sha256:createHash('sha256').update(bytes).digest('hex')};
  c.observedJdbcConnections=new Set(raw.requests.map(x=>x.connectionId).filter(x=>x>0)).size;
  c.metrics.transactionAfterBeginMillis=stats(raw.requests.map(x=>
    x.transactionToCommitMillis==null?NaN:x.transactionToCommitMillis-(x.stages.doBegin?.millis||0)));
  c.metrics.outerServerMillis=stats(raw.requests.map(x=>
    x.transactionToCommitMillis==null?NaN:x.serverMillis-x.transactionToCommitMillis));
  const waits=new Map();
  for(const observation of raw.lockObservations)for(const w of observation.waiting){
    if(!['resource_slot','reservable_resource'].includes(w.table)||w.index!=='PRIMARY')continue;
    const key=w.connectionId+':'+w.eventId;
    const span=waits.get(key)||{first:observation.nanoTime,last:observation.nanoTime,samples:0};
    span.last=observation.nanoTime;span.samples++;waits.set(key,span);
  }
  c.sampledRowLockWait={
    interpretation:'first-to-last observed blocked-sample span for hot resource PRIMARY lookups; not exact per-request wait; sampling gaps unmeasured',
    distinctStatements:waits.size,
    multipleSampleSpansMillis:stats([...waits.values()].filter(x=>x.samples>1).map(x=>(x.last-x.first)/1e6))
  };
}
writeFileSync(resolve(directory,'analyzed-summary.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({cases:report.cases.length,correctness:report.correctness,finishedAt:report.finishedAt},null,2));
