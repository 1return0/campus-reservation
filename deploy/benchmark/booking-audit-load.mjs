import { createHash, createHmac, randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { settings, sql, jsonQuery } from './audit-common.mjs';

const config = settings();
config.CAMPUS_RESERVE_DB_URL = process.env.AUDIT_DB_URL;
const schema = new URL(config.CAMPUS_RESERVE_DB_URL.replace(/^jdbc:/, '')).pathname.slice(1);
if (!/^campus_audit_[a-f0-9]{12}$/.test(schema)) throw Error('dedicated-audit-schema-required');
const attribution=process.env.AUDIT_MODE==='attribution';
const run = attribution?'fixedv1':randomBytes(4).toString('hex');
const artifact = process.env.AUDIT_ARTIFACT_DIRECTORY;
const gateway = 'http://127.0.0.1:18600';
const service = 'http://127.0.0.1:18602';
const day = process.env.ATTRIBUTION_DAY||new Date(Date.now() + 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
if(attribution)config.AUDIT_SQL_TIMESTAMP=Math.floor(Date.parse(process.env.ATTRIBUTION_FIXED_INSTANT)/1000);
const report = { run, schema, mode: process.env.AUDIT_MODE, cases: [], correctness: [], startedAt: new Date().toISOString(),
  instrumentation: 'test-only ByteBuddy private-method timings + MyBatis logical vs physical SQL + read-only P_S sampler; no production patch',
  admission: 'disabled', pool: 'Hikari default 10', serviceInstances: 1,
  attributionVariant:process.env.ATTRIBUTION_VARIANT, attributionRound:process.env.ATTRIBUTION_ROUND };
let sequence = BigInt(Date.now()) * 1000n;
let person = 0, statements = [], seedStatements=[];
const id = () => String(sequence++);
const q = value => `'${String(value).replaceAll("'", "''")}'`;
const execute = statement => sql(config, statement, schema);
const query = expression => jsonQuery(config, expression, schema);
function enqueue(statement) { statements.push(statement); if(attribution)seedStatements.push(statement);
  if (statements.length >= (attribution?200:20)||(attribution&&statements.join('\n').length>=8000)) flush(); }
function flush() { if (statements.length) { execute(statements.join('\n')); statements = []; } }
function reset() {
  flush();
  execute(`START TRANSACTION;
    DELETE FROM reservation_outbox_event; DELETE FROM slot_cancellation;
    DELETE FROM reservation_waitlist_entry; DELETE FROM reservation_order;
    DELETE FROM study_room_member_bucket; DELETE FROM study_room_booking_member; DELETE FROM study_room_booking;
    DELETE FROM study_team_member; DELETE FROM study_team; DELETE FROM study_room_open_window;
    DELETE FROM resource_slot; DELETE FROM reservable_resource; COMMIT;`);
  if(attribution){sequence=1200000000000000n;person=0;seedStatements=[];}
}
function token(user, student, role = 'STUDENT') {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({ iss: config.CAMPUS_JWT_ISSUER || 'campus-reserve',
    sub: user, studentNo: student, roles: [role], iat: now, exp: now + 7200 })).toString('base64url');
  const data = header + '.' + payload;
  return data + '.' + createHmac('sha256', Buffer.from(config.CAMPUS_JWT_HMAC_SECRET, 'base64')).update(data).digest('base64url');
}
const diagnosticToken = token('audit-observer', 'AUDOBSERVER');
function participant() {
  const n = person++;
  const user = `audit-${run}-${n}`;
  const student = `AUD${run}${n}`;
  const team = id();
  enqueue(`INSERT INTO study_team(id,leader_reference,invite_code_hash,invite_code_expires_at,status)
    VALUES(${team},${q(user)},SHA2(${q(user)},256),DATE_ADD(NOW(),INTERVAL 1 DAY),'FORMING');
    INSERT INTO study_team_member(id,team_id,student_number,user_reference,status,accepted_at)
    VALUES(${id()},${team},${q(student)},${q(user)},'ACCEPTED',NOW());`);
  return { user, student, team, token: token(user, student) };
}
function resource(kind = 'STUDY_ROOM') {
  const resourceId = id();
  enqueue(`INSERT INTO reservable_resource(id,resource_code,resource_name,resource_type,status,no_show_grace_minutes,min_participants,max_participants)
    VALUES(${resourceId},'AUD_${resourceId}','Isolated audit',${q(kind)},'PUBLISHED',15,1,8);`);
  if (kind === 'STUDY_ROOM') enqueue(`INSERT INTO study_room_open_window(id,resource_id,starts_at,ends_at,status)
    VALUES(${id()},${resourceId},'${day} 07:30:00','${day} 21:30:00','OPEN');`);
  return resourceId;
}
function roomRequest(room, p, start='14:00', end='16:00', key) {
  return { path: '/api/v1/study-rooms/bookings', token: p.token,
    key: key || `audit_${run}_${id()}`, body: { resourceId: room, teamId: p.team,
      startsAt: `${day}T${start}:00`, endsAt: `${day}T${end}:00` } };
}
function seededBooking(room, p, start, end, status = 'CONFIRMED', members = [p]) {
  const booking = id();
  enqueue(`INSERT INTO study_room_booking(id,booking_no,resource_id,team_id,leader_reference,idempotency_key,starts_at,ends_at,status,required_attendance_count)
    VALUES(${booking},'${booking}',${room},${p.team},${q(p.user)},'seed_${booking}','${day} ${start}:00','${day} ${end}:00','${status}',1);
    UPDATE study_team SET status='BOOKED' WHERE id=${p.team};`);
  for (const m of members) {
    enqueue(`INSERT INTO study_room_booking_member(id,booking_id,user_reference,student_number)
      VALUES(${id()},${booking},${q(m.user)},${q(m.student)});`);
    if (status === 'CONFIRMED') {
      for (let minute = minutes(start); minute < minutes(end); minute += 30) {
        enqueue(`INSERT INTO study_room_member_bucket(student_number,bucket_start,booking_id)
          VALUES(${q(m.student)},'${day} ${clock(minute)}:00',${booking});`);
      }
    }
  }
  return booking;
}
function minutes(time) { const [h,m] = time.split(':').map(Number); return h*60+m; }
function clock(value) { return String(Math.floor(value/60)).padStart(2,'0')+':'+String(value%60).padStart(2,'0'); }
function lecture(count, capacity=20) {
  const room = resource('LECTURE'), slot=id();
  enqueue(`INSERT INTO resource_slot(id,resource_id,starts_at,ends_at,capacity,available_capacity,status,event_title,speaker_name,activity_description)
    VALUES(${slot},${room},'${day} 14:00:00','${day} 16:00:00',${capacity},${capacity},'OPEN','Audit','Audit','Audit');`);
  const requests = Array.from({length:count}, () => { const p=participant(); return {
    path:`/api/v1/slots/${slot}/reservations`,token:p.token,key:`audit_${run}_${id()}` }; });
  flush(); return { kind:'lecture',requests,slot,room,capacity };
}
function rooms(count, roomCount=1) {
  const roomIds=Array.from({length:roomCount},()=>resource());
  const requests=Array.from({length:count},(_,i)=>roomRequest(roomIds[i%roomCount],participant()));
  flush(); return {kind:'room',requests,roomIds};
}

async function call(request, label, index, base=gateway) {
  const begin=performance.now();
  try {
    const response=await fetch(base+request.path,{ method:request.method||'POST',headers:{Authorization:`Bearer ${request.token}`,
      'Content-Type':'application/json','Idempotency-Key':request.key||`audit_${run}_${id()}`,
      'X-Audit-Case':label,'X-Audit-Request':String(index)},
      body:request.body?JSON.stringify(request.body):undefined,signal:AbortSignal.timeout(120000)});
    const text=await response.text();
    let body;
    try{body=JSON.parse(text);}catch{body={message:'non-json-error-response'};}
    return {requestId:String(index),status:response.status,outcome:response.status===200?body.data?.status||'OK':body.message,
      elapsed:performance.now()-begin, data:body.data};
  }catch(error){return{requestId:String(index),status:0,outcome:error.name,elapsed:performance.now()-begin};}
}
function stats(values) {
  const a=values.filter(x=>Number.isFinite(x)).sort((x,y)=>x-y);
  if(!a.length)return{count:0};
  const p=n=>+a[Math.max(0,Math.ceil(a.length*n)-1)].toFixed(4);
  return{count:a.length,mean:+(a.reduce((x,y)=>x+y,0)/a.length).toFixed(4),p50:p(.5),p95:p(.95),p99:p(.99),max:a.at(-1)};
}
function timing(rows, fn) { return stats(rows.map(fn)); }
function summarize(rows, clients, kind) {
  const lock=kind==='lecture'?'ResourceSlotMapper.lockById':'ReservableResourceMapper.lockById';
  return {
    clientMillis:stats(clients.map(x=>x.elapsed)),serverMillis:timing(rows,x=>x.serverMillis),
    transactionToCommitMillis:timing(rows,x=>x.transactionToCommitMillis),
    acquiredLockToCommitMillis:timing(rows,x=>x.acquiredLockToCommitMillis),
    lockSqlMillis:timing(rows,x=>x.sql[lock]?.millis),
    connectionAcquireMillis:timing(rows,x=>x.stages.getConnection?.millis),
    transactionBeginMillis:timing(rows,x=>x.stages.doBegin?.millis),
    commitMillis:timing(rows,x=>x.stages.doCommit?.millis),
    requireRoomMillis:timing(rows,x=>x.stages.requireRoom?.millis),
    physicalSqlCount:timing(rows,x=>x.physicalQueries),logicalSelectCount:timing(rows,x=>x.logicalQueries),
    promoteMillis:stats(rows.flatMap(x=>x.promote.map(p=>p.millis))),
    promotePhysicalSqlCount:stats(rows.flatMap(x=>x.promote.map(p=>p.physicalQueries))),
    promoteLogicalSelectCount:stats(rows.flatMap(x=>x.promote.map(p=>p.logicalQueries))),
    promoteCandidateRows:stats(rows.flatMap(x=>x.promote.map(p=>p.candidateRows)))
  };
}
async function drain(label) {
  const response=await fetch(service+'/__audit/drain?caseId='+label,{headers:{Authorization:`Bearer ${diagnosticToken}`}});
  if(response.status!==200)throw Error('audit-endpoint-unavailable');
  return response.json();
}
function invariants() {
  return query(`JSON_OBJECT('negativeCapacity',(SELECT COUNT(*) FROM resource_slot WHERE available_capacity<0),
    'roomOverlaps',(SELECT COUNT(*) FROM study_room_booking a JOIN study_room_booking b ON a.resource_id=b.resource_id AND a.id<b.id AND a.starts_at<b.ends_at AND a.ends_at>b.starts_at WHERE a.status='CONFIRMED' AND b.status='CONFIRMED'),
    'orders',(SELECT COUNT(*) FROM reservation_order),'roomBookings',(SELECT COUNT(*) FROM study_room_booking),
    'outbox',(SELECT COUNT(*) FROM reservation_outbox_event),
    'confirmedOrders',(SELECT COUNT(*) FROM reservation_order WHERE status='CONFIRMED'),
    'waitingOrders',(SELECT COUNT(*) FROM reservation_order WHERE status='WAITLISTED'),
    'confirmedRooms',(SELECT COUNT(*) FROM study_room_booking WHERE status='CONFIRMED'),
    'waitingRooms',(SELECT COUNT(*) FROM study_room_booking WHERE status='WAITLISTED'))`);
}
async function exercise(label, fixture, concurrency, details={}) {
  const fixedEvidence=attribution?{
    seedSha256:createHash('sha256').update(seedStatements.join('\n')).digest('hex'),
    requestSha256:createHash('sha256').update(JSON.stringify(fixture.requests.map(({token,...rest})=>rest))).digest('hex'),
    runtimeBefore:await probe('/__audit/runtime')}:{};
  const beforeState=invariants();
  const caseId='audit-'+label;
  let next=0;
  const clients=[];
  const started=performance.now();
  await Promise.all(Array.from({length:Math.min(concurrency,fixture.requests.length)},async()=>{
    while(next<fixture.requests.length){const index=next++;clients.push(await call(fixture.requests[index],caseId,index));}
  }));
  const seconds=(performance.now()-started)/1000;
  const data=await drain(caseId);
  const rows=data.requests;
  if(rows.length!==clients.length)throw Error(`missing-audit-samples:${label}:${rows.length}/${clients.length}`);
  if(rows.some(x=>x.physicalQueries>0&&!x.stages.doBegin))throw Error('transaction-probe-not-attached');
  if(rows.some(x=>x.outcome==='CONFIRMED'&&!x.stages.doCommit))throw Error('commit-probe-not-attached');
  if(fixture.kind==='room'&&!label.startsWith('same-key-')&&rows.some(x=>x.outcome==='CONFIRMED'&&!x.promote.length))throw Error('private-method-probe-not-attached');
  const outcomes={};for(const c of clients){const key=c.status+':'+c.outcome;outcomes[key]=(outcomes[key]||0)+1;}
  const sqlNames=new Set(rows.flatMap(x=>Object.keys(x.sql)));
  const sqlStats=Object.fromEntries([...sqlNames].map(name=>{
    const all=rows.flatMap(x=>x.sql[name]?.executions||[]);
    return[name,{calls:all.length,totalMillis:all.reduce((a,b)=>a+b,0),executionMillis:stats(all)}];
  }));
  const metrics=summarize(rows,clients,fixture.kind);
  const byOutcome={};
  for(const outcome of new Set(rows.map(x=>x.outcome))){
    const sub=rows.filter(x=>x.outcome===outcome);
    const ids=new Set(sub.map(x=>x.requestId));
    byOutcome[outcome]=summarize(sub,clients.filter(x=>ids.has(x.requestId)),fixture.kind);
  }
  const state=invariants();
  if(state.negativeCapacity||state.roomOverlaps)throw Error('booking-invariant-failed');
  const created=(state.orders-beforeState.orders)+(state.roomBookings-beforeState.roomBookings);
  if(state.outbox-beforeState.outbox!==created)throw Error('new-booking-outbox-count-mismatch');
  const result={label,requests:clients.length,concurrency,seconds,tps:clients.length/seconds,outcomes,...details,
    metrics,byOutcome,sqlStats,state,...fixedEvidence,
    runtimeAfter:attribution?await probe('/__audit/runtime'):undefined,lockSamplerStatus:data.lockSamplerStatus,
    lockSamples:data.lockObservations.length,
    maxObservedWaiters:Math.max(0,...data.lockObservations.map(x=>new Set(x.waiting.map(y=>y.connectionId)).size)),
    lockTables:[...new Set(data.lockObservations.flatMap(x=>x.waiting.map(y=>y.table+'/'+y.index)))]};
  writeFileSync(resolve(artifact,label+'-requests.json'),JSON.stringify({requests:rows,
    clients:clients.map(({data,...safe})=>safe),lockObservations:data.lockObservations},null,2));
  report.cases.push(result);
  writeFileSync(resolve(artifact,'summary.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({label,requests:clients.length,concurrency,tps:+result.tps.toFixed(2),outcomes,
    p95:metrics.clientMillis.p95,poolP95:metrics.connectionAcquireMillis.p95,lockP95:metrics.lockSqlMillis.p95,
    heldP95:metrics.acquiredLockToCommitMillis.p95,promoteP95:metrics.promoteMillis.p95,
    promoteSqlMean:metrics.promotePhysicalSqlCount.mean,scanMean:metrics.promoteCandidateRows.mean,
    lockTables:result.lockTables}));
  if(clients.some(x=>x.status===0))throw Error('transport-timeout-stopping-audit');
  return{clients,result};
}

