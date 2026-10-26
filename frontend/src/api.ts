import { reactive } from 'vue'
import type { Bootstrap, Analytics, Row } from './types'
export const state = reactive({configured:false,authenticated:false,csrfToken:'',checking:true,loading:false,error:'',collections:{} as Record<string,Row[]>,settings:{} as Record<string,any>,schema:[] as Bootstrap['schema'],health:[] as Row[],analytics:{} as Analytics,lifetimeSummary:{} as Analytics,lifetimeLoading:false,lifetimeError:''})
let analyticsRequest=0,lifetimeRequest=0
export async function api<T=any>(path:string,options:RequestInit={}):Promise<T>{
 const headers:Record<string,string>={...(options.headers as Record<string,string>||{})}
 if(options.body&&!headers['Content-Type'])headers['Content-Type']='application/json'
 if(options.method&&options.method!=='GET')headers['X-CSRF-Token']=state.csrfToken
 let res:Response
 try{res=await fetch(`/api${path}`,{credentials:'same-origin',...options,headers})}catch{throw new Error('无法连接本机服务。请确认应用与数据库已启动，然后重试。')}
 const raw=await res.text();let data:any
 try{data=raw?JSON.parse(raw):{}}catch{throw new Error('本机服务未返回有效数据，请检查后端是否启动。')}
 if(!res.ok){if(res.status===401)state.authenticated=false;throw new Error(data.message||`请求未完成（${res.status}），请重试。`)}
 return data as T
}
export async function authStatus(){const a=await api<{configured:boolean;authenticated:boolean;csrfToken:string}>('/auth/status');Object.assign(state,a);state.checking=false;return a}
export async function authenticate(password:string){await api(state.configured?'/auth/login':'/auth/setup',{method:'POST',body:JSON.stringify({password})});await authStatus();await refresh()}
export async function refresh(){state.loading=true;try{const b=await api<Bootstrap>('/bootstrap');state.collections=b.collections||{};state.settings=b.settings||{};state.schema=b.schema||[];state.health=b.health||[];state.error=''}catch(e){state.error=(e as Error).message;throw e}finally{state.loading=false}}
export async function refreshAnalytics(from:string,to:string){const request=++analyticsRequest;if(state.analytics.from!==from||state.analytics.to!==to)state.analytics={from,to};try{const result=await api<Analytics>(`/analytics?from=${from}&to=${to}`);if(request===analyticsRequest&&state.authenticated)state.analytics=result}catch(e){if(request===analyticsRequest&&state.authenticated)state.analytics={from,to,warnings:[(e as Error).message]}}}
export async function refreshLifetimeSummary(){const request=++lifetimeRequest;state.lifetimeLoading=true;state.lifetimeError='';try{const result=await api<Analytics>('/analytics/summary');if(request===lifetimeRequest&&state.authenticated)state.lifetimeSummary=result}catch(e){if(request===lifetimeRequest&&state.authenticated){state.lifetimeSummary={};state.lifetimeError=(e as Error).message}}finally{if(request===lifetimeRequest)state.lifetimeLoading=false}}
export async function saveRow(collection:string,fields:Record<string,any>,row?:Row){return api<Row>(`/data/${collection}${row?`/${row.id}`:''}`,{method:row?'PUT':'POST',body:JSON.stringify({...fields,...(row?{version:row.version}:{id:fields.id||crypto.randomUUID()})})})}
export async function deleteRow(collection:string,row:Row){return api(`/data/${collection}/${row.id}?version=${row.version}`,{method:'DELETE'})}
export async function restoreRow(collection:string,row:Row){return api(`/data/${collection}/${row.id}/restore`,{method:'POST',body:JSON.stringify({version:row.version})})}
export async function saveSettings(values:Record<string,any>){await api('/settings',{method:'PUT',body:JSON.stringify(values)});state.settings={...state.settings,...values}}
