import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import ts from '../frontend/node_modules/typescript/lib/typescript.js'

const source = await readFile(new URL('../frontend/src/ledgerReview.ts',import.meta.url),'utf8')
const js = ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
const {adjacentPeriod, periodHistory, periodKey, cashflow, categoryRanking, debtAccounts, dayGroups, matchingRows} = await import('data:text/javascript;base64,'+Buffer.from(js).toString('base64'))

const rows = [
  {date:'2024-02-01',amount:11.11,direction:'INCOME'},
  {date:'2024-02-29',amount:0.10,direction:'EXPENSE'},
  {date:'2024-02-29',amount:0.20,direction:'EXPENSE'},
  {date:'2024-03-01',amount:3,direction:'EXPENSE'},
  {date:'2023-02-28',amount:9,direction:'EXPENSE'},
]
const date = '2024-02-29'
for (const [period, expected] of [['day',30],['month',30],['year',330]]) {
  const selected = rows.filter(row=>periodKey(row.date,period)===periodKey(date,period))
  assert.equal(cashflow(selected).expense,expected)
}
const leapMonth = periodHistory(rows,date,'month')
assert.equal(leapMonth.length,29)
assert.deepEqual(leapMonth.at(-1), {key:date, expense:30, count:2})
assert.deepEqual(leapMonth[0],{key:'2024-02-01',expense:0,count:1})
assert.deepEqual(leapMonth[1],{key:'2024-02-02',expense:0,count:0})
const week = periodHistory(rows,'2024-03-01','day')
assert.equal(week[0].key,'2024-02-26')
assert.equal(week.at(-1).key,'2024-03-03')
const year = periodHistory(rows,date,'year')
assert.equal(year.length,12)
assert.deepEqual(year[1],{key:'2024-02',expense:30,count:3})
assert.equal(year[2].expense,300)
assert.equal(adjacentPeriod('2025-01-31','month',1),'2025-02-28')
assert.equal(adjacentPeriod(date,'year',1),'2025-02-28')
assert.equal(adjacentPeriod('2026-01-01','day',-1),'2025-12-31')
for (const period of ['day','month','year']) {
  assert.equal(adjacentPeriod('1000-01-01',period,-1),null)
  assert.equal(adjacentPeriod('9999-12-31',period,1),null)
}
const entries = [
  {id:'advance',direction:'EXPENSE',date:'2024-01-01',category:'期初欠款',debtKind:'OPENING',debtPlatform:'支付宝',amount:30},
  {id:'coffee',direction:'EXPENSE',date:'2024-02-04',category:'餐饮',debtKind:'CREDIT',debtPlatform:'支付宝',amount:100},
  {id:'food',direction:'EXPENSE',date:'2024-02-04',category:'餐饮',amount:19.90},
  {id:'book',direction:'EXPENSE',date:'2024-02-05',category:'学习',debtKind:'CREDIT',debtPlatform:'京东',amount:80},
  {id:'pay',direction:'EXPENSE',date:'2024-03-01',category:'利息手续费',debtKind:'REPAYMENT',debtPlatform:'支付宝',amount:62,debtFee:2},
  {id:'final',direction:'EXPENSE',date:'2024-03-02',category:'利息手续费',debtKind:'REPAYMENT',debtPlatform:'支付宝',amount:70},
  {id:'salary',direction:'INCOME',date:'2024-03-04',category:'工资',amount:150},
]
assert.deepEqual(cashflow(entries), {income:15000,expense:20190,net:-5190,paidPrincipal:13000,cashOut:15190})
assert.equal(periodHistory(entries,'2024-03-01','month')[0].expense,200)
assert.equal(periodHistory(entries,'2024-03-01','year')[1].expense,19990)
assert.equal(periodHistory(entries,'2024-03-01','day').find(r=>r.key==='2024-03-02').expense,0)
assert.deepEqual(categoryRanking(entries,'EXPENSE'),[['餐饮',11990],['学习',8000],['利息手续费',200]])
assert.deepEqual(matchingRows(entries,'','EXPENSE','利息手续费').map(r=>r.id),['pay'])
assert.deepEqual(matchingRows(entries,'支付宝 还款','DEBT','').map(r=>r.id),['final','pay'])
const fullPay=dayGroups(entries.filter(r=>r.id==='final'))[0]
assert.equal(fullPay.expense,0);assert.equal(fullPay.paidPrincipal,7000)
const byAccount=asOf=>debtAccounts(entries,asOf).map(a=>[a.platform,a.balance])
assert.deepEqual(byAccount('2024-03-01'),[['京东',8000],['支付宝',7000]])
assert.deepEqual(byAccount('2024-03-02'),[['京东',8000],['支付宝',0]])
const feeOnly={...entries[4],debtFee:2,amount:2}
assert.deepEqual(cashflow([feeOnly]),{income:0,expense:200,net:-200,paidPrincipal:0,cashOut:200})
const billsSource=await readFile(new URL('../frontend/src/billReview.ts',import.meta.url),'utf8')
const billsJs=ts.transpileModule(billsSource,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
const {billSuggestions}=await import('data:text/javascript;base64,'+Buffer.from(billsJs).toString('base64'))
for(const debtKind of ['REPAYMENT','OPENING','CREDIT']){
  const repeated=['2024-01-05','2024-02-05','2024-03-05'].map(date=>({...entries[4],debtKind,date}))
  assert.deepEqual(billSuggestions(repeated,'2024-03-10'),[])
}
console.log('Ledger checks passed: calendar bounds, legacy entries, credit purchases, cash out, opening balances, repayments, cash fees, separate platforms, category drilldowns and recurring bill exclusions.')