async function probe(path){
  const response=await fetch(service+path,{headers:{Authorization:`Bearer ${diagnosticToken}`},signal:AbortSignal.timeout(120000)});
  if(response.status!==200)throw Error('attribution-probe-failed:'+response.status);
  return response.json();
}

async function attributionChecks(){
  reset();
  report.warmups=[];
  for(const prefix of ['warmup','measured']){
    await exercise(prefix+'-lecture-c50',lecture(500),50);reset();
    await exercise(prefix+'-room-spread-c100',rooms(500,12),100);reset();
    await exercise(prefix+'-scan-varied-intervals-500',seededCandidates(500,'varied-intervals',30),1,{preseededDepth:500,variant:'varied-intervals'});reset();
    if(prefix==='warmup'){report.warmups=report.cases;report.cases=[];}
  }
  const isolation=await probe('/__audit/isolation-cost');
  writeFileSync(resolve(artifact,'isolation-cost.json'),JSON.stringify(isolation,null,2));
  report.isolationCost=Object.fromEntries(['RC','RR'].map(level=>{
    const rows=isolation.filter(x=>x.isolation===level).map(x=>x.sample);
    return[level,{count:rows.length,totalMillis:stats(rows.map(x=>x.serverMillis)),
      beginMillis:stats(rows.map(x=>x.stages.doBegin?.millis)),
      cleanupMillis:stats(rows.map(x=>x.stages.doCleanupAfterCompletion?.millis)),
      queryMillis:stats(rows.map(x=>x.sql['jdbc.selectOne']?.millis))}];
  }));
  if(process.env.ATTRIBUTION_VARIANT!=='A')await correctnessChecks();
  report.finishedAt=new Date().toISOString();
  writeFileSync(resolve(artifact,'summary.json'),JSON.stringify(report,null,2));
}

