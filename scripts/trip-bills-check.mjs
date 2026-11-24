import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import ts from '../frontend/node_modules/typescript/lib/typescript.js'
import {qaBase,qaPassword} from './qa-config.mjs'

const src=await readFile(new URL('../frontend/src/billReview.ts',import.meta.url),'utf8')
const js=ts.transpileModule(src,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
const {billSuggestions,monthlyDate}=await import('data:text/javascript;base64,'+Buffer.from(js).toString('base64'))
const examples=['2024-01-31','2024-02-29','2024-03-31'].map(date=>({date,amount:9.9,category:'通信',notes:'月租',direction:'EXPENSE'}))
const [suggestion]=billSuggestions(examples,'2024-03-31')
assert.equal(suggestion.monthDay,31);assert.equal(suggestion.nextDue,'2024-04-30')
assert.equal(monthlyDate('2024-02',31),'2024-02-29')
assert.deepEqual(billSuggestions([...examples,examples[1]],'2024-03-31'),[])
assert.deepEqual(billSuggestions(examples.slice(1),'2024-03-31'),[])

let cookie='',csrf='',requests=0
async function api(path,method='GET',body,expected=200) {
  const res=await fetch(qaBase+path,{method,headers:{Cookie:cookie,'Content-Type':'application/json','X-CSRF-Token':csrf},body:body===undefined?undefined:JSON.stringify(body)})
  const cookies=res.headers.getSetCookie()
  if(cookies.length)cookie=cookies.map(v=>v.split(';')[0]).join('; ')
  const result=await res.json()
  assert.equal(res.status,expected,method+' '+path+' '+(result.message||''))
  requests++
  return result
}
const auth=await api('/auth/status')
csrf=auth.csrfToken
csrf=(await api('/auth/'+(auth.configured?'login':'setup'),'POST',{password:qaPassword})).csrfToken
const mark='行程扣款测试-'+crypto.randomUUID().slice(0,8)
const create=(c,body)=>api('/data/'+c,'POST',body)
const trip=await create('journal',{title:mark+' 出差',category:'TRAVEL',startDate:'2025-03-10',endDate:'2025-03-12'})
const meeting=await create('journal',{title:mark+' 开会',category:'WORK',startDate:'2025-03-11'})
const a=await create('ledger',{date:'2025-03-10',amount:'20.20',category:'交通',notes:mark})
const b=await create('ledger',{date:'2025-03-12',amount:'70.10',category:'住宿',notes:mark})
const c=await create('ledger',{date:'2025-03-11',amount:'10',category:'会员',notes:mark})
const out=await create('ledger',{date:'2025-03-13',amount:'5',category:'其他',notes:mark})
const base='/trips/'+trip.id
const view=await api(base)
assert.ok(view.logs.some(row=>row.id===meeting.id));assert.ok(!view.expenses.some(row=>row.id===out.id))
const options=await api('/trips/options?date=2025-03-11')
assert.ok(options.some(row=>row.id===trip.id))
const select=(row,reimbursement)=>({id:row.id,version:row.version,reimbursement})
const apply=(changes,status)=>api(base+'/expenses','PUT',{version:trip.version,changes},status)
await apply([select(a,'SELF'),select(out,'PENDING')],409)
assert.equal((await api('/data/ledger/'+a.id)).tripId,null)
const changed=await apply([select(a,'SELF'),select(b,'PENDING')])
const actual=changed.expenses.filter(row=>row.tripId===trip.id)
assert.equal(actual.length,2);assert.equal(actual.reduce((sum,r)=>sum+Math.round(r.amount*100),0),9030)
assert.equal((await api('/data/ledger/'+c.id)).tripId,null)
await apply([select(b,'REIMBURSED')],409)
await apply([select(actual.find(r=>r.id===b.id),'REIMBURSED')])
const actualB=await api('/data/ledger/'+b.id)
assert.equal(actualB.reimbursement,'REIMBURSED')
await apply([select(actualB,null)])
assert.equal((await api('/data/ledger/'+b.id)).tripId,null)

const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())
const rule=await create('bills',{title:mark+' 月租',category:'通信',amount:'39.90',nextDue:today,monthDay:Number(today.slice(-2))})
const payment={version:rule.version,dueDate:today,paidDate:today,skip:false}
const [first,retry]=await Promise.all([api('/bills/'+rule.id+'/settle','POST',payment),api('/bills/'+rule.id+'/settle','POST',payment)])
assert.equal(first.ledgerId,retry.ledgerId);assert.ok(first.bill.nextDue>today)
const posted=(await api('/data/ledger')).filter(r=>r.notes===mark+' 月租')
assert.equal(posted.length,1);assert.equal(posted[0].date,today);assert.equal(Number(posted[0].amount),39.9)
const skip=await create('bills',{title:mark+' 跳过',category:'休闲',amount:'10',nextDue:today,monthDay:Number(today.slice(-2))})
await api('/bills/'+skip.id+'/settle','POST',{...payment,version:skip.version,skip:true})
assert.ok(!(await api('/data/ledger')).some(e=>e.notes===mark+' 跳过'))
await api('/auth/logout','POST')
console.log('PASS '+requests+' isolated QA requests, including trip transaction rollback, confirmed monthly expenses and concurrent retries.')
