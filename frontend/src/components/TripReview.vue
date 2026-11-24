<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {BriefcaseBusiness,Check,X} from 'lucide-vue-next'
import {api} from '../api'
import type {Row} from '../types'
import {money} from '../types'
import {journalCategories,taskStatuses} from '../journal'
import type {LedgerRow} from '../ledgerReview'

type Claim = 'SELF'|'PENDING'|'REIMBURSED'
type Expense = LedgerRow & {tripId:string|null;reimbursement:Claim|null}
type Trip = {trip:Row;expenses:Expense[];logs:Row[]}
const props=defineProps<{row:Row}>()
const emit=defineEmits<{close:[]}>()
const dialog=ref<HTMLDialogElement>(),view=ref<Trip>(),claims=ref<Record<string,Claim>>({})
const loading=ref(false),saving=ref(false),error=ref(''),notice=ref(''),confirmClose=ref(false),stale=ref(false)
const labels:Record<Claim,string>={SELF:'自费',PENDING:'待报销',REIMBURSED:'已报销'}
const abort=new AbortController()
let request=0
function reset(data:Trip) {
  view.value=data
  claims.value=Object.fromEntries(data.expenses.filter(e=>e.tripId===data.trip.id).map(e=>[e.id,e.reimbursement as Claim]))
  stale.value=false
}
async function load() {
  const turn=++request
  loading.value=true
  error.value=''
  try {
    const result=await api<Trip>('/trips/'+props.row.id,{signal:abort.signal})
    if(turn===request)reset(result)
  } catch(e) { if(!abort.signal.aborted && turn===request)error.value=(e as Error).message }
  finally { if(turn===request)loading.value=false }
}
const changes=computed(()=>view.value?.expenses.filter(e=>
  (e.tripId===props.row.id?e.reimbursement:null)!==(claims.value[e.id]||null)
).map(e=>({id:e.id,version:e.version,reimbursement:claims.value[e.id]||null})) || [])
const totals=computed(()=>{
  const result={SELF:0,PENDING:0,REIMBURSED:0}
  for(const e of view.value?.expenses||[]) {
    const claim=claims.value[e.id]
    if(claim)result[claim]+=Math.round(Number(e.amount)*100)
  }
  return result
})
function toggle(e:Expense) {
  if(claims.value[e.id])delete claims.value[e.id]
  else claims.value[e.id]='SELF'
  notice.value=''
}
async function save() {
  if(saving.value || stale.value || !view.value || !changes.value.length)return
  saving.value=true
  error.value=notice.value=''
  try {
    reset(await api<Trip>('/trips/'+props.row.id+'/expenses',{method:'PUT',body:JSON.stringify({version:view.value.trip.version,changes:changes.value}),signal:abort.signal}))
    notice.value='行程费用已保存。'
  } catch(e) {
    if(!abort.signal.aborted){error.value=(e as Error).message;stale.value=true}
  } finally {saving.value=false}
}
function close() {
  if(saving.value)return
  if(changes.value.length)confirmClose.value=true
  else emit('close')
}
watch(()=>props.row.id,load,{immediate:true})
onMounted(()=>dialog.value?.showModal())
onBeforeUnmount(()=>{abort.abort();dialog.value?.close()})
</script>
<template>
  <dialog ref="dialog" aria-label="出差回顾" class="trip-panel" @cancel.prevent="close">
    <header><div><small><BriefcaseBusiness :size="14"/> 出差回顾</small><h2 class="sensitive">{{row.title}}</h2></div>
      <button type="button" class="icon-button" aria-label="关闭出差回顾" :disabled="saving" @click="close"><X :size="18"/></button></header>
    <p class="quiet">{{row.startDate}} 至 {{row.endDate||row.startDate}}<span v-if="row.place" class="sensitive"> · {{row.place}}</span></p>
    <p v-if="error" role="alert" class="error-box">{{error}} <button class="text-link" @click="load">重新读取</button></p>
    <p v-if="loading" role="status">读取行程中…</p>
    <template v-else-if="view">
      <div class="totals sensitive"><div><small>行程实付</small><b>{{ money((totals.SELF+totals.PENDING+totals.REIMBURSED)/100) }}</b></div>
        <div><small>自费</small><b>{{ money(totals.SELF/100) }}</b></div><div><small>待报销</small><b>{{ money(totals.PENDING/100) }}</b></div><div><small>已报销</small><b>{{ money(totals.REIMBURSED/100) }}</b></div></div>
      <h3>选入行程的支出</h3>
      <p class="quiet">只汇总勾选的实付流水。状态按整笔金额计算。</p>
      <div v-if="view.expenses.length" class="list"><div v-for="e in view.expenses" :key="e.id" class="expense">
        <label><input type="checkbox" :checked="!!claims[e.id]" :disabled="saving || (!!e.tripId && e.tripId!==row.id)" @change="toggle(e)"/>
          <span class="sensitive"><strong>{{ e.notes||e.category }}</strong><small>{{ e.date }} · {{ e.category }}<template v-if="e.tripId && e.tripId!==row.id"> · 已归入其他行程</template></small></span>
        </label><strong class="sensitive">{{ money(e.amount) }}</strong>
        <select v-if="claims[e.id]" v-model="claims[e.id]" :disabled="saving" :aria-label="(e.notes||e.category)+'的报销状态'"><option v-for="(name,code) in labels" :key="code" :value="code">{{name}}</option></select>
      </div></div><p v-else class="quiet">行程期间尚无支出流水。</p>
      <h3>同期生活与工作</h3>
      <div v-if="view.logs.length" class="list logs sensitive"><div v-for="log in view.logs" :key="log.id"><small>{{log.startDate}} · {{journalCategories[log.category]||'生活'}}</small><strong>{{log.title}}</strong>
        <small v-if="log.taskTitle">关联待办 · {{log.taskTitle}} · {{taskStatuses[log.taskStatus]}}<template v-if="log.completedDate">（{{log.completedDate}} 完成）</template><template v-if="log.taskDeleted"> · 回收站</template></small>
      </div></div><p v-else class="quiet">这段日期还没有其他生活日志。</p>
      <p v-if="notice" class="notice" role="status">{{notice}}</p>
      <footer v-if="confirmClose"><span>有未保存的费用选择。</span><button class="button secondary" @click="confirmClose=false">继续核对</button><button class="button secondary" @click="emit('close')">放弃更改</button></footer>
      <footer v-else><span>{{changes.length?'有 '+changes.length+' 笔待保存':''}}</span><button class="button secondary" @click="close">关闭</button><button class="button primary" :disabled="!changes.length||saving||stale" @click="save"><Check :size="14"/>保存选择</button></footer>
    </template>
  </dialog>
