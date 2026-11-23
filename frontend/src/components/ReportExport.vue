<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Download, FileText, Info, LoaderCircle, X } from 'lucide-vue-next'
import { api } from '../api'
import { shiftDay } from '../journal'
import { shanghaiDate } from '../types'
import { formatReport, reportSections } from '../reportExport'
import type { PeriodReport, ReportFormat, ReportKey } from '../reportExport'

const props = defineProps<{quick?: boolean}>()
const emit = defineEmits<{ close: []; notify: [message: string] }>()
const dialog = ref<HTMLDialogElement>()
const presets = [7, 30, 90] as const
const preset = ref<number | 'custom'>(30)
const to = ref(shanghaiDate()), from = ref(shiftDay(to.value, -29))
const sections = ref<ReportKey[]>(reportSections.filter(s => s.key !== 'intimacy').map(s => s.key))
const format = ref<ReportFormat>('md')
const busy = ref(false), error = ref('')
const controller = new AbortController()
const allSelected = computed(() => sections.value.length === reportSections.length)
const dateError = computed(() => {
  if (![from.value, to.value].every(v => /^[1-9]\d{3}-\d{2}-\d{2}$/.test(v))) return '请填写完整的开始和结束日期。'
  if (to.value < from.value) return '结束日期须在开始日期当天或之后。'
  return ''
})
function chooseRange(days: number) {
  preset.value = days
  to.value = shanghaiDate()
  from.value = shiftDay(to.value, 1 - days)
  error.value = ''
}
function toggleAll() {
  sections.value = allSelected.value ? [] : reportSections.map(s => s.key)
}
function close() { emit('close') }
onMounted(() => {
  dialog.value?.showModal()
  if (props.quick) void download()
})
onBeforeUnmount(() => { controller.abort(); dialog.value?.close() })

