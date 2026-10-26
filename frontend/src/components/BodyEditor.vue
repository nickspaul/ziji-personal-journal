<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { Activity, Check, ChevronDown, LoaderCircle, X } from 'lucide-vue-next'
import { saveRow, state } from '../api'
import { recordDrafts } from '../drafts'
import { bodyNumber, calculateBmi, formatBodyValue, sortBodyRows } from '../bodyMetrics'
import { shanghaiDate, shortDate } from '../types'
import type { Row } from '../types'

const props = defineProps<{ row?: Row }>()
const emit = defineEmits<{ close: []; saved: [row: Row, collection: string] }>()
const rows = sortBodyRows(state.collections.body || [])
const prior = (key: string) => rows.find(row => row[key] !== '' && row[key] != null && (key !== 'sex' || row[key] !== 'UNSPECIFIED'))
const carriedHeight = prior('heightCm'), carriedAge = prior('age'), carriedSex = prior('sex')
const key = `body:${props.row?.id || 'new'}`, draft = recordDrafts[key]
const form = reactive<Record<string, any>>(draft?.form || (props.row ? { ...props.row } : {
  id: crypto.randomUUID(), measuredAt: shanghaiDate(), heightCm: carriedHeight?.heightCm ?? '',
  weightKg: '', bodyFatPct: '', waistCm: '', age: carriedAge?.age ?? '',
  sex: carriedSex?.sex || 'UNSPECIFIED', notes: '',
}))
const measuredDate = ref(draft?.measuredDate || String(form.measuredAt).slice(0, 10))
const heightEdited = ref(draft?.heightEdited || false)
const details = ref(!!props.row), saving = ref(false), submitted = ref(false), error = ref('')
const bmi = computed(() => calculateBmi(form))
const carried = computed(() => !props.row && [
  carriedHeight ? `身高（${shortDate(carriedHeight.measuredAt)}）` : '',
  carriedAge ? `年龄（${shortDate(carriedAge.measuredAt)}）` : '',
  carriedSex ? `模型性别参数（${shortDate(carriedSex.measuredAt)}）` : '',
].filter(Boolean).join('、'))
onBeforeUnmount(() => {
  if (!submitted.value && state.authenticated) recordDrafts[key] = JSON.parse(JSON.stringify({ form, measuredDate: measuredDate.value, heightEdited: heightEdited.value }))
})
async function submit() {
  error.value = ''
  const hasMeasurement = ['heightCm', 'weightKg', 'bodyFatPct', 'waistCm'].some(field => bodyNumber(form[field]) != null)
  const hasNewMeasurement = ['weightKg', 'bodyFatPct', 'waistCm'].some(field => bodyNumber(form[field]) != null) ||
    (bodyNumber(form.heightCm) != null && (!carriedHeight || heightEdited.value || Number(form.heightCm) !== Number(carriedHeight.heightCm)))
  if (!hasMeasurement || (!props.row && !hasNewMeasurement)) {
    error.value = '请填写至少一项本次测量，例如体重、体脂率或腰围；也可以更新身高。'
    return
  }
  saving.value = true
  try {
    const measuredAt = props.row && measuredDate.value === String(props.row.measuredAt).slice(0, 10)
      ? props.row.measuredAt : measuredDate.value
    const fields: Record<string, any> = { id: form.id, measuredAt }
    for (const field of ['heightCm', 'weightKg', 'bodyFatPct', 'waistCm', 'age', 'sex', 'notes']) {
      fields[field] = form[field] === '' || form[field] === undefined ? null : form[field]
    }
    fields.sex ||= 'UNSPECIFIED'
    const saved = await saveRow('body', fields, props.row)
    submitted.value = true
    delete recordDrafts[key]
    emit('saved', saved, 'body')
  } catch (e) { error.value = (e as Error).message }
  finally { saving.value = false }
}
</script>

