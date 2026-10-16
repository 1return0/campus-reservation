import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, readdirSync, readFileSync, writeFileSync, cpSync, openSync, closeSync, existsSync } from 'node:fs';
import { resolve, join, relative } from 'node:path';

const root=resolve('.');
const modules=['campus-common','campus-security','gateway-service','identity-service','reservation-service','notification-service'];
const digest=file=>createHash('sha256').update(readFileSync(file)).digest('hex');
function walk(path){return readdirSync(path,{withFileTypes:true}).flatMap(x=>x.isDirectory()?walk(join(path,x.name)):[join(path,x.name)]);}
function formalHashes(){return Object.fromEntries(modules.flatMap(m=>walk(join(root,m,'src/main'))).map(p=>[relative(root,p).replaceAll('\\','/'),digest(p)]));}
function command(name,args,cwd=root){const result=spawnSync(name,args,{cwd,encoding:'utf8',windowsHide:true,maxBuffer:16*1024*1024});if(result.status!==0)throw Error(name+' failed: '+(result.stderr||result.error?.code));return result.stdout.trim();}
const action=process.argv[2];
if(action==='prepare'){
  const directory=resolve('target/booking-attribution-'+randomBytes(5).toString('hex'));mkdirSync(directory,{recursive:true});
  const baseline=command('git',['rev-parse','HEAD']);
  if(baseline!=='e1cf801debf6fa485bb7a1eb7297971468155f17')throw Error('unexpected-baseline');
  const archive=join(directory,'baseline.tar');command('git',['archive','--format=tar','--output='+archive,baseline]);
  const changed=command('git',['diff','--name-only','--','*/src/main/*']).split(/\r?\n/).filter(Boolean);
  changed.push('reservation-service/src/main/java/com/campusreserve/reservation/web/ReservationDataAccessExceptionHandler.java');
  const service='reservation-service/src/main/java/com/campusreserve/reservation/service/impl/StudyRoomServiceImpl.java';
  const start='    private void promoteWaiting(Long resourceId) {';
  const end='    private List<StudyRoomBookingEntity> activeInWindow';
  const versions={};
  for(const variant of ['A','B','C']){
    const workspace=join(directory,'workspace-'+variant);mkdirSync(workspace);command('tar',['-xf',archive,'-C',workspace]);
    if(variant!=='A')for(const file of changed)cpSync(join(root,file),join(workspace,file));
    if(variant==='C'){
      const old=readFileSync(join(directory,'workspace-A',service),'utf8'),current=readFileSync(join(workspace,service),'utf8');
      if(!old.includes(start)||!current.includes(start)||!old.includes(end)||!current.includes(end))throw Error('unsafe-test-fixture-transform');
      // Test-fixture-only mechanical transplant, never write the real repository's main source.
      writeFileSync(join(workspace,service),current.slice(0,current.indexOf(start))+
        old.slice(old.indexOf(start),old.indexOf(end))+current.slice(current.indexOf(end)));
    }
    cpSync(join(root,'reservation-service/src/test/java/com/campusreserve/reservation/audit'),
      join(workspace,'reservation-service/src/test/java/com/campusreserve/reservation/audit'),{recursive:true});
    for(const file of readdirSync(join(root,'deploy/benchmark')))if(file.endsWith('.mjs')||file==='booking-performance-audit.ps1')
      cpSync(join(root,'deploy/benchmark',file),join(workspace,'deploy/benchmark',file));
    // Local credentials remain inside root's Git-ignored target, never in a commit or command line.
    cpSync(join(root,'deploy/.env'),join(workspace,'deploy/.env'));
    const jar='gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar';mkdirSync(join(workspace,'gateway-service/target'),{recursive:true});
    cpSync(join(root,jar),join(workspace,jar));
    versions[variant]={workspace,serviceSha256:digest(join(workspace,service)),
      meaning:variant==='A'?'historical baseline, isolated only':variant==='B'?'round1 exact main source':'correctness fixes + original promotion loop, isolated only'};
  }
  const today=new Date().toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'});
  const day=new Date(Date.parse(today+'T08:00:00+08:00')+86400000).toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'});
  const plan={baseline,directory,versions,formalBefore:formalHashes(),day,fixedInstant:new Date(today+'T08:00:00+08:00').toISOString(),
    order:[['A',1],['B',1],['C',1],['B',2],['A',2],['C',2],['A',3],['B',3],['C',3]],runs:[],createdAt:new Date().toISOString()};
  const file=join(directory,'plan.json');writeFileSync(file,JSON.stringify(plan,null,2));console.log(file);
}else if(action==='run'){
  const file=resolve(process.argv[3]);
  if(!file.startsWith(resolve('target')+'\\')&&!file.startsWith(resolve('target')+'/'))throw Error('plan-outside-target');
  const plan=JSON.parse(readFileSync(file));const limit=Number(process.argv[4]||9);
  if(JSON.stringify(plan.formalBefore)!==JSON.stringify(formalHashes()))throw Error('formal-main-source-changed');
  let executed=0;
  for(let index=plan.runs.length;index<plan.order.length&&executed<limit;index++,executed++){
    const [variant,round]=plan.order[index],workspace=plan.versions[variant].workspace;
    const attempt=Date.now();
    const stdout=join(plan.directory,`${index}-${variant}${round}-${attempt}.log`),stderr=join(plan.directory,`${index}-${variant}${round}-${attempt}.err.log`);
    const out=openSync(stdout,'w'),err=openSync(stderr,'w');
    console.log(JSON.stringify({start:variant+round,index,at:new Date().toISOString()}));
    const child=spawn('powershell',['-NoProfile','-ExecutionPolicy','Bypass','-File','deploy/benchmark/booking-performance-audit.ps1','-Mode','attribution'],
      {cwd:workspace,windowsHide:true,stdio:['ignore',out,err],env:{...process.env,ATTRIBUTION_VARIANT:variant,ATTRIBUTION_ROUND:String(round),
        ATTRIBUTION_DAY:plan.day,ATTRIBUTION_FIXED_INSTANT:plan.fixedInstant}});
    const code=await new Promise((accept,reject)=>{child.on('exit',accept);child.on('error',reject);});closeSync(out);closeSync(err);
    if(code!==0)throw Error('attribution-run-failed:'+variant+round+'; inspect '+stdout);
    const candidates=readdirSync(join(workspace,'target')).filter(x=>x.startsWith('booking-audit-')).map(x=>join(workspace,'target',x));
    const artifacts=candidates.filter(p=>existsSync(join(p,'summary.json'))).map(p=>({path:p,report:JSON.parse(readFileSync(join(p,'summary.json')))}))
      .filter(x=>x.report.attributionRound===String(round)&&x.report.attributionVariant===variant&&x.report.finishedAt);
    if(artifacts.length!==1)throw Error('ambiguous-run-artifacts');
    plan.runs.push({variant,round,artifact:artifacts[0].path,stdout,stderr});
    if(JSON.stringify(plan.formalBefore)!==JSON.stringify(formalHashes()))throw Error('formal-source-mutated');
    writeFileSync(file,JSON.stringify(plan,null,2));console.log(JSON.stringify({finished:variant+round,artifact:artifacts[0].path}));
  }
}else if(action==='refresh'){
  const plan=JSON.parse(readFileSync(resolve(process.argv[3])));
  if(plan.runs.length)throw Error('cannot-change-tools-between-measured-runs');
  for(const {workspace} of Object.values(plan.versions)){
    cpSync(join(root,'reservation-service/src/test/java/com/campusreserve/reservation/audit'),
      join(workspace,'reservation-service/src/test/java/com/campusreserve/reservation/audit'),{recursive:true});
    for(const file of readdirSync(join(root,'deploy/benchmark')))if(file.endsWith('.mjs')||file==='booking-performance-audit.ps1')
      cpSync(join(root,'deploy/benchmark',file),join(workspace,'deploy/benchmark',file));
  }
  console.log('refreshed-test-tools-only-before-first-measurement');
}else if(action==='verify'){
  const plan=JSON.parse(readFileSync(resolve(process.argv[3])));
  console.log(JSON.stringify({formalMainUnchanged:JSON.stringify(plan.formalBefore)===JSON.stringify(formalHashes()),completed:plan.runs.length}));
}else throw Error('prepare/run/verify required');
