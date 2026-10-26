import {state} from './api'
import type {Row} from './types'
import {money,shanghaiDate} from './types'

export function productName(product?:Row){
 if(!product)return '请选择种类'
 const parts=[product.brand,product.name,product.variant].filter(Boolean) as string[]
 return parts.filter((part,index)=>!parts.some((other,j)=>j!==index&&other.includes(part)&&(other!==part||j<index))).join(' · ')
}
// Normalize only an exactly repeated historical label; never substitute today's catalog name.
export function recordName(value:unknown,fallback='未选种类'){
 let name=String(value??'').trim()
 if(!name)return fallback
 let previous:string
 do{previous=name;name=name.replace(/^(.+?)(?:\s+|\s*[·•]\s*)\1$/u,'$1')}while(name!==previous)
 return name
}
export function recordCostLabel(row:Row,collection:string){
 if(row.cost!=null)return money(row.cost)
 if(!row.productId)return '待选种类'
 const product=state.collections.products?.find(p=>p.id===row.productId)
 const historicalSpec=Number(collection==='smoking'?row.snapshotUnitCount:row.snapshotBottleMl??row.bottleMl)
 const currentSpec=Number(collection==='smoking'?product?.unitCount:product?.bottleMl)
 if(row.unitPrice==null&&row.snapshotUnitPrice==null&&historicalSpec>0&&currentSpec>0&&historicalSpec!==currentSpec)return '待核对规格'
 if(collection==='smoking'&&row.unitPrice==null&&row.snapshotUnitPrice==null&&!(Number(row.snapshotUnitCount)>0))return '待核对规格'
 if(collection==='drinks'&&row.inputMode==='ML'&&!(Number(row.snapshotBottleMl)>0))return '待核对规格'
 return '待补价格'
}
export function isOnlyMissingCostRepair(before:Row,after?:Row){
 if(!after||before.id!==after.id||before.cost!=null||after.cost==null||after.version<=before.version)return false
 if(before.snapshotUnitCount!=null&&before.snapshotUnitCount!==after.snapshotUnitCount)return false
 // _kind is added only by the dashboard timeline; it is never a persisted record field.
 const allowed=new Set(['_kind','version','updatedAt','cost','costType','snapshotUnitPrice','snapshotPriceType','snapshotPriceSourceId','snapshotPriceEffectiveDate','snapshotPriceCurrency','snapshotUnitCount'])
 return [...new Set([...Object.keys(before),...Object.keys(after)])].every(key=>allowed.has(key)||JSON.stringify(before[key]??null)===JSON.stringify(after[key]??null))
}
export function productPrice(product?:Row,date=shanghaiDate()):{amount:number|null;confirmed:boolean;type:string}{
 if(!product)return{amount:null,confirmed:false,type:'PERSONAL'}
 if(product.packagePrice!=null)return{amount:Number(product.packagePrice),confirmed:product.priceConfirmed===true,type:product.priceType||'PERSONAL'}
 const priorities:Record<string,number>={ACTUAL:0,PERSONAL:1,REFERENCE:2}
 const prices=(state.collections.prices||[]).filter(p=>p.productId===product.id&&p.currency==='CNY'&&String(p.effectiveDate)<=date).sort((a,b)=>Number(b.verified===true)-Number(a.verified===true)||(priorities[a.priceType]??3)-(priorities[b.priceType]??3)||String(b.effectiveDate).localeCompare(String(a.effectiveDate))||String(b.updatedAt).localeCompare(String(a.updatedAt)))
 const latest=prices[0];return{amount:latest?.amount==null?null:Number(latest.amount),confirmed:product.priceConfirmed!==false&&latest?.verified===true,type:latest?.priceType||'PERSONAL'}
}
export function productOption(product:Row){const price=productPrice(product);return`${productName(product)} · ${price.confirmed?money(price.amount):'单价待确认'} / ${product.kind==='SMOKING'?'包':'瓶'}`}
export const equivalent42=(ethanolGrams:number|null)=>ethanolGrams==null?null:ethanolGrams/(0.789*0.42)
