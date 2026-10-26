import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {qaBase as base,qaPassword as password} from './qa-config.mjs';
let cookie='',csrf='',requests=0;
async function req(path,body){const r=await fetch(base+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json','X-CSRF-Token':csrf},body:body?JSON.stringify(body):undefined});if(r.headers.getSetCookie().length)cookie=r.headers.getSetCookie().map(v=>v.split(';')[0]).join('; ');const data=await r.json();assert.equal(r.status,200);requests++;return data}
csrf=(await req('/auth/status')).csrfToken;csrf=(await req('/auth/login',{password})).csrfToken;
const {collections}=await req('/bootstrap');
const fields=['smokingCount','alcoholSessions','alcoholMl','ethanolGrams','ethanolEquivalent42Ml','intimacyCount','consumptionCost'];
const empty=await req('/analytics?from=2026-09-01&to=2026-09-07');
for(const row of [...empty.daily,empty.totals])for(const key of fields)assert.equal(row[key],0,key);
assert.ok(empty.daily.every(d=>d.hasSmokingRecords===false&&d.hasAlcoholRecords===false&&d.hasIntimacyRecords===false));
assert.ok(!empty.warnings.some(w=>w.includes('尚未确认')));
const date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const week=await req(`/analytics?from=2026-10-18&to=${date}`);
for(const row of week.daily){if(!row.hasSmokingRecords)assert.equal(row.smokingCount,0);if(!row.hasIntimacyRecords)assert.equal(row.intimacyCount,0);if(!row.hasAlcoholRecords)assert.equal(row.ethanolEquivalent42Ml,0)}
const tomorrow=new Date(date+'T12:00:00+08:00');tomorrow.setUTCDate(tomorrow.getUTCDate()+1);const futureDate=tomorrow.toISOString().slice(0,10);
const future=await req(`/analytics?from=${futureDate}&to=${futureDate}`);assert.equal(future.daily[0].isFuture,true);for(const key of fields)assert.equal(future.daily[0][key],null,key);
const emptySession=collections.sessions.find(s=>!collections.drinks.some(d=>d.sessionId===s.id)&&!collections.drinks.some(d=>String(d.occurredAt).slice(0,10)===String(s.startedAt).slice(0,10)));
assert.ok(emptySession);const sessionDate=String(emptySession.startedAt).slice(0,10);const missing=await req(`/analytics?from=${sessionDate}&to=${sessionDate}`);assert.equal(missing.daily[0].alcoholMl,null);assert.ok(missing.daily[0].alcoholSessions>0);
await req('/auth/logout',{});await writeFile(new URL('../.local/zero-days-result.json',import.meta.url),JSON.stringify({passed:true,requests,emptyDays:7,futureExcluded:true,missingDetailsStillUnknown:true,verifiedAt:new Date().toISOString()},null,2));console.log(`PASS: ${requests} QA requests; empty days/ranges are zero, future days stay blank, incomplete drinking stays unknown.`);
