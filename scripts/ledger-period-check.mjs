import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import ts from '../frontend/node_modules/typescript/lib/typescript.js'

const source = await readFile(new URL('../frontend/src/ledgerReview.ts',import.meta.url),'utf8')
const js = ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
const {adjacentPeriod, periodHistory, periodKey, cashflow} = await import('data:text/javascript;base64,'+Buffer.from(js).toString('base64'))

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
console.log('Ledger checks passed: day/month/year totals, income-only and empty dates, leap day, cross-month weeks and supported bounds.')
