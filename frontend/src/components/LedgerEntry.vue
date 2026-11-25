<script setup lang="ts">
import {computed,nextTick,onMounted,reactive,ref,watch} from 'vue'
import {Check,X} from 'lucide-vue-next'
import {saveRow,state} from '../api'
import FormFields from './FormFields.vue'
import {cents,debtAccounts,debtLabels,expenseCents,platformPresets} from '../ledgerReview'
import type {DebtKind,Direction,LedgerRow} from '../ledgerReview'
import {money,shanghaiDate} from '../types'

type EntryMode = Direction | 'REPAYMENT' | 'OPENING'
const props=defineProps<{rows:LedgerRow[];date:string;row?:LedgerRow;repeat?:boolean;kind?:Direction|DebtKind;platform?:string;ready:boolean}>()
const emit=defineEmits<{close:[];saved:[row:LedgerRow,edited:boolean]}>()
const presets:Record<Direction,string[]>={EXPENSE:['餐饮','交通','购物','居住','学习','休闲','医疗','其他'],INCOME:['工资','兼职','奖金','其他']}
const source=props.row
const editing=ref(props.repeat?undefined:source)
const entryId=crypto.randomUUID()
const initialKind=props.kind || source?.debtKind || source?.direction || 'EXPENSE'
const mode=ref<EntryMode>(initialKind==='CREDIT'?'EXPENSE':initialKind)
const credit=ref(initialKind==='CREDIT')
const debtKind=computed<DebtKind|null>(()=>mode.value==='OPENING'||mode.value==='REPAYMENT'?mode.value:mode.value==='EXPENSE'&&credit.value?'CREDIT':null)
const direction=computed<Direction>(()=>mode.value==='INCOME'?'INCOME':'EXPENSE')
const amount=ref(source?String(source.amount):''),fee=ref(source?.debtFee?String(source.debtFee):'')
const platform=ref(props.platform || source?.debtPlatform || '')
const date=ref(props.repeat?shanghaiDate():source?.date || props.date)
const trip=reactive({date:date.value,tripId:props.repeat?'':source?.tripId||'',reimbursement:props.repeat?'':source?.reimbursement||''})
watch(date,value=>{trip.date=value})
const tripOpen=ref(!!trip.tripId)
const tripFields=computed(()=>state.schema.find(c=>c.name==='ledger')?.fields?.filter(f=>f.key==='tripId'||(f.key==='reimbursement'&&trip.tripId)) || [])
const notes=ref(source?.notes || '')
const categories=computed(()=>{
  const recent=props.rows.filter(r=>r.direction===direction.value && r.debtKind!=='REPAYMENT' && r.debtKind!=='OPENING')
    .sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt))).map(r=>r.category)
  return [...new Set([...recent,...presets[direction.value]])]
})
const category=ref(source && source.debtKind!=='OPENING' && source.debtKind!=='REPAYMENT'?source.category:categories.value[0]!)
const platforms=computed(()=>[...new Set([...platformPresets,...props.rows.map(r=>r.debtPlatform).filter((p):p is string=>!!p)])])
const account=computed(()=>debtAccounts(props.rows.filter(r=>r.id!==editing.value?.id),date.value).find(a=>a.platform===platform.value.trim()))
const balance=computed(()=>account.value?.balance ?? 0)
const platformEntries=computed(()=>props.rows.filter(r=>r.debtKind && r.debtPlatform===platform.value.trim() && r.id!==editing.value?.id))
const otherOpening=computed(()=>platformEntries.value.find(r=>r.debtKind==='OPENING'))
const earliestDate=computed(()=>platformEntries.value.map(r=>r.date).sort()[0])
const principal=computed(()=>cents(amount.value)-cents(fee.value))
const splitValid=computed(()=>validMoney(amount.value) && (!fee.value.trim()||validMoney(fee.value,true)) && principal.value>=0)
const detachesTrip=computed(()=>!!editing.value?.tripId && mode.value!=='EXPENSE')
const effectiveCategory=computed(()=>mode.value==='OPENING'?'期初欠款':mode.value==='REPAYMENT'?'利息手续费':category.value.trim())
const originalExpense=computed(()=>editing.value?expenseCents(editing.value):0)
const newExpense=computed(()=>mode.value==='REPAYMENT'?cents(fee.value):mode.value==='EXPENSE'?cents(amount.value):0)
const candidates=computed(()=>{
  if (!debtKind.value || !date.value || !validMoney(amount.value)) return []
  return props.rows.filter(r=>{
    if(r.id===editing.value?.id || r.date!==date.value || r.direction!=='EXPENSE' || cents(r.amount)!==cents(amount.value))return false
    if(!r.debtKind)return !editing.value && debtKind.value!=='OPENING'
    return r.debtKind===debtKind.value && r.debtPlatform===platform.value.trim()
  })
})
const separate=ref(false),busy=ref(false),error=ref('')
const dialog=ref<HTMLDialogElement>(),amountInput=ref<HTMLInputElement>()
const format=(n:number)=>money(n/100)
function validMoney(value:string,allowZero=false) {
  return /^(?:\d+(?:\.\d{0,2})?|\.\d{1,2})$/.test(value.trim()) && Number(value)<=999999999 && Number(value)>=(allowZero?0:.01)
}
function setMode(next:EntryMode) {
  if(mode.value===next)return
  const oldDirection=direction.value
  mode.value=next
  if(oldDirection!==direction.value)category.value=categories.value[0]!
  error.value=''
}
function useCandidate(row:LedgerRow) {
  editing.value=row
  trip.tripId=row.tripId||''
  trip.reimbursement=row.reimbursement||''
  tripOpen.value=!!trip.tripId
  if(row.debtKind==='REPAYMENT')fee.value=String(row.debtFee||'')
  if(!notes.value.trim())notes.value=row.notes||''
  if(mode.value==='EXPENSE')category.value=row.category
  separate.value=false
  error.value=''
}
function editOpening(row:LedgerRow) {
  editing.value=row
  date.value=row.date
  amount.value=String(row.amount)
  notes.value=row.notes||''
  error.value=''
}
watch([date,amount,platform,debtKind],()=>{separate.value=false;error.value=''})
const modeOptions:{kind:EntryMode;label:string}[]=[{kind:'EXPENSE',label:'支出'},{kind:'INCOME',label:'收入'},{kind:'REPAYMENT',label:'还款'}]
function close(){if(!busy.value)emit('close')}
async function save() {
  if(busy.value || !props.ready)return
  error.value=''
  if(!validMoney(amount.value)){error.value='金额须在 0.01 到 999,999,999 元之间，最多两位小数。';return}
  const kind=debtKind.value
  if(kind && (!platform.value.trim()||date.value>shanghaiDate())){error.value='请选择平台并填写今天或更早的发生日期。';return}
  if(kind==='OPENING' && otherOpening.value){error.value='该平台已有期初欠款，可编辑原期初记录。';return}
  if(kind==='OPENING' && earliestDate.value && date.value>earliestDate.value){error.value='期初日期应在该平台第一笔流水当天或更早。';return}
  if(!effectiveCategory.value){error.value='请选择或填写类别。';return}
  if(kind==='REPAYMENT'&&!splitValid.value){error.value='请核对息费金额，息费最多两位小数，且不能超过本次还款总额。';return}
  if(kind==='REPAYMENT'&&principal.value>balance.value){error.value='还款本金超过该日已记录的欠款。请核对平台和日期，或先从「平台负债」补记此前未登记的本金。';return}
  if(candidates.value.length&&!separate.value){error.value='请核对下方已有账目，或确认这是另外一笔。';return}
  busy.value=true
  try {
    const saved=await saveRow('ledger',{
      id:editing.value?.id || entryId,date:date.value,direction:direction.value,amount:Number(amount.value),
      category:effectiveCategory.value,notes:notes.value.trim(),debtKind:kind,debtPlatform:kind?platform.value.trim():null,
      debtFee:kind==='REPAYMENT'&&Number(fee.value)>0?Number(fee.value):null,
      tripId:mode.value==='EXPENSE' ? trip.tripId || null : null,
      reimbursement:mode.value==='EXPENSE' && trip.tripId ? trip.reimbursement || 'SELF' : null
    },editing.value) as LedgerRow
    emit('saved',saved,!!editing.value)
  }catch(e){error.value=(e as Error).message}
  finally{busy.value=false}
}
onMounted(()=>{dialog.value?.showModal();nextTick(()=>{amountInput.value?.focus();amountInput.value?.select()})})
</script>

