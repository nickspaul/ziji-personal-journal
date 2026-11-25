import assert from 'node:assert/strict'
import {qaBase,qaPassword} from './qa-config.mjs'

assert.equal(new URL(qaBase).port,'17891')
let cookie='',csrf=''
async function request(path,method='GET',body) {
  const response=await fetch(qaBase+path, {method,headers:{Cookie:cookie,'X-CSRF-Token':csrf,'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body)})
  const cookies=response.headers.getSetCookie()
  if(cookies.length) cookie=cookies.map(v=>v.split(';')[0]).join('; ')
  return {status:response.status,body:await response.json()}
}
async function api(path,method='GET',body,expected=200) {
  const result=await request(path,method,body)
  assert.equal(result.status,expected,`${method} ${path}: ${result.body.message||''}`)
  return result.body
}
const state=await api('/auth/status')
csrf=state.csrfToken
csrf=(await api('/auth/'+(state.configured?'login':'setup'),'POST',{password:qaPassword})).csrfToken
const platform='负债验收-'+crypto.randomUUID().slice(0,8),base='/data/ledger',day='2025-03-10',next='2025-04-10'
const report=async date=>(await api('/export/report?from='+date+'&to='+date+'&sections=ledger')).sections[0].summary
const beforeBuy=await report(day),beforePay=await report(next)
const delta=(after,before,key)=>Math.round(100*(after[key]-before[key]))
const credit={debtKind:'CREDIT',debtPlatform:platform},repayment={debtKind:'REPAYMENT',debtPlatform:platform}
const purchase=await api(base,'POST',{date:day,category:'餐饮',amount:100,notes:platform})
const debit=await api(base,'POST',{date:next,category:'其他',amount:82,notes:platform})
const edit=(row,patch,expected=200)=>api(base+'/'+row.id,'PUT',{version:row.version,...patch},expected)
const remove=(row,expected=200)=>api(base+'/'+row.id+'?version='+row.version,'DELETE',undefined,expected)
const restore=(row,expected=200)=>api(base+'/'+row.id+'/restore','POST',{version:row.version},expected)
const purchaseCredit=await edit(purchase,credit)
const repaid=await edit(debit,{...repayment,debtFee:2,category:'利息手续费'})
assert.equal(repaid.id,debit.id)
assert.equal(delta(await report(day),beforeBuy,'支出（元）'),10000)
assert.equal(delta(await report(day),beforeBuy,'现金流出（元）'),0)
assert.equal(delta(await report(next),beforePay,'支出（元）'),200)
assert.equal(delta(await report(next),beforePay,'现金流出（元）'),8200)
assert.equal(delta(await report(next),beforePay,'期间欠款本金净变动（元）'),-8000)
await remove(purchaseCredit,400)
await edit(purchaseCredit,{debtPlatform:platform+'A'},400)
await edit(repaid,{date:'2025-03-09'},400)
await edit(repaid,{debtFee:83},400)

const payment={date:next,category:'利息手续费',amount:20,...repayment}
const race=await Promise.all([request(base,'POST',{...payment,id:crypto.randomUUID()}),request(base,'POST',{...payment,id:crypto.randomUUID()})])
assert.deepEqual(race.map(r=>r.status).sort(),[200,400])
const final=race.find(r=>r.status===200).body
assert.equal((await api(base,'POST',{...payment,id:final.id})).id,final.id)
const saved=(await api(base)).filter(r=>r.debtPlatform===platform)
assert.equal(saved.length,3)
const backup=await api('/backup')
assert.equal(backup.collections.ledger.filter(r=>r.debtPlatform===platform).length,3)
const incomplete={...repaid,id:crypto.randomUUID(),debtPlatform:platform+'-缺消费'}
assert.equal((await api('/backup/preview','POST',{format:'ziji-backup',schemaVersion:1,collections:{ledger:[incomplete]}})).valid,false)

await remove(final)
const removedRepayment=await remove(repaid)
const removedPurchase=await remove(purchaseCredit)
await restore(removedRepayment,400)
const buyAgain=await restore(removedPurchase)
const paidAgain=await restore(removedRepayment)
await remove(paidAgain)
await remove(buyAgain)
const opening={date:day,category:'期初欠款',amount:300,debtKind:'OPENING',debtPlatform:platform}
const opened=await api(base,'POST',opening)
await api(base,'POST',opening,400)
assert.equal(delta(await report(day),beforeBuy,'支出（元）'),0)
assert.equal(delta(await report(day),beforeBuy,'现金流出（元）'),0)
assert.equal(delta(await report(day),beforeBuy,'期间欠款本金净变动（元）'),30000)
await remove(opened)
console.log('PASS: credit conversion, principal/interest/cash reports, concurrent payments, opening debt, historical date/account edits, recovery and backup validation.')