async function correctnessChecks() {
  const admin=token('audit-admin','AUDADMIN','ADMIN');
  const observe=(name,passed,evidence)=>{report.correctness.push({name,passed,evidence});
    writeFileSync(resolve(artifact,'summary.json'),JSON.stringify(report,null,2));};
  for(const kind of ['lecture','room']){
    const fixture=kind==='lecture'?lecture(1,1):rooms(1);
    const duplicate={...fixture,requests:Array.from({length:20},()=>fixture.requests[0])};
    const {result}=await exercise('same-key-'+kind,duplicate,20);
    observe('concurrent-idempotency-'+kind,Object.keys(result.outcomes).every(x=>x.startsWith('200:'))&&
      (kind==='lecture'?result.state.orders:result.state.roomBookings)===1,result.outcomes);
    reset();
  }
  for(const kind of ['lecture','room']){
    const fixture=kind==='lecture'?lecture(3,1):rooms(3);
    const results=[];
    for(let i=0;i<3;i++)results.push(await call(fixture.requests[i],'audit-safety-'+kind,i));
    const ids=results.map(x=>kind==='lecture'?x.data?.reservationId:x.data?.id);
    const table=kind==='lecture'?'reservation_order':'study_room_booking';
    const oldest=query(`(SELECT JSON_QUOTE(CAST(${kind==='lecture'?'reservation_id':'id'} AS CHAR)) FROM ${kind==='lecture'?'reservation_waitlist_entry':'study_room_booking'} WHERE status=${q(kind==='lecture'?'WAITING':'WAITLISTED')} ORDER BY created_at,id LIMIT 1)`);
    const cancel=await call({path:kind==='lecture'?`/api/v1/reservations/${ids[0]}`:`/api/v1/study-rooms/bookings/${ids[0]}`,
      method:'DELETE',token:fixture.requests[0].token},'audit-safety-cancel-'+kind,0);
    const statuses=query(`(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',CAST(id AS CHAR),'status',status)) FROM ${table})`);
    observe('cancellation-fifo-'+kind,cancel.status===200&&statuses.find(x=>String(x.id)===String(oldest))?.status==='CONFIRMED',
      {cancelStatus:cancel.status,statuses,state:invariants()});
    const maintenance=kind==='lecture'?{path:`/api/v1/admin/slots/${fixture.slot}/cancel`,method:'PATCH',body:{reason:'Audit isolated maintenance'},token:admin}:
      {path:`/api/v1/admin/study-rooms/windows/${query(`(SELECT id FROM study_room_open_window LIMIT 1)`)}/maintenance-close?reason=Audit`,token:admin};
    const response=await call(maintenance,'audit-safety-maintenance-'+kind,0);
    const state=invariants();
    observe('maintenance-'+kind,response.status===200&&state.confirmedOrders===0&&state.waitingOrders===0&&state.confirmedRooms===0&&state.waitingRooms===0&&
      query('(SELECT COUNT(*) FROM study_room_member_bucket)')===0,{httpStatus:response.status,state});
    await drain('audit-safety-'+kind);await drain('audit-safety-cancel-'+kind);await drain('audit-safety-maintenance-'+kind);
    reset();
  }
  for(const kind of ['lecture','room']){
    const fixture=kind==='lecture'?lecture(1,1):rooms(1);
    let response;
    execute(`CREATE TRIGGER audit_fail_outbox BEFORE INSERT ON reservation_outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit-only-outbox-failure';`);
    try{response=await call(fixture.requests[0],'audit-rollback-'+kind,0);}
    finally{execute('DROP TRIGGER audit_fail_outbox;');}
    const state=invariants();
    const snapshot=await drain('audit-rollback-'+kind);
    const rollbackCalls=snapshot.requests.reduce((n,x)=>n+(x.stages.doRollback?.calls||0),0);
    const attemptedOutboxInsert=snapshot.requests.reduce((n,x)=>n+(x.sql['ReservationOutboxEventMapper.insert']?.calls||0),0);
    observe('outbox-atomic-rollback-'+kind,response.status===500&&rollbackCalls===1&&attemptedOutboxInsert===1&&
      state.orders===0&&state.roomBookings===0&&state.outbox===0&&query('(SELECT COUNT(*) FROM study_room_member_bucket)')===0,
      {httpStatus:response.status,unexpectedHttpStatus:response.status!==500,rollbackCalls,attemptedOutboxInsert,state});
    writeFileSync(resolve(artifact,'rollback-'+kind+'-requests.json'),JSON.stringify(snapshot,null,2));reset();
  }
  // Selected real SQL concurrency/eligibility checks for the bulk-promotion change.
  {
    const room=resource(),owner=participant(),a=participant(),b=participant(),c=participant(),d=participant();
    const confirmed=seededBooking(room,owner,'14:00','16:00');
    const ids=[seededBooking(room,a,'14:00','16:00','WAITLISTED'),seededBooking(room,b,'14:00','16:00','WAITLISTED'),
      seededBooking(room,c,'15:00','17:00','WAITLISTED'),seededBooking(room,d,'16:00','17:00','WAITLISTED')];
    const backingTeam=id();
    enqueue(`INSERT INTO study_team(id,leader_reference,invite_code_hash,invite_code_expires_at,status)
      VALUES(${backingTeam},${q(a.user)},SHA2(${q(backingTeam)},256),DATE_ADD(NOW(),INTERVAL 1 DAY),'FORMING');
      INSERT INTO study_team_member(id,team_id,student_number,user_reference,status,accepted_at)
      VALUES(${id()},${backingTeam},${q(a.student)},${q(a.user)},'ACCEPTED',NOW());`);
    seededBooking(resource(),{...a,team:backingTeam},'14:00','16:00');flush();
    const response=await call({path:`/api/v1/study-rooms/bookings/${confirmed}`,method:'DELETE',token:owner.token},'audit-bulk-fifo',0);
    const statuses=query(`(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',CAST(id AS CHAR),'status',status)) FROM study_room_booking WHERE resource_id=${room})`);
    const expected=['WAITLISTED','CONFIRMED','WAITLISTED','CONFIRMED'];
    observe('bulk-fifo-ineligible-head-overlap-and-adjacency',response.status===200&&ids.every((id,i)=>
      statuses.find(x=>String(x.id)===id)?.status===expected[i])&&invariants().roomOverlaps===0&&invariants().outbox===3,
      {httpStatus:response.status,statuses,state:invariants()});
    await drain('audit-bulk-fifo');reset();
  }
  {
    const requests=[];
    for(let i=0;i<5;i++){
      const p=participant(),otherTeam=id();
      enqueue(`INSERT INTO study_team(id,leader_reference,invite_code_hash,invite_code_expires_at,status)
        VALUES(${otherTeam},${q(p.user)},SHA2(${q(otherTeam)},256),DATE_ADD(NOW(),INTERVAL 1 DAY),'FORMING');
        INSERT INTO study_team_member(id,team_id,student_number,user_reference,status,accepted_at)
        VALUES(${id()},${otherTeam},${q(p.student)},${q(p.user)},'ACCEPTED',NOW());`);
      requests.push(roomRequest(resource(),p),roomRequest(resource(),{...p,team:otherTeam}));
    }
    flush();
    const responses=await Promise.all(requests.map((r,i)=>call(r,'audit-cross-room-races',i)));
    const state=invariants();
    observe('cross-room-member-bucket-uniqueness',responses.filter(x=>x.status===200&&x.outcome==='CONFIRMED').length===5&&
      responses.filter(x=>x.status===409&&x.outcome==='study-room-participant-time-conflict').length===5&&
      state.confirmedRooms===5&&state.outbox===5&&query('(SELECT COUNT(*) FROM study_room_member_bucket)')===20,
      {outcomes:responses.map(x=>({status:x.status,outcome:x.outcome})),state});
    await drain('audit-cross-room-races');reset();
  }
  for(const kind of ['lecture','room']){
    const fixture=kind==='lecture'?lecture(20,1):rooms(20);
    const maintenance=kind==='lecture'?{path:`/api/v1/admin/slots/${fixture.slot}/cancel`,method:'PATCH',body:{reason:'Audit race'},token:admin}:
      {path:`/api/v1/admin/study-rooms/windows/${query('(SELECT id FROM study_room_open_window LIMIT 1)')}/maintenance-close?reason=Audit`,token:admin};
    const responses=await Promise.all([...fixture.requests,maintenance].map((r,i)=>call(r,'audit-maintenance-race-'+kind,i)));
    const state=invariants();
    const created=state.orders+state.roomBookings;
    observe('maintenance-vs-new-booking-'+kind,responses.at(-1).status===200&&state.confirmedOrders===0&&state.waitingOrders===0&&
      state.confirmedRooms===0&&state.waitingRooms===0&&state.outbox===created*2&&
      query('(SELECT COUNT(*) FROM study_room_member_bucket)')===0,
      {maintenanceStatus:responses.at(-1).status,outcomes:responses.map(x=>({status:x.status,outcome:x.outcome})),state});
    await drain('audit-maintenance-race-'+kind);reset();
  }
  {
    const room=resource(),owner=participant(),candidate=participant();
    const confirmed=seededBooking(room,owner,'14:00','16:00');
    const waiting=seededBooking(room,candidate,'14:00','16:00','WAITLISTED');flush();
    execute(`CREATE TRIGGER audit_fail_promotion BEFORE INSERT ON reservation_outbox_event FOR EACH ROW
      SET NEW.event_id=IF(NEW.event_type='STUDY_ROOM_PROMOTED',NULL,NEW.event_id);`);
    let response;
    try{response=await call({path:`/api/v1/study-rooms/bookings/${confirmed}`,method:'DELETE',token:owner.token},'audit-promotion-rollback',0);}
    finally{execute('DROP TRIGGER audit_fail_promotion;');}
    const state=invariants();
    const statuses=query(`(SELECT JSON_ARRAYAGG(JSON_OBJECT('id',CAST(id AS CHAR),'status',status)) FROM study_room_booking)`);
    observe('bulk-promotion-outbox-failure-rolls-back-cancellation-and-buckets',response.status===500&&state.outbox===0&&
      statuses.find(x=>String(x.id)===confirmed)?.status==='CONFIRMED'&&statuses.find(x=>String(x.id)===waiting)?.status==='WAITLISTED'&&
      query(`(SELECT COUNT(*) FROM study_room_member_bucket WHERE booking_id=${confirmed})`)===4&&
      query(`(SELECT COUNT(*) FROM study_room_member_bucket WHERE booking_id=${waiting})`)===0,
      {httpStatus:response.status,state,statuses});
    await drain('audit-promotion-rollback');reset();
  }
  if(report.correctness.some(x=>!x.passed))
    throw Error('consistency-check-failed-see-summary');
}