<template>
  <dialog ref="dialog" class="ledger-entry" aria-labelledby="ledger-entry-title" @cancel.prevent="close" @click.self="close">
    <form @submit.prevent="save">
      <header><h2 id="ledger-entry-title">{{editing?'编辑账目':mode==='OPENING'?'录期初欠款':mode==='REPAYMENT'?'记还款':'记一笔'}}</h2><button type="button" class="icon-button" title="关闭" aria-label="关闭" :disabled="busy" @click="close"><X :size="18"/></button></header>
      <fieldset :disabled="busy" class="entry-body sensitive">
        <div v-if="mode!=='OPENING'" class="entry-tabs" role="group" aria-label="收支类型">
          <button v-for="option in modeOptions" :key="option.kind" type="button" :aria-pressed="mode===option.kind" @click="setMode(option.kind)">{{option.label}}</button>
        </div>
        <label class="entry-field amount-field"><span>{{mode==='REPAYMENT'?'本次还款总额（含息费）':mode==='OPENING'?'期初未还本金':'金额'}}（元）</span><div><b>¥</b><input ref="amountInput" v-model="amount" aria-label="金额（元）" type="text" inputmode="decimal" placeholder="0.00" maxlength="16" required/></div></label>
        <div v-if="mode==='EXPENSE'" class="payment-type" role="group" aria-label="付款方式"><span>付款方式</span><button type="button" :aria-pressed="!credit" @click="credit=false">直接付款</button><button type="button" :aria-pressed="credit" @click="credit=true">信用支付（先用后还）</button></div>
        <template v-if="debtKind">
          <label class="entry-field"><span>借款平台</span><input v-model="platform" maxlength="40" placeholder="点选下方平台或输入账户名" required/></label>
          <div class="category-picks" aria-label="选择借款平台"><button v-for="p in platforms" :key="p" type="button" :aria-pressed="platform.trim()===p" @click="platform=p">{{p}}</button></div>
        </template>
        <div class="entry-fields">
          <label v-if="mode==='EXPENSE'||mode==='INCOME'" class="entry-field">类别<input v-model="category" maxlength="40" placeholder="点选或自行输入" required/></label>
          <label v-if="mode==='REPAYMENT'" class="entry-field">其中利息和手续费（元）<input v-model="fee" type="text" inputmode="decimal" maxlength="16" placeholder="0.00"/></label>
          <label class="entry-field">日期<input v-model="date" type="date" min="1000-01-01" :max="debtKind?shanghaiDate():'9999-12-31'" required/></label>
        </div>
        <div v-if="mode==='EXPENSE'||mode==='INCOME'" class="category-picks" role="group" :aria-label="(direction==='INCOME'?'收入':'支出')+'类别'">
          <button v-for="name in categories" :key="name" type="button" :aria-pressed="category===name" @click="category=name">{{name}}</button>
        </div>
        <div v-if="mode==='REPAYMENT' && platform.trim()" class="debt-preview">
          <span>{{date}} · 此笔还款前已记欠款 <b>{{format(balance)}}</b></span>
          <template v-if="splitValid"><span>归还本金 <b>{{format(principal)}}</b> · 利息手续费 <b>{{format(cents(fee))}}</b></span>
            <span>本次计入支出 <b>{{format(cents(fee))}}</b> · 当日剩余欠款 <b>{{format(balance-principal)}}</b></span>
            <small v-if="principal>balance">本金超过已记欠款。可先核对扣款日期，或在平台负债里录期初欠款。</small>
          </template>
        </div>
        <div v-else-if="debtKind==='OPENING'" class="entry-hint">填开始记账前仍欠的本金。已逐笔记过的信用消费请扣除。期初金额只增加欠款。
          <div v-if="otherOpening">该平台已录期初 {{otherOpening.date}} · {{money(otherOpening.amount)}}。<button type="button" class="text-link" @click="editOpening(otherOpening)">编辑已有期初</button></div>
          <div v-else-if="earliestDate && date>earliestDate">首笔流水是 {{earliestDate}}。<button type="button" class="text-link" @click="date=earliestDate">使用首笔日期</button></div>
        </div>
        <p v-else-if="debtKind==='CREDIT' && validMoney(amount)" class="entry-hint">本次消费计入支出 {{format(cents(amount))}}，同额记入平台欠款。下次扣款时选“还款”。</p>
        <label class="entry-field">备注<textarea v-model="notes" rows="2" maxlength="10000" placeholder="选填"/></label>
        <details v-if="mode==='EXPENSE' && tripFields.length" :open="tripOpen" class="trip-fields" @toggle="tripOpen=($event.target as HTMLDetailsElement).open">
          <summary>行程与报销{{trip.tripId?' · 已关联':''}}</summary>
          <FormFields v-if="tripOpen" :fields="tripFields" :model="trip" collection="ledger"/>
        </details>
        <div v-if="candidates.length" class="duplicate-note">
          <strong>当天已有同额账目</strong>
          <div v-for="candidate in candidates" :key="candidate.id" class="candidate"><span>{{candidate.debtKind?debtLabels[candidate.debtKind]:candidate.category}} · {{candidate.notes||'无备注'}} · {{money(candidate.amount)}}</span>
            <button v-if="!editing" type="button" class="text-link" @click="useCandidate(candidate)">{{candidate.debtKind?'查看并编辑此笔':'改记这条原流水'}}</button></div>
          <label><input v-model="separate" type="checkbox"/>已核对，这是不同的两笔</label>
        </div>
        <p v-if="editing && originalExpense!==newExpense && (editing.debtKind || debtKind)" class="entry-hint">修改原流水后，这笔计入支出的金额从 {{format(originalExpense)}} 变为 {{format(newExpense)}}。</p>
        <p v-if="detachesTrip" class="entry-hint">保存后，这笔流水会从原行程的支出中移除。</p>
      </fieldset>
      <p v-if="error" class="error-box" role="alert">{{error}}</p>
      <footer><button type="button" class="button secondary" :disabled="busy" @click="close">取消</button><button class="button primary" :disabled="busy||!ready"><Check :size="15"/>{{busy?'保存中…':editing?'保存修改':'保存'}}</button></footer>
    </form>
  </dialog>
