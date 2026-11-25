import type {Field, Row} from './types'
import {expenseCents,debtDelta,debtLabels} from './ledgerReview'
import type {LedgerRow} from './ledgerReview'

export const dataGroups = [
  {label:'日常记录', collections:['smoking','sessions','drinks','intimacy','journal','tasks','ledger','bills']},
  {label:'种类与身体', collections:['products','body']},
  {label:'参考资料', collections:['prices','health']},
  {label:'兼容数据', collections:['purchases'], legacy:true},
]

export const dataGuide:Record<string,{label:string;summary:string;usage:string;rules:string[]}> = {
  bills:{label:'固定账单',summary:'每月待支付的固定支出。',usage:'在日常记账里查看即将扣款的账单，确认实付后生成流水。',rules:['不足指定扣款日的月份取月末，次月仍按原扣款日计算。','跳过本期只推进扣款日期，已经手动入账的支出可以用此方式处理。']},
  ledger:{label:'收支明细',summary:'记录收支、信用消费与还款。',usage:'用于各账期收支、分类排行和分平台欠款。',rules:['购买当日确认消费；还款只将利息或手续费计入支出。期初欠款只增加本金。','烟酒按用量估算的费用单独显示，只有手动录入的实际付款才计入账本。']},
  smoking:{label:'吸烟记录',summary:'按根数记录，保留当时的单价与烟盒标示。',usage:'用于吸烟趋势、按用量费用、烟盒成分汇总和饮酒关联。',rules:['费用 = 根数 × 保存时的单根价格；单根价格通常由每包价格 ÷ 每包根数得到。别人提供的香烟，个人费用为 0。','焦油等合计 = 标示值 × 根数，仅表示标签值汇总，不代表身体吸收量。','饮酒关联按已录入的时间、时间精度和观察窗口判断；没有找到饮酒记录不等于确定没有喝酒。']},
  sessions:{label:'饮酒场次',summary:'一场聚餐或一次饮酒，对应一条场次和若干酒品明细。',usage:'用于统计饮酒次数、组织酒品明细，并提供饮酒开始和结束时间。',rules:['场次本身不保存酒量、单价或酒精量，这些来自关联的饮酒明细。','有场次却没有明细时，次数仍会统计，但无法知道喝了多少。']},
  drinks:{label:'饮酒明细',summary:'记录每种酒的本人用量，计算费用与纯酒精量。',usage:'用于饮酒量、烟酒费用、42° 白酒换算和酒精情景估算。',rules:['按瓶：本人饮用 ml = 瓶数 × 每瓶 ml × 本人比例。按毫升：直接采用填写的本人饮用 ml。','费用 = 本人折合瓶数 × 保存时的每瓶单价。纯酒精 g = 本人 ml × 酒精度 ÷ 100 × 0.789。','折合 42° 白酒 ml = 纯酒精 g ÷ 0.789 ÷ 0.42。它表示相同酒精量，不表示身体反应相同。']},
  intimacy:{label:'私人记录',summary:'分别记录自慰和性生活的次数，可按类型搜索并核对饮酒关联。',usage:'用于次数趋势和饮酒关联分析。',rules:['本人确认的酒后状态优先于自动判断；自动判断依赖饮酒记录和时间精度。','次数只用于回看，不设正常次数评分。']},
  journal:{label:'生活日志',summary:'按日期和时段查找做过的事，支持标签和关联待办。',usage:'用于生活回顾、工作学习检索和待办投入时长。',rules:['只记日期或时段也能保存；时长未填写时保留为未知。','关联同一待办的具体时间段去除重叠后累计，手填时长单独相加。']},
  tasks:{label:'全局待办',summary:'统一维护待办、截止日期、完成日期和累计投入。',usage:'待办不受某一天限制；关联日志用于汇总全部历史投入。',rules:['截止日期是计划期限，实际完成日期记录哪天完成。','投入时长来自关联日志，查看待办详情可追溯每一次投入。']},
  products:{label:'商品与单价',summary:'维护烟酒种类、规格和已确认单价，新增记录时自动带入。',usage:'为新记录提供品牌、单价、容量、酒精度和烟盒标示。',rules:['确认单价后用于新记录；价格缺失不会当成 0 元。','已有费用保留原值；历史缺失费用在规格匹配时可补齐。要修正已有费用或规格，先预览历史重算。']},
  body:{label:'身体测量',summary:'保存每次身高、体重、体脂率和腰围，可在身体页看趋势。',usage:'用于身体仪表盘，以及具备必要资料时的酒精情景估算。',rules:['BMI = 体重 kg ÷ 身高 m 的平方，使用同一条测量中的身高和体重。','体脂率、腰围是你维护的测量值；不根据 BMI 推算。酒精情景估算的资料选择和限制见行为分析页。']},
  prices:{label:'价格历史',summary:'保存历史与参考价格；日常改价请编辑“商品与单价”。',usage:'用于价格来源追溯、兼容旧记录和显式历史重算。',rules:['新记录优先使用商品已确认单价。未设置确认信息的旧商品可能采用已确认的历史价格。','在这里新增价格，不会替换商品当前单价或静默覆盖已有费用。']},
  health:{label:'健康参考说明',summary:'行为分析页引用的说明文字及资料来源。',usage:'用于解释和参考阅读，不是你的体检档案，也不参与数值计算。',rules:['修改后会更新行为分析页的参考内容。身体指标请在“身体测量”维护。','来源链接和复核日期便于追溯，不代表系统会自动核实或更新内容。']},
  purchases:{label:'旧版购买流水',summary:'保留早期购买资料，日常使用无需填写。',usage:'兼容旧购买批次关联；主页烟酒花费按用量与单价计算。',rules:['购买金额与消耗费用含义不同，购买金额不替代当前按用量费用。','保留原有数据及编辑、回收站功能，避免丢失历史来源。']},
}