function seededCandidates(depth, variant, requestCount) {
  const target=resource();
  if(variant==='same-interval') seededBooking(target,participant(),'14:00','16:00');
  if(variant==='varied-intervals') {
    for(const [a,b] of [['07:30','11:30'],['11:30','15:30'],['15:30','19:30'],['19:30','21:30']]) {
      seededBooking(target,participant(),a,b);
    }
  }
  const candidates=[];
  for(let i=0;i<depth;i++){
    const p=participant();candidates.push(p);
    let start='14:00',end='16:00';
    if(variant==='varied-intervals'){
      const startMinute=450+(i%25)*30;
      const duration=Math.min(240,60+Math.floor(i/25)%7*30,1290-startMinute);
      start=clock(startMinute);end=clock(startMinute+duration);
    }
    seededBooking(target,p,start,end,'WAITLISTED');
  }
  if(variant==='member-conflicts'){
    for(let i=0;i<candidates.length;i+=8){
      const group=candidates.slice(i,i+8),other=resource(),team=id();
      const leader={...group[0],team};
      enqueue(`INSERT INTO study_team(id,leader_reference,invite_code_hash,invite_code_expires_at,status)
        VALUES(${team},${q(leader.user)},SHA2(${q(team)},256),DATE_ADD(NOW(),INTERVAL 1 DAY),'FORMING');`);
      for(const member of group) enqueue(`INSERT INTO study_team_member(id,team_id,student_number,user_reference,status,accepted_at)
        VALUES(${id()},${team},${q(member.student)},${q(member.user)},'ACCEPTED',NOW());`);
      // Separate accepted team, preserving valid membership for the existing booking.
      seededBooking(other,leader,'14:00','16:00','CONFIRMED',group);
    }
  }
  const requests=Array.from({length:requestCount},()=>roomRequest(target,participant(),
    variant==='member-conflicts'?'08:00':'14:00',variant==='member-conflicts'?'09:00':'16:00'));
  flush();return{kind:'room',requests,roomIds:[target]};
}

