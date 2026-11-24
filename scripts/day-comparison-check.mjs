import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import ts from '../frontend/node_modules/typescript/lib/typescript.js'

const source = await readFile(new URL('../frontend/src/dayComparison.ts',import.meta.url),'utf8')
const js = ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText
const {compareDays,matchesScene} = await import('data:text/javascript;base64,'+Buffer.from(js).toString('base64'))
const days = [
  {date:'2025-12-31',hasAlcoholRecords:true,alcoholSessions:2,hasSmokingRecords:true,smokingCount:4},
  // Drinking details continued past midnight, without a new session starting on this date.
  {date:'2026-01-01',hasAlcoholRecords:true,alcoholSessions:0,hasSmokingRecords:false,smokingCount:0},
  {date:'2026-01-02',hasAlcoholRecords:false,alcoholSessions:0,hasSmokingRecords:true,smokingCount:2},
  {date:'2026-01-03',hasAlcoholRecords:false,alcoholSessions:0,hasSmokingRecords:false,smokingCount:0},
  {date:'2026-01-04',isFuture:true,hasAlcoholRecords:false,alcoholSessions:0,hasSmokingRecords:false,smokingCount:0}
]
const trip = {id:'1',title:'看项目',startDate:'2025-12-28',endDate:'2026-01-01',category:'TRAVEL',tags:['出差'],people:['小王']}
const meal = {id:'2',title:'聚餐',startDate:'2026-01-01',endDate:'2026-01-01',category:'MEAL',tags:['聚餐'],people:[]}
const overlap = {...trip,id:'3',startDate:'2026-01-01'}
const logs = [trip,meal,overlap]
const totals = groups => groups.map(g=>[g.days.length,g.activeDays,g.total])
assert.deepEqual(totals(compareDays(days,logs,'trip','','alcohol')),[[2,2,2],[2,0,0]])
assert.deepEqual(totals(compareDays(days,logs,'trip','','smoking')),[[2,1,4],[2,1,2]])
assert.deepEqual(totals(compareDays(days,logs,'meal','','alcohol')),[[1,1,0],[3,1,2]])
assert.deepEqual(totals(compareDays(days,logs,'study','','alcohol')),[[0,0,0],[4,2,2]])
assert.deepEqual(compareDays(days,logs,'person','','alcohol'),[])
assert.deepEqual(totals(compareDays(days,logs,'person','小王','alcohol')),[[2,2,2],[2,0,0]])
assert.deepEqual(totals(compareDays(days,logs,'tag','出差','alcohol')),[[2,2,2],[2,0,0]])
assert.equal(matchesScene({...meal,tags:[]},'meal',''),false)
assert.equal(matchesScene({...meal,tags:[],people:['小林']},'meal',''),true)
assert.equal(matchesScene({...trip,tags:[]},'trip',''),false)
assert.equal(matchesScene({...trip,category:'WORK'},'work',''),true)
const weekdays=compareDays(days,logs,'weekday','','alcohol')
assert.equal(weekdays.length,7)
assert.deepEqual(weekdays[2].days.map(d=>d.date),['2025-12-31'])
assert.deepEqual(weekdays[3].days.map(d=>d.date),['2026-01-01'])
assert.deepEqual(weekdays[6].days,[])
console.log('PASS: weekday and scene comparisons, cross-year spans, empty groups, missing days and overnight sessions.')