export const preferredColumns:Record<string,string[]> = {
  bills:['title','amount','category','nextDue','monthDay'],
  ledger:['date','direction','category','amount','expensePosted','debtPlatform'],
  smoking:['occurredAt','productId','count','source'], sessions:['startedAt','endedAt','context','notes'],
  drinks:['occurredAt','productId','actualMl','snapshotAbv','ethanolEquivalent42Ml'],
  intimacy:['occurredAt','activityType','count','moodBefore','moodAfter','manualAlcohol'],
  products:['kind','brand','name','variant','packagePrice','priceConfirmed'], body:['measuredAt','heightCm','weightKg','bodyFatPct','bmi'],
  prices:['productId','amount','priceType','effectiveDate','verified'], purchases:['occurredAt','productId','quantity','amount','payer'],
  health:['title','topic','reviewedAt'],
}
export const derivedColumns:Record<string,Field[]> = {
  ledger:[{key:'expensePosted',label:'计入支出（元）',type:'number'},{key:'principalMovement',label:'欠款本金变动（元）',type:'number'}],
  body:[{key:'bmi',label:'BMI',type:'number'}],
  drinks:[{key:'actualMl',label:'本人饮用 ml',type:'number'},{key:'snapshotAbv',label:'采用酒精度 %',type:'number'},{key:'ethanolEquivalent42Ml',label:'折合42°白酒 ml',type:'number'}],
}
const priceLabels:Record<string,string> = {PERSONAL:'个人设定',ACTUAL:'实际单价',REFERENCE:'参考估算',OVERRIDE:'本条手填单价',GIFT:'别人提供',PAID_BY_OTHER:'他人付款',UNKNOWN:'资料不足'}
const n=(v:unknown,digits=4)=>v==null||v===''?'待补':Number(v).toLocaleString('zh-CN',{maximumFractionDigits:digits})
const money=(v:unknown)=>v==null?'待补价格':`¥${Number(v).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`
export function valueFor(row:Row,field:Field,collections:Record<string,Row[]>):string {
  let v=row[field.key]
  if(field.key==='direction' && row.debtKind) return debtLabels[row.debtKind as keyof typeof debtLabels] || '支出'
  if(field.key==='expensePosted')return money(expenseCents(row as LedgerRow)/100)
  if(field.key==='principalMovement')return money(debtDelta(row as LedgerRow)/100)
  if(field.key==='bmi') v=row.heightCm>0&&row.weightKg>0?row.weightKg/(row.heightCm/100)**2:null
  if(field.key==='ethanolEquivalent42Ml') v=row.ethanolEquivalent42Ml??(row.ethanolGrams==null?null:row.ethanolGrams/0.33138)
  if(v==null||v==='')return '—'
  if(field.type==='product'){
    if(row.snapshotName)return String(row.snapshotName).replace(/^(.+?)\s+\1$/u,'$1')
    const product=collections.products?.find(p=>p.id===v)
    if(!product)return '已停用商品'
    const parts=[product.brand,product.name,product.variant].filter(Boolean) as string[]
    return parts.filter((p,i)=>!parts.some((q,j)=>j!==i&&q.includes(p)&&(q!==p||j<i))).join(' · ')
  }
  if(field.type==='session')return String(collections.sessions?.find(s=>s.id===v)?.startedAt||'已停用场次').replace('T',' ').slice(0,16)
  if(field.type==='purchase'){const purchase=collections.purchases?.find(p=>p.id===v);return purchase?`${String(purchase.occurredAt).slice(0,10)} · ${money(purchase.amount)}`:'已停用购买记录'}
  if(field.type==='boolean')return v?'是':'否'
  if(field.type==='select')return field.options?.find(o=>o.value===v)?.label||String(v)
  if(field.type==='datetime')return String(v).replace('T',' ').slice(0,16)
  if(field.type==='date')return String(v).slice(0,10)
  if(['amount','unitPrice','packagePrice','cost'].includes(field.key))return money(v)
  if(['bmi','ethanolEquivalent42Ml'].includes(field.key))return n(v,1)
  if(Array.isArray(v))return v.join('、')
  return String(v)
}
export function matchesRecord(row:Row,fields:Field[],collections:Record<string,Row[]>,query:string){
  const words=query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  const haystack=[...fields.map(f=>valueFor(row,f,collections)),row.id].join(' ').toLocaleLowerCase()
  return words.every(word=>haystack.includes(word))
}
export function recordDate(row:Row){return String(row.occurredAt||row.startedAt||row.measuredAt||row.effectiveDate||row.date||row.nextDue||row.reviewedAt||row.createdAt||'')}
export function compareRecords(a:Row,b:Row,key:string,direction:string,fields:Field[],collections:Record<string,Row[]>){
  const field=fields.find(f=>f.key===key)
  const raw=(r:Row)=>key==='expensePosted'?expenseCents(r as LedgerRow):key==='principalMovement'?debtDelta(r as LedgerRow):key==='recordDate'?recordDate(r):key==='bmi'?(r.heightCm>0&&r.weightKg>0?r.weightKg/(r.heightCm/100)**2:null):key==='ethanolEquivalent42Ml'?(r.ethanolEquivalent42Ml??(r.ethanolGrams==null?null:r.ethanolGrams/0.33138)):field?.type==='number'?r[key]:field?valueFor(r,field,collections):r[key]
  const av=raw(a),bv=raw(b)
  if(av==null||av==='')return bv==null||bv===''?String(a.id).localeCompare(String(b.id)):1
  if(bv==null||bv==='')return -1
  const result=field?.type==='number'?Number(av)-Number(bv):String(av).localeCompare(String(bv),'zh-CN',{numeric:true})
  return (direction==='asc'?1:-1)*result||String(a.id).localeCompare(String(b.id))
}
export function csvCell(value:unknown){
  let text=String(value??'')
  // Quoting alone does not stop spreadsheet applications from executing formulas.
  if(/^[\s]*[=+@-]/.test(text))text="'"+text
  return '"'+text.replaceAll('"','""')+'"'
}

