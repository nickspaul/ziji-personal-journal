<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Check, PencilLine, X } from 'lucide-vue-next'
import { api } from '../api'
import { journalCategories, journalPeriods } from '../journal'
import type { JournalFacets } from '../journal'
import { platformPresets } from '../ledgerReview'
import { parseQuickJournal } from '../quickJournal'
import type { QuickDraft } from '../quickJournal'
import { shanghaiDate } from '../types'
import type { Row } from '../types'
import TagInput from './TagInput.vue'

const props = defineProps<{ facets: JournalFacets; date?: string }>()
const emit = defineEmits<{ close: []; saved: [result: { journal?: Row; ledger?: Row }] }>()
const dialog = ref<HTMLDialogElement>(), peopleInput = ref<InstanceType<typeof TagInput>>(), tagsInput = ref<InstanceType<typeof TagInput>>()
const sentence = ref(''), parsed = ref(''), draft = ref<QuickDraft>()
const journalSelected = ref(true), expenseSelected = ref(false), busy = ref(false), error = ref('')
const credit = ref(false), platform = ref('')
const requestId = crypto.randomUUID()
const stale = computed(() => parsed.value !== sentence.value.trim())
const categories = ref(['餐饮', '交通', '购物', '居住', '学习', '休闲', '医疗', '其他'])
const customCategory = ref(false)
const categoryChoice = computed({
  get: () => customCategory.value ? '' : draft.value?.expenseCategory || '',
  set: (value: string) => {
    customCategory.value = !value
    if (draft.value) draft.value.expenseCategory = value
  },
})
onMounted(async () => {
  dialog.value?.showModal()
  try {
    const names = await api<string[]>('/journal/quick-entry/expense-categories')
    categories.value = [...new Set([...names, ...categories.value])]
  } catch { /* Custom names can also be entered directly. */ }
})
function preview() {
  const value = sentence.value.trim()
  if (!value || busy.value) return
  const today = shanghaiDate()
  draft.value = parseQuickJournal(value, today, props.date || today, props.facets.people, props.facets.places)
  journalSelected.value = true
  expenseSelected.value = !!draft.value.amount
  customCategory.value = false
  credit.value = false; platform.value = ''
  parsed.value = value
  error.value = ''
}
function close() { if (!busy.value) emit('close') }
async function save() {
  const entry = draft.value
  if (busy.value || !entry || stale.value || (!journalSelected.value && !expenseSelected.value)) return
  error.value = ''
  if (!entry.date || entry.date > shanghaiDate()) { error.value = '请核对实际发生日期。'; return }
  if (journalSelected.value && (!entry.title.trim() || peopleInput.value?.commit() === false || tagsInput.value?.commit() === false)) {
    error.value = '请核对事项、同行人和标签。'
    return
  }
  const amount = String(entry.amount).trim()
  if (expenseSelected.value && (!/^\d+(?:\.\d{1,2})?$/.test(amount) || Number(amount) <= 0 || Number(amount) > 999_999_999 || !entry.expenseCategory.trim())) {
    error.value = '请填写支出分类和本次消费金额，金额须大于 0，最多两位小数。'
    return
  }
  if (expenseSelected.value && credit.value && !platform.value.trim()) { error.value='请选择或输入信用支付平台。'; return }
  busy.value = true
  try {
    const result = await api<{journal?: Row; ledger?: Row}>('/journal/quick-entry', {
      method: 'POST',
      body: JSON.stringify({
        requestId,
        journal: journalSelected.value ? {
          title: entry.title, category: entry.category, startDate: entry.date,
          timeMode: entry.period ? 'PERIOD' : 'DATE', period: entry.period || null,
          people: entry.people, place: entry.place || null, tags: entry.tags,
        } : null,
        ledger: expenseSelected.value ? {
          date: entry.date, direction: 'EXPENSE', amount, category: entry.expenseCategory.trim(), notes: entry.notes.trim() || null,
          ...(credit.value ? {debtKind:'CREDIT',debtPlatform:platform.value.trim()} : {}),
        } : null,
      }),
    })
    emit('saved', result)
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="quick-entry" aria-labelledby="quick-journal-title" @cancel.prevent="close">
    <header><PencilLine :size="18"/><h2 id="quick-journal-title">一句话补记</h2><button class="icon-button" aria-label="关闭一句话补记" :disabled="busy" @click="close"><X :size="17"/></button></header>
    <section class="quick-body">
      <form class="sentence" @submit.prevent="preview">
        <label for="quick-sentence">发生了什么？</label>
        <textarea id="quick-sentence" v-model="sentence" rows="2" autofocus maxlength="500" :disabled="busy"
          placeholder="例如：昨晚和小林在东门吃烧烤，我付了 86 元" @keydown.ctrl.enter.prevent="preview"/>
        <button class="button secondary" :disabled="busy || !sentence.trim() || (!!draft && !stale)">{{draft?'重新识别':'生成草稿'}}</button>
      </form>
      <form v-if="draft" id="quick-journal-form" class="review" @submit.prevent="save">
        <label class="single">发生日期<input v-model="draft.date" type="date" required :max="shanghaiDate()" :disabled="busy"/></label>
        <p v-if="stale" class="note" role="status">原句已变动，请重新识别并核对草稿。</p>
        <section class="card">
          <label class="choice"><input v-model="journalSelected" type="checkbox" :disabled="busy"/>生活日志</label>
          <fieldset v-if="journalSelected" :disabled="busy" class="fields">
            <label class="wide">事项<input v-model="draft.title" required maxlength="500"/></label>
            <label>日志分类<select v-model="draft.category"><option v-for="(name,id) in journalCategories" :key="id" :value="id">{{name}}</option></select></label>
            <label>时段<select v-model="draft.period"><option value="">未细分</option><option v-for="(name,id) in journalPeriods" :key="id" :value="id">{{name}}</option></select></label>
            <label class="wide">地点<input v-model="draft.place" maxlength="500" list="quick-journal-places"/><datalist id="quick-journal-places"><option v-for="place in facets.places" :key="place" :value="place"/></datalist></label>
            <div class="quick-terms"><TagInput ref="peopleInput" v-model="draft.people" label="同行人" :tags="false" :suggestions="facets.people" :disabled="busy"/></div>
            <div class="quick-terms"><TagInput ref="tagsInput" v-model="draft.tags" label="标签" :suggestions="facets.tags.map(t=>t.name)" :disabled="busy"/></div>
          </fieldset>
        </section>
        <section class="card">
          <label class="choice"><input v-model="expenseSelected" type="checkbox" :disabled="busy"/>记一笔消费</label>
          <p v-if="draft.paymentHint" class="note">{{draft.paymentHint}}</p>
          <fieldset v-if="expenseSelected" :disabled="busy" class="fields">
            <label>{{ credit ? '信用消费金额' : '本人实付' }}（元）<input v-model="draft.amount" inputmode="decimal" required placeholder="0.00"/></label>
            <label>支出分类<select v-model="categoryChoice"><option v-for="category in categories" :key="category" :value="category">{{category}}</option><option value="">自定义分类…</option></select></label>
            <label v-if="customCategory" class="wide">新分类名称<input v-model="draft.expenseCategory" maxlength="40" required/></label>
            <label class="wide choice"><input type="checkbox" v-model="credit"/>使用平台信用支付</label>
            <label v-if="credit" class="wide">欠款平台<input v-model.trim="platform" list="quick-credit-platforms" required maxlength="40" placeholder="选择或输入平台"/><datalist id="quick-credit-platforms"><option v-for="item in platformPresets" :key="item" :value="item"/></datalist><small>这次计消费并增加欠款。还本金时只减少欠款。</small></label>
            <label class="wide">账本备注<input v-model="draft.notes" maxlength="500"/></label>
          </fieldset>
        </section>
        <p v-if="error" class="error-box" role="alert">{{error}}</p>
      </form>
    </section>
    <footer v-if="draft"><button type="button" class="button secondary" :disabled="busy" @click="close">取消</button><button form="quick-journal-form" class="button primary" :disabled="busy || stale || (!journalSelected&&!expenseSelected)"><Check :size="14"/>{{busy?'正在保存':'保存所选草稿'}}</button></footer>
  </dialog>
</template>

<style scoped>
.quick-entry { width:min(540px,calc(100vw - 24px)); max-height:88dvh; padding:0; border:1px solid var(--border); border-radius:14px; color:var(--text); background:var(--panel); box-shadow:var(--shadow); }
.quick-entry::backdrop { background:#090c14a6; }
header,footer { background:var(--panel); position:sticky; z-index:1; display:flex; align-items:center; gap:9px; padding:10px 16px; }
header { top:0; border-bottom:1px solid var(--border); } h2 { font-size:16px; flex:1; }
footer { bottom:0; justify-content:flex-end; border-top:1px solid var(--border); }
.quick-body { padding:14px 16px; }
.sentence,.review { display:grid; gap:9px; }
.sentence label,.fields label,.single { font-size:12px; color:var(--muted); }
.sentence .button { justify-self:end; }
.quick-entry .button { padding:7px 12px; font-size:12px; }
.single { display:flex; align-items:center; gap:12px; margin:4px 0; }
.single input { width:auto; }
.quick-entry input:not([type=checkbox]),.quick-entry select,.quick-entry textarea { padding:8px 10px; font-size:13px; }
.card { border:1px solid var(--border); border-radius:9px; padding:12px; }
.choice { display:flex; align-items:center; gap:8px; font-size:13px; font-weight:550; cursor:pointer; }
.fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; margin-top:11px; }
.fields>label { display:grid; gap:5px; } .wide { grid-column:1 / -1; }
.quick-terms { min-width:0; }
.quick-terms :deep(.token-field) { margin:0; }
.quick-terms :deep(.token-suggestions) { max-height:68px; }
.note { font-size:12px; color:var(--muted); margin:6px 0 0; line-height:1.6; }
@media(max-width:480px) { .quick-terms { grid-column:1 / -1; } }
</style>