<template>
  <div class="overlay" @click.self="!saving && emit('close')">
    <section class="drawer body-editor" role="dialog" aria-modal="true" :aria-label="row ? '编辑身体测量' : '新增身体测量'">
      <header class="drawer-header">
        <div class="heading-with-icon"><span class="icon-tile mint"><Activity :size="23" /></span><div><small>BODY JOURNAL</small><h2>{{ row ? '编辑' : '新增' }}身体测量</h2></div></div>
        <button class="icon-button" title="关闭身体测量编辑" :disabled="saving" @click="emit('close')"><X :size="20" /></button>
      </header>
      <form class="drawer-form" @submit.prevent="submit">
        <div class="drawer-body">
          <p class="hint">记下测量日期就好。可以只填这次测到的项目，其余留空。</p>
          <p v-if="draft" class="hint">已恢复这次打开应用期间的未提交草稿。</p>
          <div class="fields-grid">
            <label class="field full"><span>测量日期 *</span><input v-model="measuredDate" type="date" :max="shanghaiDate()" required /></label>
            <label class="field"><span>体重 · kg</span><input v-model.number="form.weightKg" type="number" min="10" max="500" step="0.01" inputmode="decimal" placeholder="本次测量值" /></label>
            <label class="field"><span>身高 · cm</span><input v-model.number="form.heightCm" @input="heightEdited = true" type="number" min="50" max="260" step="0.1" inputmode="decimal" placeholder="可沿用最近的身高" /></label>
            <label class="field"><span>体脂率 · %</span><input v-model.number="form.bodyFatPct" type="number" min="0" max="100" step="0.1" inputmode="decimal" placeholder="体脂秤等测得，未知留空" /></label>
            <label class="field"><span>腰围 · cm</span><input v-model.number="form.waistCm" type="number" min="1" max="500" step="0.1" inputmode="decimal" placeholder="选填" /></label>
          </div>
          <div class="bmi-preview sensitive"><div><span>本次 BMI</span><strong>{{ formatBodyValue(bmi) }}<small v-if="bmi != null">kg/m²</small></strong></div><p>{{ bmi == null ? '填入身高和体重后自动计算。' : '体重（kg）÷ 身高（m）的平方。' }}体脂率按你填的测量值保存。</p></div>
          <p v-if="carried" class="carry-note">已沿用上次的{{ carried }}，可以修改或清空；体重和体脂率需要填写本次测量值。</p>
          <button type="button" class="detail-toggle" @click="details = !details">{{ details ? '收起' : '补充' }}酒精估算参数与备注 <ChevronDown :size="17" :class="{ rotated: details }" /></button>
          <div v-if="details" class="fields-grid">
            <label class="field"><span>测量时年龄 · 岁</span><input v-model.number="form.age" type="number" min="1" max="120" step="1" placeholder="选填" /></label>
            <label class="field"><span>酒精模型的生理性别参数</span><select v-model="form.sex"><option value="UNSPECIFIED">暂不提供</option><option value="MALE">男性公式</option><option value="FEMALE">女性公式</option></select></label>
            <p class="hint full">这两项只供酒精模型选用公式，不影响身体数据记录或 BMI 计算。身高、体重等参数也无法确定个人的实际代谢速度。</p>
            <label class="field full"><span>测量备注</span><textarea v-model.trim="form.notes" rows="3" maxlength="10000" placeholder="例如：晨起测量、使用的体脂秤" /></label>
          </div>
          <p class="draft-note">暂时关闭后草稿会保留在本次打开的应用中；锁定或退出后清除。</p>
          <p v-if="error" class="error-box" role="alert">{{ error }}</p>
        </div>
        <footer class="drawer-footer"><button type="button" class="button secondary" :disabled="saving" @click="emit('close')">暂时关闭</button><button class="button primary" :disabled="saving"><LoaderCircle v-if="saving" class="spin" :size="17" /><Check v-else :size="17" />{{ saving ? '正在保存' : '保存测量' }}</button></footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.bmi-preview{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:17px 18px;margin:4px 0 13px;border:1px solid var(--border);background:var(--panel-soft);border-radius:10px}.bmi-preview>div{min-width:95px}.bmi-preview span{font-size:11px;color:var(--muted)}.bmi-preview strong{display:block;margin-top:7px;font-size:28px;font-weight:500;font-variant-numeric:tabular-nums}.bmi-preview strong small{font-size:10px;font-weight:400;color:var(--muted);margin-left:7px}.bmi-preview p{font-size:11px;line-height:1.9;color:var(--muted);max-width:230px;margin:0}.carry-note,.draft-note{color:var(--muted);font-size:10px;line-height:1.9;margin:10px 0 17px}.body-editor .hint.full{grid-column:1/-1;margin:0}.body-editor .field input,.body-editor .field select{min-height:42px}.body-editor .drawer-footer{justify-content:flex-end}.body-editor .drawer-footer .button{min-height:40px}@media(max-width:580px){.bmi-preview{padding:13px;gap:12px}.bmi-preview p{font-size:10px}.bmi-preview strong{font-size:25px}.carry-note,.draft-note{font-size:10px}}
</style>
