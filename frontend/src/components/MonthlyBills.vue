<script setup lang="ts">
import {computed,nextTick,onMounted,ref,watch} from 'vue'
import {CalendarClock,Check,Plus,X} from 'lucide-vue-next'
import {api,deleteRow,saveRow} from '../api'
import {money,shanghaiDate} from '../types'
import type {Row} from '../types'
import type {LedgerRow} from '../ledgerReview'
import {billSuggestions,monthlyDate} from '../billReview'
import type {BillSuggestion} from '../billReview'
import {shiftDay} from '../journal'

type Bill=Row & {title:string;category:string;amount:number;monthDay:number;nextDue:string}
const props=defineProps<{rows:LedgerRow[];refreshKey?:number}>()
const emit=defineEmits<{changed:[];notify:[text:string]}>()
const rules=ref<Bill[]>([]),today=ref(shanghaiDate()),busy=ref(false),error=ref(''),editError=ref(''),loading=ref(false)
const dialog=ref<HTMLDialogElement>(),mode=ref<'list'|'form'|'pay'|'delete'>('list'),active=ref<Bill>()
type BillForm={id:string;title:string;category:string;amount:string;month:string;day:number}
const initial=():BillForm=>({id:crypto.randomUUID(),title:'',category:'居住',amount:'',month:today.value.slice(0,7),day:Number(today.value.slice(-2))})
const form=ref(initial()),paidDate=ref(today.value),hint=ref(''),suppress=ref<string[]>([])
const storageKey='ziji-dismissed-bill-hints-v1'
const fixedFormDate=computed(()=>monthlyDate(form.value.month,Number(form.value.day)))
const upcoming=computed(()=>rules.value.filter(r=>r.nextDue<=shiftDay(today.value,6)))
const suggestions=computed(()=>billSuggestions(props.rows,today.value).filter(s=>
  !rules.value.some(b=>b.title===s.title&&b.category===s.category)&&!suppress.value.includes(suggestionKey(s))))