</template>
<style scoped>
.trip-panel{box-sizing:border-box;width:min(660px,calc(100vw - 24px));max-height:85vh;overflow:auto;margin:auto;border:1px solid var(--border);border-radius:14px;background:var(--panel);color:var(--text);padding:20px;font-size:13px}
.trip-panel::backdrop{background:#0a0c12b3}.trip-panel header,.trip-panel footer,.trip-panel label,.trip-panel header small{display:flex;align-items:center;gap:9px}.trip-panel header{justify-content:space-between}.trip-panel h2{margin:5px 0 0;font-size:20px}.trip-panel h3{margin:19px 0 4px;font-size:14px}.trip-panel small,.quiet{color:var(--muted);font-size:12px}.trip-panel p{line-height:1.6}.totals{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;padding:12px;background:var(--input);border-radius:9px}.totals b,.expense small,.logs strong,.logs small{display:block;margin-top:3px}.totals b{color:var(--mint)}.list{max-height:220px;overflow:auto}.expense,.logs>div{padding:9px 0;border-bottom:1px solid var(--border)}.expense{display:flex;gap:12px;align-items:center}.expense label{flex:1;min-width:0}.expense label strong,.logs strong{overflow-wrap:anywhere}.expense input{accent-color:var(--mint)}.expense select{color:var(--text);background:var(--input);border:1px solid var(--border);padding:5px}.trip-panel footer{margin-top:18px;justify-content:flex-end}.trip-panel footer span{margin-right:auto;font-size:12px}.notice{color:var(--mint)}@media(max-width:500px){.totals{grid-template-columns:1fr 1fr}.expense{flex-wrap:wrap}.expense label{min-width:50%}.trip-panel{padding:14px}}
</style>
