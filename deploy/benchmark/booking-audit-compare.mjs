import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const [beforePath,afterPath,outputPath,beforeSafetyPath]=process.argv.slice(2);
if(!outputPath)throw Error('usage: node booking-audit-compare.mjs before.json after.json output.json');
const before=JSON.parse(readFileSync(resolve(beforePath),'utf8'));
const after=JSON.parse(readFileSync(resolve(afterPath),'utf8'));
for(const key of ['admission','pool','serviceInstances'])if(before[key]!==after[key])throw Error('different-test-condition:'+key);
function pair(a,b,higherIsBetter=false){
  return{before:a,after:b,improvementPercent:a==null||b==null||a===0?null:
    +((higherIsBetter?(b/a-1):(1-b/a))*100).toFixed(2)};
}
const cases=[];
for(const previous of before.cases.filter(c=>!c.label.startsWith('same-key-'))){
  const current=after.cases.find(c=>c.label===previous.label);
  if(!current||current.requests!==previous.requests||current.concurrency!==previous.concurrency
      ||current.preseededDepth!==previous.preseededDepth||current.variant!==previous.variant)
    throw Error('different-workload:'+previous.label);
  const metrics={};
  for(const key of Object.keys(previous.metrics)){
    if(!current.metrics[key])continue;
    metrics[key]=Object.fromEntries(['mean','p50','p95','p99'].filter(p=>previous.metrics[key][p]!=null&&current.metrics[key][p]!=null)
      .map(p=>[p,pair(previous.metrics[key][p],current.metrics[key][p])]));
  }
  cases.push({label:previous.label,requests:previous.requests,concurrency:previous.concurrency,
    tps:pair(previous.tps,current.tps,true),outcomes:{before:previous.outcomes,after:current.outcomes},
    metrics,lockTables:{before:previous.lockTables,after:current.lockTables},
    sampledRowLockWait:{before:previous.sampledRowLockWait,after:current.sampledRowLockWait},
    evidence:{before:previous.evidence,after:current.evidence}});
}
const result={baselineRun:before.run,optimizedRun:after.run,
  note:'One sequential pass per version on shared infrastructure, not a confidence interval or causal proof for small latency/TPS changes. Positive improvementPercent is better.',
  cases,correctnessBefore:beforeSafetyPath?JSON.parse(readFileSync(resolve(beforeSafetyPath),'utf8')).correctness:before.correctness,
  correctnessAfter:after.correctness};
writeFileSync(resolve(outputPath),JSON.stringify(result,null,2));
console.log(JSON.stringify({cases:cases.length,output:resolve(outputPath)}));
