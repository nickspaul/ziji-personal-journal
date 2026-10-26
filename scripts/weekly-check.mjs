import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {qaBase as base,qaPassword as password} from './qa-config.mjs';
let cookie='',csrf='',requests=0;
async function req(path,{method='GET',body,status=200}={}){
 const r=await fetch(base+path,{method,headers:{Cookie:cookie,...(method==='GET'?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf})},body:body===undefined?undefined:JSON.stringify(body)});
 const set=r.headers.getSetCookie();if(set.length)cookie=set.map(v=>v.split(';')[0]).join('; ');
 const data=await r.json();assert.equal(r.status,status,`${method} ${path}: ${JSON.stringify(data)}`);requests++;return data;
}
const authState=await req('/auth/status');csrf=authState.csrfToken;
await req('/analytics/summary',{status:401});
const auth=await req('/auth/login',{method:'POST',body:{password}});csrf=auth.csrfToken;
const now=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date()).replace(' ','T');
const today=now.slice(0,10),start=new Date(today+'T12:00:00+08:00');start.setDate(start.getDate()-6);
const from=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(start);
const {collections}=await req('/bootstrap');
const lifetime=await req('/analytics/summary'),week=await req(`/analytics?from=${from}&to=${today}`);
const rows=(kind,weekly=false)=>(collections[kind]||[]).filter(r=>!r.deleted&&String(r.occurredAt||r.startedAt||r.date)<=now&&(!weekly||String(r.occurredAt||r.startedAt||r.date).slice(0,10)>=from));
const sum=(rs,key)=>rs.reduce((n,r)=>n+Number(r[key]||0),0),near=(actual,expected)=>assert.ok(Math.abs(Number(actual)-expected)<0.011,`${actual} != ${expected}`);
for(const [report,weekly] of [[lifetime,false],[week,true]]){
 const totals=report.totals;
 near(totals.smokingCount,sum(rows('smoking',weekly),'count'));
 near(totals.intimacyCount,sum(rows('intimacy',weekly),'count'));
 near(totals.alcoholMl,sum(rows('drinks',weekly),'actualMl'));
 near(totals.consumptionCost,sum(rows('smoking',weekly),'cost')+sum(rows('drinks',weekly),'cost'));
 near(totals.ethanolEquivalent42Ml,sum(rows('drinks',weekly),'ethanolGrams')/0.33138);
}
assert.equal(week.from,from);assert.equal(week.to,today);assert.equal(week.daily.length,7);
assert.equal(lifetime.to,today);assert.equal('daily' in lifetime,false);assert.equal('links' in lifetime,false);
const dates=['smoking','drinks','intimacy','sessions','completeness'].flatMap(k=>rows(k).map(r=>String(r.occurredAt||r.startedAt||r.date).slice(0,10))).sort();
assert.equal(lifetime.from,dates[0]||null);
assert.ok(lifetime.totals.smokingCount>week.totals.smokingCount,'Historical QA data must be excluded from recent week');
await req('/auth/logout',{method:'POST'});
await writeFile(new URL('../.local/weekly-result.json',import.meta.url),JSON.stringify({passed:true,requests,weekFrom:from,weekTo:today,lifetimeFrom:lifetime.from,verifiedAt:new Date().toISOString()},null,2));
console.log(`PASS: ${requests} read-only QA requests; weekly/lifetime quantities, costs, conversion, dates and access verified.`);
