import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {qaBase as base,qaPassword as password} from './qa-config.mjs';
let cookie='',csrf='',requests=0;
async function req(path,{method='GET',body,status=200}={}){
 const r=await fetch(base+path,{method,headers:{Cookie:cookie,...(method==='GET'?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf})},body:body===undefined?undefined:JSON.stringify(body)});
 if(r.headers.getSetCookie().length)cookie=r.headers.getSetCookie().map(v=>v.split(';')[0]).join('; ');
 const value=await r.json();assert.equal(r.status,status,`${method} ${path}: ${JSON.stringify(value)}`);requests++;return value;
}
const authStatus=await req('/auth/status');csrf=authStatus.csrfToken;
const auth=await req('/auth/login',{method:'POST',body:{password}});csrf=auth.csrfToken;
const create=(c,body)=>req('/data/'+c,{method:'POST',body:{id:crypto.randomUUID(),...body}});
const update=(c,row,body)=>req(`/data/${c}/${row.id}`,{method:'PUT',body:{version:row.version,...body}});
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
function dayAgo(n){const d=new Date(today+'T12:00:00+08:00');d.setUTCDate(d.getUTCDate()-n);return d.toISOString().slice(0,10)}
const stamp='身体仪表盘与补记 QA';
const previous=await create('body',{measuredAt:dayAgo(14),heightCm:175,weightKg:74.2,bodyFatPct:23.1,waistCm:84,age:30,sex:'MALE',notes:stamp});
await create('body',{measuredAt:dayAgo(7),heightCm:175,weightKg:73.6,bodyFatPct:22.9,waistCm:83.5,age:30,sex:'MALE',notes:stamp});
let body=await create('body',{measuredAt:today,heightCm:175,weightKg:73.1,bodyFatPct:22.4,waistCm:83,age:30,sex:'MALE',notes:stamp});
assert.equal(body.measuredAt,today+'T00:00');assert.equal(body.bodyFatPct,22.4);assert.equal(body.waistCm,83);
body=await update('body',body,{bodyFatPct:22.3});assert.equal(body.bodyFatPct,22.3);assert.equal(body.heightCm,175);
await req(`/data/body/${body.id}`,{method:'PUT',body:{version:1,weightKg:99},status:409});
await req('/data/body',{method:'POST',body:{id:crypto.randomUUID(),measuredAt:today,bodyFatPct:101},status:400});
const sparse=await create('body',{measuredAt:today,bodyFatPct:20});assert.equal(sparse.sex,'UNSPECIFIED');assert.equal(sparse.heightCm,null);assert.equal(sparse.weightKg,null);
await req(`/data/body/${sparse.id}?version=${sparse.version}`,{method:'DELETE'});
let trash=await req('/data/body?deleted=true');let trashed=trash.find(r=>r.id===sparse.id);assert.ok(trashed);
const restored=await req(`/data/body/${sparse.id}/restore`,{method:'POST',body:{version:trashed.version}});assert.equal(restored.bodyFatPct,20);
await req(`/data/body/${restored.id}?version=${restored.version}`,{method:'DELETE'});
const product=await create('products',{kind:'ALCOHOL',brand:'QA示例',name:'补记测试啤酒',bottleMl:500,abv:5,packagePrice:6,priceConfirmed:true,priceType:'PERSONAL',active:true});
const oldEmpty=await create('sessions',{startedAt:dayAgo(180)+'T20:00:00',timePrecision:'DATE',context:'QA 旧缺项'});
const session=await create('sessions',{startedAt:dayAgo(1)+'T20:00:00',timePrecision:'APPROX',context:'QA事后补记'});
const drink=await create('drinks',{sessionId:session.id,productId:product.id,occurredAt:dayAgo(1)+'T20:00:00',timePrecision:'APPROX',inputMode:'BOTTLES',bottles:1,share:1});
assert.equal(drink.ethanolGrams,19.725);assert.equal(drink.cost,6);
const smoke=await create('smoking',{occurredAt:dayAgo(1)+'T20:00:00',timePrecision:'APPROX',count:1,source:'GIFT'});
const report=await req(`/analytics?from=${dayAgo(6)}&to=${today}`);
assert.equal(report.alcohol.status,'ESTIMATE');assert.equal(report.alcohol.modelVersion,'watson-scenarios-2.0');
assert.equal(drink.timePrecision,'APPROX','本次补记的时间精度应保持不变');
// 复用的 QA 库可能已有只记日期的饮酒，整体估算需要保留更宽的输入范围。
if(report.alcohol.timePrecision==='DATE'){
 const allDrinks=await req('/data/drinks');
 assert.ok(allDrinks.some(r=>r.timePrecision==='DATE'&&r.ethanolGrams!==0&&String(r.occurredAt).slice(0,10)>=String(report.alcohol.scope.from).slice(0,10)&&String(r.occurredAt).slice(0,10)<=String(report.alcohol.scope.to).slice(0,10)),'整体日期精度必须有范围内的只记日期饮酒依据');
}else assert.equal(report.alcohol.timePrecision,'APPROX');
assert.ok(Number.isInteger(report.alcohol.durationHoursLow));assert.ok(Number.isInteger(report.alcohol.durationHoursHigh));assert.ok(report.alcohol.historicalMissingDetails.some(i=>i.id===oldEmpty.id));
assert.equal(report.smokingLinks[smoke.id].effective,'UNKNOWN','Overlapping approximate times must not assert order');
const empty=await create('sessions',{startedAt:today+'T00:00:00',timePrecision:'DATE',context:'QA 待补酒品入口'});
const blocked=await req(`/analytics?from=${dayAgo(6)}&to=${today}`);assert.equal(blocked.alcohol.status,'INSUFFICIENT_DATA');assert.ok(blocked.alcohol.missingDetails.some(i=>i.sessionId===empty.id&&i.collection==='sessions'));
const backup=await req('/backup');const preview=await req('/backup/preview',{method:'POST',body:backup});assert.equal(preview.valid,true);
const bootstrap=await req('/bootstrap');assert.ok(bootstrap.schema.find(s=>s.name==='body').fields.find(f=>f.key==='bodyFatPct'));assert.ok(bootstrap.schema.find(s=>s.name==='drinks').fields.find(f=>f.key==='timePrecision').options.some(o=>o.value==='APPROX'));
await req('/auth/logout',{method:'POST'});
await writeFile(new URL('../.local/body-check-result.json',import.meta.url),JSON.stringify({passed:true,requests,bodyId:body.id,previousId:previous.id,emptySessionId:empty.id,productId:product.id,verifiedAt:new Date().toISOString()},null,2));
console.log(`PASS: ${requests} requests; body dates/CRUD/recycle/validation/backup, approximate estimates and repair pointers verified on isolated QA.`);
