import assert from 'node:assert/strict'
import {qaBase,qaPassword} from './qa-config.mjs'

let cookie='', csrf=''
async function request(path,method='GET',body,expected=200){
  const r=await fetch(qaBase+path,{
    method,headers:{Cookie:cookie,'Content-Type':'application/json','X-CSRF-Token':csrf},
    body:body===undefined?undefined:JSON.stringify(body)
  })
  const session=r.headers.getSetCookie()
  if(session.length)cookie=session.map(item=>item.split(';')[0]).join('; ')
  const data=await r.json()
  assert.equal(r.status,expected,`${method} ${path}: ${JSON.stringify(data)}`)
  return data
}
function shift(day,by){const date=new Date(day+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+by);return date.toISOString().slice(0,10)}
const auth=await request('/auth/status');csrf=auth.csrfToken
const session=await request('/auth/'+(auth.configured?'login':'setup'),'POST',{password:qaPassword});csrf=session.csrfToken
const created=[],name='同行回顾-'+crypto.randomUUID().slice(0,8)
const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const profile=()=>request('/journal/people/recap?'+new URLSearchParams({name}))
async function add(title,startDate,place,extra={}){
  const record=await request('/data/journal','POST',{title,startDate,place,people:[name],category:'MEAL',...extra})
  created.push(record);return record
}
try{
  assert.equal((await profile()).total,0)
  const earlier=await add('合成记录 · 聚餐',shift(today,-7),'东门')
  await add('合成记录 · 吃面',shift(today,-5),'东门')
  const latest=await add('合成记录 · 外出',shift(today,-3),'书店',{endDate:shift(today,-1),category:'SOCIAL'})
  const gone=await add('合成记录 · 已撤销',today,'其他')
  await request('/data/journal/'+gone.id+'?version='+gone.version,'DELETE')
  created.pop()
  const recap=await profile()
  assert.equal(recap.total,3);assert.equal(recap.recent[0].id,latest.id)
  assert.deepEqual(recap.places,[{name:'东门',count:2},{name:'书店',count:1}])
  assert.ok(recap.recent.some(r=>r.id===earlier.id))
  const picked=await request('/journal/memory?scope=outings')
  assert.ok(picked.row.people.length>0);assert.ok(['MEAL','SOCIAL','TRAVEL'].includes(picked.row.category))
  assert.ok((picked.row.endDate||picked.row.startDate)<today)
  const next=await request('/journal/memory?scope=outings&previousId='+picked.row.id)
  assert.notEqual(next.row.id,picked.row.id)
  const {day}=await request('/journal/memory?scope=last-year')
  assert.equal(day.slice(0,4),String(Number(today.slice(0,4))-1))
  await add('合成记录 · 去年今天',shift(day,-1),'学校',{endDate:shift(day,1)})
  const anniversary=await request('/journal/memory?scope=last-year')
  assert.ok(anniversary.row.startDate<=day && (anniversary.row.endDate||anniversary.row.startDate)>=day)
  await request('/journal/memory?scope=private','GET',undefined,400)
  console.log('PASS: person recap, recurring places, hidden trash, random outings and anniversary intervals.')
}finally{
  for(const row of created)await request('/data/journal/'+row.id+'?version='+row.version,'DELETE')
  await request('/auth/logout','POST')
}