export function calculationNotes(collection:string,row:Row):{label:string;value:string}[]{
  const notes:{label:string;value:string}[]=[]
  const add=(label:string,value:string)=>notes.push({label,value})
  if(['smoking','drinks'].includes(collection)){
    if(['GIFT','PAID_BY_OTHER'].includes(row.costType))add('本人费用',`${priceLabels[row.costType]}，本人费用计 ${money(row.cost)}。`)
    else if(row.cost==null)add('费用待补','当前记录缺少可用单价或规格。请核对商品单价及本条保存的规格；缺失费用不作为 0 元。')
    else if(collection==='smoking')add('按用量费用',`${n(row.count)} 根 × ${n(row.snapshotUnitPrice,8)} 元/根 = ${money(row.cost)}`)
    else add('按用量费用',`${n(row.equivalentBottles,6)} 瓶（本人折合）× ${n(row.snapshotUnitPrice,8)} 元/瓶 = ${money(row.cost)}`)
    add('价格依据',`${priceLabels[row.snapshotPriceType]||'未保存价格来源'}${row.snapshotPriceEffectiveDate?' · '+row.snapshotPriceEffectiveDate:''}。采用记录保存的单价；列表显示金额四舍五入到分。`)
    if(collection==='smoking'){
      add('保存的规格',`每包 ${n(row.snapshotUnitCount)} 根。`)
      if(row.snapshotTarMg!=null)add('焦油标示合计',`${n(row.snapshotTarMg)} mg/根 × ${n(row.count)} 根 = ${n(row.tarTotalMg)} mg；仅为标签值合计。`)
      if(row.snapshotNicotineMg!=null)add('烟气烟碱标示合计',`${n(row.snapshotNicotineMg)} mg/根 × ${n(row.count)} 根 = ${n(row.nicotineTotalMg)} mg。`)
      if(row.snapshotCoMg!=null)add('一氧化碳标示合计',`${n(row.snapshotCoMg)} mg/根 × ${n(row.count)} 根 = ${n(row.coTotalMg)} mg。`)
    }else{
      add('本人饮用量',row.inputMode==='BOTTLES'?`${n(row.bottles)} 瓶 × ${n(row.snapshotBottleMl)} ml/瓶 × ${n(row.share??1)} 本人比例 = ${n(row.actualMl)} ml`:`直接填写 ${n(row.volumeMl)} ml；保存的每瓶容量 ${n(row.snapshotBottleMl)} ml。`)
      add('纯酒精',`${n(row.actualMl)} ml × ${n(row.snapshotAbv)}% × 0.789 g/ml = ${n(row.ethanolGrams)} g`)
      add('折合 42° 白酒',`${n(row.ethanolGrams)} g ÷ 0.789 ÷ 0.42 = ${n(row.ethanolEquivalent42Ml??(row.ethanolGrams==null?null:row.ethanolGrams/0.33138),1)} ml`)
    }
    if(row.calculationVersion)add('计算版本',String(row.calculationVersion))
  }
  if(collection==='ledger' && row.debtKind){
    add('计入支出',money(expenseCents(row as LedgerRow)/100))
    add('欠款本金变动',money(debtDelta(row as LedgerRow)/100))
  }
  if(collection==='body')add('BMI',row.heightCm>0&&row.weightKg>0?`${n(row.weightKg)} kg ÷ (${n(row.heightCm/100)} m)² = ${n(row.weightKg/(row.heightCm/100)**2,1)}`:'这条测量需同时有身高和体重才能计算 BMI，不借用另一条测量补算。')
  return notes
}