function suggestionKey(s:BillSuggestion) {
  // Store only an opaque dismissal preference in the browser.
  let hash=2166136261
  for(const char of JSON.stringify([s.title,s.category,s.amount]))hash=Math.imul(hash^char.charCodeAt(0),16777619)
  return (hash>>>0).toString(16)
}
function dismiss(s:BillSuggestion) {
  suppress.value=[...new Set([...suppress.value,suggestionKey(s)])]
  try{localStorage.setItem(storageKey,JSON.stringify(suppress.value))}catch{}
}
let request=0
async function load() {
  const turn=++request
  loading.value=true
  error.value=''
  today.value=shanghaiDate()
  try{const data=await api<Bill[]>('/data/bills');if(turn===request)rules.value=data.sort((a,b)=>a.nextDue.localeCompare(b.nextDue)||a.title.localeCompare(b.title,'zh-CN'))}
  catch(e){if(turn===request)error.value=(e as Error).message}
  finally{if(turn===request)loading.value=false}
}
watch(()=>props.refreshKey,load,{immediate:true})
onMounted(()=>{try {const values=JSON.parse(localStorage.getItem(storageKey)||'[]');if(Array.isArray(values))suppress.value=values.filter(v=>typeof v==='string')}catch{}})
function show() {mode.value='list';editError.value='';dialog.value?.showModal();void load()}
function close() {if(!busy.value)dialog.value?.close()}
function edit(row?:Bill,s?:BillSuggestion) {
  active.value=row
  const item=row||s
  form.value=item?{id:row?.id||crypto.randomUUID(),title:item.title,category:item.category,amount:String(item.amount),month:item.nextDue.slice(0,7),day:Number(item.monthDay)}:initial()
  hint.value=s?'参照这三笔支出：'+[...s.dates].reverse().join('、'):''
  editError.value=''
  mode.value='form'
}
function review(row:Bill,action:'pay'|'delete') {
  active.value=row;mode.value=action;editError.value='';paidDate.value=today.value
  if(!dialog.value?.open)dialog.value?.showModal()
}
async function save() {
  if(busy.value||!fixedFormDate.value)return
  busy.value=true;editError.value=''
  try{
    const f=form.value
    await saveRow('bills',{id:f.id,title:f.title.trim(),category:f.category.trim(),amount:f.amount,monthDay:f.day,nextDue:fixedFormDate.value},active.value)
    await load();mode.value='list';emit('notify','每月固定账单已保存。')
  }catch(e){editError.value=(e as Error).message}finally{busy.value=false}
}
async function settle(skip:boolean) {
  const row=active.value
  if(!row||busy.value)return
  busy.value=true;editError.value=''
  try {
    await api('/bills/'+row.id+'/settle',{method:'POST',body:JSON.stringify({version:row.version,dueDate:row.nextDue,paidDate:paidDate.value,skip})})
    await load();mode.value='list';emit('notify',skip?'已跳过本期。':'实际支出已记入账本。');emit('changed')
  }catch(e){editError.value=(e as Error).message;await load()}finally{busy.value=false}
}
async function remove() {
  if(!active.value||busy.value)return
  busy.value=true;editError.value=''
  try {await deleteRow('bills',active.value);await load();mode.value='list';emit('notify','固定账单已移入回收站。')}
  catch(e){editError.value=(e as Error).message}finally{busy.value=false}
}
async function add() {show();await nextTick();edit()}
</script>
<template>
  <section class="panel monthly-bills" aria-label="固定扣款">
    <header><div><CalendarClock :size="17"/><h2>固定扣款</h2><small>今天起 7 天 · 含逾期</small></div><button class="text-link" @click="show">管理账单{{suggestions.length?' · '+suggestions.length+' 个建议':''}}</button></header>
    <p v-if="error" class="error-box" role="alert">{{error}} <button class="text-link" @click="load">重试</button></p>
    <div v-else-if="upcoming.length" class="due-list"><div v-for="b in upcoming" :key="b.id"><span class="sensitive">{{b.title}} <strong>{{money(b.amount)}}</strong></span>
      <small>{{ b.nextDue }}{{b.nextDue<today?' · 已过期':b.nextDue===today?' · 今天':''}}</small><button class="text-link" @click="review(b,'pay')">核对付款</button></div></div>
    <p v-else class="quiet">{{loading?'读取中…':'这几天没有待付的固定账单。'}} <button class="text-link" @click="add">新增</button></p>
  </section>
  <dialog ref="dialog" class="bill-dialog" aria-label="管理固定账单" @cancel.prevent="close">
    <header><h2>{{mode==='form'?(active?'修改固定账单':'新增固定账单'):mode==='pay'?'核对本期扣款':mode==='delete'?'停止固定扣款':'每月固定账单'}}</h2><button class="icon-button" aria-label="关闭账单管理" :disabled="busy" @click="close"><X :size="16"/></button></header>
    <template v-if="mode==='list'">
      <button class="button secondary" @click="edit()"><Plus :size="14"/>新增固定账单</button>
      <p v-if="error" class="error-box" role="alert">{{error}} <button class="text-link" @click="load">重试</button></p>
      <p v-if="!rules.length" class="quiet">暂时没有固定账单。</p>
      <div class="rules-list"><div v-for="b in rules" :key="b.id"><div class="sensitive"><strong>{{b.title}} · {{money(b.amount)}}</strong><small>每月 {{b.monthDay}} 日 · 下次 {{b.nextDue}}</small></div>
        <button v-if="b.nextDue<=shiftDay(today,6)" class="text-link" @click="review(b,'pay')">核对付款</button><button class="text-link" @click="edit(b)">编辑</button><button class="text-link" @click="review(b,'delete')">停止</button></div></div>
      <template v-if="suggestions.length"><h3>可能的固定扣款</h3><p class="quiet">过去连续三个月各有一笔同名、同分类、同金额的支出，扣款日期相近。</p>
        <div class="rules-list"><div v-for="s in suggestions" :key="suggestionKey(s)"><div class="sensitive"><strong>{{s.title}} · {{money(s.amount)}}</strong><small>{{s.dates.join('、')}}</small></div><button class="text-link" @click="edit(undefined,s)">设为每月账单</button><button class="text-link" @click="dismiss(s)">忽略</button></div></div>
      </template>
    </template>
    <form v-else-if="mode==='form'" @submit.prevent="save">
      <p v-if="hint" class="quiet">{{hint}}</p>
      <label class="field"><span>账单名称</span><input v-model="form.title" required maxlength="100" placeholder="例如手机话费"/></label>
      <div class="fields-grid"><label class="field"><span>每期金额（元）</span><input v-model="form.amount" type="number" min="0.01" max="999999999" step="0.01" required/></label><label class="field"><span>支出分类</span><input v-model="form.category" required maxlength="40"/></label>
      <label class="field"><span>下次扣款月份</span><input v-model="form.month" type="month" required min="1000-01" max="9999-12"/></label><label class="field"><span>每月几号</span><input v-model.number="form.day" type="number" min="1" max="31" required/></label></div>
      <p class="quiet">下次应付 {{fixedFormDate||'—'}}，短月取当月最后一天。</p>
      <footer><button type="button" class="button secondary" :disabled="busy" @click="mode='list'">返回</button><button class="button primary" :disabled="busy || !fixedFormDate"><Check :size="14"/>保存规则</button></footer>
    </form>
    <template v-else-if="active">
      <p class="sensitive">{{active.title}} · {{money(active.amount)}} · 本期应付 {{active.nextDue}}</p>
      <template v-if="mode==='pay'"><label class="field"><span>实际付款日期</span><input v-model="paidDate" type="date" :max="today" required/></label><p class="quiet">确认后记一笔支出，推进到下个月。如果已经手动记过，选“跳过本期”。</p>
        <footer><button class="button secondary" :disabled="busy" @click="mode='list'">返回</button><button class="button secondary" :disabled="busy" @click="settle(true)">跳过本期</button><button class="button primary" :disabled="busy||!paidDate||paidDate>today" @click="settle(false)">已付款，记入账本</button></footer>
      </template><template v-else><p class="quiet">停止后不再提示扣款。可在数据管理的回收站恢复规则。</p><footer><button class="button secondary" @click="mode='list'">取消</button><button class="button primary" :disabled="busy" @click="remove">确认停止</button></footer></template>
    </template>
    <p v-if="editError" class="error-box" role="alert">{{editError}}</p>
  </dialog>