</template>

<style scoped>
.ledger-entry{box-sizing:border-box;margin:auto;padding:0;width:min(460px,calc(100% - 24px));max-height:calc(100dvh - 28px);overflow:auto;border:1px solid var(--border);border-radius:14px;background:var(--panel);color:var(--text);box-shadow:var(--shadow)}
.ledger-entry::backdrop{background:#070b11a8}.ledger-entry form{padding:17px}.ledger-entry header,.ledger-entry footer,.entry-tabs,.payment-type,.candidate{display:flex;align-items:center;gap:8px}.ledger-entry header{justify-content:space-between}.ledger-entry h2{font-size:18px;margin:0}.ledger-entry footer{justify-content:flex-end;margin-top:14px}.entry-body{display:grid;gap:10px;border:0;padding:0;margin:12px 0 0;min-width:0}
.entry-tabs{padding:3px;gap:3px;border-radius:8px;background:var(--input);border:1px solid var(--border)}.entry-tabs button{flex:1;padding:6px;border-radius:5px;font-size:13px;color:var(--muted)}
.payment-type{font-size:12px;color:var(--muted);flex-wrap:wrap}.payment-type button,.category-picks button{min-height:29px;padding:4px 8px;border:1px solid var(--border);border-radius:5px;font-size:12px;color:var(--text)}.ledger-entry button[aria-pressed=true]{background:var(--mint-bg);color:var(--mint)}.payment-type button[aria-pressed=true],.category-picks button[aria-pressed=true]{border-color:var(--mint)}
.entry-field{display:grid;gap:5px;font-size:12px;color:var(--muted);min-width:0}.entry-field input,.entry-field textarea{box-sizing:border-box;font-size:13px;padding:8px 9px;min-width:0;width:100%}.amount-field>div{display:flex;align-items:center;gap:7px;border-bottom:1px solid var(--border)}.amount-field b{font-size:24px;color:var(--mint)}.amount-field input{font-size:30px;font-weight:600;border:0;background:none;padding:2px 0;color:var(--text)}
.entry-fields{display:grid;grid-template-columns:1fr 1fr;gap:10px}.category-picks{display:flex;gap:5px;flex-wrap:wrap;max-height:10rem;overflow:auto;padding:1px}.debt-preview,.duplicate-note{display:grid;gap:5px;background:var(--panel-soft);border-radius:7px;padding:9px;font-size:12px}.debt-preview b{font-variant-numeric:tabular-nums}.entry-hint,.debt-preview small{margin:0;color:var(--muted);font-size:12px;line-height:1.6}.candidate{justify-content:space-between;flex-wrap:wrap}.candidate span{overflow-wrap:anywhere}.candidate .text-link{font-size:12px}.duplicate-note label{display:flex;align-items:center;gap:5px}.duplicate-note input{width:auto}
.trip-fields summary{color:var(--muted);font-size:12px;cursor:pointer}.trip-fields[open] summary{margin-bottom:8px}
</style>
