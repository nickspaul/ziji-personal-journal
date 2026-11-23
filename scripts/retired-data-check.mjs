import assert from 'node:assert/strict';
import {qaBase as base,qaPassword as password} from './qa-config.mjs';
let cookie='',csrf='',requests=0;
async function req(path,{method='GET',body,status=200}={}){
  const r=await fetch(base+path,{method,headers:{Cookie:cookie,...(method==='GET'?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf})},body:body===undefined?undefined:JSON.stringify(body)});
  const set=r.headers.getSetCookie();if(set.length)cookie=set.map(v=>v.split(';')[0]).join('; ');
  const result=await r.json();assert.equal(r.status,status,`${method} ${path}: ${JSON.stringify(result)}`);requests++;return result;
}
csrf=(await req('/auth/status')).csrfToken;
csrf=(await req('/auth/login',{method:'POST',body:{password}})).csrfToken;
const bootstrap=await req('/bootstrap');
assert.ok(bootstrap.schema.some(s=>s.name==='ledger'));
for(const name of ['completeness','goals']){
  assert.equal(name in bootstrap.collections,false);assert.equal(bootstrap.schema.some(s=>s.name===name),false);
  const id=crypto.randomUUID();
  await req(`/data/${name}`,{status:400});await req(`/data/${name}?deleted=true`,{status:400});
  await req(`/data/${name}`,{method:'POST',body:{id},status:400});
  await req(`/data/${name}/${id}`,{method:'PUT',body:{version:1},status:400});
  await req(`/data/${name}/${id}?version=1`,{method:'DELETE',status:400});
  await req(`/data/${name}/${id}/restore`,{method:'POST',body:{version:1},status:400});
}
const backup=await req('/backup');
assert.equal('goals' in backup.collections,false);assert.equal('completeness' in backup.collections,false);
const legacy={format:'ziji-backup',schemaVersion:1,collections:{
  body:[{id:crypto.randomUUID(),version:1,createdAt:'2026-01-01T10:00:00',updatedAt:'2026-01-01T10:00:00',deleted:false,measuredAt:'2026-01-01T10:00:00',heightCm:170,weightKg:65,sex:'UNSPECIFIED',notes:'旧备份兼容验收，仅测试空间'}],
  completeness:[{date:'2020-01-01',kind:'SMOKING',complete:true}],goals:[{kind:'SMOKING',limitValue:5,enabled:true}]
}};
const preview=await req('/backup/preview',{method:'POST',body:legacy});
assert.equal(preview.valid,true);assert.equal(preview.ignoredRecords,2);assert.equal(preview.newRecords,1);assert.equal(preview.warnings.length,2);
const restored=await req('/backup/restore',{method:'POST',body:{backup:legacy,mode:'merge'}});
assert.equal(restored.imported,1);assert.equal(restored.ignoredRecords,2);
const body=await req(`/data/body/${legacy.collections.body[0].id}`);assert.equal(body.weightKg,65);assert.equal(body.heightCm,170);
await req(`/data/body/${body.id}?version=${body.version}`,{method:'DELETE'});
const analytics=await req('/analytics?from=2026-10-01&to=2026-10-31');
assert.equal('completeDays' in analytics.totals,false);assert.ok(analytics.daily.every(d=>!('completeness' in d)));
assert.equal(analytics.recordingPolicy,'UNRECORDED_AS_ZERO');
const summary=await req('/analytics/summary');assert.equal('completeDays' in summary.totals,false);assert.equal('completeness' in summary.totals,false);
await req('/auth/logout',{method:'POST'});
console.log(`${requests} API requests passed: retired CRUD unavailable, exports clean, legacy restore skips retired data and preserves measurements, analytics policy retained.`);