</template>
<style scoped>
.monthly-bills{padding:16px 18px}.monthly-bills header,.monthly-bills header>div,.bill-dialog header,.bill-dialog footer,.rules-list>div,.due-list>div{display:flex;align-items:center;gap:10px}.monthly-bills header,.bill-dialog header{justify-content:space-between}.monthly-bills h2,.bill-dialog h2,.bill-dialog h3{margin:0;font-size:15px}.monthly-bills header svg{color:var(--mint)}.monthly-bills small,.bill-dialog small,.quiet{color:var(--muted);font-size:12px}.monthly-bills p{margin:8px 0 0}.due-list>div,.rules-list>div{padding:9px 0;border-bottom:1px solid var(--border);flex-wrap:wrap}.due-list>div>span,.rules-list>div>div{flex:1;overflow-wrap:anywhere}.due-list strong{margin-left:5px}.monthly-bills .text-link,.bill-dialog .text-link{font-size:12px}.bill-dialog{box-sizing:border-box;width:min(510px,calc(100vw - 24px));max-height:82vh;overflow:auto;margin:auto;padding:20px;border:1px solid var(--border);border-radius:14px;color:var(--text);background:var(--panel);font-size:13px}.bill-dialog::backdrop{background:#0a0c12b3}.bill-dialog header{margin-bottom:16px}.bill-dialog h3{margin-top:18px}.rules-list small{display:block;margin-top:4px}.bill-dialog footer{justify-content:flex-end;flex-wrap:wrap;margin-top:14px}.rules-list{max-height:230px;overflow:auto}.bill-dialog p{line-height:1.6}@media(max-width:520px){.monthly-bills header,.monthly-bills header>div{flex-wrap:wrap}.bill-dialog{padding:14px}}
</style>
