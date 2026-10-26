import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {qaBase as base,qaPassword as password} from './qa-config.mjs';
// All mutations intentionally target the isolated QA database.
let cookie='',csrf='',checks=0;
async function req(path,{method='GET',body,status=200,headers={}}={}){
 const r=await fetch(base+path,{method,headers:{Cookie:cookie,...(method==='GET'?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf}),...headers},body:body===undefined?undefined:JSON.stringify(body)});
 const set=r.headers.getSetCookie();if(set.length)cookie=set.map(v=>v.split(';')[0]).join('; ');
 const data=await r.json();assert.equal(r.status,status,`${method} ${path}: ${JSON.stringify(data)}`);checks++;return data;
}
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
const marker='日志验收-'+crypto.randomUUID().slice(0,8),day='2026-08-18';
let auth=await req('/auth/status');csrf=auth.csrfToken;
await req('/journal/query?collection=journal',{status:401});
auth=await req('/auth/'+(auth.configured?'login':'setup'),{method:'POST',body:{password}});csrf=auth.csrfToken;
const create=(c,body)=>req('/data/'+c,{method:'POST',body:{id:crypto.randomUUID(),...body}});
const update=(c,row,body)=>req('/data/'+c+'/'+row.id,{method:'PUT',body:{version:row.version,...body}});
const get=(c,id)=>req('/data/'+c+'/'+id);
function query(args={}){const p=new URLSearchParams();for(const [k,v] of Object.entries({collection:'journal',...args}))if(v!=null){for(const a of Array.isArray(v)?v:[v])p.append(k,String(a));}return req('/journal/query?'+p);}
const bootstrapBefore=await req('/bootstrap');assert.ok(bootstrapBefore.schema.some(s=>s.name==='journal'));assert.ok(bootstrapBefore.schema.some(s=>s.name==='tasks'));
assert.ok(!Object.hasOwn(bootstrapBefore.collections,'journal'));assert.ok(!Object.hasOwn(bootstrapBefore.collections,'tasks'));
await req('/data/journal',{method:'POST',body:{title:'unauthorized',startDate:day},headers:{'X-CSRF-Token':'wrong'},status:403});
let task=await create('tasks',{title:marker+' 项目A联调',content:'待完成接口联调',tags:[' #项目A ','项目a'],plannedDate:day,dueDate:'2026-08-20',important:true});
assert.equal(task.status,'TODO');assert.deepEqual(task.tags,['项目a']);
const unplanned=await create('tasks',{title:marker+' 未安排工作',tags:['出差']});assert.equal(unplanned.plannedDate,null);
const planned=await create('tasks',{title:marker+' 未来出差计划',plannedDate:'2099-01-01',tags:['出差']});
let trip=await create('journal',{title:marker+' 杭州出差',content:'客户拜访',category:'TRAVEL',startDate:'2026-08-17',endDate:'2026-08-19',timeMode:'DATE',tags:[' #出差 ','出差','项目A'],people:['小王',' 小王 '],place:'杭州'});
assert.deepEqual(trip.tags,['出差','项目a']);assert.deepEqual(trip.people,['小王']);assert.equal(trip.startTime,null);
const meal=await create('journal',{title:marker+' 和小王吃牛肉面',content:'午饭，讨论项目安排',category:'MEAL',startDate:day,timeMode:'PERIOD',period:'NOON',people:['小王','小李'],place:'学校东门',tags:['出差']});
const progress=await create('journal',{title:marker+' 修改登录接口',content:'接口已完成，联调待继续',category:'WORK',startDate:day,timeMode:'PERIOD',period:'AFTERNOON',taskId:task.id,tags:['项目a']});
assert.equal((await get('tasks',task.id)).status,'TODO','Logging progress must not complete task');
const overnight=await create('journal',{title:marker+' 跨午夜整理资料',startDate:'2026-08-18',endDate:'2026-08-19',timeMode:'EXACT',startTime:'23:00',endTime:'01:00'});assert.equal(overnight.endTime,'01:00');
for(const body of [
 {title:'future',startDate:'2099-01-01'},
 {title:'bad period',startDate:day,timeMode:'PERIOD'},
 {title:'bad range',startDate:day,endDate:'2026-08-17'},
 {title:'midnight missing end date',startDate:day,timeMode:'EXACT',startTime:'23:00',endTime:'01:00'},
 {title:'bad array',startDate:day,tags:'出差'},
 {title:'missing task',startDate:day,taskId:crypto.randomUUID()},
 {title:'derived injection',startDate:day,cost:123}
])await req('/data/journal',{method:'POST',body,status:400});
await req('/data/tasks',{method:'POST',body:{title:'future done',status:'DONE',completedDate:'2099-01-01'},status:400});
const repeated=await req('/data/journal',{method:'POST',body:{id:trip.id,title:trip.title,startDate:trip.startDate}});assert.equal(repeated.version,trip.version);
const unchanged=await update('journal',trip,{title:trip.title});assert.equal(unchanged.version,trip.version);
await req('/data/journal/'+trip.id,{method:'PUT',body:{version:999,title:'should not save'},status:409});
const inside=await query({q:marker,from:day,to:day,tags:['出差']});assert.equal(inside.total,2);assert.ok(inside.items.some(r=>r.id===trip.id));
const dates=await query({q:marker,tags:['出差'],view:'dates'});assert.equal(dates.total,3);assert.deepEqual(dates.dates.map(d=>d.date).sort(),['2026-08-17',day,'2026-08-19']);assert.equal(dates.dates.find(d=>d.date===day).count,2);
assert.equal((await query({q:marker,tags:['出差'],view:'dates',from:day,to:day})).total,1);
assert.equal((await query({q:marker+' #出差'})).total,2);
assert.equal((await query({q:marker,category:'MEAL',person:'小王',place:'东门'})).total,1);
assert.equal((await query({q:marker,tags:['出差','项目a'],tagMode:'all'})).total,1);
assert.equal((await query({q:marker,tags:['出差','项目a'],tagMode:'any'})).total,3);
assert.equal((await query({q:marker,taskId:task.id})).items[0].id,progress.id);
const percent=await create('journal',{title:marker+' 检查100%_完成',startDate:day});
await create('journal',{title:marker+' 检查1000a完成',startDate:day});
assert.equal((await query({q:marker+' 100%_'})).items[0].id,percent.id);assert.equal((await query({q:marker+' 100%_'})).total,1);
assert.equal((await query({q:"' OR 1=1 --"})).total,0);
for(let i=0;i<23;i++)await create('journal',{title:marker+' 翻页记录 '+String(i).padStart(2,'0'),startDate:day,tags:['翻页']});
const p1=await query({q:marker,tags:['翻页'],page:1,size:20}),p2=await query({q:marker,tags:['翻页'],page:2,size:20});
assert.equal(p1.total,23);assert.equal(p1.items.length,20);assert.equal(p2.items.length,3);assert.equal(new Set([...p1.items,...p2.items].map(r=>r.id)).size,23);
const deleted=await req(`/data/journal/${percent.id}?version=${percent.version}`,{method:'DELETE'});
assert.equal((await query({q:marker+' 100%_'})).total,0);assert.equal((await query({q:marker+' 100%_',deleted:true})).total,1);
await req(`/data/journal/${percent.id}/restore`,{method:'POST',body:{version:deleted.version}});
assert.equal((await query({q:marker+' 100%_'})).total,1);
task=await update('tasks',task,{status:'DONE',completedDate:'2026-08-19',result:'验收完成'});
assert.equal((await query({collection:'tasks',q:marker,status:'DONE',dateField:'completedDate',from:'2026-08-19',to:'2026-08-19'})).total,1);
assert.equal((await query({q:marker,taskId:task.id})).total,1,'Completion must not duplicate work logs');
const doneAgain=await update('tasks',task,{status:'DONE'});assert.equal(doneAgain.version,task.version);
task=await update('tasks',task,{status:'DOING'});assert.equal(task.completedDate,null);
assert.equal((await query({collection:'tasks',q:marker,status:'OPEN'})).total,3);
assert.equal((await query({collection:'tasks',q:marker,unscheduled:true})).items[0].id,unplanned.id);
assert.equal((await query({collection:'tasks',q:marker,overdue:true})).items[0].id,task.id);
const deletedTask=await req(`/data/tasks/${task.id}?version=${task.version}`,{method:'DELETE'});
const retained=(await query({q:marker,taskId:task.id})).items[0];assert.equal(retained.id,progress.id);assert.equal(retained.taskDeleted,true);
let oldLog=await get('journal',progress.id);oldLog=await update('journal',oldLog,{content:'任务回收后，实际工作过程仍保留'});assert.equal(oldLog.taskId,task.id);
await req('/data/journal',{method:'POST',body:{title:'cannot link deleted task',startDate:day,taskId:task.id},status:400});
task=await req(`/data/tasks/${task.id}/restore`,{method:'POST',body:{version:deletedTask.version}});
const originalSettings=await req('/settings');
await req('/settings',{method:'PUT',body:{journalPinnedFilters:[{id:crypto.randomUUID(),name:'出差日期',query:{collection:'journal',q:marker,tags:['出差']}}]}});
const renameFrom='验收旧标签-'+marker,renameTo='验收新标签-'+marker;
let renameLog=await create('journal',{title:marker+' 标签合并',startDate:day,tags:[renameFrom,renameTo]});
let renameTask=await create('tasks',{title:marker+' 标签任务',tags:[renameFrom]});
let preview=await req('/journal/tags/preview',{method:'POST',body:{from:renameFrom,to:renameTo}});assert.equal(preview.count,2);
renameLog=await update('journal',renameLog,{content:'让预览过期'});
await req('/journal/tags/rename',{method:'POST',body:{from:renameFrom,to:renameTo,previewToken:preview.previewToken},status:409});
preview=await req('/journal/tags/preview',{method:'POST',body:{from:renameFrom,to:renameTo}});
await req('/journal/tags/rename',{method:'POST',body:{from:renameFrom,to:renameTo,previewToken:preview.previewToken}});
assert.deepEqual((await get('journal',renameLog.id)).tags,[renameTo.toLowerCase()]);assert.deepEqual((await get('tasks',renameTask.id)).tags,[renameTo.toLowerCase()]);
const afterRename=await req('/journal/tags/preview',{method:'POST',body:{from:renameFrom,to:renameTo}});assert.equal(afterRename.count,0);
const facets=await req('/journal/facets');assert.ok(facets.tags.some(t=>t.name==='出差'));assert.ok(facets.people.includes('小王'));
const backup=await req('/backup');assert.ok(backup.collections.journal.some(r=>r.id===trip.id));assert.ok(backup.collections.tasks.some(r=>r.id===task.id));
const bp=await req('/backup/preview',{method:'POST',body:backup});assert.equal(bp.valid,true,JSON.stringify(bp.errors));
const legacy=structuredClone(backup);delete legacy.collections.journal;delete legacy.collections.tasks;delete legacy.settings.journalPinnedFilters;delete legacy.settings.journalView;
assert.equal((await req('/backup/preview',{method:'POST',body:legacy})).valid,true);
const merge=await req('/backup/restore',{method:'POST',body:{backup,mode:'merge'}});assert.equal(merge.imported,0);
await req('/settings',{method:'PUT',body:{journalPinnedFilters:originalSettings.journalPinnedFilters||[],journalView:originalSettings.journalView||'day'}});
const bootstrap=await req('/bootstrap');assert.ok(!Object.hasOwn(bootstrap.collections,'journal'));assert.ok(!Object.hasOwn(bootstrap.collections,'tasks'));
await req('/auth/logout',{method:'POST'});
await writeFile(new URL('../.local/journal-result.json',import.meta.url),JSON.stringify({passed:true,requests:checks,marker,today,ids:{trip:trip.id,task:task.id,meal:meal.id,progress:progress.id,planned:planned.id},finishedAt:new Date().toISOString()},null,2));
console.log(`PASS: ${checks} journal and task API checks on isolated QA database.`);