async function download() {
  if (busy.value || dateError.value || !sections.value.length) return
  busy.value = true
  error.value = ''
  try {
    const included = reportSections.filter(s => sections.value.includes(s.key)).map(s => s.key)
    const query = new URLSearchParams({ from: from.value, to: to.value, sections: included.join(',') })
    const report = await api<PeriodReport>(`/export/report?${query}`, { signal: controller.signal, cache: 'no-store' })
    if (controller.signal.aborted) return
    const extension = format.value
    const mime = extension === 'json' ? 'application/json' : 'text/markdown'
    const blob = new Blob([formatReport(report, extension)], { type: mime + ';charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    try {
      link.download = `自记记录_${report.from}_至_${report.to}.${extension}`
      link.href = url
      document.body.append(link)
      link.click()
    } finally {
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
    const count = report.sections.reduce((sum, section) => sum + section.records.length, 0)
    emit('notify', `已生成 ${report.sections.length} 类、${count} 条明细的分析文件。`)
    close()
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <dialog ref="dialog" class="report-export" aria-labelledby="report-export-title" @cancel.prevent="close">
    <form @submit.prevent="download">
      <header>
        <h2 id="report-export-title"><Download :size="19"/>导出分析记录</h2>
        <button type="button" class="icon-button" aria-label="关闭导出窗口" @click="close"><X :size="18"/></button>
      </header>
      <fieldset class="report-body" :disabled="busy">
        <fieldset class="report-block">
          <legend>日期范围</legend>
          <div class="range-pills" role="group" aria-label="选择日期范围">
            <button v-for="days in presets" :key="days" type="button" :aria-pressed="preset === days" @click="chooseRange(days)">最近 {{ days }} 天</button>
            <button type="button" :aria-pressed="preset === 'custom'" @click="preset='custom'">自定义</button>
          </div>
          <div v-if="preset === 'custom'" class="date-range">
            <label>开始日期<input v-model="from" type="date" required min="1000-01-01" max="9999-12-31" @input="error=''"/></label>
            <label>结束日期<input v-model="to" type="date" required min="1000-01-01" max="9999-12-31" @input="error=''"/></label>
          </div>
          <p v-if="dateError" class="range-error" role="status">{{ dateError }}</p>
          <p v-else class="range-caption">{{ from }} 至 {{ to }}，含首尾两天</p>
        </fieldset>

        <fieldset class="report-block">
          <legend>导出内容</legend>
          <div class="selection-line">
            <span>已选 {{ sections.length }} / {{ reportSections.length }} 类</span>
            <button type="button" class="text-link" @click="toggleAll">{{ allSelected ? '清空选择' : '全选' }}</button>
          </div>
          <div class="section-grid">
            <label v-for="item in reportSections" :key="item.key" :class="{chosen: sections.includes(item.key)}">
              <input v-model="sections" type="checkbox" :value="item.key" @change="error=''"/>
              <span>{{ item.label }}</span>
            </label>
          </div>
        </fieldset>

        <label class="format-picker">
          <span><FileText :size="16"/>文件格式</span>
          <select v-model="format"><option value="md">Markdown 文档（.md）</option><option value="json">结构化数据（.json）</option></select>
        </label>
        <p class="report-note"><Info :size="15"/>文件包含所选记录的备注。下载后，你可以自行交给 AI 分析。</p>
        <p v-if="error" class="error-box" role="alert">{{ error }}</p>
      </fieldset>
      <footer>
        <button class="button secondary compact-button" type="button" @click="close">取消</button>
        <button class="button primary compact-button" :disabled="busy || !!dateError || !sections.length">
          <LoaderCircle v-if="busy" :size="16" class="spin"/><Download v-else :size="16"/>{{ busy ? '正在生成…' : '下载文件' }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.report-export { width:min(510px,calc(100vw - 24px));max-height:calc(100dvh - 32px);padding:0;border:1px solid var(--border);border-radius:14px;color:var(--text);background:var(--panel);box-shadow:var(--shadow);overflow:auto;overscroll-behavior:contain }
.report-export::backdrop { background:#070a10a8 }
.report-export header,.report-export footer,.selection-line,.format-picker { display:flex;align-items:center;justify-content:space-between;gap:8px }
.report-export header,.report-export footer { padding:12px 19px }
.report-export header { border-bottom:1px solid var(--border) }
.report-export h2,.format-picker span { display:flex;align-items:center;gap:9px }
.report-export h2 { font-size:18px }
.report-body { display:grid;gap:17px;padding:17px 19px }
.report-block legend,.format-picker span { font-size:13px;font-weight:600;margin-bottom:7px;padding:0 }
.range-pills { display:flex;gap:4px;padding:3px;border:1px solid var(--border);background:var(--input);border-radius:9px }
.range-pills button { flex:1;padding:7px 3px;border-radius:6px;color:var(--muted);font-size:12px }
.range-pills button[aria-pressed=true] { color:var(--text);background:var(--panel-soft);font-weight:600;box-shadow:0 1px 3px #0002 }
.date-range { display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px }
.date-range label,.selection-line,.range-caption,.range-error,.report-note { font-size:12px;color:var(--muted);line-height:1.6 }
.date-range input,.format-picker select { font-size:13px;padding:8px 9px }
.date-range input { display:block;margin-top:3px }
.range-caption,.range-error { margin-top:6px }
.range-error { color:var(--pink) }
.selection-line { margin:0 0 7px }
.section-grid { display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px }
.section-grid label { display:flex;align-items:center;gap:7px;padding:8px;border:1px solid var(--border);border-radius:7px;background:var(--input);font-size:12px;cursor:pointer }
.section-grid label.chosen { background:var(--panel-soft) }
.section-grid input { margin:0 }
.format-picker span { margin:0;white-space:nowrap }
.format-picker select { width:auto;max-width:70% }
.report-note { display:flex;align-items:flex-start;gap:6px }
.report-note svg { margin-top:2px }
.report-export footer { justify-content:flex-end;border-top:1px solid var(--border) }
@media(max-width:430px) {
  .report-export header,.report-export footer,.report-body { padding:12px }
  .report-body { gap:13px }
  .section-grid { grid-template-columns:repeat(2,minmax(0,1fr)) }
}
</style>