try {
  if(attribution){await attributionChecks();}
  else if(process.env.AUDIT_MODE==='safety'){
    await correctnessChecks();
    report.finishedAt=new Date().toISOString();
    writeFileSync(resolve(artifact,'summary.json'),JSON.stringify(report,null,2));
    process.exitCode=0;
  } else {
  await exercise('warmup',lecture(20),2);
  report.cases=[];reset();
  const full=process.env.AUDIT_MODE==='full';
  for(const concurrency of full?[1,10,50,100,500]:[1,10]){
    await exercise('lecture-c'+concurrency,lecture(full?500:30),concurrency);reset();
    await exercise('room-c'+concurrency,rooms(full?500:30),concurrency);reset();
  }
  if(full){
    await exercise('room-spread-c100',rooms(500,12),100);reset();
    for(const variant of ['same-interval','varied-intervals','member-conflicts']){
      for(const depth of [0,50,200,500]){
        await exercise(`scan-${variant}-${depth}`,seededCandidates(depth,variant,3),1,{preseededDepth:depth,variant});reset();
      }
    }
    await exercise('scan-member-conflicts-200-c10',seededCandidates(200,'member-conflicts',30),10,
      {preseededDepth:200,variant:'member-conflicts'});reset();
    await correctnessChecks();
  }
  report.finishedAt=new Date().toISOString();
  writeFileSync(resolve(artifact,'summary.json'),JSON.stringify(report,null,2));
  }
}finally{reset();}
