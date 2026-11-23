export type Row = {id:string;version:number;createdAt?:string;updatedAt?:string;deleted?:boolean;[key:string]:any}
export type Field = {key:string;label:string;type:string;required?:boolean;options?:{value:string;label:string}[]}
export type CollectionSchema = {name:string;label:string;fields:Field[]}
export type Bootstrap = {collections:Record<string,Row[]>;settings:Record<string,any>;schema:CollectionSchema[];health:Row[]}
export type TrendPoint = {date:string;smokingCount:number;ethanolEquivalent42Ml:number|null;intimacyCount:number}
export type Analytics = {daily?:any[];trend?:{unit:'day'|'month'|'year';points:TrendPoint[]};totals?:Record<string,any>;alcohol?:Record<string,any>;links?:Record<string,any>|any[];warnings?:any[];[key:string]:any}
export const privateActivityLabels = { MASTURBATION: '自慰', SEX: '性生活' } as const
export type PrivateActivityType = keyof typeof privateActivityLabels
export function privateActivityLabel(type: unknown) {
  if (type == null || type === 'MASTURBATION') return privateActivityLabels.MASTURBATION
  return type === 'SEX' ? privateActivityLabels.SEX : '类型待核对'
}
export const kindLabels:Record<string,string> = {SMOKING:'吸烟',ALCOHOL:'饮酒',INTIMACY:'私人行为'}
export const collectionLabels:Record<string,string> = {ledger:'收支明细',journal:'生活日志',tasks:'待办任务',products:'商品资料',prices:'价格历史',purchases:'购买流水',smoking:'吸烟记录',sessions:'饮酒场次',drinks:'饮酒明细',intimacy:'私人记录',body:'身体测量',health:'健康资料'}
export const shanghaiDate = () => new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date())
export const localNow = () => new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date()).replace(' ','T')
export const money = (value:any) => value==null?'待补价格':`¥${Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`
export const number = (value:any,max=1) => Number(value||0).toLocaleString('zh-CN',{maximumFractionDigits:max})
export const shortTime = (value:any) => String(value||'').slice(11,16)||'时间待补'
export const shortDate = (value:any) => String(value||'').slice(0,10).replaceAll('-','.')
