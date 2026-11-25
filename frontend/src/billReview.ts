import type {LedgerRow} from './ledgerReview'

export type BillSuggestion={title:string;category:string;amount:number;monthDay:number;nextDue:string;dates:string[]}
export function monthlyDate(month:string,day:number) {
  if(!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)||!Number.isInteger(day)||day<1||day>31)return ''
  const [year,m]=month.split('-').map(Number)
  const length=new Date(Date.UTC(year,m,0)).getUTCDate()
  return month+'-'+String(Math.min(length,day)).padStart(2,'0')
}
const index=(date:string)=>Number(date.slice(0,4))*12+Number(date.slice(5,7))-1
function monthText(i:number) {return Math.floor(i/12)+'-'+String(i%12+1).padStart(2,'0')}
export function billSuggestions(rows:LedgerRow[],today:string):BillSuggestion[] {
  const groups=new Map<string,LedgerRow[]>()
  for(const row of rows) {
    if(row.deleted || row.debtKind || row.direction!=='EXPENSE' || row.date>today || index(row.date)<index(today)-4)continue
    const title=row.notes?.trim() || row.category
    if(title.length>100 || /[\r\n]/.test(title))continue
    const key=JSON.stringify([title,row.category,Math.round(Number(row.amount)*100)])
    const group=groups.get(key)||[]
    group.push(row)
    groups.set(key,group)
  }
  const suggestions:BillSuggestion[]=[]
  for(const group of groups.values()) {
    group.sort((a,b)=>b.date.localeCompare(a.date))
    const last=group[0],end=index(last.date)
    if(end<index(today)-1)continue
    const latest=group.filter(row=>index(row.date)>=end-2)
    if(latest.length!==3 || latest.some((row,i)=>index(row.date)!==end-i))continue
    const dates=latest.map(r=>r.date)
    const days=dates.map(d=>Number(d.slice(-2)))
    const monthEnd=dates.every(d=>d===monthlyDate(d.slice(0,7),31))
    if(!monthEnd && Math.max(...days)-Math.min(...days)>3)continue
    const monthDay=monthEnd?31:[...days].sort((a,b)=>a-b)[1]
    suggestions.push({title:last.notes?.trim()||last.category,category:last.category,amount:Number(last.amount),monthDay,
      nextDue:monthlyDate(monthText(end+1),monthDay),dates})
  }
  return suggestions.sort((a,b)=>a.nextDue.localeCompare(b.nextDue)||a.title.localeCompare(b.title,'zh-CN'))
}
