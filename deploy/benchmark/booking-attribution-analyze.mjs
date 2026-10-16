import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
const plan=JSON.parse(readFileSync(resolve(process.argv[2])));
const output=resolve(process.argv[3]||join(plan.directory,'attribution-summary.json'));
function stats(values){const a=values.filter(Number.isFinite).sort((x,y)=>x-y);if(!a.length)return{count:0};
  const p=n=>a[Math.max(0,Math.ceil(a.length*n)-1)];const mean=a.reduce((x,y)=>x+y,0)/a.length;
  return{count:a.length,mean,p50:p(.5),p95:p(.95),p99:p(.99),min:a[0],max:a.at(-1),
    cv:Math.sqrt(a.reduce((n,x)=>n+(x-mean)**2,0)/a.length)/mean};}
const runs=plan.runs.map(run=>({...run,report:JSON.parse(readFileSync(join(run.artifact,'summary.json')))}));
const labels=['measured-lecture-c50','measured-room-spread-c100','measured-scan-varied-intervals-500'];
const detail=[];
for(const run of runs)for(const label of labels){
  const c=run.report.cases.find(x=>x.label===label);if(!c)throw Error('missing-measured-case');
  const raw=JSON.parse(readFileSync(join(run.artifact,label+'-requests.json')));
  const sumSql=raw.requests.map(x=>Object.values(x.sql).reduce((s,q)=>s+q.millis,0));
  const cleanup=raw.requests.map(x=>x.stages.doCleanupAfterCompletion?.millis);
  const fresh=raw.requests.map(x=>Object.entries(x.sql).filter(([key])=>key.endsWith('.findReplayFresh')).reduce((s,[,q])=>s+q.millis,0));
  const jitDelta=c.runtimeAfter.jitMillis-c.runtimeBefore.jitMillis;
  const gcDelta=c.runtimeAfter.gc.reduce((s,g)=>s+g.millis-(c.runtimeBefore.gc.find(x=>x.name===g.name)?.millis||0),0);
  detail.push({variant:run.variant,round:run.round,label,tps:c.tps,metrics:c.metrics,sqlStats:c.sqlStats,outcomes:c.outcomes,
    actualSqlCount:stats(raw.requests.map(x=>x.physicalQueries)),sqlMillisPerRequest:stats(sumSql),
    cleanupMillis:stats(cleanup),freshMillis:stats(fresh),
    activeTransactionMillis:stats(raw.requests.map(x=>x.transactionToCommitMillis-(x.stages.doBegin?.millis||0))),
    pingMillis:stats([...c.runtimeBefore.dbPingMillis,...c.runtimeAfter.dbPingMillis]),jitDeltaMillis:jitDelta,gcDeltaMillis:gcDelta,
    fixture:{seedSha256:c.seedSha256,requestSha256:c.requestSha256},maxObservedWaiters:c.maxObservedWaiters,lockTables:c.lockTables,
    raw:join(run.artifact,label+'-requests.json')});
}
for(const label of labels){
  const set=detail.filter(x=>x.label===label);
  if(new Set(set.map(x=>JSON.stringify(x.fixture))).size!==1)throw Error('fixture-not-identical:'+label);
}
const aggregates=[];
for(const variant of ['A','B','C'])for(const label of labels){
  const rows=detail.filter(x=>x.variant===variant&&x.label===label);if(!rows.length)continue;
  const raw=rows.flatMap(x=>{const r=JSON.parse(readFileSync(x.raw));return r.requests;});
  const clients=rows.flatMap(x=>JSON.parse(readFileSync(x.raw)).clients);
  const timings=(fn)=>stats(raw.map(fn));
  const sql={};for(const row of raw)for(const [key,value] of Object.entries(row.sql)){
    const entry=sql[key]||{calls:0,executions:[]};entry.calls+=value.calls;entry.executions.push(...value.executions);sql[key]=entry;
  }
  aggregates.push({variant,label,rounds:rows.length,tpsAcrossRounds:stats(rows.map(x=>x.tps)),
    clientMillis:stats(clients.map(x=>x.elapsed)),physicalSqlCount:timings(x=>x.physicalQueries),
    lockSqlMillis:timings(x=>x.sql[label.includes('lecture')?'ResourceSlotMapper.lockById':'ReservableResourceMapper.lockById']?.millis),
    heldMillis:timings(x=>x.acquiredLockToCommitMillis),poolMillis:timings(x=>x.stages.getConnection?.millis),
    beginMillis:timings(x=>x.stages.doBegin?.millis),cleanupMillis:timings(x=>x.stages.doCleanupAfterCompletion?.millis),
    transactionMillis:timings(x=>x.transactionToCommitMillis-(x.stages.doBegin?.millis||0)),
    freshMillis:timings(x=>Object.entries(x.sql).filter(([k])=>k.endsWith('.findReplayFresh')).reduce((s,[,q])=>s+q.millis,0)),
    promoteMillis:stats(raw.flatMap(x=>x.promote.map(p=>p.millis))),
    promotePhysicalSqlCount:stats(raw.flatMap(x=>x.promote.map(p=>p.physicalQueries))),
    promoteLogicalSelectCount:stats(raw.flatMap(x=>x.promote.map(p=>p.logicalQueries))),
    sql:Object.fromEntries(Object.entries(sql).map(([k,v])=>[k,{calls:v.calls,perRequest:v.calls/raw.length,executionMillis:stats(v.executions)}]))});
}
const paired=[];
for(const label of labels)for(const [first,second] of [['A','B'],['A','C'],['C','B']]){
  const pairs=[1,2,3].map(round=>{
    const a=detail.find(x=>x.variant===first&&x.round===round&&x.label===label);
    const b=detail.find(x=>x.variant===second&&x.round===round&&x.label===label);
    if(!a||!b)return null;
    return{round,tpsRatio:b.tps/a.tps,p95Ratio:b.metrics.clientMillis.p95/a.metrics.clientMillis.p95,
      p99Ratio:b.metrics.clientMillis.p99/a.metrics.clientMillis.p99,pingRatio:b.pingMillis.mean/a.pingMillis.mean};
  }).filter(Boolean);
  paired.push({label,comparison:first+'→'+second,pairs,tpsRatio:stats(pairs.map(p=>p.tpsRatio)),p95Ratio:stats(pairs.map(p=>p.p95Ratio))});
}
const result={baseline:plan.baseline,createdAt:new Date().toISOString(),completedRuns:runs.length,
  complete:runs.length===9,order:plan.order,versions:plan.versions,detail,aggregates,paired,
  isolation:runs.map(x=>({variant:x.variant,round:x.round,...x.report.isolationCost})),
  correctness:runs.map(x=>({variant:x.variant,round:x.round,checks:x.report.correctness})),
  notes:['Business SQL counts exclude driver session-control/COMMIT commands.',
    'Lock query duration includes network/mapping; data_lock_waits proves blocking objects but is not an exact pure-wait timer.',
    'RC/RR probe is read-only, alternating ABBA, not an unsafe business isolation ablation.',
    'C keeps correctness fixes; C→B isolates the promotion implementation. A→C combines fresh replay and scoped RC; do not claim a full factorial design.',
    '3 process runs per version is a small sample; pooled request quantiles are not 1500 independent environment replications.']};
writeFileSync(output,JSON.stringify(result,null,2));
console.log(JSON.stringify({complete:result.complete,runs:runs.length,output}));
console.table(detail.map(x=>({version:x.variant+x.round,case:x.label,tps:+x.tps.toFixed(2),p95:+x.metrics.clientMillis.p95.toFixed(2),
  ping:+x.pingMillis.mean.toFixed(3),sql:x.actualSqlCount.mean,jit:x.jitDeltaMillis,gc:x.gcDeltaMillis})));
